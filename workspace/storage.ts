import { statfs } from "node:fs/promises";
import type { Config } from "./config.ts";
import type { Containers } from "./container.ts";
import type { Jobs } from "./jobs.ts";
import { RequestError } from "./protocol.ts";

const LOW_STORAGE =
  "Workspace storage is low. An administrator must free disk space or inodes; files were kept.";
const UNREADABLE_STORAGE = "Cannot verify workspace storage availability; files were kept.";
// a home is measured once the filesystem has lost this much, so a quiet controller costs one statfs.
const PROBE_AFTER_BYTES = 256 * 1024 * 1024;
// and on a slow cadence regardless, so a home that stopped growing while over its limit is still caught.
const PROBE_EVERY_MS = 60_000;

interface Space {
  bytes: number;
  inodes: number;
}

/**
 * Named volumes have no hard quota, so the ceiling is enforced by watching instead: a workspace over its
 * limit loses its container, and host-wide pressure stops every live one. Commands stay available even
 * while latched, because deleting files is the only way back; only uploads, which just add bytes, do not.
 */
export class DiskGuard {
  private failure: string | null = null;
  private freeAtLastProbe = Number.MAX_SAFE_INTEGER;
  private probedAt = 0;
  private ticking = false;

  constructor(
    private readonly config: Config,
    private readonly containers: Containers,
    private readonly jobs: Jobs,
  ) {}

  get healthy(): boolean {
    return this.failure === null;
  }

  guardUploads(): void {
    if (this.failure) throw new RequestError(this.failure, 507);
  }

  async tick(): Promise<void> {
    if (this.ticking) return;
    this.ticking = true;
    try {
      const space = await this.space();
      if (!space) return await this.latch(UNREADABLE_STORAGE);
      const due = Date.now() - this.probedAt > PROBE_EVERY_MS;
      if (this.low(space) || due || this.freeAtLastProbe - space.bytes > PROBE_AFTER_BYTES) {
        this.freeAtLastProbe = space.bytes;
        this.probedAt = Date.now();
        await this.enforceHomeLimit();
      }
      if (this.low(space)) await this.latch(LOW_STORAGE);
      else if (this.failure) {
        this.failure = null;
        console.log("workspace storage recovered");
      }
    } finally {
      this.ticking = false;
    }
  }

  private async space(): Promise<Space | null> {
    return await statfs(this.config.stateDir)
      .then((disk) => ({ bytes: disk.bavail * disk.bsize, inodes: disk.ffree }))
      .catch(() => null);
  }

  private low(space: Space): boolean {
    return space.bytes < this.config.minFreeMb * 1024 * 1024 || space.inodes < this.config.minFreeInodes;
  }

  private async enforceHomeLimit(): Promise<void> {
    const limit = this.config.maxHomeMb * 1024 * 1024;
    for (const id of this.containers.liveIds()) {
      const used = await this.containers.usage(id).catch(() => Number.NaN);
      if (!Number.isFinite(used) || used <= limit) continue;
      console.error(`workspace=[${id}] over its home limit used=[${used}] limit=[${limit}]`);
      await this.evict(
        id,
        `The workspace exceeded its ${this.config.maxHomeMb} MB limit and was stopped. ` +
          "Delete files that are no longer needed before running anything else.",
      );
    }
  }

  private async latch(message: string): Promise<void> {
    if (this.failure) return;
    this.failure = message;
    console.error(`workspace storage guard tripped: ${message}`);
    for (const id of this.containers.liveIds()) await this.evict(id, message);
  }

  private async evict(id: string, message: string): Promise<void> {
    await this.jobs.evict(id, message).catch((e) => console.error(`cannot stop workspace=[${id}]`, e));
  }
}
