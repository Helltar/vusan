import { readConfig } from "./config.ts";
import { authorized, loadToken } from "./auth.ts";
import { Containers } from "./container.ts";
import { Jobs } from "./jobs.ts";
import { PolicyGuard } from "./policy.ts";
import { DiskGuard } from "./storage.ts";
import {
  COMMAND_LIMIT,
  FILE_LIMIT,
  integer,
  jobId,
  json,
  readBounded,
  RequestError,
  workspaceId,
} from "./protocol.ts";

const config = readConfig();
await Deno.mkdir(config.stateDir, { recursive: true, mode: 0o700 });
// only one supervisor may own a namespace and its job state at a time.
const lock = await Deno.open(`${config.stateDir}/lock`, { create: true, write: true });
await lock.lock(true);
const token = await loadToken(config.token, config.tokenFile);
const namespaceFile = `${config.stateDir}/namespace`;
const previousNamespace = await Deno.readTextFile(namespaceFile).catch((e) => {
  if (e instanceof Deno.errors.NotFound) return null;
  throw e;
});
if (previousNamespace !== null && previousNamespace !== config.namespace) {
  throw new Error("WORKSPACE_NAMESPACE must not change for an existing state volume");
}
if (previousNamespace === null) {
  await Deno.writeTextFile(`${namespaceFile}.tmp`, config.namespace, { mode: 0o600 });
  await Deno.rename(`${namespaceFile}.tmp`, namespaceFile);
}
const containers = new Containers(config);
await containers.initialize();
const jobs = new Jobs(config, containers);
await jobs.recover();
const guard = new DiskGuard(config, containers, jobs);
await guard.tick();
const policy = new PolicyGuard(config, containers, jobs);
let transfers = 0;
let closing = false;

async function route(request: Request): Promise<Response> {
  if (closing) return json({ error: "Workspace service is stopping" }, 503);
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/health") {
    const ok = guard.healthy && policy.healthy;
    return json({ ok, protocol: 5 }, ok ? 200 : 503);
  }
  if (!authorized(request, token)) {
    return json({ error: "Unauthorized" }, 401);
  }
  const id = workspaceId(url.searchParams.get("id"));
  await jobs.markUsed(id);
  if (url.pathname === "/jobs" && request.method === "POST") {
    const body = JSON.parse(
      new TextDecoder().decode(await readBounded(request.body, COMMAND_LIMIT * 6 + 1024)),
    );
    if (body === null || typeof body.command !== "string") throw new RequestError("Missing command");
    const timeout = integer(body.timeoutSeconds, config.defaultTimeoutSeconds, config.maxTimeoutSeconds) ||
      config.defaultTimeoutSeconds;
    await guard.tick();
    guard.guardWrites();
    const job = await jobs.start(id, body.command, timeout);
    return json(await jobs.read(id, job.jobId, 0, 10));
  }
  if (url.pathname === "/jobs" && request.method === "GET") return json({ jobs: await jobs.list(id) });
  if (url.pathname.startsWith("/jobs/")) {
    const run = jobId(url.pathname.slice("/jobs/".length));
    if (request.method === "DELETE") await jobs.cancel(id, run);
    else if (request.method !== "GET") throw new RequestError("Method not allowed", 405);
    const offset = integer(url.searchParams.get("offset"), 0, Number.MAX_SAFE_INTEGER);
    const wait = integer(url.searchParams.get("waitSeconds"), 0, 20);
    return json(await jobs.read(id, run, offset, wait));
  }
  // deliberately outside the storage guard: emptying a workspace is how an operator gets space back.
  if (url.pathname === "/workspace" && request.method === "DELETE") {
    if (jobs.busy(id)) {
      throw new RequestError("Cancel the running command before resetting the workspace", 409);
    }
    await containers.wipe(id);
    await guard.tick();
    return json({ id, reset: true });
  }
  if (url.pathname === "/files" && ["PUT", "GET", "DELETE"].includes(request.method)) {
    if (request.method === "PUT") {
      await guard.tick();
      guard.guardWrites();
    }
    if (transfers >= 2) throw new RequestError("File transfer capacity reached; try again shortly", 409);
    const path = url.searchParams.get("path");
    if (!path || path.length > 400) throw new RequestError("Missing or invalid file path");
    if (request.method === "DELETE" && jobs.busy(id)) {
      throw new RequestError("Cancel the running command before deleting workspace files", 409);
    }
    transfers++;
    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      transfers--;
    };
    try {
      if (request.method === "DELETE") {
        await containers.deleteFile(id, path);
        await guard.tick();
        return json({ path });
      }
      if (request.method === "PUT") {
        const size = Number(request.headers.get("content-length"));
        if (size > FILE_LIMIT) throw new RequestError("File exceeds the 50 MB transfer limit", 413);
        return json({ path, bytes: await containers.writeFile(id, path, request.body) });
      }
      // the download holds its slot until the body has been read, so `release` outlives this block.
      return new Response(await containers.readFile(id, path, release), {
        headers: { "content-type": "application/octet-stream" },
      });
    } catch (e) {
      release();
      throw e;
    } finally {
      if (request.method !== "GET") release();
    }
  }
  return json({ error: "Not found" }, 404);
}

let sweeping = false;
const storageTick = setInterval(
  () =>
    void guard.tick().then(() => guard.checkHomes())
      .catch((e) => console.error("workspace storage tick failed", e)),
  1_000,
);
const sweep = setInterval(async () => {
  if (sweeping) return;
  sweeping = true;
  try {
    await containers.sweep((id) => jobs.busy(id));
    await policy.tick();
    await jobs.prune();
  } catch (e) {
    console.error("workspace sweep failed", e);
  } finally {
    sweeping = false;
  }
}, 30_000);

const server = Deno.serve({ hostname: "0.0.0.0", port: 8080 }, async (request) => {
  try {
    return await route(request);
  } catch (e) {
    if (e instanceof RequestError) return json({ error: e.message }, e.status);
    if (e instanceof SyntaxError) return json({ error: "Invalid JSON" }, 400);
    console.error("workspace request failed", e);
    return json({ error: "Workspace operation failed" }, 500);
  }
});

async function shutdown(): Promise<void> {
  if (closing) return;
  closing = true;
  clearInterval(sweep);
  clearInterval(storageTick);
  const stopped = server.shutdown();
  try {
    await jobs.shutdown();
    await stopped;
    Deno.exit(0);
  } catch (e) {
    console.error("workspace shutdown failed", e);
    Deno.exit(1);
  }
}

Deno.addSignalListener("SIGTERM", () => void shutdown());
Deno.addSignalListener("SIGINT", () => void shutdown());
