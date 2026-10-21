# Adding a tool

The files a new agent tool touches, in order. The conventions are in [`AGENTS.md`](../../AGENTS.md); the ordered
pass is the `add-tool` skill. Part of the [architecture index](../architecture.md).

A new agent tool typically touches these, in order:

1. **`tools/<feature>/<Feature>Tools.kt`** — `class <Feature>Tools(...) : ToolSet` whose constructor takes the
   `BotOutbox` and/or a client; each method is `@Tool(...) suspend fun … = suspendToolGuard { … }`, its arguments `@Arg(...)`.
2. **`tools/<feature>/<Feature>ToolDescriptions.kt`** — an `internal object` of `const val` descriptions referenced by
   the `@Tool` and `@Arg` descriptions (see the convention in `AGENTS.md`).
3. *(optional)* **`<Feature>Client.kt`** / **`<Feature>Models.kt`** — the external I/O and its DTOs.
4. **`tools/ToolCatalogFactory.kt`** — register it in `buildCatalog`; construct it only when the key it depends on is
   set (`config.fooApiKey?.let { … }`). Register it under a `ToolGroup` when a turn rarely needs it, and
   leave it visible when the model may need it without being asked for it by name; a new group also needs its one-line
   summary in `tools/ToolCatalog.kt`, since that line is all the model reads before loading it. A tool only one messenger can implement goes to that adapter's
   `PlatformToolSets` instead (`telegram/tools/TelegramToolSets.kt`), gated there on the same chat capability.
5. **Files and results between tools** — a parameter that takes a file is an `AttachedFile` (or a list of them),
   which the model names by label or `sandbox:` path, and which falls back to the request's attachment when omitted; a
   tool that makes a file calls `keepOnShelf` whether or not it sends it, and takes `send` when a file made without
   delivering it is worth having. A text parameter that carries a body worth not retyping — a message, a script, a
   file's contents — may declare `@Arg(takesReference = true)` and say so in its description. Mark an answer that is
   material to work on `copiedToSandbox`.
6. **Docs** — add the capability to the Features section of the [README](../../README.md); document setup requirements and
   implicit dependencies in [`configuration.md`](../configuration.md), and add any new env vars to both that file and
   [`.env.example`](../../.env.example).
