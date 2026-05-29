// Build-time warm-up: download every Pyodide package into the local cache so
// the running container needs no network. Invoked from the Dockerfile; the
// populated cache is then part of the image layer.
import { loadPyodide } from "pyodide";
import { PACKAGES } from "./packages.ts";

const pyodide = await loadPyodide();
await pyodide.loadPackage(PACKAGES);
await pyodide.runPythonAsync(`import matplotlib; matplotlib.use("Agg")`);
console.log("warm-up complete:", PACKAGES.join(", "));
