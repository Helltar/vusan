import { strictEqual } from "node:assert/strict";
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
