# Repository Guidelines

## Build, Test, and Development Commands
- `./gradlew test` runs the full test suite.
- `./gradlew build` compiles, tests, and packages the app.
- `./gradlew detekt` runs static analysis with `maxIssues: 0`. `detekt.yml` disables a few default rules (wildcard imports, magic numbers, complexity rules, etc.) that don't match this codebase's style — check it before "fixing" violations of those.

Use `.env` for local configuration. See `.env.example` for required variables.

## Project Status: Active Development

This project is in active development with no stable release yet. There are no external consumers to protect, so backwards compatibility is not a concern — when there is a simpler option, take it.

## Coding Style & Naming Conventions

Defaults are [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html). Beyond those:

- Never suppress compiler warnings without a good reason.
- No trailing commas in argument lists, parameter lists, constructors, collection literals, or data class declarations. When the next line closes the list with `)`, `]`, or `}`, the previous line should not end with a comma.
- In `LLMDescription` text, use backticks for exact parameter values, tags, enum-like values, commands, and formats (for example `anime_girl`, `current_chat`, `daily HH:MM`, `Europe/Kyiv`). Use double quotes only when quoting natural-language user phrases. Avoid single quotes unless the surrounding API or provider text specifically requires them.
- All `@LLMDescription` values for a tool module live in a feature-local `internal object *ToolDescriptions` as `const val`s — never inline. The rule is all-or-nothing per module: a `*Tools.kt` file should contain only `@LLMDescription(*ToolDescriptions.X)` references, never a mix of inline strings and constants, even for very short parameter descriptions. Order constants in the descriptions object by tool method order: each tool description first, then its parameter descriptions, then the next tool.
- In Kotlin strings that need literal double quotes in the output, prefer raw strings like `"""Value "$name"."""` over escaped quotes like `"Value \"$name\"."`, as long as the raw string does not introduce unwanted whitespace or make the expression harder to read.
- In log messages, prefer `key=[value]` brackets over `key="value"` quotes for delimiting values that may contain spaces or special characters. Keeps log strings as plain `"..."` literals without escapes (`\"`) or raw-string syntax.

## Architecture

- Prefer simplicity: fewer layers, fewer thin abstractions.
- Avoid single-method `object` wrappers — use top-level functions in the same file instead.
- Don't create a file for a single function if it belongs naturally next to a sibling module.
- Enforce type invariants in `init {}` blocks of `data class`/`class`, not at every call site. If a value cannot be valid without a constraint (max length, distinct items, valid index, non-empty list), validate once in `init` so the type can never exist in a broken state. Method-level validation is for input-shape concerns specific to that call site (e.g. trimming whitespace before constructing the value, dropping empty optional fields).
- Loggers (`KotlinLogging.logger {}`) belong in a `private companion object` of the class so the derived logger name matches the class FQN. Use top-level `private val log` only in files that have no class (e.g. `Main.kt`, pure utility files). Do not declare loggers as instance fields — one logger per class, not per instance.
- Class-private constants follow the companion: if the class already has a `private companion object` (typically for the logger), put `const val` tuning knobs there too. If there is no companion, leave them as top-level `private const val` rather than introducing a companion just to host constants. Constants that are also referenced by top-level helpers in the same file must stay top-level (private companion members aren't visible to top-level functions).
- For text normalization and truncation, reuse the existing `String.collapseWhitespaceAndCap(maxLength)` helper in `agent/MessageContext.kt` rather than reimplementing it.
- Do not relax visibility (`private` → `internal`/`public`) or add `open`/`protected open` solely to make a function or class testable. This includes the "production wrapper delegates to a parameterized testable overload" pattern — don't add an extra overload that exists only so tests can bypass real collaborators. If the only thing using the relaxed visibility, the extra overload, or the `open` modifier is a test, prefer making it `private`/`final` and either delete the test or rewrite it to drive the production entry point.
- For structured sections inside text sent to the LLM (assistant-history summaries of media that was sent, user-message wrappers, replied-message context, tool-result blocks), delimit with XML-style tags like `<sent>...</sent>`, `<reply_context>...</reply_context>`, `<user_message>...</user_message>`. Plain markers like `[Sent N images]` or `Reply context:` get parroted by the LLM as templated responses (the model emits the marker as plain text without performing the action it implies). XML tags also resist prompt injection better than ` ``` ` code fences, since user-controlled content cannot fake matching open/close tags reliably.
- When a tool's return text contains content the LLM should use to answer the user (image alt-text, search snippets, transcripts), frame it imperatively — e.g. "use these to describe the photo if the user asks". Avoid `"alt-text from search provider, not shown to the user"`, `"untrusted metadata"`, or similar hedges. The system prompt already marks tool outputs as untrusted globally, and stacking inline warnings makes the LLM hesitate to share even the content the user explicitly asked for.

## Kotlin-first

Write Kotlin, not transliterated Java. Beyond standard Kotlin coding conventions, these are the patterns this codebase regresses on most often:

- `runCatching { ... }.recoverCatching/onFailure/getOrNull` over manual `try/catch` for non-control-flow error handling. Keep `try { ... } finally { ... }` only for resource/lock release and `try { ... } catch (c: CancellationException) { throw c }` to preserve cancellation.
- `require(...) { "..." }` / `requireNotNull(value) { "..." }` / `check(...) { "..." }` / `checkNotNull(value) { "..." }` over throwing `IllegalArgumentException` / `IllegalStateException` directly — including the `value ?: throw IllegalArgumentException(...)` shape, which should be `requireNotNull(value) { ... }`.
- `?.let { ... }`, `?:` defaults, `takeIf`/`takeUnless`, scope functions over `if (x != null) { ... }` ladders.
- Properties (`val foo: Foo get() = ...`) over Java-style `getFoo()` accessors; computed members over external utility methods that take the receiver as a parameter.
- Avoid `!!`. If a value is genuinely non-null at that point, prove it via smart-cast, `requireNotNull`, or `checkNotNull` with a message.

Reach for Java APIs only when no Kotlin equivalent exists (e.g. `java.util.Locale`, `java.net.URI`, or `Collections.synchronizedMap` wrapping a custom `LinkedHashMap` LRU).

## Reference Repositories

These upstream libraries power the bot. If you keep a local checkout, prefer reading source from there over reverse-engineering JARs.

- Koog — https://github.com/JetBrains/koog
- Exposed — https://github.com/JetBrains/Exposed
- ktgbotapi — https://github.com/InsanusMokrassar/ktgbotapi
- ktgbotapi examples — https://github.com/InsanusMokrassar/TelegramBotAPI-examples
