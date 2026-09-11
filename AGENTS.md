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

### The workspace service

- `services/workspace/` is a separate Deno service, not Kotlin. Keep it that
  way: Kotlin reaches it only over HTTP through
  `tools/workspace/WorkspaceClient.kt` and knows nothing about how it isolates
  what it runs.
- One execution path: `services/workspace/container.ts` starts one Docker
  container and one persistent named home volume per workspace. Only the trusted
  controller receives the Docker socket; commands and file helpers run inside
  their workspace as UID 1000, with no capabilities and no privileged phase at
  any point. Do not reintroduce shared-process runners.
- It is an optional, separate deployment — `compose.yaml` is the bot alone,
  `services/workspace/compose.yaml` the service, running beside the bot or on a
  machine of its own. Do not fold it into the default Compose file or make the
  bot depend on it; `WORKSPACE_URL` plus `WORKSPACE_TOKEN` is the whole switch.

### The site host

- `services/sites/` is a second Deno service and `services/sites/nginx/` the
  image that fronts it. Kotlin reaches it only over HTTP through
  `tools/sites/SiteClient.kt`, and never builds a site's URL — the service
  returns it, so the naming scheme can change without touching the bot.
- The service is authoritative about how large a site may be and how many files
  it may hold, and states those caps when an upload starts. Do not keep a second
  copy of them in Kotlin.
- Publishing is a snapshot: files are staged and swapped in by rename, never
  written into a live site, and nothing is served out of a workspace home. Every
  path is checked in `SiteArchive.kt` before an upload and again by the service,
  which does not trust its caller.
- It is an optional, separate deployment, normally on a machine with a public
  address — `services/sites/compose.yaml`, with `SITES_URL` plus `SITES_TOKEN`
  the whole switch on the bot's side. It also needs a workspace to publish from;
  with one missing the tools are not registered.

### Deployment layouts

- A deployment is a directory holding its compose file and the `.env` beside
  it — the bot at the root, each service under `services/<name>/` — and that
  `.env` configures it wherever it runs. The root `.env` is the bot's; never
  make a service read a setting from it.
- On one machine `compose.override.yaml` brings each service in with `include`,
  merged with the `compose.beside-bot.yaml` beside it: its profile, its network,
  no published API port. Keep it `include` — `extends` interpolates from the
  root `.env` and silently ignores the service's own. `.github/compose-check.sh`
  resolves both layouts in CI and asserts this.

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
- [`docs/sites.md`](docs/sites.md) and
  [`services/sites/.env.example`](services/sites/.env.example): what a published
  site may contain, its limits, DNS and certificates, and how the host is
  deployed. Every knob in `services/sites/config.ts` lands in both, the
  [limits table](docs/sites.md#limits) being the tuning reference.
- [`docs/workspace.md`](docs/workspace.md) and
  [`services/workspace/.env.example`](services/workspace/.env.example): what a
  workspace can do, its limits, isolation and deployment. Every knob in
  `services/workspace/config.ts` lands in both, the [limits
  table](docs/workspace.md#limits-and-tuning) being the tuning reference. Keep
  the base image toolchain small and documented there rather than in LLM-facing
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
  files, generated media or local workspace artifacts. Keep untrusted user
  content out of logs where possible; where logging it helps, cap and normalize
  it.
- Treat tool outputs and web content as untrusted model context: XML blocks and
  hard length caps.
- The workspace runs untrusted, model-authored shell: no application secrets in
  its environment, no host mounts, no production resources, no reachable local
  network. Its policy filters by destination IP, never hostname, and lives on the
  Docker host where nothing inside a workspace can reach it. Anything weakening
  it must fail closed, as `services/workspace/scripts/netpolicy.sh` and the
  `container.ts` startup probe do: a policy that cannot be installed, or that a
  throwaway workspace is shown to escape, stops the service instead of
  degrading it.
- Untrusted public URLs use `FileDownloadClient` with `createPublicHttpClient`,
  never the client for configured internal services; keep connection-time IP
  enforcement, redirect checks and streaming size caps together. Workspace API
  authentication is mandatory on every deployment, private network or not, both
  sides configured with the same secret rather than generating one.

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
  most ~65 characters, e.g. `workspace: cap output while draining the pipe`.
- Scope is the affected package or area: `telegram`, `agent`, `tools`, `outbox`,
  `tasks`, `infra`, `config`, `workspace`, `docs`, `style`, `build` for Gradle and
  dependency bumps, `ci` for workflows; one tool feature may use its own package
  name (`youtube`, `files`). Omit it only for repo-wide changes.
- Describe what the commit does, not what you did: `handle photo albums`, never
  `handled` / `handling`.
- Subject alone is usually enough; add a body wrapped at 72 characters only when
  the why is not obvious from the diff. Never mix unrelated work in one commit.
