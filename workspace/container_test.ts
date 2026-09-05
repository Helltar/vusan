import { strictEqual } from "node:assert/strict";
import { accumulateBurn } from "./container.ts";

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
