# AGENTS.md

Vusan is a Telegram AI agent for private chats and groups. It uses
[Koog](https://github.com/JetBrains/koog), [ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi),
and Exposed/SQLite.

This is the root instruction file for coding agents. Keep it concise and
actionable; put product docs in `README.md` or `docs/`.

## Scope

- Applies to the whole repository.
- If a nested `AGENTS.md` appears later, the nearest file to the edited path wins.
- Explicit user instructions in the current chat override this file.
- `CLAUDE.md` should stay a tiny Claude Code shim that imports this file.

## Start Here

- Read [`docs/architecture.md`](docs/architecture.md) before changing request
  flow, delivery, tools, storage, scheduling, or startup wiring.
- Use the symptom map in [`docs/architecture.md`](docs/architecture.md#where-to-look-when)
  before broad searching.
- Check [`docs/configuration.md`](docs/configuration.md) and [`.env.example`](.env.example)
  before changing env vars.
- Check [`docs/features.md`](docs/features.md) before changing agent tools or
  user-visible capabilities.
- Project status: active development, no stable release. Prefer clean removals
  over compatibility shims.

## Setup Commands

- `./gradlew test` - full test suite.
- `./gradlew detekt` - static analysis, `maxIssues: 0`.
- `./gradlew build` - compile, test, and package.
- `./gradlew run` - local bot process using `.env`.

Run Gradle itself on JDK 21. The build uses `jvmToolchain(21)`, and detekt
1.23.x is known to crash on JDK 25+.

## Verification

- Run the narrowest meaningful test first while iterating.
- Before finishing code changes, run `./gradlew test` and `./gradlew detekt`
  unless the change is docs-only or the user says not to.
- If a check cannot run, report the exact command and blocker.
- Do not treat Gradle deprecation warnings as failures unless the task is about
  Gradle compatibility.
- Claude Code has a Stop hook in `.claude/hooks/kotlin-warnings.sh` that blocks
  on Kotlin compiler warnings. Keep it aligned with this file's warning policy.

## Architecture Rules

- Preserve package boundaries from [`docs/architecture.md`](docs/architecture.md):
  `telegram/` for Telegram I/O, `agent/` for Koog orchestration, `tools/` for
  agent-callable capabilities, `outbox/` for output models, `request/` for
  per-turn context, `tasks/` for scheduling, `infra/` for DB/HTTP, `config/`
  for runtime config.
- `TelegramBotRunner` normalizes inbound updates and builds `RequestContext`.
  Tools consume `RequestContext`/`AttachedFile`; they should not reach back into
  Telegram message objects.
- Tools enqueue `BotOutput` into `BotOutbox`. They should not call ktgbotapi
  send methods directly.
- `TelegramDelivery` owns routing policy. `TelegramOutputSender` owns Telegram
  send mechanics and fallbacks.
- `BotOutbox.useDirectMessages()` affects subsequent enqueues. Reactions are
  intentionally never redirected to DMs.
- Keep `BotOutput` immutable and enforce invariants in `init {}` blocks.
- Avoid thin abstractions and one-off helper objects. Add an abstraction only
  when it removes real complexity or matches an existing local pattern.

## Documentation Triggers

Update docs in the same change as behavior:

- [`docs/architecture.md`](docs/architecture.md): lifecycle, package/layer
  moves, delivery policy, scheduler behavior, startup wiring, or core
  orchestrators (`AgentRunner`, `AgentFactory`, `ToolRegistryFactory`,
  `TelegramDelivery`, `TelegramOutputSender`, `TaskScheduler`).
- [`docs/configuration.md`](docs/configuration.md) and [`.env.example`](.env.example):
  env var additions, removals, renames, default changes, or semantics changes.
- [`docs/features.md`](docs/features.md): added/removed/renamed tools or
  changed user-visible tool capability.

## Kotlin Style

- Prefer `runCatching { ... }.recoverCatching/onFailure/getOrNull` for
  non-control-flow errors.
- Preserve cancellation. Re-throw `CancellationException`, or use
  `Throwable.rethrowIfCancellation()`.
- Use `require`, `requireNotNull`, `check`, and `checkNotNull` instead of
  directly throwing `IllegalArgumentException` / `IllegalStateException`.
- Prefer null-safe expressions (`?.let`, `?:`, `takeIf`, `takeUnless`) over
  nested null ladders.
- Prefer properties and receiver-style helpers over Java-style `getFoo()`
  accessors.
- Avoid `!!`; prove non-null via smart cast, `requireNotNull`, or `checkNotNull`.
- Use Java APIs only when Kotlin has no reasonable equivalent.
- Do not suppress compiler warnings without a specific reason.
- Comment sparingly: code should explain itself through naming and structure.
  Comment only non-obvious constraints, invariants, or surprising behavior,
  and say why, not what the code does. Never leave commented-out code.
- KDoc is normal prose: sentences start with an uppercase letter. Ordinary
  `//` comments are entirely lowercase, including sentences after a period.
  Keep code identifiers and acronyms (`RunResponse`, `WAL`, `URL`) as written.
- Prefer raw strings for text containing quotes when readable.
- In logs, delimit values as `key=[value]`, not `key="value"`.
- Use `kotlin.time.Duration` overloads (`delay(5.seconds)`,
  `withTimeout(timeout)`); convert `java.time.Duration` via `.toKotlinDuration()`.

## Logging and Constants

- Class loggers live in a `private companion object`:
  `val log = KotlinLogging.logger {}`.
- Use top-level `private val log` only in files without classes or for deliberate
  named utility loggers such as `ToolGuard`.
- If a class already has a private companion for logging, put class-private
  constants there too.
- Otherwise keep private constants top-level. Constants used by top-level
  helpers must stay top-level.

## Prompt and Text Handling

- Shared helpers live in [`common/Strings.kt`](src/main/kotlin/com/helltar/vusan/common/Strings.kt):
  `collapseWhitespaceAndCap`, `limitTo`, `isEffectivelyBlank`,
  `sanitizeFilename`, `xmlBlock`.
- Use `collapseWhitespaceAndCap(max)` for metadata, logs, and snippets where
  layout whitespace is noise.
- Use `limitTo(max)` when preserving inner whitespace matters.
- Use `xmlBlock(tag, content)` for structured text sent to the LLM: user
  message wrappers, reply context, transcripts, tool-result blocks, media
  summaries, and injected memory.
- Avoid plain prompt markers such as `Reply context:` or `[Sent N images]`;
  models tend to parrot them.

## Tools

Tool layout:

1. `tools/<feature>/<Feature>Tools.kt` - `ToolSet` surface.
2. `tools/<feature>/<Feature>ToolDescriptions.kt` - feature-local
   `internal object *ToolDescriptions`.
3. Optional client/model files for external I/O.
4. Registration in [`ToolRegistryFactory`](src/main/kotlin/com/helltar/vusan/tools/ToolRegistryFactory.kt).
5. Docs updates in `docs/features.md` and, if needed, config docs/env template.

Tool rules:

- Every Koog tool method returning `String` is wrapped in
  `suspendToolGuard { ... }` from
  [`tools/ToolGuard.kt`](src/main/kotlin/com/helltar/vusan/tools/ToolGuard.kt).
- Do not add duplicate broad `try/catch` around tool bodies for the same guard
  behavior.
- Use `requireToolText(label, maxChars)` for required text args when it fits.
- `@LLMDescription` values are all-or-nothing per module: use constants only,
  never a mix of constants and inline strings.
- Order description constants by tool method order.
- In description text, use backticks for exact parameter values, tags, commands,
  enum-like values, and formats (`current_chat`, `daily HH:MM`, `Europe/Kyiv`).
- Tool return text containing answer material should be imperative: "Use these
  snippets..." / "Use these descriptions...". Avoid extra "untrusted" warnings.
- Optional external tools are built through `ToolRegistryFactory.optional(...)`;
  missing keys disable the tool with a warning, not a startup failure.

## Database and Configuration

- `Db.connect(config)` in [`infra/Database.kt`](src/main/kotlin/com/helltar/vusan/infra/Database.kt)
  is the single DB initialization point.
- Application DB access goes through `Db.dbTransaction { ... }`. Do not call
  Exposed `transaction {}` or `suspendTransaction(...)` outside `infra/Database.kt`.
- Env vars are parsed in `AppConfig.Companion` via private `readEnv` /
  `requireEnv`. Do not call `System.getenv` directly.
- Optional env var: `readEnv("NAME")` with fallback or `null`.
- Required env var: `requireEnv("NAME")`.

## Telegram Delivery and Outbox

- `TelegramDelivery` owns route choice, reply anchoring, reply-missing retry,
  and private-blocked notices.
- `TelegramOutputSender` owns markdown fallback, media-to-document fallback,
  and media-group fallback.
- `BotOutput.Photo(fallbackToDocument = false)` is only for previews that
  already have a separate document copy queued, such as sandbox image outputs.
  Leave the default `true` for standalone photos.

## Security and Secrets

- Do not commit `.env`, API keys, Telegram tokens, cookies, DB files, generated
  media, or local sandbox artifacts.
- Keep untrusted user content out of logs when possible; if logging it is useful,
  cap and normalize it.
- The code execution sandbox runs untrusted Python. Keep it isolated: no bot
  secrets, no host mounts, no internet assumptions, and no access to production
  resources.
- Treat tool outputs and web content as untrusted model context. Use XML blocks
  and hard length caps.

## Test Authoring

- Use `kotlin.test` assertions.
- Suspend tests use `runBlocking { ... }` inside `@Test`.
- Test paths mirror production paths under `src/test/kotlin/...`.
- Prefer one focused `*Test.kt` per production class or cohesive behavior.
- Do not relax visibility, add `open`, or add production overloads only for
  tests. Drive production entry points instead.
- Shared routing, prompt construction, DB behavior, and tool contracts deserve
  tests; mechanical docs-only edits usually do not.

## Commit Instructions

- Subject format: `scope: imperative lowercase phrase`, no trailing period,
  at most ~65 characters, e.g. `sandbox: avoid duplicate image documents`.
- Scope is the affected package or area (`telegram`, `agent`, `sandbox`,
  `infra`, `config`, `tasks`, `docs`, `style`). Omit it only for genuinely
  repo-wide changes.
- Describe what the commit does, not what you did: `handle photo albums`,
  not `handled` / `handling`.
- Subject alone is usually enough. Add a body only when the why is not
  obvious from the diff; wrap it at 72 characters.
- Do not mix unrelated work in one commit.

## External References

- Koog: <https://github.com/JetBrains/koog>
- ktgbotapi: <https://github.com/InsanusMokrassar/ktgbotapi>
- ktgbotapi examples: <https://github.com/InsanusMokrassar/TelegramBotAPI-examples>
- Exposed: <https://github.com/JetBrains/Exposed>
