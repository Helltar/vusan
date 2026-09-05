import { readConfig } from "./config.ts";
import { strictEqual, throws } from "node:assert/strict";
import { accumulateBurn, workspaceCapacity } from "./container.ts";

Deno.test("unattended cpu accumulates only between a workspace's own commands", () => {
  const second = 1_000_000;

  const first = accumulateBurn(undefined, 5 * second, false);
  strictEqual(first.seconds, 0, "the first reading is a baseline, not a measurement");

  const idle = accumulateBurn(first, 9 * second, false);
  strictEqual(idle.seconds, 4);

  const duringCommand = accumulateBurn(idle, 60 * second, true);
  strictEqual(duringCommand.seconds, 4, "a command's own cost is not unattended burn");
  strictEqual(duringCommand.usec, 60 * second);

  const afterCommand = accumulateBurn(duringCommand, 70 * second, false);
  strictEqual(afterCommand.seconds, 14);

  // `rebaseline` clears the mark a finished command left behind.
  const rebaselined = accumulateBurn({ usec: Number.NaN, seconds: 14 }, 500 * second, false);
  strictEqual(rebaselined.seconds, 14);
  strictEqual(accumulateBurn(rebaselined, 501 * second, false).seconds, 15);
});

Deno.test("a cgroup reading that moved backwards cannot credit a workspace", () => {
  const back = accumulateBurn({ usec: 90_000_000, seconds: 30 }, 10_000_000, false);
  strictEqual(back.seconds, 30);
  strictEqual(back.usec, 10_000_000);
});

Deno.test("container admission budgets all live containers against host memory and cores", () => {
  const config = { ...readConfig(), maxActive: 8, memoryMb: 1024, cpus: 1 };
  const gib = 1024 ** 3;
  strictEqual(workspaceCapacity(config, 4 * gib, 8), 2);
  strictEqual(workspaceCapacity(config, 32 * gib, 4), 2);
  strictEqual(workspaceCapacity(config, 2 * gib, 1), 1);
  strictEqual(workspaceCapacity(config, gib, 8), 0);
  strictEqual(workspaceCapacity({ ...config, cpus: 3 }, 32 * gib, 4), 0);
  strictEqual(workspaceCapacity({ ...config, maxActive: 1 }, 64 * gib, 32), 1);
  throws(() => workspaceCapacity(config, Number.NaN, 4));
  throws(() => workspaceCapacity(config, 8 * gib, 0));
});
