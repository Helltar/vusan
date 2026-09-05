import { deepStrictEqual, rejects, strictEqual, throws } from "node:assert/strict";
import { integer, jobId, readBounded, RequestError, workspaceId } from "./protocol.ts";

Deno.test("identifiers cannot inject paths or Docker names", () => {
  strictEqual(workspaceId("u42"), "u42");
  for (const id of ["../u42", "u42/other", "--privileged", "u42_g123", "u042", null, "u" + "1".repeat(64)]) {
    throws(() => workspaceId(id), RequestError);
  }
  strictEqual(jobId("d6e07bfb-61dd-469a-94ad-2d05e1a19493"), "d6e07bfb-61dd-469a-94ad-2d05e1a19493");
  throws(() => jobId("-".repeat(36)), RequestError);
});

Deno.test("timeouts and offsets reject invalid values and clamp the maximum", () => {
  strictEqual(integer(undefined, 10, 20), 10);
  strictEqual(integer("30", 10, 20), 20);
  strictEqual(integer(0, 10, 20), 0);
  for (const value of [-1, 0.5, "", "bad", true, {}, Number.MAX_SAFE_INTEGER + 1]) {
    throws(() => integer(value, 10, 20), RequestError);
  }
});

Deno.test("bounded reads reject overflow and cancel the upstream stream", async () => {
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array(5));
    },
    cancel() {
      cancelled = true;
    },
  });
  await rejects(() => readBounded(stream, 4), RequestError);
  strictEqual(cancelled, true);
  deepStrictEqual(await readBounded(null, 4), new Uint8Array());
  deepStrictEqual(await readBounded(new Response(new Uint8Array([1, 2])).body, 2), new Uint8Array([1, 2]));
});
