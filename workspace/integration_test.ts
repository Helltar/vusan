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
        "-e",
        "WORKSPACE_IDLE_CPU_SECONDS=2",
        "-e",
        "WORKSPACE_BLOCKED_CIDRS=203.0.113.7/32",
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
      await t.step("a large transfer streams through the controller in both directions", async () => {
        // neither direction may be buffered whole: the controller runs on a 512 MiB ceiling.
        const bulk = new Uint8Array(40 * 1024 * 1024);
        // a position-dependent pattern, so a reordered or dropped chunk changes the digest.
        for (let i = 0; i < bulk.length; i++) bulk[i] = (i * 31 + (i >> 16)) & 0xff;
        const digest = async (bytes: BufferSource) =>
          [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
            .map((byte) => byte.toString(16).padStart(2, "0")).join("");
        const written = await request("/files?id=u90001&path=bulk.bin", { method: "PUT", body: bulk });
        strictEqual(written.body.bytes, bulk.length, JSON.stringify(written));
        const response = await fetch(`${base}/files?id=u90001&path=bulk.bin`, { headers });
        strictEqual(response.status, 200);
        strictEqual(await digest(await response.arrayBuffer()), await digest(bulk));
        strictEqual((await run("rm bulk.bin")).body.exitCode, 0);
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
      await t.step("a server bound to every interface is still unreachable from the bridge", async () => {
        const started = await run("python3 -m http.server 8766 --bind 0.0.0.0 >open.log 2>&1 &");
        strictEqual(started.body.exitCode, 0, JSON.stringify(started.body));
        const address = await dockerText([
          "inspect",
          "--format",
          "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
          `${namespace}-workspace-u90001`,
        ]);
        ok(/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(address), address);
        const reached = await docker([
          "run",
          "--rm",
          "--network=bridge",
          "--entrypoint",
          "/usr/bin/curl",
          image!,
          "-fsS",
          "--max-time",
          "3",
          `http://${address}:8766/`,
        ], { timeoutMs: 30_000 }).then(() => true).catch(() => false);
        ok(!reached, "a neighbour on the bridge reached into the workspace");
        // the bracket keeps the pattern from matching the shell that carries it on its own command line.
        const own = await run(
          "curl -fsS --max-time 3 http://127.0.0.1:8766/ >/dev/null; pkill -f '[h]ttp.server 8766'",
        );
        strictEqual(own.body.exitCode, 0, JSON.stringify(own.body));
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
        strictEqual(limits.NanoCpus, 1_000_000_000);
        strictEqual(limits.PidsLimit, 256);
        strictEqual(limits.ReadonlyRootfs, true);
        strictEqual(limits.Privileged, false);
        // a workspace holds no capability at all, not even one it could never use: the network policy
        // that used to need them lives on the host now.
        deepStrictEqual(limits.CapAdd ?? [], []);
        deepStrictEqual(limits.CapDrop, ["ALL"]);
        const identity = await dockerText([
          "inspect",
          "--format",
          "{{.Config.User}} {{index .Config.Entrypoint 0}}",
          `${namespace}-workspace-u90001`,
        ]);
        strictEqual(identity, "1000:1000 /usr/bin/sleep");
        const status = await run("grep -E '^(CapBnd|CapEff|NoNewPrivs):' /proc/self/status");
        ok(status.body.output.includes("CapBnd:\t0000000000000000"), status.body.output);
        ok(status.body.output.includes("NoNewPrivs:\t1"), status.body.output);
        strictEqual(limits.BlkioDeviceWriteBps[0].Rate, 50 * 1024 * 1024);
        strictEqual(limits.BlkioDeviceReadBps[0].Rate, 100 * 1024 * 1024);
        const rules = await dockerText([
          "run",
          "--rm",
          "--network=host",
          "--tmpfs",
          "/run:size=1m",
          "--cap-drop=ALL",
          "--cap-add=NET_ADMIN",
          "--entrypoint",
          "/usr/sbin/iptables",
          image!,
          "-S",
        ]);
        ok(rules.includes("203.0.113.7/32"), rules);
        ok(rules.includes("--hashlimit-above 2000/sec"), rules);
        ok(rules.includes("--hashlimit-above 100/sec"), rules);
        const chain = `WS_${namespace.toUpperCase().replace(/[^A-Z0-9]/g, "_").slice(0, 20)}`;
        ok(rules.includes(`-A DOCKER-USER -j ${chain}`), rules);
        ok(rules.includes(`-A ${chain}_IN -i br-`) && rules.includes("-j DROP"), rules);
        // the ranges an operator added by hand reach the policy too, not just the built-in ones.
        ok(rules.includes(`-A ${chain} -s`) && rules.includes("-d 203.0.113.7/32 -j REJECT"), rules);
        const result = await run(
          'test -z "${WORKSPACE_TOKEN+x}" && test ! -e /state && test ! -e /storage && test ! -e /dev/loop-control && test ! -S /var/run/docker.sock && ' +
            "test -z \"$(cat /proc/*/environ 2>/dev/null | tr '\\0' '\\n' | grep -a '^WORKSPACE_' || true)\"",
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
      await t.step("a reset empties the workspace and leaves it usable", async () => {
        const id = "u90006";
        // a deep tree with an unreadable directory is exactly what a recursive delete struggles with.
        const seeded = await run(
          "mkdir -p junk/deep/deeper && echo x > junk/deep/deeper/file && chmod 000 junk/deep && echo mine > mine.txt",
          id,
        );
        strictEqual(seeded.body.exitCode, 0, JSON.stringify(seeded.body));
        const reset = await request(`/workspace?id=${id}`, { method: "DELETE" });
        strictEqual(reset.status, 200, JSON.stringify(reset));
        const after = await run("test ! -e junk && test ! -e mine.txt && echo fresh > again.txt", id);
        strictEqual(after.body.exitCode, 0, JSON.stringify(after.body));
        // the replacement is a fresh bounded disk, not a leftover directory
        const home = JSON.parse(
          await dockerText(["volume", "inspect", `${namespace}-workspace-${id}-home`]),
        )[0];
        strictEqual(home.Labels["com.helltar.vusan.storage"], "bounded-home");
        // and nobody else's files moved
        strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
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
        const result = await run("dd if=/dev/zero of=disposable.bin bs=1M count=2 status=none");
        strictEqual(result.body.diskWarning, true);
        strictEqual((await run("rm disposable.bin")).body.exitCode, 0);
      });
      await t.step(
        "the filesystem stops fast multi-file allocation without consuming host storage",
        async () => {
          const filling = await run(
            "mkdir -p fill; for i in $(seq 30); do fallocate -l 16M fill/$i || exit 7; done",
            "u90001",
            120,
          );
          strictEqual(filling.body.exitCode, 7, JSON.stringify(filling.body));
          ok(filling.body.output.includes("No space left on device"), JSON.stringify(filling.body));
          const kept = await request("/files?id=u90001&path=fill", { method: "DELETE" });
          strictEqual(kept.status, 200, JSON.stringify(kept));
          strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
        },
      );
      await t.step("the home inode limit stops tiny-file exhaustion inside the bounded disk", async () => {
        const result = await run(
          "mkdir -p tiny; python3 -c \"from pathlib import Path; [(Path('tiny') / str(i)).touch() for i in range(200000)]\"",
          "u90001",
          120,
        );
        let job = result.body;
        while (job.status === "running") {
          job = (await request(`/jobs/${job.jobId}?id=u90001&waitSeconds=20`)).body;
        }
        ok(job.exitCode !== 0, JSON.stringify(job));
        ok(job.output.includes("No space left on device"), JSON.stringify(job));
        strictEqual((await request("/files?id=u90001&path=tiny", { method: "DELETE" })).status, 200);
        strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
      });
      await t.step(
        "cleanup bypasses poisoned profiles and repairs inaccessible owned directories",
        async () => {
          const poison = await run(
            "mkdir -p closed/nested; printf disposable > closed/nested/file; chmod 000 closed/nested closed; " +
              "printf 'touch /work/profile-ran\\n' > /work/.bash_profile",
          );
          strictEqual(poison.body.exitCode, 0);
          const cleanup = await request("/files?id=u90001&path=closed", { method: "DELETE" });
          strictEqual(cleanup.status, 200, JSON.stringify(cleanup));
          const marker = await fetch(`${base}/files?id=u90001&path=profile-ran`, { headers });
          strictEqual(marker.status, 422);
          await marker.arrayBuffer();
          strictEqual(
            (await request("/files?id=u90001&path=.bash_profile", { method: "DELETE" })).status,
            200,
          );
          strictEqual((await run("test ! -e closed && test ! -e profile-ran")).body.exitCode, 0);
        },
      );
      await t.step("a single file cannot grow past the workspace file limit", async () => {
        const result = await run("dd if=/dev/zero of=oversized.bin bs=1M count=128 status=none");
        ok(result.body.exitCode !== 0, JSON.stringify(result.body));
        strictEqual((await run("stat -c %s oversized.bin")).body.output.trim(), String(64 * 1024 * 1024));
        strictEqual((await run("rm -f oversized.bin")).body.exitCode, 0);
      });
      await t.step("a workspace burning cpu with no command of its own is removed", async () => {
        const burner = "nohup sh -c 'while :; do :; done' >/dev/null 2>&1 &";
        const started = await run(`${burner} ${burner} echo detached`);
        strictEqual(started.body.exitCode, 0, JSON.stringify(started.body));
        let owned = "unknown";
        for (let i = 0; i < 120 && owned; i++) {
          owned = await dockerText(["ps", "-aq", "--filter", `label=${label}`]);
          if (owned) await new Promise((resolve) => setTimeout(resolve, 1000));
        }
        strictEqual(owned, "", "the container should have been removed for unattended cpu");
        strictEqual((await run("cat kept.txt")).body.output.trim(), "saved");
      });
      await t.step("ordinary shutdown removes child containers but preserves volumes", async () => {
        const limits = JSON.parse(
          await dockerText(["inspect", "--format", "{{json .HostConfig}}", `${namespace}-workspace-u90001`]),
        );
        const loop = limits.BlkioDeviceWriteBps[0].Path.split("/").at(-1);
        ok(/^loop[0-9]+$/.test(loop));
        await docker(["stop", "--time", "30", supervisor]);
        await docker([
          "run",
          "--rm",
          "--network=none",
          "--cap-drop=ALL",
          "--user=1000:1000",
          "--read-only",
          "--entrypoint",
          "/usr/bin/sh",
          image!,
          "-c",
          `! /usr/bin/grep -F '${namespace}' /sys/block/${loop}/loop/backing_file 2>/dev/null`,
        ]);
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
        for (let i = 0; i < 300; i++) {
          const result = await request(`/jobs/${started.body.jobId}?id=u90001`);
          const owned = await dockerText(["ps", "-aq", "--filter", `label=${label}`]);
          if (result.body.status === "failed" && !owned) break;
          await new Promise((resolve) => setTimeout(resolve, 100));
        }
        strictEqual((await request(`/jobs/${started.body.jobId}?id=u90001`)).body.status, "failed");
        strictEqual(await dockerText(["ps", "-aq", "--filter", `label=${label}`]), "");
        strictEqual((await upload("blocked")).status, 507);
        // arbitrary shell cannot claim to be cleanup, and repeated attempts must remain blocked.
        strictEqual((await run("printf refused")).status, 507);
        strictEqual((await run("printf refused-again")).status, 507);
        strictEqual((await request("/files?id=u90001&path=kept.txt", { method: "DELETE" })).status, 200);
        await docker(["exec", supervisor, "rm", "/state/pressure"]);
        for (let i = 0; i < 300; i++) {
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
      await docker(["network", "rm", `${namespace}-workspaces`]).catch(() => {});
      const chain = `WS_${namespace.toUpperCase().replace(/[^A-Z0-9]/g, "_").slice(0, 20)}`;
      await docker([
        "run",
        "--rm",
        "--network=host",
        "--tmpfs",
        "/run:size=1m",
        "--cap-drop=ALL",
        "--cap-add=NET_ADMIN",
        "--entrypoint",
        "/usr/bin/bash",
        image!,
        "-c",
        `iptables -D DOCKER-USER -j ${chain}; iptables -D INPUT -j ${chain}_IN;
         iptables -F ${chain}; iptables -X ${chain};
         iptables -F ${chain}_IN; iptables -X ${chain}_IN; true`,
      ]).catch(() => {});
    }
  },
});
