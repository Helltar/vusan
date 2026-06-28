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
// pure-Python document-library wheels baked into the image (see extra-wheels.txt);
// unpacked onto sys.path at init so python-docx/fpdf/pypdf import offline.
// WHEEL_DIR is the host path; the wheels are copied to a top-level dir in the
// Pyodide filesystem and extracted from there onto sys.path.
const WHEEL_DIR = "/app/wheels";
const WHEEL_FS_DIR = "/wheels";
const WHEEL_SITE = "/wheels-site";
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
  await unpackWheels(pyodide);
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

# Pillow here is built without raqm, so any single font draws no emoji glyphs and
# a string mixing text with emoji shows boxes. The model writes ordinary
# ImageDraw.text(...) calls and cannot be relied on to use a custom helper, so we
# patch ImageDraw.text itself: a single line containing emoji is split into text
# and emoji runs, the emoji drawn with the monochrome ${FONT_DIR}/NotoEmoji.ttf
# (no color emoji: this FreeType lacks CBDT). Anything else falls straight through
# to the original, and the fallback path degrades to the original on any error so
# a script can never crash because of the patch.
import functools
from PIL import ImageDraw as _ImageDraw, ImageFont as _ImageFont
_EMOJI_BLOCKS = ((0x1F000, 0x1FAFF), (0x2600, 0x26FF), (0x2700, 0x27BF), (0x1F1E6, 0x1F1FF))
_orig_text = _ImageDraw.ImageDraw.text

@functools.lru_cache(maxsize=None)
def _emoji_font(size):
    return _ImageFont.truetype("${FONT_DIR}/NotoEmoji.ttf", size)

def _is_emoji(text, i):
    if i + 1 < len(text) and ord(text[i + 1]) == 0xFE0F:
        return True
    cp = ord(text[i])
    return any(a <= cp <= b for a, b in _EMOJI_BLOCKS)

def _emoji_runs(text):
    runs = []
    for i, ch in enumerate(text):
        if ord(ch) in (0xFE0F, 0xFE0E):  # variation selectors have no glyph
            continue
        em = _is_emoji(text, i)
        if runs and runs[-1][0] == em:
            runs[-1][1] += ch
        else:
            runs.append([em, ch])
    return runs

def _text_with_emoji(self, xy, text, fill=None, font=None, anchor=None, **kw):
    if (not isinstance(font, _ImageFont.FreeTypeFont) or not isinstance(text, str)
            or "\\n" in text or not any(_is_emoji(text, i) for i in range(len(text)))):
        return _orig_text(self, xy, text, fill=fill, font=font, anchor=anchor, **kw)
    try:
        emoji = _emoji_font(font.size)
        runs = [(emoji if em else font, s) for em, s in _emoji_runs(text)]
        total = sum(self.textlength(s, font=f) for f, s in runs)
        x, y = xy
        ax = (anchor or "la")[0]
        x -= total / 2 if ax == "m" else total if ax == "r" else 0
        run_anchor = "l" + (anchor or "la")[1]  # keep the caller's vertical anchor
        for f, s in runs:
            _orig_text(self, (x, y), s, fill=fill, font=f, anchor=run_anchor, **kw)
            x += self.textlength(s, font=f)
    except Exception:
        return _orig_text(self, xy, text, fill=fill, font=font, anchor=anchor, **kw)

_ImageDraw.ImageDraw.text = _text_with_emoji
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
    let bytes: Uint8Array;
    try {
      bytes = decodeBase64(file.base64);
    } catch {
      continue; // invalid base64; skip the file rather than crash the whole run
    }
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
      // cut an oversized single chunk before concatenating, so one print() of a
      // giant string cannot spike memory far past the cap
      text += s.slice(0, MAX_OUTPUT_CHARS + 1 - text.length) + "\n";
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

// Unpack the baked document-library wheels onto sys.path. The wheels are pure
// Python, so extracting each zip into one site directory is enough; their
// compiled/in-distribution deps are already loaded via loadPackage(PACKAGES).
// Silently does nothing when the wheel dir is absent (local dev without a build).
async function unpackWheels(pyodide: PyodideInterface): Promise<void> {
  let names: string[];
  try {
    names = [...Deno.readDirSync(WHEEL_DIR)]
      .filter((e) => e.isFile && e.name.endsWith(".whl"))
      .map((e) => e.name);
  } catch {
    return;
  }
  if (names.length === 0) return;

  pyodide.FS.mkdir(WHEEL_FS_DIR);
  for (const name of names) {
    pyodide.FS.writeFile(`${WHEEL_FS_DIR}/${name}`, Deno.readFileSync(`${WHEEL_DIR}/${name}`));
  }

  await pyodide.runPythonAsync(`
import sys, os, zipfile
os.makedirs("${WHEEL_SITE}", exist_ok=True)
for _whl in os.listdir("${WHEEL_FS_DIR}"):
    if _whl.endswith(".whl"):
        zipfile.ZipFile("${WHEEL_FS_DIR}/" + _whl).extractall("${WHEEL_SITE}")
sys.path.insert(0, "${WHEEL_SITE}")
`);
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
