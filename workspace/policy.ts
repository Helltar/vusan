import type { Config } from "./config.ts";
import type { Containers } from "./container.ts";
import type { Jobs } from "./jobs.ts";

const LOST_POLICY =
  "The workspace network policy is not in effect. Commands are paused; files were kept. Ask an administrator to check the host firewall.";

/**
 * The policy is proved once, at startup, and then lives on a host this service does not own. A firewall
 * frontend rebuilding the tables, an administrator flushing them, a switch to another tool — any of it
 * removes the rules without telling anyone, and the workspaces behind them keep running unconfined.
 * So it is re-read on a slow cadence, repaired if it has drifted, and admission closes if it cannot be.
 */
export class PolicyGuard {
  private failure: string | null = null;
  private checkedAt = 0;
  private checking = false;

  constructor(
    private readonly config: Config,
    private readonly containers: Containers,
    private readonly jobs: Jobs,
  ) {}

  get healthy(): boolean {
    return this.failure === null;
  }

  async tick(): Promise<void> {
    if (this.checking || !this.containers.policed) return;
    if (Date.now() - this.checkedAt < this.config.policyCheckSeconds * 1000) return;
    this.checking = true;
    this.checkedAt = Date.now();
    try {
      if (await this.containers.policyHolds()) return this.recovered();
      console.error("workspace network policy is missing from the host; reinstalling it");
      await this.containers.restorePolicy();
      this.recovered();
      console.log("workspace network policy reinstalled");
    } catch (e) {
      await this.latch(e);
    } finally {
      this.checking = false;
    }
  }

  private recovered(): void {
    if (!this.failure) return;
    this.failure = null;
    this.containers.blockPolicy(null);
    console.log("workspace network policy restored");
  }

  /** Nothing was confined while the rules were gone, so live workspaces stop along with admission. */
  private async latch(cause: unknown): Promise<void> {
    if (!this.failure) console.error("cannot restore the workspace network policy", cause);
    this.failure = LOST_POLICY;
    this.containers.blockPolicy(LOST_POLICY);
    for (const id of this.containers.liveIds()) {
      await this.jobs.evict(id, LOST_POLICY).catch((e) => console.error(`cannot stop workspace=[${id}]`, e));
    }
  }
}
