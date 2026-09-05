import { rejects, strictEqual } from "node:assert/strict";
import { deleteWorkspacePath, resolveFile } from "./files.ts";
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
      await rejects(() => resolveFile(`${root}/home`, path, "write"), RequestError);
    }
    await rejects(() => Deno.stat(`${root}/outside/new`), Deno.errors.NotFound);
    await rejects(() => Deno.stat(`${root}/home/new`), Deno.errors.NotFound);
    strictEqual(
      await resolveFile(`${root}/home`, "project/src/main.py", "write"),
      `${root}/home/project/src/main.py`,
    );
    strictEqual((await Deno.stat(`${root}/home/project/src`)).isDirectory, true);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("cleanup removes inaccessible trees and final links without following parent links", async () => {
  const root = await Deno.makeTempDir();
  try {
    await Deno.mkdir(`${root}/home`);
    await Deno.mkdir(`${root}/outside`);
    await Deno.writeTextFile(`${root}/outside/kept`, "keep");
    await Deno.symlink(`${root}/outside`, `${root}/home/link`);
    await rejects(() => deleteWorkspacePath(`${root}/home`, "link/kept"), RequestError);
    for (const path of [".", "./", "../outside", "/", ""]) {
      await rejects(() => deleteWorkspacePath(`${root}/home`, path), RequestError);
    }
    await deleteWorkspacePath(`${root}/home`, "link");
    strictEqual(await Deno.readTextFile(`${root}/outside/kept`), "keep");
    await Deno.mkdir(`${root}/home/closed/nested`, { recursive: true });
    await Deno.writeTextFile(`${root}/home/closed/nested/file`, "discard");
    await Deno.chmod(`${root}/home/closed/nested`, 0);
    await Deno.chmod(`${root}/home/closed`, 0);
    await deleteWorkspacePath(`${root}/home`, "closed");
    await rejects(() => Deno.stat(`${root}/home/closed`), Deno.errors.NotFound);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});
