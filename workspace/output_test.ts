import { strictEqual } from "node:assert/strict";
import { clean, completeUtf8Prefix, JobLog } from "./output.ts";

const encode = (s: string) => new TextEncoder().encode(s);

Deno.test("output cleanup preserves CRLF lines and removes terminal control codes", () => {
  strictEqual(clean(encode("first\r\nsecond\r\n")), "first\nsecond\n");
  strictEqual(clean(encode("old\rnew\n\x1b[31mred\x1b[0m\x00")), "new\nred");
  strictEqual(clean(new Uint8Array([255, 255, 255, 255])), "[binary output omitted]");
});

Deno.test("UTF-8 pagination does not split a multibyte character", () => {
  const bytes = encode("a🍋");
  for (const length of [2, 3, 4]) strictEqual(completeUtf8Prefix(bytes.subarray(0, length)), 1);
  strictEqual(completeUtf8Prefix(bytes), bytes.length);
  strictEqual(completeUtf8Prefix(encode("recipe")), 6);
});

Deno.test("concurrent log streams share a hard byte cap and continue draining", async () => {
  const path = await Deno.makeTempFile();
  try {
    using file = await Deno.open(path, { write: true });
    const log = new JobLog(file, 10);
    await Promise.all([
      log.drain(new Response(encode("a".repeat(100))).body!),
      log.drain(new Response(encode("b".repeat(100))).body!),
    ]);
    strictEqual(log.bytes, 10);
    strictEqual(log.truncated, true);
    strictEqual((await Deno.stat(path)).size, 10);
  } finally {
    await Deno.remove(path);
  }
});
