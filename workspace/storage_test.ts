import { rejects } from "node:assert/strict";
import { statfs } from "node:fs/promises";
import { readConfig } from "./config.ts";
import { checkStorage } from "./storage.ts";

Deno.test("storage admission fails closed for low bytes or inodes without filling a disk", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root, minFreeMb: 1, minFreeInodes: 1 };
    await checkStorage(config);
    const disk = await statfs(root);
    await rejects(
      () => checkStorage({ ...config, minFreeMb: disk.blocks * disk.bsize / 1024 / 1024 + 1 }),
      /storage is low/,
    );
    await rejects(() => checkStorage({ ...config, minFreeInodes: disk.files + 1 }), /storage is low/);
    await rejects(() => checkStorage({ ...config, stateDir: `${root}/missing` }));
  } finally {
    await Deno.remove(root);
  }
});
