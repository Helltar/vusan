import { deepStrictEqual, ok, strictEqual } from "node:assert/strict";
import { docker, dockerText } from "./docker.ts";

const image = Deno.env.get("WORKSPACE_TEST_IMAGE");

Deno.test({
  name: "container workspace lifecycle, files, isolation and command control",
  ignore: !image,
  async fn(t) {
    const namespace = `vusan-test-${crypto.randomUUID().slice(0, 8)}`;
    const supervisor = `${namespace}-supervisor`;
    const state = `${namespace}-state`;
    const auth = `${namespace}-auth`;
    const label = `com.helltar.vusan.workspace=${namespace}`;
    const encoder = new TextEncoder();
    let base = "";
    const headers = { authorization: "" };
    const request = async (path: string, options: RequestInit = {}) => {
      const response = await fetch(`${base}${path}`, { ...options, headers });
      return { status: response.status, body: await response.json() };
    };
    const run = (command: string, id = "u90001", timeoutSeconds = 30) =>
      request(`/jobs?id=${id}`, {
        method: "POST",
        body: JSON.stringify({ command, timeoutSeconds }),
      });
    const startSupervisor = async (network = "open", pressureTest = false) => {
      await docker([
        "run",
        "-d",
        "--name",
        supervisor,
        "--init",
        "--read-only",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--tmpfs",
        "/tmp:size=128m",
        "-p",
        "127.0.0.1::8080",
        "-v",
        "/var/run/docker.sock:/var/run/docker.sock",
        ...(pressureTest
          ? ["--tmpfs", "/state:size=128m"]
          : ["--mount", `type=volume,src=${state},dst=/state`]),
        "--mount",
        `type=volume,src=${auth},dst=/run/workspace-auth`,
        "-e",
        `WORKSPACE_IMAGE=${image}`,
        "-e",
        `WORKSPACE_NAMESPACE=${namespace}`,
        "-e",
        `WORKSPACE_NETWORK=${network}`,
        "-e",
        "WORKSPACE_TOKEN_FILE=/run/workspace-auth/token",
        "-e",
        `WORKSPACE_MIN_FREE_MB=${pressureTest ? 120 : 1}`,
        "-e",
        "WORKSPACE_MAX_CONCURRENT=1",
        "-e",
        "WORKSPACE_MAX_ACTIVE=3",
        "-e",
        "WORKSPACE_MEMORY_MB=512",
        "-e",
        "WORKSPACE_DISK_WARN_MB=1",
        "-e",
        "WORKSPACE_MAX_HOME_MB=128",
        "-e",
        "WORKSPACE_MAX_FILE_MB=64",
        image!,
      ]);
      base = `http://${await dockerText(["port", supervisor, "8080/tcp"])}`;
      for (let i = 0; i < 100; i++) {
        const ready = await fetch(`${base}/health`).then(async (r) => {
          await r.arrayBuffer();
          return r.ok;
        }).catch(() => false);
        if (ready) {
          const token = await dockerText([
            "exec",
            "--user",
            "1000:1000",
            supervisor,
            "cat",
            "/run/workspace-auth/token",
          ]);
          strictEqual(token.length, 64);
          if (headers.authorization) strictEqual(headers.authorization, `Bearer ${token}`);
          headers.authorization = `Bearer ${token}`;
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(await dockerText(["logs", supervisor]));
    };
    try {
      await startSupervisor();
      await t.step("API authentication is required outside the health check", async () => {
        const response = await fetch(`${base}/jobs?id=u90001`);
        strictEqual(response.status, 401);
        await response.arrayBuffer();
        const file = await fetch(`${base}/files?id=u90001&path=private.png`);
        strictEqual(file.status, 401);
        await file.arrayBuffer();
        const wrong = await fetch(`${base}/jobs?id=u90001`, { headers: { authorization: "Bearer wrong" } });
        strictEqual(wrong.status, 401);
        await wrong.arrayBuffer();
        strictEqual((await request("/jobs?id=u90001_g42")).status, 400);
      });
      await t.step("bash starts unprivileged with a persistent home", async () => {
        const result = await run("id -u; printf 'alpha\\r\\nbeta\\r\\n'; printf saved > kept.txt");
        strictEqual(result.status, 200);
        strictEqual(result.body.status, "completed", JSON.stringify(result.body));
        strictEqual(result.body.exitCode, 0, JSON.stringify(result.body));
        ok(result.body.output.includes("1000\nalpha\nbeta\n"), JSON.stringify(result.body));
      });
      await t.step("file transfers create parents and preserve exact bytes", async () => {
        const content = "recipe = 'blue kettle'\n";
        const written = await request("/files?id=u90001&path=project/recipe.txt", {
          method: "PUT",
          body: content,
        });
        strictEqual(written.status, 200, JSON.stringify(written));
        const response = await fetch(`${base}/files?id=u90001&path=project/recipe.txt`, { headers });
        strictEqual(response.status, 200, await response.clone().text());
        strictEqual(await response.text(), content);
      });
      await t.step("the base tools and an unprivileged browser work without language SDKs", async () => {
        const result = await run(
          "command -v bash python3 node git curl jq pandoc ffmpeg sqlite3 rg; " +
            "! command -v java && ! command -v kotlinc && " +
            "python3 -m venv .venv && .venv/bin/python -c 'print(42)' && " +
            "chromium --headless --dump-dom about:blank 2>/dev/null",
        );
        strictEqual(result.body.exitCode, 0, JSON.stringify(result.body));
        ok(result.body.output.includes("<html>"), JSON.stringify(result.body));
      });
      await t.step("files and local servers stay separate between people", async () => {
        const first = await run("python3 -m http.server 8765 --bind 127.0.0.1 >server.log 2>&1 &");
        strictEqual(first.body.exitCode, 0);
        const second = await run(
          "test ! -f kept.txt && ! curl -fsS --max-time 1 http://127.0.0.1:8765/kept.txt",
          "u90002",
        );
        strictEqual(second.body.exitCode, 0, JSON.stringify(second.body));
        const local = await run("curl -fsS --max-time 3 http://127.0.0.1:8765/kept.txt");
        strictEqual(local.body.output.trim(), "saved");
      });
      await t.step("file transfers refuse symlinks and traversal before writing", async () => {
        await run("ln -s /tmp redirected; ln -s /etc/passwd passwd-link");
        const read = await fetch(`${base}/files?id=u90001&path=passwd-link`, { headers });
        ok(!read.ok);
        await read.arrayBuffer();
        const write = await request("/files?id=u90001&path=redirected/new-dir/file", {
          method: "PUT",
          body: "blocked",
        });
        ok(write.status !== 200);
        strictEqual((await run("test ! -e /tmp/new-dir")).body.exitCode, 0);
        const traversal = await request("/files?id=u90001&path=../bad-dir/file", {
          method: "PUT",
          body: "blocked",
        });
        ok(traversal.status !== 200);
      });
      await t.step("oversized files are refused before transfer", async () => {
        await run("truncate -s 60M large.bin");
        const response = await fetch(`${base}/files?id=u90001&path=large.bin`, { headers });
        ok(!response.ok);
        await response.arrayBuffer();
        strictEqual((await run("rm large.bin")).body.exitCode, 0);
      });
      await t.step("private egress and firewall changes are blocked", async () => {
        const result = await run(
          "! curl -fsS --max-time 2 http://169.254.169.254/ && ! iptables -F OUTPUT && test ! -S /var/run/docker.sock",
        );
        strictEqual(result.body.exitCode, 0, JSON.stringify(result.body));
      });
      await t.step("resource limits and controller secrets stay outside user control", async () => {
        const limits = JSON.parse(
          await dockerText([
            "inspect",
            "--format",
            "{{json .HostConfig}}",
            `${namespace}-workspace-u90001`,
          ]),
        );
        strictEqual(limits.Memory, 512 * 1024 * 1024);
        strictEqual(limits.MemorySwap, limits.Memory);
        strictEqual(limits.NanoCpus, 2_000_000_000);
        strictEqual(limits.PidsLimit, 256);
        strictEqual(limits.ReadonlyRootfs, true);
        const result = await run(
          'test -z "${WORKSPACE_TOKEN+x}" && test ! -e /state && test ! -S /var/run/docker.sock',
        );
        strictEqual(result.body.exitCode, 0, JSON.stringify(result.body));
      });
      await t.step("user-installed commands cannot shadow transfer helpers", async () => {
        await run(
          "mkdir -p .local/bin; printf '#!/bin/sh\\nexit 99\\n' > .local/bin/deno; chmod +x .local/bin/deno",
        );
        const response = await fetch(`${base}/files?id=u90001&path=kept.txt`, { headers });
        strictEqual(response.status, 200);
        strictEqual(await response.text(), "saved");
      });
      await t.step("concurrency is reserved before asynchronous startup", async () => {
        const results = await Promise.all([run("sleep 2"), run("sleep 2", "u90002")]);
        deepStrictEqual(results.map((r) => r.status).sort(), [200, 409]);
      });
      await t.step(
        "idle slots are reclaimed without deleting files or evicting a running command",
        async () => {
          await run("printf third > marker.txt", "u90003");
          const started = await run("sleep 100", "u90001", 120);
          strictEqual(started.body.status, "running");
          for (const id of ["u90004", "u90005"]) {
            const write = await request(`/files?id=${id}&path=marker.txt`, { method: "PUT", body: "marker" });
            strictEqual(write.status, 200);
          }
          strictEqual((await request(`/jobs/${started.body.jobId}?id=u90001`)).body.status, "running");
          await request(`/jobs/${started.body.jobId}?id=u90001`, { method: "DELETE" });
          strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
          strictEqual((await run("cat marker.txt", "u90003")).body.output.trim(), "third");
        },
      );
      await t.step("timeout kills detached descendants while retaining files", async () => {
        const result = await run("setsid sleep 100 & wait", "u90001", 1);
        strictEqual(result.body.status, "timed_out", JSON.stringify(result.body));
        const next = await run("cat kept.txt; test -z \"$(pgrep -f '^sleep 100$')\"");
        strictEqual(next.body.exitCode, 0);
        strictEqual(next.body.output.trim(), "saved");
      });
      await t.step("long jobs return an id and can be cancelled", async () => {
        const started = await run("printf waiting; sleep 100", "u90001", 120);
        strictEqual(started.body.status, "running");
        const wrong = await request(`/jobs/${started.body.jobId}?id=u90002`);
        strictEqual(wrong.status, 404);
        const stopped = await request(`/jobs/${started.body.jobId}?id=u90001`, { method: "DELETE" });
        strictEqual(stopped.body.status, "cancelled");
      });
      await t.step("restart marks active jobs interrupted and keeps file volumes", async () => {
        const started = await run("sleep 100", "u90001", 120);
        strictEqual(started.body.status, "running");
        await docker(["rm", "-f", supervisor]);
        await startSupervisor();
        const result = await request(`/jobs/${started.body.jobId}?id=u90001`);
        strictEqual(result.body.status, "interrupted");
        strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
      });
      await t.step("output can be collected without rerunning a command", async () => {
        const result = await run("python3 -c 'print(\"x\" * 20000)' ");
        strictEqual(result.body.hasMore, true);
        const tail = await request(`/jobs/${result.body.jobId}?id=u90001&offset=${result.body.nextOffset}`);
        strictEqual(encoder.encode(result.body.output + tail.body.output).length, 20001);
        strictEqual(tail.body.hasMore, false);
      });
      await t.step("UTF-8 output remains intact across page boundaries", async () => {
        const result = await run("python3 -c 'print(\"🍋\" * 5000)' ");
        const tail = await request(`/jobs/${result.body.jobId}?id=u90001&offset=${result.body.nextOffset}`);
        strictEqual(result.body.output + tail.body.output, "🍋".repeat(5000) + "\n");
      });
      await t.step("output truncation does not block the command or pretend to keep a full log", async () => {
        const result = await run("head -c 9000000 /dev/zero");
        strictEqual(result.body.exitCode, 0);
        strictEqual(result.body.truncated, true);
      });
      await t.step("disk warnings leave cleanup commands available", async () => {
        const result = await run("truncate -s 2M disposable.bin");
        strictEqual(result.body.diskWarning, true);
        strictEqual((await run("rm disposable.bin")).body.exitCode, 0);
      });
      await t.step("a home over its hard limit loses its container mid-command", async () => {
        const filling = await run(
          "for i in $(seq 30); do dd if=/dev/zero of=fill.$i bs=1M count=16 status=none; sleep 0.2; done",
          "u90001",
          120,
        );
        let job = filling.body;
        for (let i = 0; i < 100; i++) {
          job = (await request(`/jobs/${filling.body.jobId}?id=u90001`)).body;
          const owned = await dockerText(["ps", "-aq", "--filter", `label=${label}`]);
          if (job.status !== "running" && !owned) break;
          await new Promise((resolve) => setTimeout(resolve, 200));
        }
        strictEqual(job.status, "failed", JSON.stringify(job));
        ok(job.error.includes("exceeded its 128 MB limit"), job.error);
        strictEqual(await dockerText(["ps", "-aq", "--filter", `label=${label}`]), "");
        strictEqual((await run("rm -f fill.*")).body.exitCode, 0);
      });
      await t.step("a single file cannot grow past the workspace file limit", async () => {
        const result = await run("dd if=/dev/zero of=oversized.bin bs=1M count=128 status=none");
        ok(result.body.exitCode !== 0, JSON.stringify(result.body));
        strictEqual((await run("stat -c %s oversized.bin")).body.output.trim(), String(64 * 1024 * 1024));
        strictEqual((await run("rm -f oversized.bin")).body.exitCode, 0);
      });
      await t.step("ordinary shutdown removes child containers but preserves volumes", async () => {
        await docker(["stop", "--time", "30", supervisor]);
        strictEqual(await dockerText(["ps", "-aq", "--filter", `label=${label}`]), "");
        await docker(["rm", supervisor]);
        await startSupervisor();
        strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
      });
      await t.step("offline networking still allows local servers", async () => {
        await docker(["stop", "--time", "30", supervisor]);
        await docker(["rm", supervisor]);
        await startSupervisor("none");
        const local = await run("python3 -m http.server 8765 --bind 127.0.0.1 >server.log 2>&1 &");
        strictEqual(local.body.exitCode, 0);
        const result = await run(
          "curl -fsS --max-time 3 http://127.0.0.1:8765/kept.txt && ! curl -fsS --max-time 1 http://1.1.1.1",
        );
        strictEqual(result.body.exitCode, 0, JSON.stringify(result.body));
        ok(result.body.output.startsWith("saved"));
      });
      await t.step("storage pressure stops containers, refuses uploads and then clears itself", async () => {
        const upload = (body: string) =>
          request("/files?id=u90001&path=pressure.txt", { method: "PUT", body });
        await docker(["stop", "--time", "30", supervisor]);
        await docker(["rm", supervisor]);
        await startSupervisor("open", true);
        const started = await run("sleep 100", "u90001", 120);
        strictEqual(started.body.status, "running");
        // a bounded tmpfs simulates low space; the host data disk is never filled.
        await docker(["exec", supervisor, "dd", "if=/dev/zero", "of=/state/pressure", "bs=1M", "count=16"]);
        for (let i = 0; i < 100; i++) {
          const result = await request(`/jobs/${started.body.jobId}?id=u90001`);
          const owned = await dockerText(["ps", "-aq", "--filter", `label=${label}`]);
          if (result.body.status === "failed" && !owned) break;
          await new Promise((resolve) => setTimeout(resolve, 100));
        }
        strictEqual((await request(`/jobs/${started.body.jobId}?id=u90001`)).body.status, "failed");
        strictEqual(await dockerText(["ps", "-aq", "--filter", `label=${label}`]), "");
        strictEqual((await upload("blocked")).status, 507);
        // deleting files is the only way back, so commands are not part of what the latch closes.
        strictEqual((await run("printf cleanup")).body.exitCode, 0);
        await docker(["exec", supervisor, "rm", "/state/pressure"]);
        for (let i = 0; i < 100; i++) {
          if (await fetch(`${base}/health`).then((response) => response.ok)) break;
          await new Promise((resolve) => setTimeout(resolve, 100));
        }
        strictEqual((await upload("allowed")).status, 200);
      });
    } finally {
      // let the controller finish its own removals before test cleanup touches the same children.
      await docker(["stop", "--time", "30", supervisor]).catch(() => {});
      const owned = await dockerText(["ps", "-aq", "--filter", `label=${label}`]);
      for (const id of owned.split("\n").filter(Boolean)) await docker(["rm", "-f", id]);
      await docker(["rm", "-f", supervisor]).catch(() => {});
      const volumes = await dockerText(["volume", "ls", "-q", "--filter", `label=${label}`]);
      for (const volume of volumes.split("\n").filter(Boolean)) await docker(["volume", "rm", volume]);
      await docker(["volume", "rm", state]);
      await docker(["volume", "rm", auth]);
    }
  },
});
