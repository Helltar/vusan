import { strictEqual, throws } from "node:assert/strict";
import { statfs } from "node:fs/promises";
import { readConfig } from "./config.ts";
import { Containers } from "./container.ts";
import { Jobs } from "./jobs.ts";
import { DiskGuard } from "./storage.ts";

Deno.test("the storage guard latches on pressure and clears itself once space returns", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root, minFreeMb: 1, minFreeInodes: 1 };
    const containers = new Containers(config);
    const guard = new DiskGuard(config, containers, new Jobs(config, containers));
    const disk = await statfs(root);

    await guard.tick();
    strictEqual(guard.healthy, true);
    guard.guardUploads();

    config.minFreeMb = disk.blocks * disk.bsize / 1024 / 1024 + 1;
    await guard.tick();
    strictEqual(guard.healthy, false);
    throws(() => guard.guardUploads(), /storage is low/);

    config.minFreeMb = 1;
    await guard.tick();
    strictEqual(guard.healthy, true);

    config.minFreeInodes = disk.files + 1;
    await guard.tick();
    strictEqual(guard.healthy, false);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("a state filesystem it cannot read fails closed for uploads", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: `${root}/missing` };
    const containers = new Containers(config);
    const guard = new DiskGuard(config, containers, new Jobs(config, containers));
    await guard.tick();
    strictEqual(guard.healthy, false);
    throws(() => guard.guardUploads(), /Cannot verify/);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});
