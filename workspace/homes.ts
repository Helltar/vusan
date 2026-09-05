import type { Config } from "./config.ts";
import { docker, dockerText } from "./docker.ts";
import { RequestError } from "./protocol.ts";

/** A fixed, preallocated filesystem bounds both bytes and inodes without host filesystem setup. */
export class Homes {
  constructor(private readonly config: Config, private readonly image: string) {}

  private async inspect(name: string) {
    const exists = await dockerText(["volume", "ls", "-q", "--filter", `name=^${name}$`]);
    if (!exists) return null;
    const [volume] = JSON.parse(await dockerText(["volume", "inspect", name]));
    if (volume.Labels?.["com.helltar.vusan.workspace"] !== this.config.namespace) {
      throw new Error("Workspace volume belongs to another owner");
    }
    return volume;
  }

  private async helper(name: string, action: "prepare" | "release"): Promise<string> {
    return new TextDecoder().decode(
      await docker([
        "run",
        "--rm",
        "--pull=never",
        "--network=none",
        "--read-only",
        "--name",
        `${name}-storage`,
        "--label",
        `com.helltar.vusan.workspace=${this.config.namespace}`,
        "--cap-drop=ALL",
        "--cap-add=SYS_ADMIN",
        "--cap-add=MKNOD",
        "--security-opt=no-new-privileges",
        "--pids-limit=32",
        "--memory=128m",
        "--memory-swap=128m",
        "--cpus=1",
        "--tmpfs",
        "/tmp:size=16m",
        "--device-cgroup-rule",
        "b 7:* rwm",
        "--device",
        "/dev/loop-control",
        // loop nodes must also exist in the daemon's /dev; only this fixed trusted helper sees that mount.
        "--mount",
        "type=bind,src=/dev,dst=/dev",
        "--mount",
        `type=volume,src=${name}-disk,dst=/storage`,
        "--entrypoint",
        "/usr/local/bin/home-disk.sh",
        this.image,
        action,
        String(this.config.maxHomeMb),
        String(this.config.minFreeMb),
      ], { timeoutMs: 120_000 }),
    ).trim();
  }

  async open(name: string, existingOnly = false): Promise<string> {
    const home = await this.inspect(`${name}-home`);
    if (home && home.Labels?.["com.helltar.vusan.storage"] !== "bounded-home") {
      throw new Error("Legacy workspace volume needs migration to a bounded disk; existing files were kept");
    }
    if (existingOnly && !await this.inspect(`${name}-disk`)) {
      throw new RequestError("Workspace has no files to delete", 404);
    }
    if (home) await docker(["volume", "rm", `${name}-home`]);
    await docker([
      "volume",
      "create",
      "--label",
      `com.helltar.vusan.workspace=${this.config.namespace}`,
      "--label",
      "com.helltar.vusan.storage=backing-disk",
      `${name}-disk`,
    ]);
    const disk = await this.inspect(`${name}-disk`);
    if (
      disk?.Labels?.["com.helltar.vusan.storage"] !== "backing-disk" || disk.Driver !== "local" ||
      (disk.Options && Object.keys(disk.Options).length)
    ) {
      throw new Error("Workspace backing disk must use an ordinary local Docker volume");
    }
    const device = await this.helper(name, "prepare");
    if (!/^\/dev\/loop[0-9]+$/.test(device)) throw new Error("Invalid workspace loop device");
    await docker([
      "volume",
      "create",
      "--label",
      `com.helltar.vusan.workspace=${this.config.namespace}`,
      "--label",
      "com.helltar.vusan.storage=bounded-home",
      "--driver",
      "local",
      "--opt",
      "type=ext4",
      "--opt",
      `device=${device}`,
      "--opt",
      "o=rw,nosuid,nodev,nodiscard",
      `${name}-home`,
    ]);
    return device;
  }

  /** Drops the home outright, backing image included; the next `open` formats an empty one. */
  async destroy(name: string): Promise<void> {
    // unlike `close`, this also takes an unbounded volume left by an older build: nothing is kept.
    if (await this.inspect(`${name}-home`)) await docker(["volume", "rm", `${name}-home`]);
    const disk = await this.inspect(`${name}-disk`);
    if (disk?.Labels?.["com.helltar.vusan.storage"] === "backing-disk") {
      await this.helper(name, "release");
      await docker(["volume", "rm", `${name}-disk`]);
    }
  }

  async close(name: string): Promise<void> {
    const home = await this.inspect(`${name}-home`);
    if (home?.Labels?.["com.helltar.vusan.storage"] === "bounded-home") {
      await docker(["volume", "rm", `${name}-home`]);
    }
    const disk = await this.inspect(`${name}-disk`);
    if (disk?.Labels?.["com.helltar.vusan.storage"] === "backing-disk") await this.helper(name, "release");
  }
}
