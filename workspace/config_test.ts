import { strictEqual, throws } from "node:assert/strict";
import { readConfig } from "./config.ts";

Deno.test("configuration fails closed on unsupported networking and invalid limits", () => {
  const settings = ["WORKSPACE_NETWORK", "WORKSPACE_MAX_ACTIVE", "WORKSPACE_NAMESPACE"];
  const saved = settings.map((name) => Deno.env.get(name));
  try {
    for (
      const [name, value] of [
        ["WORKSPACE_NETWORK", "external"],
        ["WORKSPACE_MAX_ACTIVE", "-1"],
        ["WORKSPACE_NAMESPACE", "../other"],
      ]
    ) {
      for (const key of settings) Deno.env.delete(key);
      Deno.env.set(name, value);
      throws(() => readConfig());
    }
    for (const key of settings) Deno.env.delete(key);
    strictEqual(readConfig().network, "open");
    strictEqual(readConfig().maxActive, 4);
    strictEqual(readConfig().namespace, "vusan");
  } finally {
    settings.forEach((name, index) => {
      const value = saved[index];
      if (value === undefined) Deno.env.delete(name);
      else Deno.env.set(name, value);
    });
  }
});
