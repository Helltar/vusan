// executed inside one workspace as its unprivileged user, never by the supervisor.
import { FILE_LIMIT, readBounded, RequestError } from "./protocol.ts";

export async function resolveFile(home: string, raw: string, createParents = false): Promise<string> {
  if (!raw || raw.length > 400 || raw.startsWith("/") || raw.includes("\0")) {
    throw new RequestError("Invalid relative path");
  }
  const parts = raw.split("/").filter((part) => part !== "" && part !== ".");
  if (parts.includes("..")) throw new RequestError("Path must stay inside the workspace");
  let path = home;
  for (const [index, part] of parts.entries()) {
    path += `/${part}`;
    let stat = await Deno.lstat(path).catch((e) => {
      if (e instanceof Deno.errors.NotFound) return null;
      throw e;
    });
    if (stat?.isSymlink) throw new RequestError("File transfers do not follow symlinks; copy the file first");
    if (index < parts.length - 1) {
      if (!stat && createParents) {
        await Deno.mkdir(path, { mode: 0o700 });
        stat = await Deno.lstat(path);
      }
      if (!stat?.isDirectory) throw new RequestError("Missing parent directory");
    }
  }
  return path;
}

async function transfer(action: string, raw: string): Promise<void> {
  const path = await resolveFile("/work", raw, action === "write");
  if (action === "write") {
    const bytes = await readBounded(Deno.stdin.readable, FILE_LIMIT);
    // rename replaces a raced final symlink instead of writing through it.
    const parent = path.slice(0, path.lastIndexOf("/"));
    const temporary = await Deno.makeTempFile({ dir: parent, prefix: ".upload-" });
    try {
      await Deno.writeFile(temporary, bytes, { mode: 0o600 });
      await Deno.rename(temporary, path);
    } finally {
      await Deno.remove(temporary).catch(() => {});
    }
    return;
  }
  const info = await Deno.lstat(path);
  if (!info.isFile) throw new RequestError("Not a regular file");
  if (info.size > FILE_LIMIT) throw new RequestError("File exceeds the 50 MB transfer limit");
  using file = await Deno.open(path, { read: true });
  const bytes = await readBounded(file.readable, FILE_LIMIT);
  let offset = 0;
  while (offset < bytes.length) offset += await Deno.stdout.write(bytes.subarray(offset));
}

if (import.meta.main) {
  try {
    await transfer(Deno.args[0], Deno.args[1]);
  } catch (e) {
    console.error(e instanceof Error ? e.message : "File transfer failed");
    Deno.exit(1);
  }
}
