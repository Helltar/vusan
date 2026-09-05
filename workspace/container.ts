import type { Config } from "./config.ts";
import { docker, dockerText, kill, spawnDocker } from "./docker.ts";
import { workspaceEnvironment } from "./env.ts";
import { FILE_LIMIT, readBounded, RequestError, workspaceId } from "./protocol.ts";

function empty(): ReadableStream<Uint8Array> {
  return new ReadableStream({ start: (controller) => controller.close() });
}

export class Containers {
  private readonly live = new Map<string, number>();
  private readonly leases = new Map<string, number>();
  private gate: Promise<unknown> = Promise.resolve();
  private image = "";
  private closing = false;
  private readonly label: string;

  constructor(private readonly config: Config) {
    this.label = `com.helltar.vusan.workspace=${config.namespace}`;
  }

  name(id: string): string {
    return `${this.config.namespace}-workspace-${workspaceId(id)}`;
  }

  reserve(id: string): Disposable {
    workspaceId(id);
    this.leases.set(id, (this.leases.get(id) ?? 0) + 1);
    return {
      [Symbol.dispose]: () => {
        const remaining = (this.leases.get(id) ?? 1) - 1;
        if (remaining) this.leases.set(id, remaining);
        else this.leases.delete(id);
        this.touch(id);
      },
    };
  }

  private exclusive<T>(action: () => Promise<T>): Promise<T> {
    const next = this.gate.then(action);
    this.gate = next.catch(() => {});
    return next;
  }

  async initialize(): Promise<void> {
    // a restart interrupts commands explicitly; their files live in independent volumes.
    const old = await dockerText(["ps", "-aq", "--filter", `label=${this.label}`]);
    for (const id of old.split("\n").filter(Boolean)) await docker(["rm", "-f", id]);
    this.image = await dockerText(["image", "inspect", "--format", "{{.Id}}", this.config.image]);
  }

  async ensure(id: string): Promise<string> {
    return await this.exclusive(async () => {
      if (this.closing) throw new RequestError("Workspace service is stopping", 503);
      const name = this.name(id);
      if (this.live.has(id)) {
        const running = await dockerText([
          "ps",
          "-q",
          "--filter",
          `label=${this.label}`,
          "--filter",
          `name=^/${name}$`,
        ]);
        if (running) {
          this.live.set(id, Date.now());
          return name;
        }
        await this.remove(id);
      }
      if (this.live.size >= this.config.maxActive) {
        const idle = [...this.live].filter(([key]) => !this.leases.has(key))
          .sort((a, b) => a[1] - b[1])[0];
        if (!idle) throw new RequestError("All workspace slots are busy; try again shortly", 409);
        await this.remove(idle[0]);
      }
      const volume = `${name}-home`;
      await docker(["volume", "create", "--label", this.label, volume]);
      const owner = await dockerText([
        "volume",
        "inspect",
        "--format",
        '{{index .Labels "com.helltar.vusan.workspace"}}',
        volume,
      ]);
      if (owner !== this.config.namespace) throw new Error("Workspace volume belongs to another owner");
      const env = { ...workspaceEnvironment(), WORKSPACE_NETWORK: this.config.network };
      try {
        await docker([
          "run",
          "--detach",
          "--pull=never",
          "--name",
          name,
          "--label",
          this.label,
          "--label",
          `com.helltar.vusan.workspace-id=${id}`,
          "--init",
          "--no-healthcheck",
          "--read-only",
          "--mount",
          `type=volume,src=${volume},dst=/work`,
          "--tmpfs",
          "/tmp:rw,nosuid,nodev,size=256m",
          "--tmpfs",
          "/run:rw,nosuid,nodev,size=16m",
          "--cap-drop=ALL",
          "--cap-add=NET_ADMIN",
          "--cap-add=NET_RAW",
          "--cap-add=SETUID",
          "--cap-add=SETGID",
          "--cap-add=SETPCAP",
          "--security-opt=no-new-privileges",
          "--pids-limit",
          String(this.config.pids),
          "--ulimit",
          `fsize=${this.config.maxFileMb * 1024 * 1024}`,
          "--memory",
          `${this.config.memoryMb}m`,
          "--memory-swap",
          `${this.config.memoryMb}m`,
          "--cpus",
          String(this.config.cpus),
          "--network",
          this.config.network === "none" ? "none" : "bridge",
          "--sysctl",
          "net.ipv6.conf.all.disable_ipv6=1",
          "--sysctl",
          "net.ipv6.conf.default.disable_ipv6=1",
          ...(this.config.network === "open" ? ["--dns", "1.1.1.1", "--dns", "8.8.8.8"] : []),
          ...(this.config.writeDevice && this.config.writeBps
            ? ["--device-write-bps", `${this.config.writeDevice}:${this.config.writeBps}`]
            : []),
          "--log-driver",
          "local",
          "--log-opt",
          "max-size=1m",
          "--log-opt",
          "max-file=2",
          ...Object.entries(env).flatMap(([key, value]) => ["--env", `${key}=${value}`]),
          this.image,
          "workspace",
        ]);
        this.live.set(id, Date.now());
        await docker([
          "exec",
          "--user",
          "1000:1000",
          name,
          "/usr/bin/timeout",
          "15",
          "/usr/bin/sh",
          "-c",
          "until test -f /run/workspace-ready; do sleep 0.1; done; mkdir -p /work/tmp /work/inbox",
        ]);
      } catch (e) {
        const details = await docker(["logs", "--tail", "10", name], { includeStderr: true })
          .then((out) => new TextDecoder().decode(out).trim()).catch(() => "");
        await this.remove(id);
        throw new Error(`Workspace startup failed: ${details || String(e)}`);
      }
      return name;
    });
  }

