import { strictEqual, throws } from "node:assert/strict";
import { readConfig } from "../config.ts";

Deno.test("configuration fails closed on unsupported networking and invalid limits", () => {
  const settings = [
    "WORKSPACE_NETWORK",
    "WORKSPACE_MAX_ACTIVE",
    "WORKSPACE_NAMESPACE",
    "WORKSPACE_MAX_HOME_MB",
    "WORKSPACE_MAX_FILE_MB",
    "WORKSPACE_IDLE_CPU_SECONDS",
    "WORKSPACE_NETWORK_MBIT",
    "WORKSPACE_BLOCKED_CIDRS",
    "WORKSPACE_WRITE_BPS",
    "WORKSPACE_READ_BPS",
  ];
  const saved = settings.map((name) => Deno.env.get(name));
  try {
    for (
      const [name, value] of [
        ["WORKSPACE_NETWORK", "external"],
        ["WORKSPACE_MAX_ACTIVE", "-1"],
        ["WORKSPACE_NAMESPACE", "../other"],
        ["WORKSPACE_MAX_HOME_MB", "0"],
        ["WORKSPACE_MAX_FILE_MB", "8"],
        ["WORKSPACE_IDLE_CPU_SECONDS", "0"],
        ["WORKSPACE_NETWORK_MBIT", "10mbit"],
        ["WORKSPACE_BLOCKED_CIDRS", "1.2.3.999/24"],
        ["WORKSPACE_WRITE_BPS", "unlimited"],
        ["WORKSPACE_READ_BPS", "fast"],
      ]
    ) {
      for (const key of settings) Deno.env.delete(key);
      Deno.env.set(name, value);
      throws(() => readConfig());
    }
    for (const key of settings) Deno.env.delete(key);
    strictEqual(readConfig().network, "open");
    strictEqual(readConfig().maxActive, 2);
    strictEqual(readConfig().namespace, "vusan");
    strictEqual(readConfig().maxHomeMb, 4096);
    strictEqual(readConfig().maxFileMb, 4096);
    strictEqual(readConfig().writeBps, "50mb");
    strictEqual(readConfig().readBps, "100mb");
    // `none` is the only way to ask for no cap at all; an empty value still means the default.
    Deno.env.set("WORKSPACE_WRITE_BPS", "none");
    strictEqual(readConfig().writeBps, null);
    Deno.env.delete("WORKSPACE_WRITE_BPS");
    strictEqual(readConfig().idleCpuSeconds, 600);
    strictEqual(readConfig().networkMbit, "50");
  } finally {
    settings.forEach((name, index) => {
      const value = saved[index];
      if (value === undefined) Deno.env.delete(name);
      else Deno.env.set(name, value);
    });
  }
});
