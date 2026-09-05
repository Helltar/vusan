import type { Config } from "./config.ts";
import { docker, dockerText, kill, spawnDocker } from "./docker.ts";
import { workspaceEnvironment } from "./env.ts";
import { Homes } from "./homes.ts";
import { FILE_LIMIT, readBounded, RequestError, workspaceId } from "./protocol.ts";

// workspaces get the CPU nobody else wants: a runaway one must not slow the bot, the database or a
// waiting administrator. it caps nothing on an idle host, which is what `--cpus` is for.
const WORKSPACE_CPU_SHARES = 256;

// docker builds every exec's environment from the container's own, which carries the host's interface
// addresses in WORKSPACE_BLOCKED_CIDRS — readable in /proc for as long as the process lives. nothing
// exec'd here needs it: these commands are absolute paths, and the helper is told where its cache goes
// rather than falling back to one inside the user's home. keep DENO_DIR equal to the image's own.
const CLEAN_ENV = ["/usr/bin/env", "-i"];
const HELPER_ENV = [...CLEAN_ENV, "DENO_DIR=/tmp/deno-cache"];

// one chain per namespace: two controllers on the same host must never flush each other's rules.
function policyChain(namespace: string): string {
  return `WS_${namespace.toUpperCase().replace(/[^A-Z0-9]/g, "_").slice(0, 20)}`;
}

// what a workspace must not be able to open: the metadata service, private space in each RFC 1918
// range, and the machine hosting it — the controller's own API included. Printing anything means the
// policy is not doing its job, and the controller refuses to serve.
const PROBE = `
for target in 169.254.169.254 10.255.255.254 172.31.255.254 192.168.255.254; do
  for port in 80 8080; do
    timeout 2 bash -c "exec 3<>/dev/tcp/$target/$port" 2>/dev/null && echo "reached $target:$port"
  done
done
gateway=$(ip -o -4 route show default | awk '{print $3; exit}')
timeout 3 bash -c "exec 3<>/dev/tcp/$gateway/8080" 2>/dev/null && echo "reached the host at $gateway:8080"
exit 0
`;

/** Keep the workspace pool within half the host's memory and cores, sharing a single-core host. */
export function workspaceCapacity(config: Config, memoryBytes: number, cpus: number): number {
  if (!Number.isSafeInteger(memoryBytes) || memoryBytes <= 0 || !Number.isSafeInteger(cpus) || cpus <= 0) {
    throw new Error("Cannot determine Docker host resource capacity");
  }
  return Math.min(
    config.maxActive,
    Math.floor(memoryBytes / 2 / (config.memoryMb * 1024 * 1024)),
    Math.floor(Math.max(1, Math.floor(cpus / 2)) / config.cpus),
  );
}

export interface Burn {
  usec: number;
  seconds: number;
}

/**
 * Unattended CPU is what a workspace spends while none of its own commands is running. A tick during a
 * command, and the tick right after one, only re-baseline: that cost was asked for and is already
 * bounded by the command timeout.
 */
export function accumulateBurn(previous: Burn | undefined, usec: number, attended: boolean): Burn {
  const seconds = previous?.seconds ?? 0;
  if (attended || !previous || !Number.isFinite(previous.usec)) return { usec, seconds };
  return { usec, seconds: seconds + Math.max(0, usec - previous.usec) / 1_000_000 };
}

function isWorkspaceId(value: string): boolean {
  return /^u(?:0|[1-9][0-9]{0,18})$/.test(value);
}

function empty(): ReadableStream<Uint8Array> {
  return new ReadableStream({ start: (controller) => controller.close() });
}

