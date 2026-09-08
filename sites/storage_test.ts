import { deepStrictEqual, rejects, strictEqual } from "node:assert/strict";
import { statfs } from "node:fs/promises";
import { type Config, readConfig } from "./config.ts";
import { Sites } from "./storage.ts";

Deno.env.set("SITES_DOMAIN", "example.com");

async function fresh(overrides: Partial<Config> = {}) {
  const root = await Deno.makeTempDir();
  const config = { ...readConfig(), dataDir: root, ...overrides };
  const sites = new Sites(config);
  await sites.initialize();
  return { root, config, sites };
}

function body(text: string): ReadableStream<Uint8Array> {
  return new Response(text).body!;
}

async function publish(sites: Sites, owner: string, files: Record<string, string>) {
  const id = await sites.begin(owner);
  for (const [path, text] of Object.entries(files)) await sites.put(id, path, body(text), null);
  return await sites.commit(id);
}

async function names(dir: string): Promise<string[]> {
  const found: string[] = [];
  for await (const entry of Deno.readDir(dir)) found.push(entry.name);
  return found.sort();
}

Deno.test("publishing replaces the whole site and leaves nothing behind", async () => {
  const { root, sites } = await fresh();
  try {
    const first = await publish(sites, "u42", { "index.html": "<h1>one</h1>", "assets/game.js": "run()" });
    strictEqual(first.files, 2);
    strictEqual(sites.url(first.label), "https://42.example.com/");
    strictEqual(await Deno.readTextFile(`${root}/sites/42/index.html`), "<h1>one</h1>");

    const second = await publish(sites, "u42", { "index.html": "<h1>two</h1>" });
    strictEqual(second.createdAt, first.createdAt);
    strictEqual(await Deno.readTextFile(`${root}/sites/42/index.html`), "<h1>two</h1>");
    // the previous tree is gone whole, rather than merged with the new one
    await rejects(() => Deno.stat(`${root}/sites/42/assets/game.js`), Deno.errors.NotFound);
    deepStrictEqual(await names(`${root}/sites`), ["42"]);
    deepStrictEqual(await names(`${root}/staging`), []);

    strictEqual(await sites.remove("u42"), true);
    deepStrictEqual(await names(`${root}/sites`), []);
    deepStrictEqual(await names(`${root}/meta`), []);
    strictEqual(await sites.status("u42"), null);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("one site cannot exceed its file, size or path limits", async () => {
  const { root, sites } = await fresh({ maxMb: 1, maxFileMb: 1, maxFiles: 2 });
  try {
    const id = await sites.begin("u42");
    await sites.put(id, "index.html", body("x"), null);
    await rejects(() => sites.put(id, "index.html", body("x"), null), /already uploaded/);
    await rejects(() => sites.put(id, "../escape.html", body("x"), null), /stay inside/);
    await sites.put(id, "assets/game.js", body("x"), null);
    await rejects(() => sites.put(id, "third.txt", body("x"), null), /at most 2 files/);

    // a declared length is refused before a byte is read, an undeclared one while it streams
    const other = await sites.begin("u43");
    await rejects(() => sites.put(other, "big.bin", body("x"), 4 * 1024 * 1024), /at most 1 MB/);
    await rejects(() => sites.put(other, "big.bin", body("y".repeat(1024 * 1024 + 1)), null), /size budget/);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("the sweep removes expired and blocked sites but never an undated one", async () => {
  const { root, sites } = await fresh({ retainDays: 1 });
  try {
    const record = await publish(sites, "u42", { "index.html": "old" });
    await Deno.writeTextFile(
      `${root}/meta/42.json`,
      JSON.stringify({ ...record, updatedAt: Date.now() - 3 * 24 * 3_600_000 }),
    );
    await publish(sites, "u99", { "index.html": "blocked" });
    await Deno.writeTextFile(`${root}/blocked`, "99\n\n# a comment line is ignored\n");
    // a directory with no record cannot be dated, so it is left alone
    await Deno.mkdir(`${root}/sites/777`);

    await sites.sweep();

    deepStrictEqual(await names(`${root}/sites`), ["777"]);
    await rejects(() => sites.begin("u99"), /blocked/);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("publishing stops while the host is low on space and resumes once it returns", async () => {
  const { root, config, sites } = await fresh();
  try {
    const disk = await statfs(root);
    config.minFreeMb = disk.blocks * disk.bsize / 1024 / 1024 + 1;
    await rejects(() => sites.begin("u42"), /low on disk space/);
    strictEqual(sites.healthy, false);

    config.minFreeMb = 1;
    await publish(sites, "u42", { "index.html": "back" });
    strictEqual(sites.healthy, true);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});

Deno.test("a publish loop is rate limited per person", async () => {
  const { root, sites } = await fresh({ publishesPerHour: 1 });
  try {
    await publish(sites, "u42", { "index.html": "one" });
    await rejects(() => sites.begin("u42"), /Too many publishes/);
    // someone else's budget is their own
    await publish(sites, "u43", { "index.html": "one" });
  } finally {
    await Deno.remove(root, { recursive: true });
  }
});
