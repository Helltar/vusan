import { rejects, strictEqual } from "node:assert/strict";
import { readConfig } from "./config.ts";
import { Containers } from "./container.ts";
import { Jobs } from "./jobs.ts";

Deno.test("recovery marks running jobs interrupted without losing output or touching other statuses", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root };
    const jobs = new Jobs(config, new Containers(config));
    await Deno.mkdir(`${root}/u42`);
    const running = crypto.randomUUID();
    const completed = crypto.randomUUID();
    for (const [jobId, status] of [[running, "running"], [completed, "completed"]]) {
      await Deno.writeTextFile(
        `${root}/u42/${jobId}.json`,
        JSON.stringify({
          jobId,
          workspaceId: "u42",
          status,
          startedAt: 1,
          truncated: false,
        }),
      );
    }
    await Deno.writeTextFile(`${root}/u42/${running}.log`, "saved output");
    await jobs.recover();
    const recovered = await jobs.read("u42", running, 0, 0);
    strictEqual(recovered.status, "interrupted");
    strictEqual(recovered.output, "saved output");
    strictEqual((await jobs.read("u42", completed, 0, 0)).status, "completed");
    strictEqual((await jobs.list("u42")).length, 2);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("shutdown during job admission cannot start a container afterwards", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root };
    const jobs = new Jobs(config, new Containers(config));
    const starting = jobs.start("u42", "printf should-not-run", 10);
    const stopping = jobs.shutdown();
    const job = await starting;
    await stopping;
    const result = await jobs.read("u42", job.jobId, 0, 0);
    strictEqual(result.status, "interrupted");
    strictEqual(result.error, undefined);
    strictEqual(result.output, "");
    strictEqual(jobs.busy("u42"), false);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("a workspace nobody came back to is deleted whole, and not on every sweep", async () => {
  const root = await Deno.makeTempDir();
  try {
    const config = { ...readConfig(), stateDir: root, retainDays: 14 };
    const wiped: string[] = [];
    const containers = {
      homeIds: () => Promise.resolve(["u5"]),
      liveIds: () => [],
      // u5 has no records of its own; its home volume is all there is to date it by.
      homeCreated: (id: string) => Promise.resolve(id === "u5" ? 0 : Date.now()),
      wipe: (id: string) => {
        wiped.push(id);
        return Promise.resolve();
      },
    } as unknown as Containers;
    const jobs = new Jobs(config, containers);
    const record = async (id: string, startedAt: number) => {
      const jobId = crypto.randomUUID();
      await Deno.mkdir(`${root}/${id}`, { recursive: true });
      await Deno.writeTextFile(
        `${root}/${id}/${jobId}.json`,
        JSON.stringify({ jobId, workspaceId: id, status: "completed", startedAt, truncated: false }),
      );
    };
    const stale = Date.now() - 20 * 24 * 60 * 60_000;
    await record("u1", stale);
    await record("u2", Date.now());
    // a stale mark cannot condemn a workspace that ran something since: the newest signal wins.
    await record("u3", Date.now());
    await Deno.writeTextFile(`${root}/u3/used`, "");
    await Deno.utime(`${root}/u3/used`, new Date(stale), new Date(stale));

    await jobs.prune();
    strictEqual(wiped.sort().join(","), "u1,u5");
    await rejects(() => Deno.stat(`${root}/u1`));
    strictEqual((await jobs.list("u2")).length, 1);
    strictEqual((await jobs.list("u3")).length, 1);

    await record("u4", stale);
    await jobs.prune();
    strictEqual((await Deno.stat(`${root}/u4`)).isDirectory, true);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});
