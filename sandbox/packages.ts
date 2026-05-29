// Pyodide packages baked into the image and loaded into every warm worker.
// Keep in sync with the `Available libraries` line in the bot's RUN_CODE
// tool description.
export const PACKAGES = ["numpy", "pandas", "matplotlib", "sympy"];
