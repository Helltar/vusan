// Workspace service: a durable shell workspace per person per chat.
//
// The security boundaries live OUTSIDE this process — the container's capabilities and
// limits, and the egress rules the entrypoint installs before dropping privileges. This
// service owns what those cannot express: who gets which uid, one command at a time per
// workspace, the timeout, the disk quota, and the hygiene of everything it hands back.

import { config } from "./config.ts";
import { listEntries, resolvePath, usedBytes, writeFile } from "./files.ts";
import { killWorkspace, runCommand } from "./runner.ts";
import {
  BadRequest,
  forgetWorkspace,
  listWorkspaceIds,
  resolveWorkspace,
  type Workspace,
} from "./registry.ts";

const quotaBytes = config.quotaMb * 1024 * 1024;
const busy = new Set<string>();
let running = 0;

const SWEEP_INTERVAL_MS = 60_000;

// a command started with `setsid` survives its run on purpose, which is how a long build or an
// encode is meant to work — but nothing else would ever reap one that was simply abandoned.
const lastTouched = new Map<string, { workspace: Workspace; at: number }>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function authorized(request: Request): boolean {
  if (!config.token) return true;
  return request.headers.get("authorization") === `Bearer ${config.token}`;
}

async function workspaceOf(url: URL, body?: Record<string, unknown>): Promise<Workspace> {
  const id = (body?.workspaceId as string) ?? url.searchParams.get("id") ?? "";
  if (!id) throw new BadRequest("Missing workspace id");

  const workspace = await resolveWorkspace(id);
  lastTouched.set(workspace.id, { workspace, at: Date.now() });
  return workspace;
}

async function handleExec(body: Record<string, unknown>, url: URL): Promise<Response> {
  const workspace = await workspaceOf(url, body);
  const command = String(body.command ?? "").trim();
  if (!command) throw new BadRequest("Missing command");

  const requested = Number(body.timeoutSeconds) || config.defaultTimeoutSeconds;
  const timeout = Math.min(Math.max(requested, 1), config.maxTimeoutSeconds);

  // a refusal below is an answer the model has to read, not a transport failure, so it travels
  // as 200 with an `error` field; the bot's shared HTTP client turns any non-2xx into an exception
  if (busy.has(workspace.id)) {
    return json({ error: "This workspace is already running a command. Wait for it to finish." });
  }
  if (running >= config.maxConcurrent) {
    return json({ error: "The workspace service is at capacity. Try again shortly." });
  }

  // the quota is checked before the command rather than enforced during it: a filesystem
  // quota needs privileges this container does not have, so the gate is "you are over,
  // clean up" instead of a write that fails halfway through a build
  const before = await usedBytes(workspace);
  if (before > quotaBytes) {
    return json({
      error: `The workspace is out of space (${Math.round(before / 1048576)} MB of ` +
        `${config.quotaMb} MB). Delete files before running anything else.`,
      usedBytes: before,
      quotaBytes,
    });
  }

  busy.add(workspace.id);
  running++;
  try {
    const result = await runCommand(workspace, command, timeout);
    return json({
      exitCode: result.exitCode,
      timedOut: result.timedOut,
      stdout: result.stdout.text,
      stdoutTruncated: result.stdout.truncated,
      stderr: result.stderr.text,
      stderrTruncated: result.stderr.truncated,
      elapsedMs: result.elapsedMs,
      logPath: result.logPath,
      usedBytes: await usedBytes(workspace),
      quotaBytes,
    });
  } finally {
    busy.delete(workspace.id);
    running--;
  }
}

async function handleWrite(request: Request, url: URL): Promise<Response> {
  const workspace = await workspaceOf(url);
  const path = url.searchParams.get("path") ?? "";
  if (!path) throw new BadRequest("Missing path");

  const bytes = new Uint8Array(await request.arrayBuffer());
  await writeFile(workspace, path, bytes);
  return json({ path, bytes: bytes.length });
}

async function handleRead(url: URL): Promise<Response> {
  const workspace = await workspaceOf(url);
  const path = url.searchParams.get("path") ?? "";
  if (!path) throw new BadRequest("Missing path");

  const resolved = await resolvePath(workspace, path);
  const stat = await Deno.stat(resolved).catch(() => null);
  if (!stat?.isFile) throw new BadRequest(`Not a file: ${path}`);

  const file = await Deno.open(resolved, { read: true });
  return new Response(file.readable, {
    headers: {
      "content-type": "application/octet-stream",
      "content-length": String(stat.size),
    },
  });
}

async function handleReset(url: URL): Promise<Response> {
  const workspace = await workspaceOf(url);
  await killWorkspace(workspace);
  await Deno.remove(workspace.home, { recursive: true }).catch(() => {});
  await forgetWorkspace(workspace.id);
  lastTouched.delete(workspace.id);
  return json({ reset: workspace.id });
}

async function route(request: Request): Promise<Response> {
  const url = new URL(request.url);

  if (url.pathname === "/health") return json({ ok: true, isolation: config.isolation });
  if (!authorized(request)) return json({ error: "Unauthorized" }, 401);

  switch (`${request.method} ${url.pathname}`) {
    case "POST /exec":
      return await handleExec(await request.json(), url);
    case "PUT /files":
      return await handleWrite(request, url);
    case "GET /files":
      return await handleRead(url);
    case "GET /list": {
      const workspace = await workspaceOf(url);
      return json({
        entries: await listEntries(workspace, url.searchParams.get("path") ?? "."),
        usedBytes: await usedBytes(workspace),
        quotaBytes,
      });
    }
    case "GET /workspaces":
      return json({ ids: await listWorkspaceIds() });
    case "DELETE /workspace":
      return await handleReset(url);
    default:
      return json({ error: "Not found" }, 404);
  }
}

async function handler(request: Request): Promise<Response> {
  try {
    return await route(request);
  } catch (error) {
    if (error instanceof BadRequest) return json({ error: error.message }, 400);
    console.error("request failed:", error);
    return json({ error: "Workspace service error" }, 500);
  }
}

console.log(
  `workspace service on :${config.port} isolation=[${config.isolation}] root=[${config.root}] ` +
    `quota=[${config.quotaMb}MB] concurrency=[${config.maxConcurrent}] idle=[${config.idleMinutes}m]`,
);

async function sweepIdleWorkspaces(): Promise<void> {
  const cutoff = Date.now() - config.idleMinutes * 60_000;

  for (const [id, entry] of lastTouched) {
    if (entry.at > cutoff || busy.has(id)) continue;

    lastTouched.delete(id);
    const killed = await killWorkspace(entry.workspace).catch(() => 0);
    // logged whichever runner is in use: in `container` mode this is the teardown of a container,
    // which is worth a line even when the workspace left nothing of its own running
    console.log(`swept idle workspace [${id}], ${killed} process(es) still running`);
  }
}

setInterval(() => void sweepIdleWorkspaces(), SWEEP_INTERVAL_MS);

Deno.serve({ port: config.port, hostname: "0.0.0.0" }, handler);
