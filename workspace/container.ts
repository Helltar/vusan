// The `container` runner: one container per workspace, started on demand from the same image the
// supervisor runs, holding nothing but that workspace's directory.
//
// This is where the isolation actually lives — a workspace sees its own filesystem, its own
// process table, its own network namespace and its own share of CPU and memory, none of which
// `shared` can offer. The price is a container engine, which is why this mode belongs on a machine
// that runs nothing else: reaching the engine is reaching that host.

import { config } from "./config.ts";
import { workspaceEnvironment } from "./env.ts";
import { capture, LogSink } from "./output.ts";
import type { ExecResult } from "./exec.ts";
import type { Workspace } from "./registry.ts";

const KILL_GRACE_MS = 3_000;
const HOME = "/work";
const START_TIMEOUT_MS = 60_000;

function containerName(workspace: Workspace): string {
  return `vusan-ws-${workspace.id}`;
}

async function engine(args: string[], timeoutMs = START_TIMEOUT_MS): Promise<{ ok: boolean; out: string }> {
  const command = new Deno.Command(config.engine, { args, stdout: "piped", stderr: "piped" });
  const child = command.spawn();
  const abort = setTimeout(() => child.kill("SIGKILL"), timeoutMs);

  try {
    const result = await child.output();
    const text = new TextDecoder().decode(result.success ? result.stdout : result.stderr).trim();
    return { ok: result.success, out: text };
  } finally {
    clearTimeout(abort);
  }
}

async function isRunning(name: string): Promise<boolean> {
  const state = await engine(["inspect", "-f", "{{.State.Running}}", name], 10_000);
  return state.ok && state.out === "true";
}

/**
 * Brings the workspace's container up if it is not already. The container is disposable and holds
 * no state: everything that has to survive is on the mounted directory, so losing one costs a
 * second of start-up and nothing else.
 */
async function ensureRunning(workspace: Workspace): Promise<void> {
  const name = containerName(workspace);
  if (await isRunning(name)) return;

  // a stopped leftover cannot be started back into a clean state — its network policy and its
  // limits were set at creation, and either may have changed since
  await engine(["rm", "-f", name], 20_000);

  const env = workspaceEnvironment(HOME, workspace.slot);
  const args = [
    "run",
    "--detach",
    "--name",
    name,
    "--init",
    // the image's healthcheck belongs to the supervisor role; here it would curl an API that this
    // container does not serve and report unhealthy forever
    "--no-healthcheck",
    // the whole workspace, and nothing else on the host, at the path its commands expect
    "--volume",
    `${config.root}/${workspace.id}:${HOME}`,
    "--workdir",
    HOME,
    "--read-only",
    "--tmpfs",
    "/tmp:size=512m",
    "--tmpfs",
    "/run",
    "--cap-drop=ALL",
    // the only two it needs, and only at start-up: the entrypoint installs this container's egress
    // policy before anything of the workspace's runs. commands run as an unprivileged uid, whose
    // effective set is empty, so nothing they start can reach them.
    "--cap-add=NET_ADMIN",
    "--cap-add=NET_RAW",
    "--security-opt=no-new-privileges",
    "--pids-limit",
    String(config.pidsLimit),
    "--memory",
    config.memory,
    "--memory-swap",
    config.memory,
    "--cpus",
    config.cpus,
    ...(config.runtime ? ["--runtime", config.runtime] : []),
    "--env",
    `WORKSPACE_NETWORK=${Deno.env.get("WORKSPACE_NETWORK") ?? "open"}`,
    ...Object.entries(env).flatMap(([key, value]) => ["--env", `${key}=${value}`]),
    config.image,
    "workspace",
  ];

  const started = await engine(args);
  if (!started.ok) throw new Error(`Could not start the workspace container: ${started.out}`);

  console.log(
    `started container=[${name}] image=[${config.image}] runtime=[${config.runtime ?? "default"}] ` +
      `mem=[${config.memory}] cpus=[${config.cpus}]`,
  );
}

