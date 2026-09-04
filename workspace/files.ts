import { config } from "./config.ts";
import { BadRequest, type Workspace } from "./registry.ts";

export interface Entry {
  path: string;
  bytes: number;
  dir: boolean;
}

const MAX_ENTRIES = 500;

/**
 * Resolves a workspace-relative path, refusing anything that leaves the workspace.
 * Symlinks are resolved before the check, so a link planted by the workspace's own code
 * cannot be used to read or overwrite the host side of the volume.
 */
export async function resolvePath(workspace: Workspace, raw: string): Promise<string> {
  const relative = raw.trim().replace(/^\/+/, "");
  if (!relative || relative.split("/").includes("..")) throw new BadRequest(`Invalid path: ${raw}`);

  const home = await Deno.realPath(workspace.home);
  const target = `${home}/${relative}`;
  const existing = await Deno.realPath(target).catch(() => null);
  if (existing) return assertInside(home, existing, raw);

  // the file may not exist yet (a write), so the directory that will hold it is what has
  // to be inside the workspace
  const parent = target.slice(0, target.lastIndexOf("/")) || home;
  const realParent = await Deno.realPath(parent).catch(() => {
    throw new BadRequest(`No such directory for path: ${raw}`);
  });
  assertInside(home, realParent, raw);
  return `${realParent}/${target.slice(target.lastIndexOf("/") + 1)}`;
}

function assertInside(home: string, path: string, raw: string): string {
  if (path !== home && !path.startsWith(`${home}/`)) {
    throw new BadRequest(`Path escapes the workspace: ${raw}`);
  }
  return path;
}

export async function writeFile(workspace: Workspace, raw: string, bytes: Uint8Array): Promise<string> {
  if (bytes.length > config.maxUploadBytes) throw new BadRequest("File is too large for the workspace");

  const relative = raw.trim().replace(/^\/+/, "");
  const slash = relative.lastIndexOf("/");
  if (slash > 0) await makeDirs(workspace, relative.slice(0, slash));

  const path = await resolvePath(workspace, relative);
  await Deno.writeFile(path, bytes, { mode: 0o600 });
  await Deno.chown(path, workspace.uid, workspace.gid).catch(() => {});
  return path;
}

async function makeDirs(workspace: Workspace, relative: string): Promise<void> {
  let walked = "";
  for (const segment of relative.split("/").filter(Boolean)) {
    walked = walked ? `${walked}/${segment}` : segment;
    const path = `${workspace.home}/${walked}`;
    const created = await Deno.mkdir(path, { mode: 0o700 }).then(() => true).catch(() => false);
    if (created) await Deno.chown(path, workspace.uid, workspace.gid).catch(() => {});
  }
  await resolvePath(workspace, relative); // rejects a path that symlinked its way out
}

export async function listEntries(workspace: Workspace, raw: string): Promise<Entry[]> {
  const base = await resolvePath(workspace, raw || ".");
  const home = await Deno.realPath(workspace.home);
  const entries: Entry[] = [];

  async function walk(dir: string): Promise<void> {
    if (entries.length >= MAX_ENTRIES) return;
    for await (const entry of Deno.readDir(dir)) {
      if (entries.length >= MAX_ENTRIES) return;
      if (entry.name === ".vusan") continue;

      const path = `${dir}/${entry.name}`;
      const stat = await Deno.lstat(path).catch(() => null);
      if (!stat) continue;

      entries.push({
        path: path.slice(home.length + 1),
        bytes: stat.isFile ? stat.size : 0,
        dir: stat.isDirectory,
      });

      // node_modules and .git are thousands of entries nobody reads in a listing
      if (stat.isDirectory && !["node_modules", ".git", "venv", ".venv"].includes(entry.name)) {
        await walk(path);
      }
    }
  }

  const stat = await Deno.stat(base).catch(() => null);
  if (!stat) throw new BadRequest(`No such path: ${raw}`);
  if (stat.isFile) return [{ path: base.slice(home.length + 1), bytes: stat.size, dir: false }];

  await walk(base);
  return entries.sort((a, b) => a.path.localeCompare(b.path));
}

/** Disk use of the whole workspace, in bytes. `du` is cheaper than walking it in Deno. */
export async function usedBytes(workspace: Workspace): Promise<number> {
  const output = await new Deno.Command("du", {
    args: ["-sb", workspace.home],
    stdout: "piped",
    stderr: "null",
  })
    .output()
    .catch(() => null);

  if (!output?.success) return 0;
  return Number.parseInt(new TextDecoder().decode(output.stdout).split(/\s/)[0], 10) || 0;
}
