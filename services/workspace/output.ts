// deno-lint-ignore-file no-control-regex
import { LOG_LIMIT } from "./protocol.ts";

export function completeUtf8Prefix(bytes: Uint8Array): number {
  if (!bytes.length) return 0;
  let start = bytes.length - 1;
  while (start > 0 && (bytes[start] & 0xc0) === 0x80) start--;
  const lead = bytes[start];
  const length = lead >= 0xc2 && lead <= 0xdf
    ? 2
    : lead >= 0xe0 && lead <= 0xef
    ? 3
    : lead >= 0xf0 && lead <= 0xf4
    ? 4
    : 1;
  return start + length > bytes.length ? start : bytes.length;
}

export function clean(bytes: Uint8Array): string {
  const text = new TextDecoder().decode(bytes)
    .replace(/\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g, "")
    .replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, "")
    .replace(/\x1b[@-Z\\-_]/g, "")
    .replace(/\r\n/g, "\n")
    .split("\n").map((line) => line.slice(line.lastIndexOf("\r") + 1)).join("\n")
    .replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, "");
  const replacements = text.match(/\ufffd/g)?.length ?? 0;
  return text.length && replacements / text.length > 0.2 ? "[binary output omitted]" : text;
}

export class JobLog {
  bytes = 0;
  truncated = false;
  private queue: Promise<void> = Promise.resolve();

  constructor(private readonly file: Deno.FsFile, private readonly cap = LOG_LIMIT) {}

  async drain(stream: ReadableStream<Uint8Array>): Promise<void> {
    for await (const chunk of stream) {
      const part = chunk.subarray(0, Math.max(0, this.cap - this.bytes));
      this.truncated ||= part.length < chunk.length;
      this.bytes += part.length;
      if (part.length) {
        this.queue = this.queue.then(async () => {
          let offset = 0;
          while (offset < part.length) offset += await this.file.write(part.subarray(offset));
        });
        await this.queue;
      }
    }
  }
}
