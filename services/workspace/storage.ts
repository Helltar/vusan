import { statfs } from "node:fs/promises";
import type { Config } from "./config.ts";
import type { Containers } from "./container.ts";
import type { Jobs } from "./jobs.ts";
import { RequestError } from "./protocol.ts";

const LOW_STORAGE =
  "Workspace storage is low. Commands and uploads are paused; ask an administrator to free host space. Files were kept.";
const UNREADABLE_STORAGE =
  "Cannot verify workspace storage availability. Commands and uploads are paused; files were kept.";
// a home is measured once the filesystem has lost this much, so a quiet controller costs one statfs.
const PROBE_AFTER_BYTES = 256 * 1024 * 1024;
// and on a slow cadence regardless, so a home that stopped growing while over its limit is still caught.
const PROBE_EVERY_MS = 60_000;

interface Space {
  bytes: number;
  inodes: number;
}

/**
 * Fixed home disks enforce capacity in the kernel. This guard separately watches the host reserve and
 * catches failed home measurements. Isolated deletion never runs user-controlled code.
 */
export class DiskGuard {
  private failure: string | null = null;
  private freeAtLastProbe = Number.MAX_SAFE_INTEGER;
  private probedAt = 0;
  private checking: Promise<void> | null = null;
  private probing = false;
  private probeNeeded = false;

  constructor(
    private readonly config: Config,
    private readonly containers: Containers,
    private readonly jobs: Jobs,
  ) {}

  get healthy(): boolean {
    return this.failure === null;
  }

  guardWrites(): void {
    if (this.failure) throw new RequestError(this.failure, 507);
  }

  async tick(): Promise<void> {
    if (this.checking) return await this.checking;
    this.checking = this.checkSpace();
    try {
      await this.checking;
    } finally {
      this.checking = null;
    }
  }

  private async checkSpace(): Promise<void> {
    const space = await this.space();
    if (!space) return await this.latch(UNREADABLE_STORAGE);
    // an emergency never waits for a directory walk, which a workspace can deliberately slow down.
    if (this.low(space)) return await this.latch(LOW_STORAGE);
    if (this.failure) {
      this.failure = null;
      this.containers.blockStorage(null);
      console.log("workspace storage recovered");
    }
    if (
      Date.now() - this.probedAt > PROBE_EVERY_MS || this.freeAtLastProbe - space.bytes > PROBE_AFTER_BYTES
    ) {
      this.probeNeeded = true;
      this.freeAtLastProbe = space.bytes;
      this.probedAt = Date.now();
    }
  }

  /** Directory walks run separately so a slow or unreadable home cannot stall the host reserve check. */
  async checkHomes(): Promise<void> {
    if (this.probing || !this.probeNeeded || this.failure) return;
    this.probing = true;
    this.probeNeeded = false;
    try {
      await this.enforceHomeLimit();
    } finally {
      this.probing = false;
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
      if (!Number.isFinite(used)) {
        await this.evict(
          id,
          "Cannot measure workspace storage. Use deleteWorkspaceFile to remove inaccessible directories.",
        );
        continue;
      }
      if (used <= limit) continue;
      console.error(`workspace=[${id}] over its home limit used=[${used}] limit=[${limit}]`);
      await this.evict(
        id,
        `The workspace exceeded its ${this.config.maxHomeMb} MB limit and was stopped. ` +
          "Use deleteWorkspaceFile to remove files that are no longer needed.",
      );
    }
  }

  private async latch(message: string): Promise<void> {
    if (this.failure !== message) console.error(`workspace storage guard tripped: ${message}`);
    this.failure = message;
    this.containers.blockStorage(message);
    // retry removals on every tick, including after a failed docker operation.
    await Promise.all(this.containers.liveIds().map((id) => this.evict(id, message)));
  }

  private async evict(id: string, message: string): Promise<void> {
    await this.jobs.evict(id, message).catch((e) => console.error(`cannot stop workspace=[${id}]`, e));
  }
}
