/// <reference lib="deno.worker" />
import { loadPyodide, type PyodideInterface } from "pyodide";
import { encodeBase64 } from "@std/encoding/base64";
import { PACKAGES } from "./packages.ts";

const WORK_DIR = "/work";
const FONT_DIR = "/fonts";
const MAX_FILES = 8;
const MAX_FILE_BYTES = 10 * 1024 * 1024;

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

// Tell the pool manager this worker is warm and may receive its one job.
ready.then(() => self.postMessage({ type: "ready" }));

self.onmessage = async (event: MessageEvent<{ code: string }>) => {
  const pyodide = await ready;
  const { code } = event.data;

  let stdout = "";
  let stderr = "";
  pyodide.setStdout({ batched: (s: string) => (stdout += s + "\n") });
  pyodide.setStderr({ batched: (s: string) => (stderr += s + "\n") });

  let ok = true;
  let error = "";
  try {
    await pyodide.runPythonAsync(code);
  } catch (err) {
    ok = false;
    error = err instanceof Error ? err.message : String(err);
  }

  const result: RunResult = { ok, error, stdout, stderr, files: collectFiles(pyodide) };
  self.postMessage(result);
};

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
