import { rejects, strictEqual, throws } from "node:assert/strict";
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
    guard.guardWrites();

    config.minFreeMb = disk.blocks * disk.bsize / 1024 / 1024 + 1;
    await guard.tick();
    strictEqual(guard.healthy, false);
    throws(() => guard.guardWrites(), /storage is low/);

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
    throws(() => guard.guardWrites(), /Cannot verify/);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("pressure retries failed evictions and blocks both queued startup and shell execution", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root, minFreeMb: Number.MAX_SAFE_INTEGER };
    class ObservedContainers extends Containers {
      stops = 0;
      override liveIds() {
        return ["u1"];
      }
      override stop(_id: string): Promise<void> {
        this.stops++;
        return Promise.reject(new Error("temporary daemon failure"));
      }
      override usage(_id: string): Promise<number> {
        return Promise.reject(new Error("must not measure during pressure"));
      }
    }
    const containers = new ObservedContainers(config);
    const guard = new DiskGuard(config, containers, new Jobs(config, containers));
    await guard.tick();
    await guard.tick();
    strictEqual(containers.stops, 2);
    await rejects(() => containers.ensure("u1"), /storage is low/);
    throws(() => containers.command("u1", "printf refused"), /storage is low/);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("unreadable homes are evicted and slow measurements cannot stall the host reserve", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root, minFreeMb: 1, minFreeInodes: 1 };
    const measurement = Promise.withResolvers<number>();
    class ObservedContainers extends Containers {
      stopped: string[] = [];
      override liveIds() {
        return ["u1", "u2"];
      }
      override stop(id: string): Promise<void> {
        this.stopped.push(id);
        return Promise.resolve();
      }
      override async usage(id: string): Promise<number> {
        if (id === "u1") throw new Error("permission denied");
        return await measurement.promise;
      }
    }
    const containers = new ObservedContainers(config);
    const guard = new DiskGuard(config, containers, new Jobs(config, containers));
    await guard.tick();
    const probe = guard.checkHomes();
    // let the first failed measurement reach eviction, then keep the second measurement pending.
    await new Promise((resolve) => setTimeout(resolve, 0));
    strictEqual(containers.stopped.includes("u1"), true);
    config.minFreeMb = Number.MAX_SAFE_INTEGER;
    await guard.tick();
    strictEqual(guard.healthy, false);
    strictEqual(containers.stopped.includes("u2"), true);
    measurement.resolve(0);
    await probe;
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});
