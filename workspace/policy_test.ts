import { ok, strictEqual } from "node:assert/strict";
import { readConfig } from "./config.ts";
import type { Containers } from "./container.ts";
import type { Jobs } from "./jobs.ts";
import { PolicyGuard } from "./policy.ts";

Deno.test("a policy that disappears is reinstalled, and a workspace waits only if it cannot be", async () => {
  const config = { ...readConfig(), policyCheckSeconds: 0 };
  let holds = true;
  let restorable = true;
  let restores = 0;
  let blocked: string | null = null;
  const evicted: string[] = [];
  const containers = {
    policed: true,
    liveIds: () => ["u1", "u2"],
    policyHolds: () => Promise.resolve(holds),
    restorePolicy: () => {
      restores++;
      if (!restorable) return Promise.reject(new Error("iptables refused"));
      holds = true;
      return Promise.resolve();
    },
    blockPolicy: (message: string | null) => {
      blocked = message;
    },
  } as unknown as Containers;
  const jobs = {
    evict: (id: string) => {
      evicted.push(id);
      return Promise.resolve();
    },
  } as unknown as Jobs;
  const guard = new PolicyGuard(config, containers, jobs);

  // an intact policy costs one read and nothing else
  await guard.tick();
  strictEqual(restores, 0);
  ok(guard.healthy);

  // a missing one is repaired in place, without stopping anybody
  holds = false;
  await guard.tick();
  strictEqual(restores, 1);
  ok(guard.healthy);
  strictEqual(evicted.length, 0);

  // one that cannot be repaired closes admission and stops what was running unconfined
  holds = false;
  restorable = false;
  await guard.tick();
  ok(!guard.healthy);
  ok(String(blocked).includes("Commands are paused"));
  strictEqual(evicted.join(","), "u1,u2");

  // while it is closed, the cheap read no longer counts: a half-written policy passes it, so nothing
  // reopens until the rules have been rebuilt and proved again.
  holds = true;
  await guard.tick();
  ok(!guard.healthy);

  // and it lets everyone back in only once that succeeds
  restorable = true;
  await guard.tick();
  ok(guard.healthy);
  strictEqual(blocked, null);
});

Deno.test("an offline workspace service has no policy to watch", async () => {
  const config = { ...readConfig(), policyCheckSeconds: 0 };
  let reads = 0;
  const containers = {
    policed: false,
    policyHolds: () => {
      reads++;
      return Promise.resolve(true);
    },
  } as unknown as Containers;
  const guard = new PolicyGuard(config, containers, {} as unknown as Jobs);

  await guard.tick();
  strictEqual(reads, 0);
  ok(guard.healthy);
});
