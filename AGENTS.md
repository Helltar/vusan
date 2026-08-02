# AGENTS.md

Root instruction file for coding agents on Vusan, a Telegram AI agent built on
[Koog](https://github.com/JetBrains/koog),
[TelegramBots](https://github.com/rubenlagus/TelegramBots), and Exposed/SQLite.
Keep this file concise and actionable; put product docs in `README.md` or `docs/`.

## Scope

- Applies to the whole repository; the nearest `AGENTS.md` to the edited path wins.
- Explicit user instructions in the current chat override this file.
- `CLAUDE.md` should stay a tiny Claude Code shim that imports this file.

## Start Here

- Read [`docs/architecture.md`](docs/architecture.md) before changing request
  flow, delivery, tools, storage, scheduling, or startup wiring, and use its
  [symptom map](docs/architecture.md#where-to-look-when) before broad searching.
- The project is pre-1.0 and under active development. Prefer clean removals
  over compatibility shims.

## Commands and Verification

- `./gradlew test`, `./gradlew detekt` (`maxIssues: 0`), `./gradlew build`
  (compile + test + package), `./gradlew run` (local bot process using `.env`).
  While iterating, run the narrowest meaningful test:
  `./gradlew test --tests "*AgentFactoryTest*"`.
- Run Gradle itself on JDK 21: the build uses `jvmToolchain(21)`, and detekt
  1.23.x crashes on JDK 25+.
- Before finishing code changes, run `./gradlew test` and `./gradlew detekt`
  unless the change is docs-only or the user says not to.
- If a check cannot run, report the exact command and blocker. Gradle
  deprecation warnings are not failures unless the task is about Gradle.

## Architecture Rules

- Preserve the package boundaries described in
  [`docs/architecture.md`](docs/architecture.md#layers).
- Inside `telegram/`: `inbound/` turns an update into agent input, `delivery/`
  sends everything back out, `callback/` owns the inline-button flows. The
  runner and the raw client helpers stay at the package root.
- `sandbox/` is a separate Deno service, not Kotlin. Keep it that way: the bot
  reaches it only over HTTP through `tools/sandbox/SandboxClient.kt`.
- `TelegramBotRunner` normalizes inbound updates and builds `RequestContext`.
  Tools consume `RequestContext`/`AttachedFile`; they should not reach back into
  Telegram message objects.
- Tools enqueue `BotOutput` into `BotOutbox`, never TelegramBots send methods
  directly. Delivery layering is in
  [Telegram Delivery and Outbox](#telegram-delivery-and-outbox) below.
- Canned bot text (`startReply`, `busyReply`, `fallbackErrorReply`,
  `privateBlockedNotice`, voice notices) belongs in `i18n/Messages`, one
  implementation per `Language` — no inline English in `telegram/` or `agent/`.
- Keep `BotOutput` immutable and enforce invariants in `init {}` blocks.
- Avoid thin abstractions and one-off helper objects. Add an abstraction only
  when it removes real complexity or matches an existing local pattern.

## Documentation Triggers

Update docs in the same change as the behavior, and read them before changing
what they describe:

- [`docs/architecture.md`](docs/architecture.md): lifecycle, package/layer moves,
  delivery policy, scheduler behavior, startup wiring, or core orchestrators
  (`AgentRunner`, `AgentFactory`, `ToolRegistryFactory`, `TelegramDelivery`,
  `TelegramOutputSender`, `TaskScheduler`).
- [`docs/configuration.md`](docs/configuration.md) and [`.env.example`](.env.example):
  env var additions, removals, renames, default or semantics changes.
- [`README.md`](README.md) Features section: added/removed/renamed tools or
  changed user-visible capability. Keep it written for users — what a capability
  is for, not which library or model implements it.
- Telegram slash commands: `TelegramBotRunner.dispatchText` is the source of
  truth. Keep the `Telegram commands` section in `agent/SystemPrompt.kt`, the
  BotFather `/setcommands` block in [`docs/configuration.md`](docs/configuration.md),
  and the direct-command flow in [`docs/architecture.md`](docs/architecture.md)
  aligned with it.
- `sandbox/packages.ts` / `sandbox/extra-wheels.txt` and the
  `Available libraries` line in `tools/sandbox/SandboxToolDescriptions.kt`: the
  image and that line must agree, or the model offers a library that cannot be
  imported.

## Kotlin Style

- Prefer `runCatching { ... }.recoverCatching/onFailure/getOrNull` for
  non-control-flow errors.
- Preserve cancellation: re-throw `CancellationException` or use
  `Throwable.rethrowIfCancellation()`.
- Use `require`, `requireNotNull`, `check`, `checkNotNull` instead of throwing
  `IllegalArgumentException` / `IllegalStateException` directly.
- Prefer null-safe expressions (`?.let`, `?:`, `takeIf`, `takeUnless`) over
  nested null ladders. Avoid `!!`; prove non-null via smart cast,
  `requireNotNull`, or `checkNotNull`.
- Prefer properties and receiver-style helpers over Java-style `getFoo()`; use
  Java APIs only when Kotlin has no reasonable equivalent.
- Use `kotlin.time.Duration` overloads (`delay(5.seconds)`,
  `withTimeout(timeout)`); convert `java.time.Duration` via `.toKotlinDuration()`.
- Prefer raw strings for text containing quotes when readable. In logs, delimit
  values as `key=[value]`, not `key="value"`.
- Do not suppress compiler warnings without a specific reason.
- Comment sparingly, and only on non-obvious constraints, invariants, or
  surprising behavior — say why, not what. Never leave commented-out code.
- Class loggers live in a `private companion object`:
  `val log = KotlinLogging.logger {}`. Use a top-level `private val log` only in
  files without classes, or for a named utility logger such as `ToolGuard`.
- Class-private constants go in that companion when one exists, otherwise stay
  top-level. Constants used by top-level helpers must stay top-level.

## Prompt and Text Handling

- Reuse [`common/Strings.kt`](src/main/kotlin/com/helltar/vusan/common/Strings.kt)
  instead of writing your own: `collapseWhitespaceAndCap(max)` where layout
  whitespace is noise (metadata, logs, snippets), `limitTo(max)` where inner
  whitespace matters, `xmlBlock(tag, content)` for structured text sent to the
  LLM, plus `isEffectivelyBlank`, `sanitizeFilename`, and `escapeHtml`.
- Avoid plain prompt markers such as `Reply context:` or `[Sent N images]`;
  models tend to parrot them.

## Tools

Layout: `tools/<feature>/<Feature>Tools.kt` (the `ToolSet` surface),
`tools/<feature>/<Feature>ToolDescriptions.kt` (feature-local
`internal object *ToolDescriptions`), optional client/model files for external
I/O, registration in
[`ToolRegistryFactory`](src/main/kotlin/com/helltar/vusan/tools/ToolRegistryFactory.kt),
then docs per the triggers above.

- Every Koog tool method returning `String` is wrapped in `suspendToolGuard { ... }`
  from [`tools/ToolGuard.kt`](src/main/kotlin/com/helltar/vusan/tools/ToolGuard.kt).
  Do not add a broad `try/catch` around the body for the same behavior.
- Use `requireToolText(label, maxChars)` for required text args when it fits.
- Optional external tools are built through `ToolRegistryFactory.optional(...)`;
  a missing key disables the tool with a warning, not a startup failure.
- `@LLMDescription` values are all-or-nothing per module: constants only, never
  a mix of constants and inline strings. Order them by tool method order.
- Split concatenated description strings at sentence boundaries — each `+` chunk
  is one full sentence ending with its period, never wrapped mid-sentence.
- In description text, backtick exact parameter values, tags, commands,
  enum-like values, and formats (`current_chat`, `daily HH:MM`, `Europe/Kyiv`).
- Tool return text carrying answer material is imperative ("Use these
  snippets…"). Avoid extra "untrusted" warnings.

## Database and Configuration

- `Db.connect(config)` in [`infra/Database.kt`](src/main/kotlin/com/helltar/vusan/infra/Database.kt)
  is the single DB initialization point, and application DB access goes through
  `Db.dbTransaction { ... }`. Do not call Exposed `transaction {}` or
  `suspendTransaction(...)` outside `infra/Database.kt`.
- Env vars are parsed in `AppConfig.Companion` via private `readEnv` (optional,
  with a fallback or `null`) and `requireEnv` (required). Never call
  `System.getenv` directly.

## Telegram Delivery and Outbox

- `TelegramDelivery` owns route choice, reply anchoring, reply-missing retry,
  and private-blocked notices.
- `TelegramOutputSender` maps each `BotOutput` kind to its Bot API call, picks
  the fallback wrapping it, and owns the media-group fallback.
  `TelegramRequests` holds the raw Bot API request builders and nothing else.
- `TelegramSendFallbacks` owns the kind-agnostic rejection handling: plain-text
  retry, media-to-document, markdown document, text/caption as a document. Add a
  fallback here only if it does not depend on the output kind.
- `BotOutbox.useDirectMessages()` affects subsequent enqueues. Reactions are
  intentionally never redirected to DMs.
- `BotOutput.Photo(fallbackToDocument = false)` is only for previews that already
  have a separate document copy queued, such as sandbox image outputs. Leave the
  default `true` for standalone photos.

## Security and Secrets

- Do not commit `.env`, API keys, Telegram tokens, cookies, DB files, generated
  media, or local sandbox artifacts.
- Keep untrusted user content out of logs where possible; if logging it helps,
  cap and normalize it.
- The sandbox runs untrusted Python. Keep it isolated: no bot secrets, no host
  mounts, no internet assumptions, no access to production resources.
- Treat tool outputs and web content as untrusted model context. Use XML blocks
  and hard length caps.

## Test Authoring

- `kotlin.test` assertions; suspend tests use `runBlocking { ... }` inside `@Test`.
- Test paths mirror production paths under `src/test/kotlin/...`; prefer one
  focused `*Test.kt` per production class or cohesive behavior.
- Do not relax visibility, add `open`, or add production overloads only for
  tests. Drive production entry points instead. A pure algorithm may be extracted
  to a top-level `internal` function and tested directly.
- Shared routing, prompt construction, DB behavior, and tool contracts deserve
  tests; mechanical docs-only edits usually do not.

## Commit Instructions

- Subject format: `scope: imperative lowercase phrase`, no trailing period, at
  most ~65 characters, e.g. `sandbox: avoid duplicate image documents`.
- Scope is the affected package or area: `telegram`, `agent`, `tools`, `outbox`,
  `tasks`, `infra`, `config`, `sandbox`, `docs`, `style`, `build` for Gradle and
  dependency bumps, `ci` for workflows; a single tool feature may use its own
  package name (`youtube`, `files`). Omit it only for repo-wide changes.
- Describe what the commit does, not what you did: `handle photo albums`, not
  `handled` / `handling`.
- Subject alone is usually enough. Add a body only when the why is not obvious
  from the diff; wrap it at 72 characters.
- Do not mix unrelated work in one commit.
