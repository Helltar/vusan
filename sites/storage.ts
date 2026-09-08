import { statfs } from "node:fs/promises";
import type { Config } from "./config.ts";
import { RequestError, siteLabel, sitePath } from "./protocol.ts";

const DIR_MODE = 0o755;
const FILE_MODE = 0o644;
const HOUR_MS = 3_600_000;
// live sites are named by a numeric label, so a dot-prefixed name is unreachable through nginx.
const INCOMING = ".publishing-";
const RETIRED = ".retired-";

const LOW_STORAGE = "The site host is low on disk space. Publishing is paused; published sites were kept.";
const UNREADABLE_STORAGE = "Cannot verify site storage availability. Publishing is paused.";

export interface SiteFile {
  path: string;
  bytes: number;
}

export interface SiteRecord {
  owner: string;
  label: string;
  files: number;
  bytes: number;
  createdAt: number;
  updatedAt: number;
}

interface Upload {
  owner: string;
  label: string;
  dir: string;
  files: Set<string>;
  bytes: number;
  touchedAt: number;
}

function suffix(): string {
  return [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function isLabel(name: string): boolean {
  return /^(?:0|[1-9][0-9]{0,18})$/.test(name);
}

/**
 * One published site per person, replaced whole. Uploads are staged in a sibling directory and swapped in
 * by rename, so a visitor sees either the previous site or the new one — never a half-written tree.
 */
export class Sites {
  private readonly root: string;
  private readonly staging: string;
  private readonly metaDir: string;
  private readonly blockedFile: string;
  private readonly uploads = new Map<string, Upload>();
  private readonly commits = new Map<string, number[]>();
  private spaceFailure: string | null = null;
  private transfers = 0;

  constructor(private readonly config: Config) {
    this.root = `${config.dataDir}/sites`;
    this.staging = `${config.dataDir}/staging`;
    this.metaDir = `${config.dataDir}/meta`;
    this.blockedFile = `${config.dataDir}/blocked`;
  }

  get healthy(): boolean {
    return this.spaceFailure === null;
  }

  async initialize(): Promise<void> {
    for (const dir of [this.root, this.staging, this.metaDir]) {
      await Deno.mkdir(dir, { recursive: true, mode: DIR_MODE });
      await Deno.chmod(dir, DIR_MODE).catch(() => {});
    }
    await this.sweep();
  }

  url(label: string): string {
    return `https://${label}.${this.config.domain}/`;
  }

  /** Told to the client when an upload starts, so one side owns these numbers rather than both. */
  get limits() {
    return {
      files: this.config.maxFiles,
      fileBytes: this.config.maxFileMb * 1024 * 1024,
      totalBytes: this.config.maxMb * 1024 * 1024,
      pathDepth: this.config.maxDepth,
    };
  }

  async begin(owner: string): Promise<string> {
    const label = siteLabel(owner);
    if ((await this.blocked()).has(label)) {
      throw new RequestError("This site is blocked by the administrator", 403);
    }
    this.rateLimit(owner);
    await this.guardWrites();
    for (const [id, upload] of this.uploads) if (upload.owner === owner) await this.discard(id);
    const id = suffix() + suffix();
    const dir = `${this.staging}/${id}`;
    await Deno.mkdir(dir, { mode: DIR_MODE });
    this.uploads.set(id, { owner, label, dir, files: new Set(), bytes: 0, touchedAt: Date.now() });
    return id;
  }

  async put(id: string, rawPath: string, body: ReadableStream<Uint8Array> | null, declared: number | null) {
    const upload = this.open(id);
    const path = sitePath(rawPath, this.config.maxDepth);
    const fileCap = this.config.maxFileMb * 1024 * 1024;
    const remaining = this.config.maxMb * 1024 * 1024 - upload.bytes;
    if (upload.files.has(path)) throw new RequestError(`\`${path}\` was already uploaded`, 409);
    if (upload.files.size >= this.config.maxFiles) {
      throw new RequestError(`A site may hold at most ${this.config.maxFiles} files`, 413);
    }
    const cap = Math.min(fileCap, remaining);
    if (declared !== null && declared > cap) {
      throw new RequestError(
        remaining < fileCap
          ? `The site would exceed its ${this.config.maxMb} MB limit`
          : `A file may be at most ${this.config.maxFileMb} MB`,
        413,
      );
    }
    if (this.transfers >= 2) throw new RequestError("Transfer capacity reached; try again shortly", 409);
    if (upload.files.size % 32 === 0) await this.guardWrites();
    this.transfers++;
    try {
      const target = `${upload.dir}/${path}`;
      const parent = target.slice(0, target.lastIndexOf("/"));
      await Deno.mkdir(parent, { recursive: true, mode: DIR_MODE });
      const bytes = await this.write(target, body, cap);
      upload.files.add(path);
      upload.bytes += bytes;
      upload.touchedAt = Date.now();
      return { path, bytes };
    } finally {
      this.transfers--;
    }
  }

  async commit(id: string): Promise<SiteRecord> {
    const upload = this.open(id);
    if (!upload.files.size) throw new RequestError("Nothing was uploaded", 400);
    await this.guardWrites();
    this.uploads.delete(id);
    const previous = await this.record(upload.label);
    await this.swap(upload.label, upload.dir);
    const now = Date.now();
    const record: SiteRecord = {
      owner: upload.owner,
      label: upload.label,
      files: upload.files.size,
      bytes: upload.bytes,
      createdAt: previous?.createdAt ?? now,
      updatedAt: now,
    };
    await this.writeRecord(record);
    this.commits.set(upload.owner, [...(this.commits.get(upload.owner) ?? []), now]);
    return record;
  }

  async discard(id: string): Promise<void> {
    const upload = this.uploads.get(id);
    if (!upload) return;
    this.uploads.delete(id);
    await Deno.remove(upload.dir, { recursive: true }).catch(() => {});
  }

  async status(owner: string): Promise<SiteRecord | null> {
    return await this.record(siteLabel(owner));
  }

  /**
   * What a site actually holds, so the answer to "what is published" comes from the site rather than
   * from whoever remembers what they uploaded. Capped, because the caller is an LLM tool result.
   */
  async listing(owner: string, limit: number): Promise<{ files: SiteFile[]; truncated: boolean }> {
    const root = `${this.root}/${siteLabel(owner)}`;
    const files: SiteFile[] = [];
    let truncated = false;

    const walk = async (dir: string, prefix: string): Promise<void> => {
      const entries: Deno.DirEntry[] = [];
      for await (const entry of Deno.readDir(dir)) entries.push(entry);
      entries.sort((a, b) => a.name.localeCompare(b.name));
      for (const entry of entries) {
        if (files.length >= limit) {
          truncated = true;
          return;
        }
        const path = prefix ? `${prefix}/${entry.name}` : entry.name;
        // nothing here ever writes a symlink, and a directory is only ever walked, never followed out.
        if (entry.isDirectory) await walk(`${dir}/${entry.name}`, path);
        else if (entry.isFile) files.push({ path, bytes: (await Deno.stat(`${dir}/${entry.name}`)).size });
      }
    };

    await walk(root, "").catch((e) => {
      if (!(e instanceof Deno.errors.NotFound)) throw e;
    });
    return { files, truncated };
  }

  async remove(owner: string): Promise<boolean> {
    const label = siteLabel(owner);
    for (const [id, upload] of this.uploads) if (upload.owner === owner) await this.discard(id);
    return await this.drop(label);
  }

  /** Retention, the block list, abandoned uploads and anything a crash left behind, on one pass. */
  async sweep(): Promise<void> {
    await this.checkSpace();
    const now = Date.now();
    const idle = this.config.uploadIdleMinutes * 60_000;
    for (const [id, upload] of this.uploads) if (now - upload.touchedAt > idle) await this.discard(id);
    const live = new Set([...this.uploads.values()].map((upload) => upload.dir));
    for await (const entry of Deno.readDir(this.staging)) {
      const path = `${this.staging}/${entry.name}`;
      if (!live.has(path)) await Deno.remove(path, { recursive: true }).catch(() => {});
    }
    const blocked = await this.blocked();
    const retain = this.config.retainDays === null ? null : this.config.retainDays * 24 * HOUR_MS;
    const labels = new Set<string>();
    for await (const entry of Deno.readDir(this.root)) {
      if (entry.name.startsWith(INCOMING) || entry.name.startsWith(RETIRED)) {
        await Deno.remove(`${this.root}/${entry.name}`, { recursive: true }).catch(() => {});
        continue;
      }
      if (!isLabel(entry.name)) continue;
      labels.add(entry.name);
      if (blocked.has(entry.name)) {
        console.log(`site=[${entry.name}] removed: blocked by the administrator`);
        await this.drop(entry.name);
        continue;
      }
      if (retain === null) continue;
      // a site that cannot be dated is left alone: being unsure must never delete someone's work.
      const record = await this.record(entry.name);
      if (record && now - record.updatedAt > retain) {
        console.log(`site=[${entry.name}] removed: not republished in ${this.config.retainDays} days`);
        await this.drop(entry.name);
      }
    }
    for await (const entry of Deno.readDir(this.metaDir)) {
      const label = entry.name.replace(/\.json$/, "");
      if (!labels.has(label)) await Deno.remove(`${this.metaDir}/${entry.name}`).catch(() => {});
    }
  }

  private open(id: string): Upload {
    const upload = this.uploads.get(id);
    if (!upload) throw new RequestError("Unknown upload; start a new one", 404);
    return upload;
  }

  private rateLimit(owner: string): void {
    const now = Date.now();
    const recent = (this.commits.get(owner) ?? []).filter((at) => now - at < HOUR_MS);
    this.commits.set(owner, recent);
    if (recent.length >= this.config.publishesPerHour) {
      throw new RequestError("Too many publishes in the last hour; try again later", 429);
    }
  }

  private async write(
    target: string,
    body: ReadableStream<Uint8Array> | null,
    cap: number,
  ): Promise<number> {
    if (!body) throw new RequestError("Missing file content");
    let size = 0;
    try {
      using file = await Deno.open(target, { write: true, createNew: true, mode: FILE_MODE });
      for await (const chunk of body) {
        size += chunk.length;
        if (size > cap) throw new RequestError("The file exceeds the remaining size budget", 413);
        let offset = 0;
        while (offset < chunk.length) offset += await file.write(chunk.subarray(offset));
      }
    } catch (e) {
      await Deno.remove(target).catch(() => {});
      throw e;
    }
    return size;
  }

  /** Rename in, rename out, delete the old copy. The gap where neither exists is one syscall wide. */
  private async swap(label: string, from: string): Promise<void> {
    const live = `${this.root}/${label}`;
    const incoming = `${this.root}/${INCOMING}${label}-${suffix()}`;
    const retired = `${this.root}/${RETIRED}${label}-${suffix()}`;
    await Deno.rename(from, incoming);
    const replaced = await Deno.rename(live, retired).then(() => true).catch((e) => {
      if (e instanceof Deno.errors.NotFound) return false;
      throw e;
    });
    try {
      await Deno.rename(incoming, live);
    } catch (e) {
      if (replaced) await Deno.rename(retired, live).catch(() => {});
      await Deno.remove(incoming, { recursive: true }).catch(() => {});
      throw e;
    }
    if (replaced) await Deno.remove(retired, { recursive: true }).catch(() => {});
  }

  private async drop(label: string): Promise<boolean> {
    const retired = `${this.root}/${RETIRED}${label}-${suffix()}`;
    const removed = await Deno.rename(`${this.root}/${label}`, retired).then(() => true).catch((e) => {
      if (e instanceof Deno.errors.NotFound) return false;
      throw e;
    });
    if (removed) await Deno.remove(retired, { recursive: true }).catch(() => {});
    await Deno.remove(`${this.metaDir}/${label}.json`).catch(() => {});
    return removed;
  }

  private async record(label: string): Promise<SiteRecord | null> {
    const raw = await Deno.readTextFile(`${this.metaDir}/${label}.json`).catch(() => null);
    if (raw === null) return null;
    const parsed = JSON.parse(raw) as SiteRecord;
    return typeof parsed?.updatedAt === "number" ? parsed : null;
  }

  private async writeRecord(record: SiteRecord): Promise<void> {
    const target = `${this.metaDir}/${record.label}.json`;
    const temporary = `${target}.${suffix()}`;
    await Deno.writeTextFile(temporary, JSON.stringify(record), { mode: FILE_MODE });
    await Deno.rename(temporary, target);
  }

  private async blocked(): Promise<Set<string>> {
    const text = await Deno.readTextFile(this.blockedFile).catch(() => "");
    return new Set(text.split("\n").map((line) => line.trim()).filter(isLabel));
  }

  async guardWrites(): Promise<void> {
    await this.checkSpace();
    if (this.spaceFailure) throw new RequestError(this.spaceFailure, 507);
  }

  // one statfs, taken fresh: a cached answer is how a full disk keeps accepting writes for a while.
  private async checkSpace(): Promise<void> {
    const free = await statfs(this.config.dataDir)
      .then((disk) => disk.bavail * disk.bsize)
      .catch(() => null);
    const failure = free === null
      ? UNREADABLE_STORAGE
      : free < this.config.minFreeMb * 1024 * 1024
      ? LOW_STORAGE
      : null;
    if (failure !== this.spaceFailure) console.log(failure ?? "site storage recovered");
    this.spaceFailure = failure;
  }
}
