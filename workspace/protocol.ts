export const FILE_LIMIT = 50 * 1024 * 1024;
export const COMMAND_LIMIT = 16_000;
export const LOG_LIMIT = 8 * 1024 * 1024;
export const OUTPUT_CHUNK = 16 * 1024;
export const JOBS_RETAINED = 20;

export class RequestError extends Error {
  constructor(message: string, readonly status = 400) {
    super(message);
  }
}

export function workspaceId(value: unknown): string {
  if (typeof value !== "string" || !/^u[0-9]+(?:_g[0-9]+)?$/.test(value) || value.length > 64) {
    throw new RequestError("Invalid workspace id");
  }
  return value;
}

export function jobId(value: unknown): string {
  if (typeof value !== "string" || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)) {
    throw new RequestError("Invalid job id");
  }
  return value;
}

export function integer(value: unknown, fallback: number, max: number): number {
  if (value === undefined || value === null) return fallback;
  if ((typeof value !== "number" && typeof value !== "string") || value === "") {
    throw new RequestError("Expected a non-negative integer");
  }
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0) throw new RequestError("Expected a non-negative integer");
  return Math.min(n, max);
}

export function json(value: unknown, status = 200): Response {
  return Response.json(value, { status });
}

export async function readBounded(
  stream: ReadableStream<Uint8Array> | null,
  cap: number,
): Promise<Uint8Array> {
  if (!stream) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > cap) throw new RequestError("File or response exceeds the size limit", 413);
    chunks.push(chunk);
  }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}
