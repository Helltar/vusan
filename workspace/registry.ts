import { config } from "./config.ts";

// telegram ids do not fit uid_t, so a workspace gets a slot from the pool baked into
// the image and keeps it for life — the files on disk are owned by that uid.
export interface Workspace {
  id: string;
  uid: number;
  gid: number;
  home: string;
  slot: number;
}

interface Registry {
  slots: Record<string, number>;
}

const ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const registryFile = `${config.root}/.vusan/registry.json`;

let registry: Registry = { slots: {} };
let loaded = false;

export function assertValidId(id: string): void {
  if (!ID_PATTERN.test(id)) throw new BadRequest(`Invalid workspace id: ${id}`);
}

export class BadRequest extends Error {}

async function load(): Promise<void> {
  if (loaded) return;
  await Deno.mkdir(`${config.root}/.vusan`, { recursive: true });
  // traversable but not listable: a workspace reaches its own home by name and learns
  // nothing about who else has one
  await Deno.chmod(config.root, 0o711).catch(() => {});
  registry = await Deno.readTextFile(registryFile)
    .then((raw) => JSON.parse(raw) as Registry)
    .catch(() => ({ slots: {} }));
  loaded = true;
}

async function persist(): Promise<void> {
  // write-then-rename: a torn registry would strand every workspace's files under a
  // uid nothing maps to any more
  const tmp = `${registryFile}.tmp`;
  await Deno.writeTextFile(tmp, JSON.stringify(registry, null, 2));
  await Deno.rename(tmp, registryFile);
}

export async function resolveWorkspace(id: string): Promise<Workspace> {
  assertValidId(id);
  await load();

  let slot = registry.slots[id];
  if (slot === undefined) {
    slot = nextFreeSlot();
    registry.slots[id] = slot;
    await persist();
  }

  const uid = config.uidBase + slot;
  const workspace: Workspace = { id, slot, uid, gid: uid, home: `${config.root}/${id}` };
  await prepareHome(workspace);
  return workspace;
}

function nextFreeSlot(): number {
  const taken = new Set(Object.values(registry.slots));
  for (let slot = 0; slot < config.uidCount; slot++) {
    if (!taken.has(slot)) return slot;
  }
  throw new Error(`The uid pool is exhausted (${config.uidCount} workspaces)`);
}

async function prepareHome(workspace: Workspace): Promise<void> {
  // 0700 throughout: in a group every member has their own workspace, and one member's
  // files are not the group's
  for (const dir of ["", "/inbox", "/tmp", "/.vusan", "/.vusan/logs"]) {
    const path = `${workspace.home}${dir}`;
    await Deno.mkdir(path, { recursive: true, mode: 0o700 }).catch(() => {});
    await Deno.chown(path, workspace.uid, workspace.gid).catch(() => {});
    await Deno.chmod(path, 0o700).catch(() => {});
  }
}

export async function listWorkspaceIds(): Promise<string[]> {
  await load();
  return Object.keys(registry.slots).sort();
}

export async function forgetWorkspace(id: string): Promise<void> {
  await load();
  delete registry.slots[id];
  await persist();
}
