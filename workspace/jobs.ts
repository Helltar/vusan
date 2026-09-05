import type { Config } from "./config.ts";
import type { Containers } from "./container.ts";
import { clean, completeUtf8Prefix, JobLog } from "./output.ts";
import { COMMAND_LIMIT, jobId, JOBS_RETAINED, OUTPUT_CHUNK, RequestError, workspaceId } from "./protocol.ts";

type Status = "running" | "completed" | "timed_out" | "cancelled" | "interrupted" | "failed";
export interface Job {
  jobId: string;
  workspaceId: string;
  status: Status;
  startedAt: number;
  finishedAt?: number;
  exitCode?: number;
  truncated: boolean;
  usedBytes?: number;
  error?: string;
}

interface Running {
  job: Job;
  done: Promise<void>;
  reason?: "timed_out" | "cancelled" | "interrupted" | "failed";
  child?: Deno.ChildProcess;
}

export class Jobs {
  private readonly active = new Map<string, Running>();

  constructor(private readonly config: Config, private readonly containers: Containers) {}

  busy(id: string): boolean {
    return this.active.has(id);
  }

  private directory(id: string): string {
    return `${this.config.stateDir}/${workspaceId(id)}`;
  }

  private path(id: string, run: string): string {
    return `${this.directory(id)}/${jobId(run)}`;
  }

  private async save(job: Job): Promise<void> {
    const path = `${this.path(job.workspaceId, job.jobId)}.json`;
    const tmp = `${path}.${crypto.randomUUID()}.tmp`;
    await Deno.writeTextFile(tmp, JSON.stringify(job), { mode: 0o600 });
    await Deno.rename(tmp, path);
  }

  private async load(id: string, run: string): Promise<Job> {
    try {
      return JSON.parse(await Deno.readTextFile(`${this.path(id, run)}.json`));
    } catch (e) {
      if (e instanceof Deno.errors.NotFound) {
        throw new RequestError("Command not found in this workspace", 404);
      }
      throw e;
    }
  }

  async recover(): Promise<void> {
    for await (const directory of Deno.readDir(this.config.stateDir)) {
      if (!directory.isDirectory || !/^u(?:0|[1-9][0-9]{0,18})$/.test(directory.name)) continue;
      const id = workspaceId(directory.name);
      for (const job of await this.list(id)) {
        if (job.status !== "running") continue;
        job.status = "interrupted";
        job.finishedAt = Date.now();
        job.error = "The workspace service restarted. Files were kept; the command was stopped.";
        await this.save(job);
      }
    }
  }

  async list(id: string): Promise<Job[]> {
    const jobs: Job[] = [];
    try {
      for await (const entry of Deno.readDir(this.directory(id))) {
        if (entry.isFile && entry.name.endsWith(".json")) {
          jobs.push(await this.load(id, entry.name.slice(0, -5)));
        }
      }
    } catch (e) {
      if (!(e instanceof Deno.errors.NotFound)) throw e;
    }
    return jobs.sort((a, b) => b.startedAt - a.startedAt);
  }

  async start(id: string, command: string, timeout: number): Promise<Job> {
    workspaceId(id);
    if (!command.trim() || command.length > COMMAND_LIMIT || command.includes("\0")) {
      throw new RequestError("Invalid command");
    }
    // reserve before the first await, across both admission checks and container startup.
    if (this.active.has(id)) {
      throw new RequestError("A command is already running in this workspace; read or cancel it first", 409);
    }
    if (this.active.size >= this.config.maxConcurrent) {
      throw new RequestError("The workspace service is at capacity; try again shortly", 409);
    }
    const completion = Promise.withResolvers<void>();
    const job: Job = {
      jobId: crypto.randomUUID(),
      workspaceId: id,
      status: "running",
      startedAt: Date.now(),
      truncated: false,
    };
    const running: Running = { job, done: completion.promise };
    this.active.set(id, running);
    try {
      await Deno.mkdir(this.directory(id), { recursive: true, mode: 0o700 });
      for (const old of (await this.list(id)).slice(JOBS_RETAINED - 1)) {
        await Deno.remove(`${this.path(id, old.jobId)}.json`);
        await Deno.remove(`${this.path(id, old.jobId)}.log`).catch(() => {});
      }
      await this.save(job);
    } catch (e) {
      this.active.delete(id);
      throw e;
    }
    void this.execute(running, command, timeout).finally(() => completion.resolve());
    return job;
  }

