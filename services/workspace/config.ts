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

// device throughput is capped so one workspace cannot stall everything else sharing the disk. that is
// worth much less on a machine of its own, where the fixed home size already bounds the damage, so
// `none` removes the cap entirely.
function rate(name: string, fallback: string): string | null {
  if (Deno.env.get(name)?.trim() === "none") return null;
  return shaped(name, /^[1-9][0-9]*(kb|mb|gb)?$/, "a byte rate such as `50mb`, or `none`") ?? fallback;
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
  const networkMbit = shaped("WORKSPACE_NETWORK_MBIT", /^[1-9][0-9]{0,4}$/, "a whole number of megabits") ??
    "50";
  const writeBps = rate("WORKSPACE_WRITE_BPS", "50mb");
  const readBps = rate("WORKSPACE_READ_BPS", "100mb");
  const blockedCidrs = (Deno.env.get("WORKSPACE_BLOCKED_CIDRS")?.trim() || "").split(/[\s,]+/).filter(
    Boolean,
  );
  for (const cidr of blockedCidrs) {
    const [address, prefix] = cidr.split("/");
    if (
      !/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?:\/(?:[0-9]|[12][0-9]|3[0-2]))?$/.test(cidr) ||
      address.split(".").some((octet) => Number(octet) > 255 || String(Number(octet)) !== octet) ||
      (prefix !== undefined && Number(prefix) > 32)
    ) {
      throw new Error("WORKSPACE_BLOCKED_CIDRS must contain IPv4 addresses or CIDRs");
    }
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
    maxActive: positive("WORKSPACE_MAX_ACTIVE", 2),
    idleMinutes: positive("WORKSPACE_IDLE_MINUTES", 60),
    retainDays: positive("WORKSPACE_RETAIN_DAYS", 14),
    diskWarnMb: positive("WORKSPACE_DISK_WARN_MB", 2048),
    maxHomeMb: positive("WORKSPACE_MAX_HOME_MB", 4096),
    idleCpuSeconds: positive("WORKSPACE_IDLE_CPU_SECONDS", 600),
    policyCheckSeconds: positive("WORKSPACE_POLICY_CHECK_SECONDS", 300),
    maxFileMb,
    minFreeMb: positive("WORKSPACE_MIN_FREE_MB", 1024),
    minFreeInodes: positive("WORKSPACE_MIN_FREE_INODES", 10_000),
    memoryMb: positive("WORKSPACE_MEMORY_MB", 1024),
    cpus: positive("WORKSPACE_CPUS", 1),
    pids: positive("WORKSPACE_PIDS_LIMIT", 256),
    networkMbit,
    blockedCidrs,
    writeBps,
    readBps,
  };
}

export type Config = ReturnType<typeof readConfig>;
