# AGENTS.md

Root instruction file for coding agents on Vusan, a Telegram AI agent built on
[Koog](https://github.com/JetBrains/koog),
[TelegramBots](https://github.com/rubenlagus/TelegramBots), and Exposed/SQLite.
It applies to the whole repository; `CLAUDE.md` is just the one-line `@AGENTS.md`
import that points Claude Code here. Keep this file short and actionable —
product docs go in `README.md` or `docs/`. The project is pre-1.0, so prefer
clean removals over compatibility shims.

Read [`docs/architecture.md`](docs/architecture.md) before changing request flow,
delivery, tools, storage, scheduling or startup wiring, and use its
[symptom map](docs/architecture.md#where-to-look-when) before broad searching.

## Local notes

A checkout may carry `notes/` — local, gitignored prose on *why* things are the
way they are: decisions and the reasoning behind them, live constraints, traps.
It is not part of the repository and never reaches GitHub. If the directory
exists, read `notes/README.md` first, then open only the notes the task needs.
Whatever a note settles belongs there, not here.

## Commands and Verification

- `./gradlew test`, `./gradlew detekt` (`maxIssues: 0`), `./gradlew build`
  (compile + test + package), `./gradlew run` (local bot on `.env`).
  While iterating: `./gradlew test --tests "*AgentFactoryTest*"`.
- Run Gradle itself on JDK 21: the build uses `jvmToolchain(21)`, and detekt
  1.23.x crashes on JDK 25+.
- Before finishing code changes, run `./gradlew test` and `./gradlew detekt`
  unless the change is docs-only or the user says not to.
- If a check cannot run, report the exact command and blocker. Gradle
  deprecation warnings are not failures unless the task is about Gradle.

## Architecture Rules

Preserve the package boundaries in [`docs/architecture.md`](docs/architecture.md#layers).

- Inside `telegram/`: `inbound/` turns an update into agent input, `delivery/`
  sends everything back out, `callback/` owns the inline-button flows, `tools/`
  holds the tools no other messenger could implement; the runner, `AgentTurns`
  and the raw client helpers stay at the package root.
- `TelegramBotRunner` normalizes inbound updates into a prompt; `AgentTurns`
  builds the `AgentRequest` from there and owns the turn up to its delivery.
  Tools consume `RequestContext`/`AttachedFile`, never Telegram message objects.
- One `RequestContext` is built at ingress and read unchanged from there on:
  identity (`UserRef`/`ChatRef`/`ConversationScope`) is platform-qualified, and
  every external reference is opaque text — a message, a topic or a reply anchor
  as much as a person or a chat, in a stored row as much as in memory. Convert to
  a messenger's own width only inside that adapter — for Telegram,
  `telegram/TelegramIds.kt` and nowhere else.
- Outside an adapter, deliver through the `delivery/OutputDelivery` port and
  read chat facts through `request/ChatProfileLookup`. `tasks/` and everything
  else shared must stay free of a messenger client; `PlatformBoundaryTest`
  enforces that with an allowlist that may only shrink.
- Tools enqueue `BotOutput` into `BotOutbox`, never TelegramBots send methods
  directly. Keep `BotOutput` immutable and enforce invariants in `init {}` blocks.
- Canned bot text (`startReply`, `busyReply`, `fallbackErrorReply`,
  `privateBlockedNotice`, voice notices) belongs in `i18n/Messages`, one
  implementation per `Language` — no inline English in `telegram/` or `agent/`.
- A kind-specific send fallback belongs in `TelegramOutputSender`, a
  kind-agnostic one in `TelegramSendFallbacks`, raw Bot API builders in
  `TelegramRequests` and nowhere else; route choice and reply anchoring stay in
  `TelegramDelivery`. `BotOutbox.useDirectMessages()` affects subsequent
  enqueues, and reactions are intentionally never redirected to DMs.
- `Db.connect(config)` in [`infra/Database.kt`](src/main/kotlin/com/helltar/vusan/infra/Database.kt)
  is the single DB initialization point and application access goes through
  `Db.dbTransaction { ... }`; never call Exposed `transaction {}` or
  `suspendTransaction(...)` outside that file. A schema change is a table edit plus a
  raised `Schema.VERSION` in [`infra/Schema.kt`](src/main/kotlin/com/helltar/vusan/infra/Schema.kt),
  and deployed databases moved by hand to match — nothing is reconciled by comparing
  declarations and no migration runs in code.
- Env vars are parsed in `AppConfig.Companion` via private `readEnv` (optional,
  with a fallback or `null`) and `requireEnv` (required). Never call
  `System.getenv` directly.
- Avoid thin abstractions and one-off helper objects. Add an abstraction only
  when it removes real complexity or matches an existing local pattern.

### The sandbox

- The sandbox is a Regolith server: a separate project, deployed on its own,
  reached only through Regolith's Kotlin SDK, and only from
  `tools/sandbox/SandboxClient.kt`; nothing here knows how a sandbox is
  isolated, and no Docker socket reaches this side.
- The SDK reads the server's answers by its own protocol, so its version moves
  with the server's minor version — and only to a release Maven Central already
  serves, or the public build breaks. `docs/sandbox.md` names that version.
- Do not copy the server's settings into Kotlin. It is authoritative about
  images, resources, timeouts, retention and network policy, and states its
  limits in `GET /v1/info`; the client reads them there and trims what the model
  asks for, rather than keeping a second copy.
- One sandbox per person, named by `personKeyOrNull` and created on first use.
  Vusan sends no sandbox settings when it creates one: the server's defaults are
  the deployment's business.
- It is optional — `REGOLITH_URL` plus `REGOLITH_TOKEN` is the whole switch, and
  without it neither the shell tools nor publishing are registered.

### Publishing to the web

- A site is published by the Regolith server, not from here: one call sends a
  directory's path and gets the address back. Never build a site's URL in Kotlin
  — the server returns it, so its naming scheme can change without touching the
  bot.
- The server is authoritative about what a site may hold, where it is served and
  how long it is kept. Do not keep a copy of any of it here, and do not add a
  setting for whether publishing exists: `GET /v1/info` says so.
- Publishing is a snapshot of a directory, and the tools say so to the model.
  Nothing is served out of a live sandbox home, which is reclaimed when its
  session stops.
- The one check worth doing on this side is the missing `index.html`, because a
  directory without one publishes fine and its link opens nothing.

### Deployment layouts

- One deployment ships from this repository: the bot, its `compose.yaml` and the
  `.env` beside it. Nothing else belongs in it — the sandbox and the sites it
  publishes are a Regolith server's, deployed separately and reached with one URL
  and one token.
- The bot only ever connects out. Never add an inbound port or a service that
  expects to reach it, so a deployment behind CGNAT stays as ordinary as one on a
  public machine.

## Documentation Triggers

Update docs in the same change as the behavior, and read them before changing
what they describe:

- [`docs/architecture.md`](docs/architecture.md): lifecycle, package/layer moves,
  delivery policy, scheduler behavior, startup wiring, or core orchestrators
  (`AgentRunner`, `AgentFactory`, `ToolRegistryFactory`, `TelegramDelivery`,
  `TelegramOutputSender`, `TaskScheduler`).
- [`docs/configuration.md`](docs/configuration.md) and
  [`.env.example`](.env.example): env var additions, removals,
  renames, default or semantics changes.
- [`docs/sites.md`](docs/sites.md): what publishing does for a person and what
  the bot expects of the server that serves it. Limits, addresses and retention
  are the server's to document, not this repository's.
- [`docs/sandbox.md`](docs/sandbox.md): what the sandbox can do, what the
  bot expects of a Regolith server, and which side owns each limit — the [limits
  table](docs/sandbox.md#limits) lists only the bounds this repository
  enforces. Keep claims about isolation, images and defaults on the server's
  side of the line rather than restating them here or in LLM-facing
  descriptions: the agent checks what its task needs.
- [`README.md`](README.md) Features section: added/removed/renamed tools or
  changed user-visible capability. Write it for users — what a capability does,
  never which library, model, key or optional service enables it. Setup
  requirements and implicit dependencies go to `docs/configuration.md` instead,
  even when no new env var is involved.
- Telegram slash commands: `TelegramBotRunner.dispatchText` is the source of
  truth. Keep aligned with it the `Telegram commands` section in
  `agent/SystemPrompt.kt`, the menu in `telegram/CommandMenu.kt` (a description
  per `Language` in `i18n/Messages`), and architecture.md's direct-command flow.

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
- Blank lines carry meaning, and code is read more often than written. A wrapped
  class header is followed by one. A `return` that ends a function body has one
  above it whenever there is work above it, while a `return` inside a small block
  stays where it is. A multi-line `if`, `when`, `try`, `for` or `while` at the top
  of a function body is separated from what surrounds it. Never two in a row.
- A parameter or argument list that wraps over several lines ends with a trailing
  comma, which is what the conventions encourage at the declaration site.

## Prompt and Text Handling

- Reuse [`common/Strings.kt`](src/main/kotlin/com/helltar/vusan/common/Strings.kt)
  rather than writing your own: `collapseWhitespaceAndCap(max)` where layout
  whitespace is noise (metadata, logs, snippets), `limitTo(max)` where inner
  whitespace matters, `xmlBlock(tag, content)` for structured text sent to the
  LLM, plus `isEffectivelyBlank`, `sanitizeFilename` and `escapeHtml`.
- `xmlBlock` already escapes a closing tag of its own name, so tool output and
  fetched pages cannot end their block early; text quoted from a message needs
  `neutralizePromptBlocks` too, which defuses every tag the prompt uses. Avoid
  plain markers such as `Reply context:` or `[Sent N images]` — models parrot them.

## Tools

Layout: `tools/<feature>/<Feature>Tools.kt` (the `ToolSet` surface),
`<Feature>ToolDescriptions.kt` (a local `internal object *ToolDescriptions`), optional
client/model files for external I/O, registration in
[`ToolRegistryFactory`](src/main/kotlin/com/helltar/vusan/tools/ToolRegistryFactory.kt),
then docs per the triggers above. Registration also decides whether the tool's schemas ride
in every request or wait for `loadTools`: a set registered under a `ToolGroup` in
[`ToolCatalog`](src/main/kotlin/com/helltar/vusan/tools/ToolCatalog.kt) is registered all
the same, only offered later. Group what a turn rarely needs; leave visible what the model
may need without being asked for it by name. A tool needing an optional key is registered
through `ToolRegistryFactory.optional(...)`, which disables it with a warning
rather than failing startup.

A tool only one messenger can implement — Telegram's `file_id`, its sticker sets —
is not registered there at all: it lives in that adapter (`telegram/tools/`) and
reaches the registry through the `PlatformToolSets` port, gated there on the same
chat capabilities. Nothing under `tools/` may name a messenger, and
`PlatformBoundaryTest`'s allowlist is empty — keep it that way.

- Every Koog tool method returning `String` is wrapped in `suspendToolGuard { ... }`
  from [`tools/ToolGuard.kt`](src/main/kotlin/com/helltar/vusan/tools/ToolGuard.kt);
  no broad `try/catch` for the same behavior. It throws koog's `ToolException`, so
  the call is recorded as failed and the model still reads the message as the
  result; test one with `toolFailure { }`.
- Use `requireToolText(label, maxChars)` for required text args when it fits.
- `@LLMDescription` values are all-or-nothing per module: constants only, never
  mixed with inline strings, ordered by tool method order. Split a concatenated
  one at sentence boundaries — each `+` chunk is one full sentence ending in its
  period, never wrapped mid-sentence.
- In description text, backtick exact parameter values, tags, commands,
  enum-like values and formats (`current_chat`, `daily HH:MM`, `Europe/Kyiv`).
  Tool return text carrying answer material is imperative ("Use these
  snippets…"); avoid extra "untrusted" warnings.

## Security and Secrets

- Never commit a deployment's `.env`, API keys, Telegram tokens, cookies, DB
  files, generated media or local sandbox artifacts. Keep untrusted user
  content out of logs where possible; where logging it helps, cap and normalize
  it.
- Treat tool outputs and web content as untrusted model context: XML blocks and
  hard length caps.
- The sandbox runs untrusted, model-authored shell, and the server that hosts
  it is the boundary — never this repository. Send it nothing a sandbox should
  not hold: no application secrets, no tokens, no host paths. A sandbox is not
  a place to put anything the bot would not publish.
- Untrusted public URLs use `FileDownloadClient` with `createPublicHttpClient`,
  never the client for configured internal services; keep connection-time IP
  enforcement, redirect checks and streaming size caps together. Sandbox and
  site API authentication is mandatory on every deployment, private network or
  not, both sides configured with the same secret rather than generating one.

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
