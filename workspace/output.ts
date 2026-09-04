// output hygiene. a command's stdout is attacker-controlled bytes on their way into a
// telegram message, so it is capped while the pipe is drained, stripped of terminal
// control sequences, and forced back into valid UTF-8 before anything else sees it.

// the escape and control characters this module exists to remove are matched literally
// deno-lint-ignore-file no-control-regex

const ANSI_CSI = /\x1b\[[0-9;?]*[ -/]*[@-~]/g;
const ANSI_OSC = /\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g;
const ANSI_SINGLE = /\x1b[@-Z\\-_]/g;
// everything in C0 except tab and newline, plus DEL
const CONTROL = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g;

export interface Capture {
  text: string;
  bytes: number;
  truncated: boolean;
}

/**
 * Drains the stream to the end while keeping at most `cap` bytes. Draining past the cap
 * is the point: a reader that stops reading blocks the writer, and the command would
 * hang instead of finishing.
 */
export async function capture(
  stream: ReadableStream<Uint8Array>,
  cap: number,
  log?: LogSink,
): Promise<Capture> {
  const kept: Uint8Array[] = [];
  let keptBytes = 0;
  let total = 0;

  for await (const chunk of stream) {
    total += chunk.length;
    await log?.write(chunk);

    if (keptBytes < cap) {
      const room = cap - keptBytes;
      const slice = chunk.length <= room ? chunk : chunk.subarray(0, room);
      kept.push(slice);
      keptBytes += slice.length;
    }
  }

  return { text: readable(clean(concat(kept, keptBytes))), bytes: total, truncated: total > keptBytes };
}

function concat(chunks: Uint8Array[], size: number): Uint8Array {
  const out = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

// binary on stdout is a mistake the model should be told about rather than shown: a page of
// replacement characters costs tokens, teaches it nothing, and reads as a broken tool.
function readable(text: string): string {
  if (!text) return text;
  const replacements = (text.match(/\ufffd/g) ?? []).length;
  return replacements / text.length > 0.2 ? "[binary output, not shown]" : text;
}

export function clean(bytes: Uint8Array): string {
  // fatal:false turns a half-written multi-byte character — normal at a byte cap — into
  // a replacement character rather than an exception
  const decoded = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  return collapseCarriageReturns(
    decoded.replace(ANSI_OSC, "").replace(ANSI_CSI, "").replace(ANSI_SINGLE, ""),
  ).replace(CONTROL, "");
}

// progress bars rewrite one line with \r. keeping only what follows the last \r leaves
// the final state of the bar instead of every frame it drew.
function collapseCarriageReturns(text: string): string {
  if (!text.includes("\r")) return text;
  return text
    .split("\n")
    .map((line) => (line.includes("\r") ? line.slice(line.lastIndexOf("\r") + 1) : line))
    .join("\n");
}

export class LogSink {
  private written = 0;
  private closed = false;

  private constructor(private readonly file: Deno.FsFile, private readonly cap: number) {}

  static async open(path: string, cap: number, uid: number, gid: number): Promise<LogSink | undefined> {
    try {
      const file = await Deno.open(path, { write: true, create: true, truncate: true, mode: 0o600 });
      await Deno.chown(path, uid, gid).catch(() => {});
      return new LogSink(file, cap);
    } catch {
      return undefined; // a missing log is never a reason to fail the command
    }
  }

  async write(chunk: Uint8Array): Promise<void> {
    if (this.closed || this.written >= this.cap) return;
    const slice = chunk.length <= this.cap - this.written
      ? chunk
      : chunk.subarray(0, this.cap - this.written);
    this.written += slice.length;
    await this.file.write(slice).catch(() => {
      this.closed = true;
    });
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    try {
      this.file.close();
    } catch {
      // already gone
    }
  }
}
