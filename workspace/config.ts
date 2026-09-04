// every knob the supervisor has. anything not listed here is a compose/runtime
// concern (limits the engine applies, firewall rules the entrypoint installs).

function num(name: string, fallback: number): number {
  const value = Number(Deno.env.get(name));
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function str(name: string, fallback: string): string {
  return Deno.env.get(name)?.trim() || fallback;
}

// an unknown value stops the service rather than quietly running everything in one container
// after someone asked for stronger isolation
const ISOLATIONS = ["shared", "container"];

export const config = {
  port: 8080,
  root: str("WORKSPACE_ROOT", "/work"),
  isolation: str("WORKSPACE_ISOLATION", "shared"),
  token: Deno.env.get("WORKSPACE_TOKEN")?.trim() || null,

  defaultTimeoutSeconds: num("WORKSPACE_TIMEOUT_SECONDS", 120),
  maxTimeoutSeconds: num("WORKSPACE_MAX_TIMEOUT_SECONDS", 600),
  maxConcurrent: num("WORKSPACE_MAX_CONCURRENT", 2),

  // how long a workspace may sit untouched before whatever it left running is killed. a
  // command started with `setsid` deliberately outlives its run, so nothing else would ever
  // reap an abandoned one; generous enough that a long background build is not cut off.
  idleMinutes: num("WORKSPACE_IDLE_MINUTES", 60),
  quotaMb: num("WORKSPACE_QUOTA_MB", 2048),

  // the uid pool baked into the image; a workspace holds its slot for life, because
  // the files on disk are owned by it
  uidBase: num("WORKSPACE_UID_BASE", 20000),
  uidCount: num("WORKSPACE_UID_COUNT", 256),

  // read caps. the byte cap is applied while draining the pipe, never after: the point
  // is that `cat /dev/urandom` costs the workspace and not the bot
  maxOutputBytes: num("WORKSPACE_MAX_OUTPUT_BYTES", 64 * 1024),
  maxLogBytes: num("WORKSPACE_MAX_LOG_BYTES", 8 * 1024 * 1024),
  maxUploadBytes: num("WORKSPACE_MAX_UPLOAD_BYTES", 64 * 1024 * 1024),

  // `container` mode only. podman and docker agree on run/exec/stop/rm, so one code path drives
  // either; which one is right depends on where this is deployed, not on the code.
  engine: str("WORKSPACE_ENGINE", "docker"),
  // the runtime the engine hands each workspace to: `runsc` (gVisor) and `kata` cut the kernel
  // attack surface, and neither needs anything here to change
  runtime: Deno.env.get("WORKSPACE_RUNTIME")?.trim() || null,
  image: str("WORKSPACE_IMAGE", "ghcr.io/helltar/vusan-workspace:latest"),
  // limits per workspace rather than one ceiling shared by everyone, which is the whole point of
  // this mode: /sys/fs/cgroup is read-only inside a container, so `shared` cannot apply them
  memory: str("WORKSPACE_MEM", "2g"),
  cpus: str("WORKSPACE_CPUS", "2"),
  pidsLimit: num("WORKSPACE_PIDS_LIMIT", 512),
} as const;

if (!ISOLATIONS.includes(config.isolation)) {
  console.error(
    `WORKSPACE_ISOLATION=[${config.isolation}] is not supported by this build (available: ${
      ISOLATIONS.join(", ")
    })`,
  );
  Deno.exit(1);
}
