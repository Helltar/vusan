// Sandbox service: runs untrusted Python (LLM-written) in pooled Pyodide
// workers. Each request gets a fresh, warm worker that is destroyed afterwards,
// so there is no state leak between requests and a hung script is terminated.
//
// Security boundaries live OUTSIDE this process: the container has no network
// (internal compose network), no secrets in its env, and no host mounts. This
// service only enforces time/output limits and single-use isolation.

const PORT = Number(Deno.env.get("PORT") ?? 8080);
const POOL_SIZE = Number(Deno.env.get("SANDBOX_POOL_SIZE") ?? 2);
const TIMEOUT_MS = Number(Deno.env.get("SANDBOX_TIMEOUT_MS") ?? 30_000);
const MAX_CODE_CHARS = 100_000;
const ACQUIRE_TIMEOUT_MS = 30_000;
const RESPAWN_BACKOFF_MS = 1_000;

interface RunResult {
  ok: boolean;
  timedOut?: boolean;
  error: string;
  stdout: string;
  stderr: string;
  files: Array<{ name: string; base64: string }>;
}

const warm: Worker[] = [];
const waiters: Array<(w: Worker) => void> = [];

function spawn(): void {
  const worker = new Worker(new URL("./worker.ts", import.meta.url), { type: "module" });
  worker.onmessage = (e: MessageEvent) => {
    if (e.data?.type !== "ready") return;
    worker.onmessage = null; // the per-request handler is attached on dispatch
    const waiter = waiters.shift();
    if (waiter) waiter(worker);
    else warm.push(worker);
  };
  worker.onerror = (e) => {
    e.preventDefault(); // keep a worker crash from propagating to the service
    console.error("worker crashed during warm-up:", e.message);
    worker.terminate();
    setTimeout(spawn, RESPAWN_BACKOFF_MS); // avoid a tight loop if warm-up keeps failing
  };
}

function acquire(): Promise<Worker> {
  const ready = warm.shift();
  if (ready) return Promise.resolve(ready);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const i = waiters.indexOf(onReady);
      if (i >= 0) waiters.splice(i, 1);
      reject(new Error("no sandbox worker available"));
    }, ACQUIRE_TIMEOUT_MS);
    const onReady = (w: Worker) => {
      clearTimeout(timer);
      resolve(w);
    };
    waiters.push(onReady);
  });
}

function runOnce(worker: Worker, code: string): Promise<RunResult> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      resolve({ ok: false, timedOut: true, error: "execution timed out", stdout: "", stderr: "", files: [] });
    }, TIMEOUT_MS);
    worker.onmessage = (e: MessageEvent<RunResult>) => {
      clearTimeout(timer);
      resolve(e.data);
    };
    worker.postMessage({ code });
  }).finally(() => {
    // Single-use: discard the worker and refill the pool in the background.
    worker.terminate();
    spawn();
  }) as Promise<RunResult>;
}

async function handleRun(req: Request): Promise<Response> {
  const body = await req.json().catch(() => null);
  const code = body?.code;
  if (typeof code !== "string" || code.trim() === "") {
    return Response.json({ error: "missing `code`" }, { status: 400 });
  }
  if (code.length > MAX_CODE_CHARS) {
    return Response.json({ error: "code too large" }, { status: 413 });
  }

  const worker = await acquire().catch(() => null);
  if (!worker) return Response.json({ error: "sandbox busy" }, { status: 503 });

  const result = await runOnce(worker, code);
  return Response.json(result);
}

for (let i = 0; i < POOL_SIZE; i++) spawn();

Deno.serve({ port: PORT, hostname: "0.0.0.0" }, (req) => {
  const { pathname } = new URL(req.url);
  if (req.method === "POST" && pathname === "/run") return handleRun(req);
  if (req.method === "GET" && pathname === "/health") {
    return Response.json({ ok: true, warm: warm.length });
  }
  return new Response("not found", { status: 404 });
});
