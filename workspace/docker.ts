import { readBounded } from "./protocol.ts";

export function spawnDocker(args: string[], input = false): Deno.ChildProcess {
  return new Deno.Command("docker", {
    args,
    stdin: input ? "piped" : "null",
    stdout: "piped",
    stderr: "piped",
  }).spawn();
}

export async function docker(
  args: string[],
  options: { input?: Uint8Array; cap?: number; timeoutMs?: number; includeStderr?: boolean } = {},
): Promise<Uint8Array> {
  const child = spawnDocker(args, options.input !== undefined);
  let expired = false;
  const kill = () => {
    try {
      child.kill("SIGKILL");
    } catch { /* already exited */ }
  };
  const timer = setTimeout(() => {
    expired = true;
    kill();
  }, options.timeoutMs ?? 30_000);
  try {
    const write = async () => {
      if (options.input === undefined) return;
      const writer = child.stdin.getWriter();
      try {
        await writer.write(options.input);
        await writer.close();
      } finally {
        writer.releaseLock();
      }
    };
    const [out, err, status] = await Promise.all([
      readBounded(child.stdout, options.cap ?? 64 * 1024),
      readBounded(child.stderr, 16 * 1024),
      child.status,
      write(),
    ]);
    if (expired) throw new Error("Docker operation timed out");
    if (!status.success) {
      throw new Error(new TextDecoder().decode(err).trim().slice(0, 1000) || "Docker operation failed");
    }
    if (!options.includeStderr) return out;
    const combined = new Uint8Array(out.length + err.length);
    combined.set(out);
    combined.set(err, out.length);
    return combined;
  } finally {
    clearTimeout(timer);
    kill();
    await child.status;
  }
}

export async function dockerText(args: string[]): Promise<string> {
  return new TextDecoder().decode(await docker(args)).trim();
}
