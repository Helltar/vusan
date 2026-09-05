import { timingSafeEqual } from "node:crypto";

export function validateToken(token: string): string {
  if (!/^[\x21-\x7e]{32,256}$/.test(token)) {
    throw new Error("WORKSPACE_TOKEN must contain 32 to 256 printable non-whitespace ASCII characters");
  }
  return token;
}

/** The bot and the controller are configured with the same secret; nothing is generated here. */
export async function loadToken(token: string | null, path: string | null): Promise<string> {
  if (token) return validateToken(token);
  if (!path) throw new Error("WORKSPACE_TOKEN or WORKSPACE_TOKEN_FILE is required");
  const saved = await Deno.readTextFile(path).catch(() => {
    throw new Error(`Cannot read WORKSPACE_TOKEN_FILE at ${path}`);
  });
  return validateToken(saved.trim());
}

export function authorized(request: Request, token: string): boolean {
  const encoder = new TextEncoder();
  const expected = encoder.encode(`Bearer ${token}`);
  const supplied = encoder.encode(request.headers.get("authorization") ?? "");
  return expected.length === supplied.length && timingSafeEqual(expected, supplied);
}
