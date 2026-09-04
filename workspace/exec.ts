import { config } from "./config.ts";
import { type Capture, capture, LogSink } from "./output.ts";
import type { Workspace } from "./registry.ts";
import { workspaceEnvironment } from "./env.ts";

export interface ExecResult {
  exitCode: number;
  timedOut: boolean;
  stdout: Capture;
  stderr: Capture;
  elapsedMs: number;
  logPath: string | null;
}

const KILL_GRACE_MS = 3_000;

export async function runCommand(
  workspace: Workspace,
  command: string,
  timeoutSeconds: number,
): Promise<ExecResult> {
  const started = performance.now();
  const runId = crypto.randomUUID().slice(0, 8);
  const logPath = `${workspace.home}/.vusan/logs/${runId}.log`;
  const log = await LogSink.open(logPath, config.maxLogBytes, workspace.uid, workspace.gid);

  // setsid puts the command in a session of its own, so the timeout can take down the whole
  // tree by process group instead of only the shell that spawned it
  const child = new Deno.Command("setsid", {
    args: ["bash", "-lc", command],
    cwd: workspace.home,
    clearEnv: true,
    env: workspaceEnvironment(workspace.home, workspace.slot),
    uid: workspace.uid,
    gid: workspace.gid,
    stdin: "null",
    stdout: "piped",
    stderr: "piped",
  }).spawn();

  let timedOut = false;
  const deadline = setTimeout(() => {
    timedOut = true;
    killGroup(child.pid, "SIGTERM");
    setTimeout(() => killGroup(child.pid, "SIGKILL"), KILL_GRACE_MS);
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
  }
}

// the timeout is enforced from out here on purpose: a `timeout` inside the workspace is
// the command's own child and is trivially bypassed by the code it is meant to bound.
function killGroup(pid: number, signal: Deno.Signal): void {
  try {
    Deno.kill(-pid, signal);
  } catch {
    // the group is already gone, which is the outcome we wanted
  }
}

/** Everything a workspace still has running, killed by uid. Returns how many there were. */
export async function killWorkspace(workspace: Workspace): Promise<number> {
  const running = await new Deno.Command("pgrep", {
    args: ["-u", String(workspace.uid)],
    stdout: "piped",
    stderr: "null",
  }).output().catch(() => null);

  const count = running?.success
    ? new TextDecoder().decode(running.stdout).trim().split("\n").filter(Boolean).length
    : 0;

  if (count === 0) return 0;

  await new Deno.Command("pkill", { args: ["-KILL", "-u", String(workspace.uid)] })
    .output()
    .catch(() => undefined);

  return count;
}
