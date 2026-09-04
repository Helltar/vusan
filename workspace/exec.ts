import { config } from "./config.ts";
import { type Capture, capture, LogSink } from "./output.ts";
import type { Workspace } from "./registry.ts";

export interface ExecResult {
  exitCode: number;
  timedOut: boolean;
  stdout: Capture;
  stderr: Capture;
  elapsedMs: number;
  logPath: string | null;
}

const KILL_GRACE_MS = 3_000;

/**
 * Nothing in here may wait on a prompt: a command blocked on stdin would sit until the
 * timeout and report nothing useful. stdin is /dev/null and the environment tells every
 * common tool it is not on a terminal.
 */
function environment(workspace: Workspace): Record<string, string> {
  const home = workspace.home;
  return {
    HOME: home,
    USER: `ws${workspace.slot}`,
    LOGNAME: `ws${workspace.slot}`,
    SHELL: "/bin/bash",
    PWD: home,
    TMPDIR: `${home}/tmp`,
    PATH:
      `${home}/.local/bin:${home}/node_modules/.bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`,
    LANG: "C.UTF-8",
    LC_ALL: "C.UTF-8",
    TERM: "dumb",
    NO_COLOR: "1",
    CI: "1",
    DEBIAN_FRONTEND: "noninteractive",
    GIT_TERMINAL_PROMPT: "0",
    PIP_DISABLE_PIP_VERSION_CHECK: "1",
    PIP_NO_INPUT: "1",
    PYTHONUNBUFFERED: "1",
    npm_config_yes: "true",
    npm_config_fund: "false",
    npm_config_audit: "false",
    npm_config_progress: "false",
  };
}

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
    env: environment(workspace),
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
