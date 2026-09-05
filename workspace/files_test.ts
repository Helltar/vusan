import { rejects, strictEqual } from "node:assert/strict";
import { resolveFile } from "./files.ts";
import { RequestError } from "./protocol.ts";

Deno.test("file paths refuse traversal and symlinks before creating parents", async () => {
  const root = await Deno.makeTempDir();
  try {
    await Deno.mkdir(`${root}/home`);
    await Deno.mkdir(`${root}/outside`);
    await Deno.symlink(`${root}/outside`, `${root}/home/link`);
    for (
      const path of ["../outside/new/file", "new/../../file", "link/new/file", "/etc/passwd", "\0bad", ""]
    ) {
      await rejects(() => resolveFile(`${root}/home`, path, true), RequestError);
    }
    await rejects(() => Deno.stat(`${root}/outside/new`), Deno.errors.NotFound);
    await rejects(() => Deno.stat(`${root}/home/new`), Deno.errors.NotFound);
    strictEqual(
      await resolveFile(`${root}/home`, "project/src/main.py", true),
      `${root}/home/project/src/main.py`,
    );
    strictEqual((await Deno.stat(`${root}/home/project/src`)).isDirectory, true);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});
