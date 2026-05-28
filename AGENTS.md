# Vusan — Agent & Contributor Guide

Vusan is a Telegram AI agent (private chats and groups) built on the [Koog](https://github.com/JetBrains/koog) agent framework, with [ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi) for Telegram I/O and Exposed/SQLite for storage. Read [`docs/architecture.md`](docs/architecture.md) first for the lay of the land, then follow the conventions below.

**Project status — active development.** No stable release. Backwards compatibility is not a concern — prefer the cleaner design; change or remove old code freely instead of keeping compatibility shims.

## Documentation

Read these to understand the system, and keep them in sync with the code in the same change that alters the behavior they describe:

- **[`docs/architecture.md`](docs/architecture.md)** — the orientation map: layers, the request lifecycle, and background flows. Update it when you change how a request flows, add/move/remove a package or layer, or change a core orchestrator (`AgentRunner`, `AgentFactory`, `ToolRegistryFactory`, `TelegramDelivery`, `TaskScheduler`).
- **[`docs/configuration.md`](docs/configuration.md)** + **[`.env.example`](.env.example)** — every environment variable. Update both whenever you add, rename, or remove a config option.
- **[`docs/features.md`](docs/features.md)** — the user-facing tool list. Update it whenever you add or remove an agent tool.

`README.md` links to all three — keep those links valid.

## Build, Test, and Lint

- `./gradlew test` runs the full test suite.
- `./gradlew build` compiles, tests, and packages the app.
- `./gradlew detekt` runs static analysis with `maxIssues: 0`. `detekt.yml` disables a few default rules (wildcard imports, magic numbers, complexity rules, etc.) that don't match this codebase's style — check it before "fixing" violations of those.
- The build targets a JDK 21 toolchain (`jvmToolchain(21)`), regardless of your system default JDK. Note that `detekt` 1.23.x crashes on JDK 25+, so run Gradle itself under JDK 21 (the CI uses Temurin 21).

Use `.env` for local configuration — see `.env.example` for required variables.

## Architecture & Design

- Prefer simplicity: fewer layers, fewer thin abstractions. Avoid single-method `object` wrappers and one-function files — use a top-level function next to a sibling module instead.
- Enforce type invariants in `init {}` blocks. If a value cannot be valid without a constraint (length, distinct items, non-empty list), validate once in `init` so the type can never exist in a broken state. Method-level validation is for input-shape concerns specific to that call site (trimming, dropping empty optional fields).
- Loggers (`KotlinLogging.logger {}`) live in a `private companion object` of the class so the derived logger name matches the class FQN. Use top-level `private val log` only in files that have no class (`Main.kt`, pure utility files). Never one logger per instance.
- Class-private constants follow the companion: if the class already has a `private companion object` (typically for the logger), put `const val` tuning knobs there too. Otherwise leave them as top-level `private const val` rather than introducing a companion just to host constants. Constants also referenced by top-level helpers in the same file must stay top-level (private companion members aren't visible to top-level functions).
- For text normalization and truncation, reuse `String.collapseWhitespaceAndCap(maxLength)` from `agent/MessageContext.kt`.
- For structured sections inside text sent to the LLM (assistant-history summaries of media, user-message wrappers, replied-message context, tool-result blocks), delimit with XML-style tags: `<sent>...</sent>`, `<reply_context>...</reply_context>`, `<user_message>...</user_message>`. Plain markers like `[Sent N images]` or `Reply context:` get parroted as templated responses. XML also resists prompt injection better than ` ``` ` fences since user content cannot fake matching open/close tags reliably.

## Kotlin & Style

Defaults are [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html). Beyond those, these are the patterns this codebase regresses on most often:

- `runCatching { ... }.recoverCatching/onFailure/getOrNull` over manual `try/catch` for non-control-flow error handling. Keep `try { ... } finally { ... }` only for resource/lock release and `try { ... } catch (c: CancellationException) { throw c }` to preserve cancellation.
- `require(...) { "..." }` / `requireNotNull(value) { "..." }` / `check(...) { "..." }` / `checkNotNull(value) { "..." }` over throwing `IllegalArgumentException` / `IllegalStateException` directly — including the `value ?: throw IllegalArgumentException(...)` shape, which should be `requireNotNull(value) { ... }`.
- `?.let { ... }`, `?:` defaults, `takeIf`/`takeUnless`, scope functions over `if (x != null) { ... }` ladders.
- Properties (`val foo: Foo get() = ...`) over Java-style `getFoo()` accessors; computed members over external utility methods that take the receiver as a parameter.
- Avoid `!!`. If a value is genuinely non-null at that point, prove it via smart-cast, `requireNotNull`, or `checkNotNull` with a message.
- Reach for Java APIs only when no Kotlin equivalent exists (e.g. `java.util.Locale`, `java.net.URI`, or `Collections.synchronizedMap` wrapping a `LinkedHashMap` LRU).
- Never suppress compiler warnings without a good reason.
- Prefer raw strings `"""Value "$name"."""` over escaped quotes `"Value \"$name\"."` — unless raw form adds unwanted whitespace or hurts readability. In concatenated strings, only the segments that actually contain `"` need raw form; mixing `"..." + """..."""` is fine.
- In log messages, use `key=[value]` brackets over `key="value"` quotes for delimiting values that may contain spaces or special characters. Keeps log strings as plain `"..."` literals without escapes (`\"`) or raw-string syntax.
- Use the `kotlin.time.Duration` overloads of `delay`, `withTimeout`, etc. — never the legacy `Long`-millis ones (`delay(5000)` warns; `delay(5.seconds)` is the form). For literals, use the `kotlin.time` extensions: `5.seconds`, `200.milliseconds`, `1.minutes`. For a `java.time.Duration`, convert via `.toKotlinDuration()` (`kotlin.time.toKotlinDuration`).

## Koog Tools, DB, Configuration

- **Tool guard**: every Koog tool method returning `String` is wrapped in `suspendToolGuard { ... }` from `tools/common/ToolResult.kt`. It rethrows `CancellationException`, logs other failures, and returns a user-facing fallback. Do not add your own `try/catch` around the body for the same purpose.
- **`@LLMDescription` constants**: all `@LLMDescription` values for a tool module live in a feature-local `internal object *ToolDescriptions` as `const val`s — never inline. All-or-nothing per module: a `*Tools.kt` file contains only `@LLMDescription(*ToolDescriptions.X)` references, never a mix with inline strings, even for very short parameter descriptions. Order constants by tool method order: each tool description first, then its parameter descriptions, then the next tool.
- **`@LLMDescription` text style**: use backticks for exact parameter values, tags, enum-like values, commands, and formats (`anime_girl`, `current_chat`, `daily HH:MM`, `Europe/Kyiv`). Use double quotes only when quoting natural-language user phrases. Avoid single quotes unless the surrounding API requires them.
- **Tool return text for the user**: when a tool's return contains content the LLM should use to answer the user (alt-text, search snippets, transcripts), frame it imperatively — e.g. "use these to describe the photo if the user asks". Avoid hedges like `"alt-text from search provider, not shown to the user"` or `"untrusted metadata"`. The system prompt already marks tool outputs as untrusted globally; stacking inline warnings makes the model hesitate to share what the user explicitly asked for.
- **Database**: all Exposed access goes through `Db.dbTransaction { ... }` (`infra/Database.kt`). `Db.connect(config)` is the single initialization point — do not construct a second `Database` or call `transaction { }` / `suspendTransaction(db)` directly.
- **Configuration**: new env variables are read via the private `readEnv` / `requireEnv` helpers in `AppConfig.Companion` (`config/AppConfig.kt`). Optional → `readEnv` with a fallback or `null`. Required → `requireEnv`. Do not call `System.getenv` directly. Treat blank values as missing (the helpers already do). Remember to update `docs/configuration.md` and `.env.example` (see [Documentation](#documentation)).

## Tests

- Framework: `kotlin.test` (`assertEquals`, `assertIs`, `assertTrue`, `assertFailsWith`). Suspend tests run via `runBlocking { ... }` inside the `@Test` function — not via JUnit-specific runners.
- Layout: `src/test/kotlin/...` mirrors `src/main/kotlin/...`. One `*Test.kt` per production class.
- Do not relax visibility (`private` → `internal`/`public`) or add `open` / `protected open` solely to make a function or class testable. This includes the "production wrapper delegates to a parameterized testable overload" pattern — no extra overloads that exist only so tests can bypass real collaborators. If the only thing using the relaxed visibility / extra overload / `open` modifier is a test, prefer `private`/`final` and either delete the test or rewrite it to drive the production entry point.

## Reference Repositories

Upstream libraries powering the bot. If you keep a local checkout, read source from there rather than reverse-engineering JARs.

- Koog — https://github.com/JetBrains/koog
- Exposed — https://github.com/JetBrains/Exposed
- ktgbotapi — https://github.com/InsanusMokrassar/ktgbotapi
- ktgbotapi examples — https://github.com/InsanusMokrassar/TelegramBotAPI-examples
