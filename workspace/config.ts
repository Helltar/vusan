function positive(name: string, fallback: number): number {
  const raw = Deno.env.get(name);
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`);
  return value;
}

export function readConfig() {
  const namespace = Deno.env.get("WORKSPACE_NAMESPACE")?.trim() || "vusan";
  if (!/^[a-z][a-z0-9-]{0,31}$/.test(namespace)) throw new Error("Invalid WORKSPACE_NAMESPACE");
  const network = Deno.env.get("WORKSPACE_NETWORK") || "open";
  if (network !== "open" && network !== "none") throw new Error("WORKSPACE_NETWORK must be open or none");
  const maxTimeoutSeconds = positive("WORKSPACE_MAX_TIMEOUT_SECONDS", 600);
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
    minFreeMb: positive("WORKSPACE_MIN_FREE_MB", 1024),
    minFreeInodes: positive("WORKSPACE_MIN_FREE_INODES", 10_000),
    memoryMb: positive("WORKSPACE_MEMORY_MB", 2048),
    cpus: positive("WORKSPACE_CPUS", 2),
    pids: positive("WORKSPACE_PIDS_LIMIT", 256),
  };
}

export type Config = ReturnType<typeof readConfig>;
