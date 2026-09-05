import { ok, rejects, strictEqual, throws } from "node:assert/strict";
import { authorized, loadToken, validateToken } from "./auth.ts";

const secret = "synthetic-workspace-token-123456789";

Deno.test("authentication fails closed without a secret or with a weak secret", async () => {
  await rejects(() => loadToken(null, null), /required/);
  for (const value of ["", "short", "a".repeat(32) + "\n", "a".repeat(257)]) {
    throws(() => validateToken(value));
  }
  strictEqual(await loadToken(secret, null), secret);
  strictEqual(authorized(new Request("http://workspace/jobs"), secret), false);
  strictEqual(
    authorized(new Request("http://workspace/jobs", { headers: { authorization: "Bearer wrong" } }), secret),
    false,
  );
  ok(
    authorized(
      new Request("http://workspace/jobs", { headers: { authorization: `Bearer ${secret}` } }),
      secret,
    ),
  );
});

Deno.test("automatic API secrets survive restart and support explicit rotation", async () => {
  const directory = await Deno.makeTempDir();
  const path = `${directory}/auth/token`;
  try {
    const initial = await loadToken(null, path);
    strictEqual(initial.length, 64);
    strictEqual(await loadToken(null, path), initial);
    strictEqual(await loadToken(secret, path), secret);
    strictEqual(await Deno.readTextFile(path), secret);
    strictEqual((await Deno.stat(path)).mode! & 0o777, 0o444);
    await Deno.chmod(path, 0o600);
    await Deno.writeTextFile(path, "broken");
    await rejects(() => loadToken(null, path), /32 to 256/);
  } finally {
    await Deno.remove(directory, { recursive: true });
  }
});