export async function runCommand(
  workspace: Workspace,
  command: string,
  timeoutSeconds: number,
): Promise<ExecResult> {
  const started = performance.now();
  await ensureRunning(workspace);

  const runId = crypto.randomUUID().slice(0, 8);
  const logPath = `${workspace.home}/.vusan/logs/${runId}.log`;
  const log = await LogSink.open(logPath, config.maxLogBytes, workspace.uid, workspace.gid);
  const pgidFile = `${workspace.home}/.vusan/run-${runId}.pgid`;

  // the command travels as an environment variable and is never spliced into a shell string, so
  // nothing in it can be read as syntax by the layers carrying it
  const child = new Deno.Command(config.engine, {
    args: [
      "exec",
      "--user",
      `${workspace.uid}:${workspace.gid}`,
      "--env",
      `VUSAN_COMMAND=${command}`,
      "--env",
      `VUSAN_PGID_FILE=${HOME}/.vusan/run-${runId}.pgid`,
      containerName(workspace),
      "bash",
      "-c",
      // no `setsid` here, unlike the shared runner: the engine already gives an exec its own
      // session, so this process is the group leader and `$$` is the pgid the timeout needs.
      // Calling setsid anyway would fork, and the direct child would then exit ahead of the real
      // command — taking the output stream with it.
      'echo $$ > "$VUSAN_PGID_FILE"; exec bash -lc "$VUSAN_COMMAND"',
    ],
    stdin: "null",
    stdout: "piped",
    stderr: "piped",
  }).spawn();

  let timedOut = false;
  const deadline = setTimeout(() => {
    timedOut = true;
    void killRun(workspace, pgidFile, "TERM");
    setTimeout(() => void killRun(workspace, pgidFile, "KILL"), KILL_GRACE_MS);
  }, timeoutSeconds * 1000);

  try {
    const [stdout, stderr, status] = await Promise.all([
      capture(child.stdout, config.maxOutputBytes, log),
      capture(child.stderr, config.maxOutputBytes, log),
      child.status,
    ]);

    return {
      exitCode: status.code,
      timedOut,
      stdout,
      stderr,
      elapsedMs: Math.round(performance.now() - started),
      logPath: log ? `.vusan/logs/${runId}.log` : null,
    };
  } finally {
    clearTimeout(deadline);
    log?.close();
    await Deno.remove(pgidFile).catch(() => {});
  }
}

/**
 * The timeout is enforced from out here, and by process group, for the same two reasons as in the
 * shared runner: a `timeout` inside the workspace is the command's own child, and killing only the
 * shell leaves its tree running. Signalling runs as the workspace's own uid, so the container needs
 * no `CAP_KILL` for it.
 *
 * The pgid is read from the host side of the mount rather than asked of the container, which keeps
 * the kill working even when the container is too loaded to answer promptly.
 */
async function killRun(workspace: Workspace, pgidFile: string, signal: string): Promise<void> {
  const pgid = await Deno.readTextFile(pgidFile).then((raw) => raw.trim()).catch(() => "");
  if (!/^\d+$/.test(pgid)) return;

  await engine([
    "exec",
    "--user",
    `${workspace.uid}:${workspace.gid}`,
    containerName(workspace),
    "kill",
    `-${signal}`,
    `-${pgid}`,
  ], 10_000).catch(() => undefined);
}

/** Removes the workspace's container. Returns how many processes it still had. */
export async function killWorkspace(workspace: Workspace): Promise<number> {
  const name = containerName(workspace);
  if (!await isRunning(name)) {
    await engine(["rm", "-f", name], 20_000).catch(() => undefined);
    return 0;
  }

  const counted = await engine([
    "exec",
    "--user",
    `${workspace.uid}:${workspace.gid}`,
    name,
    "pgrep",
    "-c",
    "-u",
    String(workspace.uid),
  ], 10_000).catch(() => ({ ok: false, out: "" }));

  await engine(["rm", "-f", name], 20_000);
  return counted.ok ? Number.parseInt(counted.out, 10) || 0 : 0;
}
