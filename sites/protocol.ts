export const MAX_PATH_CHARS = 400;
export const MAX_SEGMENT_CHARS = 120;

export class RequestError extends Error {
  constructor(message: string, readonly status = 400) {
    super(message);
  }
}

/** The same key the workspace uses, so one person is one identity across both services. */
export function ownerId(value: unknown): string {
  if (typeof value !== "string" || !/^u(?:0|[1-9][0-9]{0,18})$/.test(value)) {
    throw new RequestError("Invalid owner id");
  }
  return value;
}

/**
 * The subdomain a site is served at. Digits only, asserted here and again in the nginx `server_name`:
 * a numeric label can never shadow `api` or `www`, and can never name a directory outside the tree.
 */
export function siteLabel(owner: unknown): string {
  return ownerId(owner).slice(1);
}

export function uploadId(value: unknown): string {
  if (typeof value !== "string" || !/^[a-f0-9]{32}$/.test(value)) {
    throw new RequestError("Unknown upload", 404);
  }
  return value;
}

function controlCharacters(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

/**
 * A path inside one site. Segments starting with a dot are refused outright: `.git` and `.env` reach a
 * published tree by accident far more often than anything needs them, and nothing here serves ACME.
 */
export function sitePath(raw: unknown, maxDepth: number): string {
  if (typeof raw !== "string" || !raw || raw.length > MAX_PATH_CHARS) {
    throw new RequestError("Missing or invalid file path");
  }
  if (raw.startsWith("/") || raw.includes("\\") || controlCharacters(raw)) {
    throw new RequestError("Invalid characters in file path");
  }
  const parts = raw.split("/").filter((part) => part !== "" && part !== ".");
  if (!parts.length) throw new RequestError("Path must name a file inside the site");
  if (parts.length > maxDepth) throw new RequestError(`Paths may be at most ${maxDepth} levels deep`);
  for (const part of parts) {
    if (part === "..") throw new RequestError("Path must stay inside the site");
    if (part.startsWith(".")) throw new RequestError("Path segments must not start with a dot");
    if (part.length > MAX_SEGMENT_CHARS) throw new RequestError("Path segment is too long");
  }
  return parts.join("/");
}

export function json(value: unknown, status = 200): Response {
  return Response.json(value, { status });
}
