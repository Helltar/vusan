// Pyodide packages baked into the image and loaded into every warm worker.
// numpy..Pillow are the user-facing compute libraries; keep them in sync with
// the `Available libraries` line in the bot's RUN_CODE tool description.
// lxml and typing-extensions are not advertised: they are the in-distribution
// dependencies of the document libraries (python-docx) installed from the extra
// wheels in `extra-wheels.txt` and unpacked in worker.ts.
export const PACKAGES = [
  "numpy",
  "pandas",
  "matplotlib",
  "sympy",
  "scipy",
  "Pillow",
  "lxml",
  "typing-extensions",
];
