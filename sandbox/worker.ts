/// <reference lib="deno.worker" />
import { loadPyodide, type PyodideInterface } from "pyodide";
import { encodeBase64 } from "@std/encoding/base64";
import { PACKAGES } from "./packages.ts";

const WORK_DIR = "/work";
const FONT_DIR = "/fonts";
const MAX_FILES = 8;
const MAX_FILE_BYTES = 10 * 1024 * 1024;
// Per-stream cap so a script printing in a tight loop cannot grow the captured
// output without bound and OOM the worker before the run-time limit fires. Far
// larger than what the bot ultimately shows; the Kotlin side truncates again.
const MAX_OUTPUT_CHARS = 256 * 1024;

interface RunResult {
  ok: boolean;
  error: string;
  stdout: string;
  stderr: string;
  files: Array<{ name: string; base64: string }>;
}

const ready: Promise<PyodideInterface> = (async () => {
  const pyodide = await loadPyodide();
  await pyodide.loadPackage(PACKAGES);
  injectFonts(pyodide);
  // Headless backend, register the fallback fonts so labels with emoji / CJK /
  // symbols render instead of tofu, and a throwaway render so the matplotlib
  // font cache is built during warm-up (hidden by the pool), not on first use.
  await pyodide.runPythonAsync(`
import io, glob, matplotlib
matplotlib.use("Agg")
import matplotlib.font_manager as fm
for _p in glob.glob("${FONT_DIR}/*"):
    try:
        fm.fontManager.addfont(_p)
    except Exception:
        pass
_noto = sorted({f.name for f in fm.fontManager.ttflist if "Noto" in f.name})
matplotlib.rcParams["font.family"] = ["DejaVu Sans"] + _noto
import matplotlib.pyplot as plt
plt.plot([0, 1]); plt.savefig(io.BytesIO()); plt.close("all")
`);
  pyodide.FS.mkdir(WORK_DIR);
  pyodide.FS.chdir(WORK_DIR);
  return pyodide;
})();

ready.then(() => self.postMessage({ type: "ready" }));

self.onmessage = async (event: MessageEvent<{ code: string }>) => {
  const pyodide = await ready;
  const { code } = event.data;

  const out = makeSink();
  const err = makeSink();
  pyodide.setStdout({ batched: (s: string) => out.write(s) });
  pyodide.setStderr({ batched: (s: string) => err.write(s) });

  let ok = true;
  let error = "";
  try {
    await pyodide.runPythonAsync(code);
  } catch (e) {
    ok = false;
    error = e instanceof Error ? e.message : String(e);
  }

  const result: RunResult = { ok, error, stdout: out.value, stderr: err.value, files: collectFiles(pyodide) };
  self.postMessage(result);
};

function makeSink() {
  let text = "";
  let capped = false;
  return {
    write(s: string): void {
      if (capped) return;
      text += s + "\n";
      if (text.length > MAX_OUTPUT_CHARS) {
        text = text.slice(0, MAX_OUTPUT_CHARS) + "\n...[output truncated]";
        capped = true;
      }
    },
    get value(): string {
      return text;
    },
  };
}

// Copy the image's fallback fonts into the Pyodide filesystem so matplotlib can
// register them. Silently does nothing when the font dir is absent (local dev).
function injectFonts(pyodide: PyodideInterface): void {
  let files: string[];
  try {
    files = [...Deno.readDirSync(FONT_DIR)].filter((e) => e.isFile).map((e) => e.name);
  } catch {
    return;
  }
  if (files.length === 0) return;

  pyodide.FS.mkdir(FONT_DIR);
  for (const name of files) {
    pyodide.FS.writeFile(`${FONT_DIR}/${name}`, Deno.readFileSync(`${FONT_DIR}/${name}`));
  }
}

function collectFiles(pyodide: PyodideInterface): RunResult["files"] {
  const files: RunResult["files"] = [];
  for (const name of pyodide.FS.readdir(WORK_DIR)) {
    if (name === "." || name === "..") continue;
    if (files.length >= MAX_FILES) break;

    const path = `${WORK_DIR}/${name}`;
    const stat = pyodide.FS.stat(path);
    if (!pyodide.FS.isFile(stat.mode)) continue;

    const bytes: Uint8Array = pyodide.FS.readFile(path);
    if (bytes.length === 0 || bytes.length > MAX_FILE_BYTES) continue;

    files.push({ name, base64: encodeBase64(bytes) });
  }
  return files;
}