  private async execute(running: Running, command: string, timeout: number): Promise<void> {
    using _lease = this.containers.reserve(running.job.workspaceId);
    const job = running.job;
    let child: Deno.ChildProcess | undefined;
    let deadline: ReturnType<typeof setTimeout> | undefined;
    try {
      if (running.reason) return;
      await this.containers.ensure(job.workspaceId);
      if (running.reason) return;
      using file = await Deno.open(`${this.path(job.workspaceId, job.jobId)}.log`, {
        create: true,
        write: true,
        mode: 0o600,
      });
      const log = new JobLog(file);
      child = this.containers.command(job.workspaceId, command);
      running.child = child;
      deadline = setTimeout(() => {
        void this.stop(running, "timed_out").catch((e) => console.error("timeout cleanup failed", e));
      }, timeout * 1000);
      const [status] = await Promise.all([child.status, log.drain(child.stdout), log.drain(child.stderr)]);
      clearTimeout(deadline);
      job.exitCode = status.code;
      job.truncated = log.truncated;
      job.status = "completed";
      if (!running.reason) {
        job.usedBytes = await this.containers.usage(job.workspaceId).catch(() => undefined);
      }
    } catch (e) {
      job.status = "failed";
      job.error = e instanceof Error ? e.message : "Command failed";
      await this.containers.stop(job.workspaceId).catch((error) =>
        console.error("command cleanup failed", error)
      );
      if (child) {
        try {
          child.kill("SIGKILL");
        } catch { /* already exited */ }
        await child.status;
      }
    } finally {
      clearTimeout(deadline);
      job.status = running.reason ?? job.status;
      job.finishedAt = Date.now();
      this.containers.touch(job.workspaceId);
      try {
        await this.save(job);
      } catch (e) {
        console.error("cannot persist command result", e);
      }
      this.active.delete(job.workspaceId);
      console.log(
        `exec workspace=[${job.workspaceId}] job=[${job.jobId}] status=[${job.status}] exit=[${job.exitCode}] elapsed=[${
          job.finishedAt - job.startedAt
        }ms] cmd=[${command.replace(/\s+/g, " ").slice(0, 200)}]`,
      );
    }
  }

  /** The disk guard stops a workspace whether or not one of its commands is still running. */
  async evict(id: string, message: string): Promise<void> {
    const running = this.active.get(id);
    if (!running) return await this.containers.stop(id);
    running.job.error = message;
    await this.stop(running, "failed");
    await running.done;
  }

  private async stop(running: Running, reason: "timed_out" | "cancelled" | "failed"): Promise<void> {
    running.reason ??= reason;
    // removing the container also kills setsid children and processes holding an output pipe open.
    try {
      await this.containers.stop(running.job.workspaceId);
    } catch (e) {
      running.reason = "failed";
      running.job.error = "Docker cleanup failed; cannot confirm that workspace processes stopped.";
      try {
        running.child?.kill("SIGKILL");
      } catch { /* already exited */ }
      throw e;
    }
  }

  async shutdown(): Promise<void> {
    const pending = [...this.active.values()];
    for (const running of pending) running.reason ??= "interrupted";
    await this.containers.shutdown();
    await Promise.all(pending.map((running) => running.done));
  }

  async cancel(id: string, run: string): Promise<void> {
    await this.load(id, run);
    const running = this.active.get(id);
    if (running?.job.jobId === run) {
      await this.stop(running, "cancelled");
      await running.done;
    }
  }

  async read(id: string, run: string, offset: number, waitSeconds: number) {
    const running = this.active.get(id);
    if (running?.job.jobId === run && waitSeconds > 0) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      await Promise.race([
        running.done,
        new Promise<void>((resolve) => {
          timer = setTimeout(resolve, waitSeconds * 1000);
        }),
      ]);
      clearTimeout(timer);
    }
    const job = running?.job.jobId === run ? running.job : await this.load(id, run);
    this.containers.touch(id);
    let output = "";
    let nextOffset = offset;
    let hasMore = false;
    try {
      using file = await Deno.open(`${this.path(id, run)}.log`, { read: true });
      const size = (await file.stat()).size;
      nextOffset = Math.min(offset, size);
      await file.seek(nextOffset, Deno.SeekMode.Start);
      const bytes = new Uint8Array(OUTPUT_CHUNK);
      let read = await file.read(bytes) ?? 0;
      if (nextOffset + read < size || job.status === "running") {
        read = completeUtf8Prefix(bytes.subarray(0, read));
      }
      output = clean(bytes.subarray(0, read));
      nextOffset += read;
      hasMore = nextOffset < size;
    } catch (e) {
      if (!(e instanceof Deno.errors.NotFound)) throw e;
    }
    return {
      ...job,
      output,
      nextOffset,
      hasMore,
      elapsedMs: (job.finishedAt ?? Date.now()) - job.startedAt,
      diskWarning: (job.usedBytes ?? 0) > this.config.diskWarnMb * 1024 * 1024,
    };
  }
}
