import { statfs } from "node:fs/promises";
import type { Config } from "./config.ts";
import { RequestError } from "./protocol.ts";

export async function checkStorage(config: Config): Promise<void> {
  const disk = await statfs(config.stateDir);
  const bytes = disk.bavail * disk.bsize;
  if (bytes < config.minFreeMb * 1024 * 1024 || disk.ffree < config.minFreeInodes) {
    throw new RequestError(
      "Workspace storage is low. An administrator must free disk space/inodes and restart the controller; files were kept.",
      507,
    );
  }
}
