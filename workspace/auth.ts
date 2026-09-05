import { timingSafeEqual } from "node:crypto";

export function validateToken(token: string): string {
  if (!/^[\x21-\x7e]{32,256}$/.test(token)) {
    throw new Error("WORKSPACE_TOKEN must contain 32 to 256 printable non-whitespace ASCII characters");
  }
  return token;
}

export async function loadToken(token: string | null, path: string | null): Promise<string> {
  if (token) validateToken(token);
  if (!path) {
    if (!token) throw new Error("WORKSPACE_TOKEN or WORKSPACE_TOKEN_FILE is required");
    return token;
  }
  const slash = path.lastIndexOf("/");
  if (!path.startsWith("/") || slash <= 0 || path.endsWith("/")) {
    throw new Error("WORKSPACE_TOKEN_FILE must be an absolute file path");
  }
  await Deno.mkdir(path.slice(0, slash), { recursive: true, mode: 0o755 });
  const saved = await Deno.readTextFile(path).catch((e) => {
    if (e instanceof Deno.errors.NotFound) return null;
    throw e;
  });
  const chosen = validateToken(
    token ?? saved?.trim() ??
      Array.from(crypto.getRandomValues(new Uint8Array(32)), (byte) => byte.toString(16).padStart(2, "0"))
        .join(""),
  );
  // the secret volume is shared only with the trusted bot, whose UID differs from the controller's.
  const temporary = await Deno.makeTempFile({ dir: path.slice(0, slash), prefix: ".token-" });
  try {
    await Deno.writeTextFile(temporary, chosen, { mode: 0o600 });
    await Deno.chmod(temporary, 0o444);
    await Deno.rename(temporary, path);
  } finally {
    await Deno.remove(temporary).catch(() => {});
  }
  return chosen;
}

export function authorized(request: Request, token: string): boolean {
  const encoder = new TextEncoder();
  const expected = encoder.encode(`Bearer ${token}`);
  const supplied = encoder.encode(request.headers.get("authorization") ?? "");
  return expected.length === supplied.length && timingSafeEqual(expected, supplied);
}
