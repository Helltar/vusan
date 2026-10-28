# AGENTS.md

Rules for coding agents on Vusan, a Telegram AI agent in Kotlin. They apply to the whole
repository; `CLAUDE.md` only imports this file. This file holds rules, and how the code works
is in `docs/`: [`docs/architecture.md`](docs/architecture.md) indexes the architecture pages —
read the one that covers what you are changing, and use its
[symptom map](docs/architecture.md#where-to-look-when) before broad searching. Prefer clean
removals over compatibility shims.

## Commands and Verification

- `./gradlew test`, `./gradlew detekt` (any finding fails it), `./gradlew build`
  (compile + test + package), `./gradlew run` (local bot on `.env`).
  While iterating: `./gradlew test --tests "*AgentFactoryTest*"`.
- `./gradlew -q exportPrompt --args="--out <file>"` writes what a model is
  sent before any conversation — the system prompt and every tool definition —
  as JSON built by the production code; the flags are in `PromptExport.kt`.
  Read it instead of reconstructing the prompt from the sources.
- `./gradlew detekt` is detekt 2's light analysis and the one `check` runs.
  `detektMain` and `detektTest` add the rules that need types, and are not
  part of the build while their findings on this code are mostly false.
  It enforces several rules below (member order, trailing commas at call
  sites, one blank line, `Db.dbTransaction`) through ktlint's wrapper too;
  `./gradlew detekt --auto-correct` fixes what ktlint can.
- Before finishing code changes, run `./gradlew test` and `./gradlew detekt`
  unless the change is docs-only or the user says not to.
- If a check cannot run, report the exact command and blocker. Gradle
  deprecation warnings are not failures unless the task is about Gradle.

## Architecture Rules

Keep the package boundaries in [`docs/architecture/layers.md`](docs/architecture/layers.md).

- Inside `telegram/`: `inbound/` turns an update into agent input, `delivery/`
  sends everything back out, `callback/` owns the inline-button flows, `tools/`
  holds the tools no other messenger could implement; the runner, `AgentTurns`,
  `TelegramCommands` and the raw client helpers stay at the package root. Tools
  consume `RequestContext`/`AttachedFile`, never Telegram message objects.
- One `RequestContext` is built at ingress and read unchanged from there on.
  Identity (`UserRef`/`ChatRef`/`ConversationScope`) is platform-qualified, and
  every external reference is opaque text, stored or in memory. Only the adapter
  converts one to its messenger's width — for Telegram, `telegram/TelegramIds.kt`
  and nowhere else.
- Outside an adapter, deliver through `delivery/OutputDelivery` and read chat
  facts through `request/ChatProfileLookup`. Shared code, `tasks/` included,
  holds no messenger client; `PlatformBoundaryTest`'s allowlist may only shrink.
- Tools enqueue `BotOutput` into `BotOutbox`, never TelegramBots send methods.
  `BotOutput` is immutable, with its invariants in `init {}` blocks.
- Canned bot text belongs in `i18n/Messages`, one implementation per `Language` —
  no inline English in `telegram/` or `agent/`.
- A kind-specific send fallback goes in `TelegramOutputSender`, a kind-agnostic
  one in `TelegramSendFallbacks`, raw Bot API builders in `TelegramRequests`;
  route choice and reply anchoring stay in `TelegramDelivery`.
  `BotOutbox.useDirectMessages()` affects later enqueues only, and reactions are
  never redirected to DMs.
- `Db.connect(config)` in [`infra/Database.kt`](src/main/kotlin/com/helltar/vusan/infra/Database.kt)
  is the only DB initialization and `Db.dbTransaction { ... }` the only access;
  never call Exposed `transaction {}` or `suspendTransaction(...)` outside that
  file. A schema change is a table edit, a raised `Schema.VERSION` in
  [`infra/Schema.kt`](src/main/kotlin/com/helltar/vusan/infra/Schema.kt) and a plain
  SQL step in `Schema.steps`, never edited once released; `DatabaseMigrationTest`
  proves the steps land on what a fresh database gets.
- Env vars are read only in `AppConfig.Companion`, through its private `readEnv`
  (optional) and `requireEnv` (required). Never call `System.getenv`.
- `llm/` knows nothing about the bot: no outbox, no messenger, no `AppConfig`.
  Every model call goes through `LlmClient` with its `Message` model. A field one
  API grew lives in that API's client (`llm/openai/`, `llm/anthropic/`), and a
  reasoning block is replayed verbatim to the endpoint that returned it and no
  other. Prove behavior on the wire with a test over a mock engine.
- A model provider is a client under `llm/<vendor>/`, a config class in
  `config/llm/LlmProviderConfig.kt`, and `config/llm/<Vendor>Provider.kt` with
  its runtime and startup check. What a model can do travels as facts on
  `LlmProviderConfig` and `LlmModel`; the roles, the agent and the fallback never
  name a provider.
- No thin abstractions or one-off helper objects. Add one only when it removes
  real complexity or matches an existing local pattern.

### Regolith, the sandbox and publishing

How the bot uses the server is in
[`docs/architecture/sandbox-and-sites.md`](docs/architecture/sandbox-and-sites.md).

- The Regolith server is reached only through its Kotlin SDK, and only from
  `tools/sandbox/SandboxClient.kt`. Nothing here knows how a sandbox is isolated,
  and no Docker socket reaches this side.
- The SDK version follows the server's minor version, and moves only to a
  release Maven Central already serves, or the public build breaks.
  `docs/sandbox.md` names that version.
- The server owns images, resources, timeouts, retention, network policy and
  everything about sites. Read its limits from `GET /v1/info`; never copy them
  into Kotlin, send them when creating a sandbox or add a setting for them, and
  never build a site's URL here — the server returns it.
- One deployment ships from this repository: the bot, `compose.yaml` and `.env`.
  The bot only connects out: never add an inbound port or a service that expects
  to reach it.

## Documentation Triggers

Update docs in the same change as the behavior, and read them before changing
what they describe:

- [`docs/architecture/`](docs/architecture.md), the page that covers it: the
  request lifecycle, package moves, delivery policy, scheduling, startup wiring
  or a core orchestrator (`AgentRunner`, `AgentFactory`, `ToolCatalogFactory`,
  `TelegramDelivery`, `TelegramOutputSender`, `TaskScheduler`). A new page gets
  its line in the index.
- [`docs/configuration.md`](docs/configuration.md) and [`.env.example`](.env.example):
  env var additions, removals, renames, default or semantics changes.
- [`docs/sandbox.md`](docs/sandbox.md) and [`docs/sites.md`](docs/sites.md): what
  the sandbox and publishing do for a person and what the bot expects of the
  server. Limits, isolation, images and retention are the server's to document —
  not here and not in LLM-facing descriptions; the sandbox
  [limits table](docs/sandbox.md#limits) lists only bounds this repository enforces.
- [`docs/chats.md`](docs/chats.md): how Vusan behaves with people — history,
  memory, tasks, group behavior, being left alone — and the fixed numbers
  behind it, such as limits, windows and quiet hours.
- [`README.md`](README.md) Features: added, removed or renamed user-visible
  capability. Say what it does, never which library, model, key or service
  enables it; setup requirements go to `docs/configuration.md`.
- Telegram slash commands: `telegram/TelegramCommands.kt` is the source of
  truth. Keep aligned with it the `Telegram commands` section in
  `agent/SystemPrompt.kt`, the menu in `telegram/CommandMenu.kt` (a description
  per `Language` in `i18n/Messages`), and `docs/architecture/commands.md`.

## Kotlin Style

- Prefer `runCatching { ... }.recoverCatching/onFailure/getOrNull` for
  non-control-flow errors, and preserve cancellation: re-throw
  `CancellationException` or use `Throwable.rethrowIfCancellation()`.
- Use `require`, `requireNotNull`, `check`, `checkNotNull` instead of throwing
  `IllegalArgumentException` / `IllegalStateException` directly.
- Prefer null-safe expressions (`?.let`, `?:`, `takeIf`, `takeUnless`) to nested
  null ladders. Avoid `!!`; prove non-null via smart cast or `requireNotNull`.
- Prefer Kotlin idiom to Java: properties and receiver-style helpers over
  `getFoo()`, `kotlin.time.Duration` overloads (`delay(5.seconds)`) over
  `java.time.Duration` (`.toKotlinDuration()` converts), raw strings for quoted
  text. Never suppress a compiler warning without a reason. In logs, delimit
  values as `key=[value]`, not `key="value"`.
- Comment sparingly, and only on non-obvious constraints, invariants, or
  surprising behavior — say why, not what. Never leave commented-out code.
- Class loggers (`val log = KotlinLogging.logger {}`) and class-private constants
  live in a `private companion object`. A top-level `private val log` or constant
  belongs only in a file without classes, in a named utility logger such as
  `ToolGuard`, or where a top-level helper uses it.
- Member order is the one the Kotlin conventions give: properties and
  initializer blocks, secondary constructors, methods, and the companion object
  last.
- Blank lines carry meaning. A wrapped class header is followed by one. A
  `return` that ends a function body has one above it whenever there is work
  above it, while a `return` inside a small block stays where it is. A multi-line
  `if`, `when`, `try`, `for` or `while` at the top of a function body is
  separated from what surrounds it. Never two in a row.
- A parameter or argument list that wraps over several lines ends with a
  trailing comma.

## Prompt and Text Handling

- Reuse [`common/Strings.kt`](src/main/kotlin/com/helltar/vusan/common/Strings.kt)
  rather than writing your own: `collapseWhitespaceAndCap(max)` where layout
  whitespace is noise (metadata, logs, snippets), `limitTo(max)` where inner
  whitespace matters, `xmlBlock(tag, content)` for structured text sent to the
  LLM, plus `isEffectivelyBlank`, `sanitizeFilename` and `escapeHtml`.
- `xmlBlock` already escapes a closing tag of its own name; text quoted from a
  message needs `neutralizePromptBlocks` too, which defuses every tag the prompt
  uses. Avoid plain markers such as `Reply context:` or `[Sent N images]` —
  models parrot them.
- Text a model writes for the bot to keep — a recap, a diary entry, a memory
  item — is held to its limit by asking the model again: `completeWithin` in
  [`agent/CompleteWithin.kt`](src/main/kotlin/com/helltar/vusan/agent/CompleteWithin.kt)
  for a side call, a refusal the model reads for a tool argument. Never cut it
  silently: the cut drops what was written last.
- The blocks the current request arrives in — `<reply_context>`,
  `<quoted_fragment>`, `<attached_file>`, `<album>`, a transcript, a pressed
  choice — are written by `agent/TurnInput.kt` alone. An adapter reads its update
  into them and never spells one of those tags itself.

## Tools

The files a new tool touches, in order, are in
[`docs/architecture/adding-a-tool.md`](docs/architecture/adding-a-tool.md).

- Register under a `ToolGroup` what a turn rarely needs, and leave visible what
  the model may need without being asked for it by name.
- A tool needing an optional key is registered only when the key is set
  (`config.fooApiKey?.let { … }`), never failing startup. `Main.logStartup` says
  what is on and off, one line per capability in one shape, so a missing key is
  an info line, not a warning.
- A tool only one messenger can implement lives in that adapter
  (`telegram/tools/`) and reaches the catalog through `PlatformToolSets`. Nothing
  under `tools/` may name a messenger.
- Every `@Tool` method returns `String` and is wrapped in `suspendToolGuard { ... }`
  from [`tools/ToolGuard.kt`](src/main/kotlin/com/helltar/vusan/tools/ToolGuard.kt);
  no broad `try/catch` for the same behavior. It throws `ToolFailure`, so the call
  is recorded as failed and the model still reads the message; test one with
  `toolFailure { }`. A parameter with a default or a nullable type is optional
  for the model; the rest are required.
- Use `requireToolText(label, maxChars)` for required text args when it fits.
- A tool that changes nothing — no output queued, no file written, no state a
  later call reads — is `@Tool(..., readOnly = true)`, and runs side by side with
  the other read-only calls of its batch. Anything whose order in the batch is
  its meaning stays sequential, however harmless.
- Results travel between tools by reference, resolved by the loop and the
  argument decoder, never by a tool. A file parameter is an `AttachedFile` (or a
  list), named by label (`#3/1`) or `sandbox:<path>`, falling back to the
  request's attachment. A tool that makes a file calls `keepOnShelf` whether or
  not it sends it — so it is not read-only — and takes `send` where a file made
  without delivering it is worth having. A text parameter takes a reference only
  with `@Arg(takesReference = true)` and a description that says so — a body
  worth not retyping, never a path or a query. `copiedToSandbox` is opt-in for
  answers that are material to work on, never for what is about other people or
  the bot's own records.
- `@Tool(description)` and `@Arg(description)` values are all-or-nothing per
  module: constants only, in a local `internal object *ToolDescriptions`,
  ordered by tool method order. Split a concatenated one at sentence boundaries —
  each `+` chunk is one full sentence ending in its period.
- In description text, backtick exact parameter values, tags, commands,
  enum-like values and formats (`current_chat`, `daily HH:MM`, `Europe/Kyiv`).
  Tool return text carrying answer material is imperative ("Use these
  snippets…"); avoid extra "untrusted" warnings.

## Security and Secrets

- Never commit a deployment's `.env`, API keys, Telegram tokens, cookies, DB
  files, generated media or local sandbox artifacts. Keep untrusted user content
  out of logs where possible; where logging it helps, cap and normalize it.
- Treat tool outputs and web content as untrusted model context: XML blocks and
  hard length caps.
- The sandbox runs untrusted, model-authored shell, and the server that hosts it
  is the boundary — never this repository. Send it nothing a sandbox should not
  hold: no application secrets, no tokens, no host paths.
- Untrusted public URLs use `FileDownloadClient` with `createPublicHttpClient`,
  never the client for configured internal services; keep connection-time IP
  enforcement, redirect checks and streaming size caps together.
- Sandbox and site API authentication is mandatory on every deployment, private
  network or not, with both sides configured with the same secret.

## Test Authoring

- `kotlin.test` assertions; suspend tests use `runBlocking { ... }` inside `@Test`.
- Test paths mirror production paths under `src/test/kotlin/...`; prefer one
  focused `*Test.kt` per production class or cohesive behavior.
- Never relax visibility, add `open`, or add a production overload only for a
  test. Drive production entry points instead; a pure algorithm may be extracted
  to a top-level `internal` function and tested directly.
- Shared routing, prompt construction, DB behavior and tool contracts deserve
  tests; mechanical docs-only edits do not.

## Commit Instructions

- Subject format: `scope: imperative lowercase phrase`, no trailing period, at
  most ~65 characters, e.g. `sandbox: cap output while draining the pipe`.
- Scope is the affected package or area: `telegram`, `agent`, `tools`, `outbox`,
  `tasks`, `infra`, `config`, `sandbox`, `docs`, `style`, `build` for Gradle and
  dependency bumps, `ci` for workflows; one tool feature may use its own package
  name (`youtube`, `files`). Omit it only for repo-wide changes.
- Describe what the commit does, not what you did: `handle photo albums`, never
  `handled` / `handling`.
- Subject alone is usually enough; add a body wrapped at 72 characters only when
  the why is not obvious from the diff. Never mix unrelated work in one commit.