export class Containers {
  private readonly live = new Map<string, number>();
  private readonly burn = new Map<string, Burn>();
  private readonly leases = new Map<string, number>();
  private gate: Promise<unknown> = Promise.resolve();
  private image = "";
  private homes!: Homes;
  private capacity = 1;
  private network = "none";
  private closing = false;
  private storageFailure: string | null = null;
  private readonly cleaning = new Set<string>();
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
    this.homes = new Homes(this.config, this.image);
    const info = JSON.parse(await dockerText(["info", "--format", "{{json .}}"]));
    if (info.CgroupVersion !== "2" || !info.MemoryLimit || !info.PidsLimit || !info.CpuCfsQuota) {
      throw new Error("Workspace requires cgroup v2 with memory, process and CPU limits");
    }
    this.capacity = workspaceCapacity(this.config, info.MemTotal, info.NCPU);
    if (this.capacity < 1) throw new Error("Not enough host memory or CPU budget for a workspace");
    if (this.config.network === "open") await this.openNetwork();
    const previous = await dockerText([
      "volume",
      "ls",
      "-q",
      "--filter",
      `label=${this.label}`,
      "--filter",
      "label=com.helltar.vusan.storage=backing-disk",
    ]);
    for (const disk of previous.split("\n").filter(Boolean)) {
      if (!disk.startsWith(`${this.config.namespace}-workspace-u`) || !disk.endsWith("-disk")) {
        throw new Error("Invalid workspace backing volume name");
      }
      await this.homes.close(disk.slice(0, -5));
    }
  }

  /**
   * The pool gets a network of its own, and its policy is installed in the Docker host's namespace
   * rather than inside each container: a workspace holds no capabilities and cannot reach the rules
   * that bound it. Inter-container traffic is off at the bridge as well, so the two locks are
   * independent. A policy that cannot be installed, or that does not hold, stops startup.
   */
  private async openNetwork(): Promise<void> {
    const name = `${this.config.namespace}-workspaces`;
    if (!await dockerText(["network", "ls", "-q", "--filter", `name=^${name}$`])) {
      await docker([
        "network",
        "create",
        "--driver",
        "bridge",
        "--label",
        this.label,
        "--opt",
        "com.docker.network.bridge.enable_icc=false",
        name,
      ]);
    }
    const [network] = JSON.parse(await dockerText(["network", "inspect", name]));
    if (network.Labels?.["com.helltar.vusan.workspace"] !== this.config.namespace) {
      throw new Error("Workspace network belongs to another owner");
    }
    const subnet = network.IPAM?.Config?.[0]?.Subnet ?? "";
    if (!/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}\/[0-9]{1,2}$/.test(subnet)) {
      throw new Error("Cannot read the workspace network subnet");
    }
    // the daemon derives a bridge from the network id unless it was given a name of its own.
    const bridge = network.Options?.["com.docker.network.bridge.name"] || `br-${network.Id.slice(0, 12)}`;
    await docker([
      "run",
      "--rm",
      "--pull=never",
      "--network=host",
      "--read-only",
      "--tmpfs",
      "/run:size=1m",
      "--cap-drop=ALL",
      "--cap-add=NET_ADMIN",
      "--security-opt=no-new-privileges",
      "--pids-limit=32",
      "--memory=64m",
      "--entrypoint",
      "/usr/local/bin/netpolicy.sh",
      this.image,
      policyChain(this.config.namespace),
      bridge,
      subnet,
      this.config.networkMbit ?? "",
      ...this.config.blockedCidrs,
    ], { timeoutMs: 120_000 });
    this.network = name;
    await this.verifyNetwork();
  }

  /** Installing rules is not the same as having them: this asks a workspace what it can actually reach. */
  private async verifyNetwork(): Promise<void> {
    const reachable = await docker([
      "run",
      "--rm",
      "--pull=never",
      "--network",
      this.network,
      "--read-only",
      "--user=1000:1000",
      "--cap-drop=ALL",
      "--security-opt=no-new-privileges",
      "--pids-limit=32",
      "--memory=64m",
      "--entrypoint",
      "/usr/bin/bash",
      this.image,
      "-c",
      PROBE,
    ], { timeoutMs: 120_000, includeStderr: true });
    const reached = new TextDecoder().decode(reachable).trim();
    if (reached) throw new Error(`Workspace network policy is not in effect: ${reached}`);
  }

  async ensure(id: string): Promise<string> {
    return await this.exclusive(() => this.ensureLocked(id));
  }

  blockStorage(message: string | null): void {
    this.storageFailure = message;
  }

  private admit(id: string): void {
    if (this.storageFailure) throw new RequestError(this.storageFailure, 507);
    if (this.cleaning.has(id)) throw new RequestError("Workspace cleanup is in progress", 409);
  }

  private async ensureLocked(id: string, cleanup = false): Promise<string> {
    if (this.closing) throw new RequestError("Workspace service is stopping", 503);
    if (!cleanup) this.admit(id);
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
    if (this.live.size >= this.capacity) {
      const idle = [...this.live].filter(([key]) => !this.leases.has(key))
        .sort((a, b) => a[1] - b[1])[0];
      if (!idle) throw new RequestError("All workspace slots are busy; try again shortly", 409);
      await this.remove(idle[0]);
    }
    const volume = `${name}-home`;
    const device = await this.homes.open(name, cleanup);
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
        "--user",
        "1000:1000",
        "--mount",
        `type=volume,src=${volume},dst=/work`,
        "--tmpfs",
        "/tmp:rw,nosuid,nodev,size=256m",
        "--tmpfs",
        "/run:rw,nosuid,nodev,size=16m",
        "--cap-drop=ALL",
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
        "--cpu-shares",
        String(WORKSPACE_CPU_SHARES),
        "--network",
        cleanup || this.config.network === "none" ? "none" : this.network,
        "--sysctl",
        "net.ipv6.conf.all.disable_ipv6=1",
        "--sysctl",
        "net.ipv6.conf.default.disable_ipv6=1",
        ...(this.config.network === "open" && !cleanup ? ["--dns", "1.1.1.1", "--dns", "8.8.8.8"] : []),
        ...(this.config.writeBps ? ["--device-write-bps", `${device}:${this.config.writeBps}`] : []),
        ...(this.config.readBps ? ["--device-read-bps", `${device}:${this.config.readBps}`] : []),
        "--log-driver",
        "local",
        "--log-opt",
        "max-size=1m",
        "--log-opt",
        "max-file=2",
        ...Object.entries(workspaceEnvironment()).flatMap(([key, value]) => ["--env", `${key}=${value}`]),
        // nothing in a workspace container runs as root, not even for the instant before it drops.
        "--entrypoint",
        "/usr/bin/sleep",
        this.image,
        "infinity",
      ]);
      this.live.set(id, Date.now());
      this.burn.set(id, { usec: 0, seconds: 0 });
      await this.settled(name, cleanup);
    } catch (e) {
      const details = await docker(["logs", "--tail", "10", name], { includeStderr: true })
        .then((out) => new TextDecoder().decode(out).trim()).catch(() => "");
      await this.remove(id);
      throw new Error(`Workspace startup failed: ${details || String(e)}`);
    }
    return name;
  }

  /** `docker run` returns once the container is started, which an exec can still narrowly lose to. */
  private async settled(name: string, cleanup: boolean): Promise<void> {
    for (let attempt = 0;; attempt++) {
      try {
        await docker([
          "exec",
          "--user",
          "1000:1000",
          name,
          ...CLEAN_ENV,
          "/usr/bin/timeout",
          "15",
          "/usr/bin/sh",
          "-c",
          cleanup ? "true" : "/usr/bin/mkdir -p /work/tmp /work/inbox",
        ]);
        return;
      } catch (e) {
        if (attempt >= 5) throw e;
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
    }
  }

  command(id: string, command: string): Deno.ChildProcess {
    this.admit(id);
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

  private helper(name: string, action: "read" | "write" | "delete", path: string): Deno.ChildProcess {
    return spawnDocker([
      "exec",
      ...(action === "write" ? ["-i"] : []),
      "--user",
      "1000:1000",
      name,
      ...HELPER_ENV,
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

  /** Cleanup runs alone in a fresh, offline container and never loads a user shell profile. */
  async deleteFile(id: string, path: string): Promise<void> {
    if (this.cleaning.has(id)) throw new RequestError("Workspace cleanup is in progress", 409);
    using _lease = this.reserve(id);
    this.cleaning.add(id);
    try {
      await this.exclusive(async () => {
        await this.remove(id);
        try {
          const name = await this.ensureLocked(id, true);
          const child = this.helper(name, "delete", path);
          try {
            const [, stderr, status] = await Promise.all([
              readBounded(child.stdout, 16 * 1024),
              readBounded(child.stderr, 16 * 1024),
              child.status,
            ]);
            if (!status.success) throw await this.helperFailure(Promise.resolve(stderr));
          } finally {
            kill(child);
            await child.status;
          }
        } finally {
          await this.remove(id);
        }
      });
    } finally {
      this.cleaning.delete(id);
    }
  }

  /**
   * Empties a workspace by dropping the home itself rather than walking it: a home holding a million
   * files, or one whose directories were made unreadable, is removed just as fast as an empty one.
   * The next command formats a fresh home in its place.
   */
  async wipe(id: string): Promise<void> {
    if (this.cleaning.has(id)) throw new RequestError("Workspace cleanup is in progress", 409);
    using _lease = this.reserve(id);
    this.cleaning.add(id);
    try {
      await this.exclusive(async () => {
        await this.remove(id);
        await this.homes.destroy(this.name(id));
      });
    } finally {
      this.cleaning.delete(id);
    }
  }

  private async helperFailure(stderr: Promise<Uint8Array>): Promise<RequestError> {
    const reason = new TextDecoder().decode(await stderr).trim().slice(0, 1000);
    return new RequestError(reason || "File transfer failed", 422);
  }

  /** Bytes reach the helper's stdin as they arrive; nothing the size of a whole file is ever held here. */
  async writeFile(id: string, path: string, body: ReadableStream<Uint8Array> | null): Promise<number> {
    using _lease = this.reserve(id);
    const name = await this.ensure(id);
    this.admit(id);
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
      this.admit(id);
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
      ...CLEAN_ENV,
      "/usr/bin/timeout",
      "5",
      "/usr/bin/du",
      "-sB1",
      "/work",
    ]);
    const bytes = Number(out.split(/\s+/)[0]);
    if (!Number.isSafeInteger(bytes) || bytes < 0) throw new Error("Invalid workspace disk usage");
    return bytes;
  }

  /** Every home on this host, whether or not the controller has heard from it since starting. */
  async homeIds(): Promise<string[]> {
    const volumes = await dockerText([
      "volume",
      "ls",
      "-q",
      "--filter",
      `label=${this.label}`,
      "--filter",
      "label=com.helltar.vusan.storage=backing-disk",
    ]);
    const prefix = `${this.config.namespace}-workspace-`;
    return volumes.split("\n").filter(Boolean)
      .map((volume) => volume.slice(prefix.length, -"-disk".length))
      .filter(isWorkspaceId);
  }

  /** When a home first appeared, for one that has no record of ever being used. */
  async homeCreated(id: string): Promise<number> {
    const created = await dockerText([
      "volume",
      "inspect",
      "--format",
      "{{.CreatedAt}}",
      `${this.name(id)}-disk`,
    ]).catch(() => "");
    const stamp = Date.parse(created);
    return Number.isFinite(stamp) ? stamp : Date.now();
  }

  liveIds(): string[] {
    return [...this.live.keys()].filter((id) => !this.cleaning.has(id));
  }

  /** A finished command's own cost is not unattended burn, so the next tick measures from here. */
  rebaseline(id: string): void {
    const previous = this.burn.get(id);
    if (previous) this.burn.set(id, { usec: Number.NaN, seconds: previous.seconds });
  }

  private async cpuUsec(id: string): Promise<number> {
    const out = await dockerText([
      "exec",
      "--user",
      "1000:1000",
      this.name(id),
      ...CLEAN_ENV,
      "/usr/bin/timeout",
      "5",
      "/usr/bin/head",
      "-1",
      "/sys/fs/cgroup/cpu.stat",
    ]);
    return Number.parseInt(out.split(/\s+/)[1] ?? "", 10);
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
    await this.homes.close(this.name(id));
    this.live.delete(id);
    this.burn.delete(id);
  }

  async sweep(busy: (id: string) => boolean): Promise<void> {
    // measured outside the gate: reading a cgroup is one exec per live container.
    const usage = new Map<string, number>();
    for (const id of this.liveIds()) {
      const usec = await this.cpuUsec(id).catch(() => Number.NaN);
      if (Number.isFinite(usec)) usage.set(id, usec);
    }
    await this.exclusive(async () => {
      const cutoff = Date.now() - this.config.idleMinutes * 60_000;
      for (const [id, touched] of this.live) {
        if (touched < cutoff && !busy(id) && !this.leases.has(id)) {
          await this.remove(id);
          continue;
        }
        const usec = usage.get(id);
        if (usec === undefined) continue;
        const burn = accumulateBurn(this.burn.get(id), usec, busy(id) || this.leases.has(id));
        this.burn.set(id, burn);
        if (burn.seconds > this.config.idleCpuSeconds) {
          console.error(
            `workspace=[${id}] burned cpu=[${Math.round(burn.seconds)}s] with no command of its own ` +
              `(limit ${this.config.idleCpuSeconds}s); removing it`,
          );
          await this.remove(id);
        }
      }
    });
  }
}
