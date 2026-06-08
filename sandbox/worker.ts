/// <reference lib="deno.worker" />
import { loadPyodide, type PyodideInterface } from "pyodide";
import { decodeBase64, encodeBase64 } from "@std/encoding/base64";
import { PACKAGES } from "./packages.ts";

interface InputFile {
  name: string;
  base64: string;
}

const WORK_DIR = "/work";
const FONT_DIR = "/fonts";
const MAX_FILES = 8;
const MAX_FILE_BYTES = 10 * 1024 * 1024;
// Per-stream cap so a script printing in a tight loop cannot grow the captured
// output without bound and OOM the worker before the run-time limit fires. Far
// larger than what the bot ultimately shows; the Kotlin side truncates again.
const MAX_OUTPUT_CHARS = 256 * 1024;

interface SkippedFile {
  name: string;
  bytes: number;
  reason: "too_large" | "too_many";
}

interface RunResult {
  ok: boolean;
  error: string;
  stdout: string;
  stderr: string;
  files: Array<{ name: string; base64: string }>;
  skipped: SkippedFile[];
  elapsedMs: number;
}

const ready: Promise<PyodideInterface> = (async () => {
  const pyodide = await loadPyodide();
  await pyodide.loadPackage(PACKAGES);
  injectFonts(pyodide);
  // Headless backend, register the fallback fonts so labels with emoji / CJK /
  // symbols render instead of tofu, and a throwaway render so the matplotlib
  // font cache is built during warm-up (hidden by the pool), not on first use.
  await pyodide.runPythonAsync(`
import io, glob, os, shutil, matplotlib
matplotlib.use("Agg")
import matplotlib.font_manager as fm
# Expose matplotlib's bundled DejaVu (Latin + Cyrillic) at a stable path so Pillow can draw real text:
# PIL's load_default() has no Cyrillic and renders boxes. Path: ${FONT_DIR}/DejaVuSans.ttf (+ -Bold).
os.makedirs("${FONT_DIR}", exist_ok=True)
_mpl_ttf = os.path.join(os.path.dirname(matplotlib.__file__), "mpl-data", "fonts", "ttf")
for _fn in ("DejaVuSans.ttf", "DejaVuSans-Bold.ttf"):
    _src = os.path.join(_mpl_ttf, _fn)
    if os.path.exists(_src):
        shutil.copy(_src, "${FONT_DIR}/" + _fn)
for _p in glob.glob("${FONT_DIR}/*"):
    try:
        fm.fontManager.addfont(_p)
    except Exception:
        pass
_noto = sorted({f.name for f in fm.fontManager.ttflist if "Noto" in f.name})
matplotlib.rcParams["font.family"] = ["DejaVu Sans"] + _noto
# Default 100 DPI renders soft, fuzzy text once Telegram recompresses the photo; render at 2x so
# labels stay crisp. Scripts that set their own dpi/figsize still override this.
matplotlib.rcParams["figure.dpi"] = 200
import matplotlib.pyplot as plt
plt.plot([0, 1]); plt.savefig(io.BytesIO()); plt.close("all")
`);
  pyodide.FS.mkdir(WORK_DIR);
  pyodide.FS.chdir(WORK_DIR);
  return pyodide;
})();

ready.then(() => self.postMessage({ type: "ready" }));

self.onmessage = async (event: MessageEvent<{ code: string; files?: InputFile[] }>) => {
  const pyodide = await ready;
  const { code, files: inputFiles } = event.data;

  // Names of files written before the run; excluded from the output so the user's own upload
  // is not echoed back as a "result".
  const inputNames = writeInputFiles(pyodide, inputFiles ?? []);

  const out = makeSink();
  const err = makeSink();
  pyodide.setStdout({ batched: (s: string) => out.write(s) });
  pyodide.setStderr({ batched: (s: string) => err.write(s) });

  let ok = true;
  let error = "";
  const startedAt = performance.now();
  try {
    await pyodide.runPythonAsync(code);
  } catch (e) {
    ok = false;
    error = e instanceof Error ? e.message : String(e);
  }
  const elapsedMs = Math.round(performance.now() - startedAt);

  const { files, skipped } = collectFiles(pyodide, inputNames);
  const result: RunResult = { ok, error, stdout: out.value, stderr: err.value, files, skipped, elapsedMs };
  self.postMessage(result);
};

// Write caller-supplied files into the working directory so the script can read them by name.
// Basenames only (no path traversal) and the same per-file size cap as outputs.
function writeInputFiles(pyodide: PyodideInterface, files: InputFile[]): Set<string> {
  const written = new Set<string>();
  for (const file of files) {
    const name = file.name.split("/").pop()?.split("\\").pop() ?? "";
    if (name === "" || name === "." || name === "..") continue;
    const bytes = decodeBase64(file.base64);
    if (bytes.length === 0 || bytes.length > MAX_FILE_BYTES) continue;
    pyodide.FS.writeFile(`${WORK_DIR}/${name}`, bytes);
    written.add(name);
  }
  return written;
}

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

// Walk the working directory depth-first so files written into subdirectories — e.g. extracted
// from an archive — are returned, not just top-level ones. Names are relative to WORK_DIR; the
// bot reduces them to basenames for delivery but keeps the extension for image/animation routing.
// Inputs are always written flat at the top level, so a relative path can only match an input
// when it is itself top-level, which is exactly when the user's own upload should be excluded.
function collectFiles(
  pyodide: PyodideInterface,
  inputNames: Set<string>,
): { files: RunResult["files"]; skipped: SkippedFile[] } {
  const files: RunResult["files"] = [];
  const skipped: SkippedFile[] = [];

  const walk = (dir: string, prefix: string): void => {
    for (const name of pyodide.FS.readdir(dir)) {
      if (name === "." || name === ".." || name === "__pycache__") continue;

      const rel = prefix ? `${prefix}/${name}` : name;
      const path = `${dir}/${name}`;
      const stat = pyodide.FS.stat(path);

      if (pyodide.FS.isDir(stat.mode)) {
        walk(path, rel);
        continue;
      }
      if (!pyodide.FS.isFile(stat.mode)) continue;
      if (inputNames.has(rel)) continue;

      // Size from stat, so an oversized file is reported without ever reading it into memory.
      const size: number = stat.size;
      if (size === 0) continue;
      if (size > MAX_FILE_BYTES) {
        skipped.push({ name: rel, bytes: size, reason: "too_large" });
        continue;
      }
      if (files.length >= MAX_FILES) {
        skipped.push({ name: rel, bytes: size, reason: "too_many" });
        continue;
      }

      files.push({ name: rel, base64: encodeBase64(pyodide.FS.readFile(path)) });
    }
  };

  walk(WORK_DIR, "");
  return { files, skipped };
}