  command(id: string, command: string): Deno.ChildProcess {
    return spawnDocker([
      "exec",
      "--user",
      "1000:1000",
      "--workdir",
      "/work",
      this.name(id),
      "/usr/bin/env",
      "-i",
      ...Object.entries(workspaceEnvironment()).map(([key, value]) => `${key}=${value}`),
      "/usr/bin/bash",
      "-lc",
      command,
    ]);
  }

  private helper(name: string, action: "read" | "write", path: string): Deno.ChildProcess {
    return spawnDocker([
      "exec",
      ...(action === "write" ? ["-i"] : []),
      "--user",
      "1000:1000",
      name,
      "/usr/bin/timeout",
      "-k",
      "1",
      "20",
      "/usr/bin/deno",
      "run",
      "--no-prompt",
      "--allow-read=/work",
      "--allow-write=/work",
      "/app/files.ts",
      action,
      path,
    ], action === "write");
  }

  private async helperFailure(stderr: Promise<Uint8Array>): Promise<RequestError> {
    const reason = new TextDecoder().decode(await stderr).trim().slice(0, 1000);
    return new RequestError(reason || "File transfer failed", 422);
  }

  /** Bytes reach the helper's stdin as they arrive; nothing the size of a whole file is ever held here. */
  async writeFile(id: string, path: string, body: ReadableStream<Uint8Array> | null): Promise<number> {
    using _lease = this.reserve(id);
    const name = await this.ensure(id);
    const child = this.helper(name, "write", path);
    const stderr = readBounded(child.stderr, 16 * 1024);
    const stdout = readBounded(child.stdout, 16 * 1024);
    let bytes = 0;
    let overflow = false;
    try {
      const writer = child.stdin.getWriter();
      try {
        for await (const chunk of body ?? empty()) {
          bytes += chunk.length;
          if (bytes > FILE_LIMIT) {
            overflow = true;
            break;
          }
          await writer.write(chunk);
        }
        if (!overflow) await writer.close();
      } catch {
        /* the helper closed the pipe; its exit status carries the reason */
      } finally {
        writer.releaseLock();
      }
      if (overflow) throw new RequestError("File exceeds the 50 MB transfer limit", 413);
      await stdout;
      if (!(await child.status).success) throw await this.helperFailure(stderr);
      return bytes;
    } finally {
      kill(child);
      await child.status;
    }
  }

  /**
   * The helper validates the path and size before it writes anything, so one peek at stdout separates a
   * clean rejection from a transfer that has started. `release` runs once, whenever the stream ends.
   */
  async readFile(id: string, path: string, release: () => void): Promise<ReadableStream<Uint8Array>> {
    const lease = this.reserve(id);
    let child: Deno.ChildProcess | undefined;
    let closed = false;
    const done = () => {
      if (closed) return;
      closed = true;
      lease[Symbol.dispose]();
      release();
    };
    try {
      const name = await this.ensure(id);
      child = this.helper(name, "read", path);
      const process = child;
      const stderr = readBounded(process.stderr, 16 * 1024);
      const reader = process.stdout.getReader();
      const first = await reader.read();
      if (first.done && !(await process.status).success) throw await this.helperFailure(stderr);
      let bytes = first.value?.length ?? 0;
      return new ReadableStream<Uint8Array>({
        start: (controller) => {
          if (first.value) controller.enqueue(first.value);
        },
        pull: async (controller) => {
          const next = await reader.read();
          if (next.done) {
            const status = await process.status;
            done();
            if (status.success) controller.close();
            else controller.error(new Error("File transfer failed"));
            return;
          }
          bytes += next.value.length;
          if (bytes > FILE_LIMIT) {
            kill(process);
            done();
            controller.error(new Error("File exceeds the 50 MB transfer limit"));
            return;
          }
          controller.enqueue(next.value);
        },
        cancel: () => {
          kill(process);
          done();
        },
      });
    } catch (e) {
      if (child) kill(child);
      done();
      throw e;
    }
  }

  async usage(id: string): Promise<number> {
    const out = await dockerText([
      "exec",
      "--user",
      "1000:1000",
      this.name(id),
      "/usr/bin/timeout",
      "5",
      "/usr/bin/du",
      "-sb",
      "/work",
    ]);
    return Number.parseInt(out, 10);
  }

  liveIds(): string[] {
    return [...this.live.keys()];
  }

  touch(id: string): void {
    if (this.live.has(id)) this.live.set(id, Date.now());
  }

  async stop(id: string): Promise<void> {
    await this.exclusive(() => this.remove(id));
  }

  async shutdown(): Promise<void> {
    this.closing = true;
    await this.exclusive(async () => {
      await Promise.all([...this.live.keys()].map((id) => this.remove(id)));
    });
  }

  private async remove(id: string): Promise<void> {
    const owned = await dockerText([
      "ps",
      "-aq",
      "--filter",
      `label=${this.label}`,
      "--filter",
      `name=^/${this.name(id)}$`,
    ]);
    if (owned) await docker(["rm", "-f", owned]);
    this.live.delete(id);
  }

  async sweep(busy: (id: string) => boolean): Promise<void> {
    await this.exclusive(async () => {
      const cutoff = Date.now() - this.config.idleMinutes * 60_000;
      for (const [id, touched] of this.live) {
        if (touched < cutoff && !busy(id) && !this.leases.has(id)) await this.remove(id);
      }
    });
  }
}
