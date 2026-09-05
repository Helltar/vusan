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

Deno.test("a secret may come from a file, and a broken one stops the service", async () => {
  const directory = await Deno.makeTempDir();
  const path = `${directory}/token`;
  try {
    await rejects(() => loadToken(null, path), /Cannot read/);
    await Deno.writeTextFile(path, `${secret}\n`);
    strictEqual(await loadToken(null, path), secret);
    // an explicit value wins over the file, and the file is never written back
    strictEqual(await loadToken("b".repeat(40), path), "b".repeat(40));
    strictEqual((await Deno.readTextFile(path)).trim(), secret);
    await Deno.writeTextFile(path, "broken");
    await rejects(() => loadToken(null, path), /32 to 256/);
  } finally {
    await Deno.remove(directory, { recursive: true });
  }
});
