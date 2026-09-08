import { readConfig } from "./config.ts";
import { authorized, loadToken } from "./auth.ts";
import { Sites } from "./storage.ts";
import { json, ownerId, RequestError, uploadId } from "./protocol.ts";

const config = readConfig();
const token = await loadToken(config.token, config.tokenFile);
const sites = new Sites(config);
await sites.initialize();
let closing = false;

async function route(request: Request): Promise<Response> {
  if (closing) return json({ error: "The site service is stopping" }, 503);
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/health") {
    return json({ ok: sites.healthy, protocol: 1 }, sites.healthy ? 200 : 503);
  }
  if (!authorized(request, token)) return json({ error: "Unauthorized" }, 401);

  if (url.pathname === "/site") {
    const owner = ownerId(url.searchParams.get("owner"));
    if (request.method === "GET") {
      const record = await sites.status(owner);
      return json(
        record ? { ...record, published: true, url: sites.url(record.label) } : { published: false },
      );
    }
    if (request.method === "DELETE") return json({ owner, removed: await sites.remove(owner) });
    throw new RequestError("Method not allowed", 405);
  }

  if (url.pathname === "/uploads" && request.method === "POST") {
    const owner = ownerId(url.searchParams.get("owner"));
    return json({ uploadId: await sites.begin(owner), expiresInMinutes: config.uploadIdleMinutes });
  }

  if (url.pathname.startsWith("/uploads/")) {
    const rest = url.pathname.slice("/uploads/".length);
    const committing = rest.endsWith("/commit");
    const id = uploadId(committing ? rest.slice(0, -"/commit".length) : rest);
    if (committing) {
      if (request.method !== "POST") throw new RequestError("Method not allowed", 405);
      const record = await sites.commit(id);
      return json({ ...record, url: sites.url(record.label) });
    }
    if (request.method === "PUT") {
      const declared = request.headers.get("content-length");
      const length = declared === null ? null : Number(declared);
      if (length !== null && (!Number.isSafeInteger(length) || length < 0)) {
        throw new RequestError("Invalid content length");
      }
      return json(await sites.put(id, url.searchParams.get("path") ?? "", request.body, length));
    }
    if (request.method === "DELETE") {
      await sites.discard(id);
      return json({ uploadId: id, discarded: true });
    }
    throw new RequestError("Method not allowed", 405);
  }
  return json({ error: "Not found" }, 404);
}

let sweeping = false;
const sweep = setInterval(async () => {
  if (sweeping) return;
  sweeping = true;
  try {
    await sites.sweep();
  } catch (e) {
    console.error("site sweep failed", e);
  } finally {
    sweeping = false;
  }
}, 600_000);

const server = Deno.serve({ hostname: "0.0.0.0", port: 8090 }, async (request) => {
  try {
    return await route(request);
  } catch (e) {
    // an unread body leaves the connection waiting for bytes nobody will read.
    if (!request.bodyUsed) await request.body?.cancel().catch(() => {});
    if (e instanceof RequestError) return json({ error: e.message }, e.status);
    if (e instanceof SyntaxError) return json({ error: "Invalid JSON" }, 400);
    console.error("site request failed", e);
    return json({ error: "Site operation failed" }, 500);
  }
});

async function shutdown(): Promise<void> {
  if (closing) return;
  closing = true;
  clearInterval(sweep);
  try {
    await server.shutdown();
    Deno.exit(0);
  } catch (e) {
    console.error("site service shutdown failed", e);
    Deno.exit(1);
  }
}

Deno.addSignalListener("SIGTERM", () => void shutdown());
Deno.addSignalListener("SIGINT", () => void shutdown());
