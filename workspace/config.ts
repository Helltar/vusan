import { FILE_LIMIT } from "./protocol.ts";

function positive(name: string, fallback: number): number {
  const raw = Deno.env.get(name);
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`);
  return value;
}

function shaped(name: string, shape: RegExp, hint: string): string | null {
  const raw = Deno.env.get(name)?.trim();
  if (!raw) return null;
  if (!shape.test(raw)) throw new Error(`${name} must be ${hint}`);
  return raw;
}

export function readConfig() {
  const namespace = Deno.env.get("WORKSPACE_NAMESPACE")?.trim() || "vusan";
  if (!/^[a-z][a-z0-9-]{0,31}$/.test(namespace)) throw new Error("Invalid WORKSPACE_NAMESPACE");
  const network = Deno.env.get("WORKSPACE_NETWORK") || "open";
  if (network !== "open" && network !== "none") throw new Error("WORKSPACE_NETWORK must be open or none");
  const maxTimeoutSeconds = positive("WORKSPACE_MAX_TIMEOUT_SECONDS", 600);
  // a per-file ceiling under the transfer limit would reject uploads the API still advertises.
  const maxFileMb = positive("WORKSPACE_MAX_FILE_MB", 4096);
  if (maxFileMb * 1024 * 1024 < FILE_LIMIT) {
    throw new Error("WORKSPACE_MAX_FILE_MB must not be below the 50 MB file transfer limit");
  }
  const writeDevice = shaped("WORKSPACE_WRITE_DEVICE", /^\/dev\/[A-Za-z0-9/_-]+$/, "an absolute /dev path");
  const writeBps = shaped("WORKSPACE_WRITE_BPS", /^[1-9][0-9]*(kb|mb|gb)?$/, "a byte rate such as `50mb`");
  // half a throttle is no throttle, and it would fail silently rather than at startup.
  if ((writeDevice === null) !== (writeBps === null)) {
    throw new Error("WORKSPACE_WRITE_DEVICE and WORKSPACE_WRITE_BPS must be set together");
  }
  return {
    namespace,
    network,
    image: Deno.env.get("WORKSPACE_IMAGE") || "ghcr.io/helltar/vusan-workspace:latest",
    token: Deno.env.get("WORKSPACE_TOKEN")?.trim() || null,
    tokenFile: Deno.env.get("WORKSPACE_TOKEN_FILE")?.trim() || null,
    stateDir: "/state",
    defaultTimeoutSeconds: Math.min(positive("WORKSPACE_TIMEOUT_SECONDS", 120), maxTimeoutSeconds),
    maxTimeoutSeconds,
    maxConcurrent: positive("WORKSPACE_MAX_CONCURRENT", 2),
    maxActive: positive("WORKSPACE_MAX_ACTIVE", 4),
    idleMinutes: positive("WORKSPACE_IDLE_MINUTES", 60),
    diskWarnMb: positive("WORKSPACE_DISK_WARN_MB", 2048),
    maxHomeMb: positive("WORKSPACE_MAX_HOME_MB", 4096),
    maxFileMb,
    minFreeMb: positive("WORKSPACE_MIN_FREE_MB", 1024),
    minFreeInodes: positive("WORKSPACE_MIN_FREE_INODES", 10_000),
    memoryMb: positive("WORKSPACE_MEMORY_MB", 2048),
    cpus: positive("WORKSPACE_CPUS", 2),
    pids: positive("WORKSPACE_PIDS_LIMIT", 256),
    writeDevice,
    writeBps,
  };
}

export type Config = ReturnType<typeof readConfig>;
