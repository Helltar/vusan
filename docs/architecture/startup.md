# Startup and deployment

How `Main.kt` wires the application, what the startup log says, and what ships as a deployment. Part of the
[architecture index](../architecture.md).

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client → (only with
a model on `codex` — the chat, its fallback or a role) build the `CodexAuthStore` → preflight every configured model
(`config/llm/ModelPreflight`): the Codex one proves the ChatGPT session works and fills the context window in from the
account's model catalog, an OpenAI one is confirmed against the vendor's list, an Anthropic one also brings its window
and what it takes back, and a compatible one reads whatever its server's list states — whether it sees, its window, its
efforts — so a typo fails here
→ create the LLM runtime, whose client everything downstream then shares — wrapped in `llm/FallbackLlmClient` when
`LLM_FALLBACK_PROVIDER` names a second runtime, so a spent subscription hands every call to it until the deadline its
refusal named, and a digest or diary entry the primary declines (`RequestOptions.fallBackOnRefusal`) is asked of it
once → build repositories, context policy, conversation compactor, the
Telegram client and its `BotProfile` — `getMe`, then `getChat` on the bot itself for every active handle (a premium `@name_agent` beside the `@name_bot` one), shared by the runner, which matches mentions of any of them, and `AgentFactory`, which puts the handles in the system prompt; both and the long-polling registration's `deleteWebhook` are asked again with a growing pause while Telegram answers 5xx or not at all (`waitingOutTelegram`), so an outage holds the start instead of ending it → (only when image
generation or `ELEVENLABS_API_KEY` is configured, the two things that use it) `resolveSelfImage`
(`tools/imagegen/SelfImage.kt`), which reads the reference photo self-portraits and round video messages are drawn from:
`SELF_IMAGE_FILE` when set, otherwise whatever avatar loader startup hands it — for Telegram, one
`getUserProfilePhotos` on the bot's own id (`telegram/BotAvatar.kt`), and a failure there is a warning rather than a
failed startup → (only with a vision runtime) the `StickerCatalog`, then `TelegramToolSets` over it and the client,
`ToolCatalogFactory`, `AgentFactory`, `AgentRunner` → create `TaskMenuHandler` and `InlineChoiceHandler`, and
optionally enable voice transcription → start `TelegramBotRunner`, which builds its own `AgentTurns` and
`CallbackRouter` over those, and launch `TaskScheduler`, the sticker description worker and — where switched on — the
`Diary` and `Initiative` loops, then block on the runner job
until shutdown (closing the executor, HTTP client, and DB in `finally`).

Public URL downloads have their own `createPublicHttpClient` (`infra/PublicHttp.kt`), also closed at shutdown. Its
OkHttp DNS resolver supplies only validated public addresses to the actual connection; literal IPs are guarded
separately because OkHttp bypasses DNS for them. Proxies and automatic redirects are disabled. `FileDownloadClient` owns
bounded streaming and explicit redirect validation, shared by file downloads, image search and Telegram channel
pages/images. Do not give these downloaders the internal-service HTTP client or replace connection-time enforcement with
a separate DNS preflight.

What that leaves in the log, in order: a `Starting Vusan <version>` banner (read from the jar manifest, `dev` on a
classpath run), then whatever the Codex preflight and the optional-tool checks have to say, then one summary block from
`logStartup` just before the runner starts — the LLM line (provider, model, reasoning effort, service tier), context
window, vision, database file, enabled tools. `TelegramBotRunner` closes it with the bot's own handle and
the allowlist.

The runner's first act is `publishCommandMenu` (`telegram/CommandMenu.kt`): a `setMyCommands` call per `Language`, so
the menu Telegram shows follows `TelegramCommands` without an operator step. It writes the same list BotFather's
`/setcommands` edits, which means a manual edit there is replaced on the next start. A rejected call is a warning, not a
failed startup — an out-of-date menu is not worth refusing to serve over.

Registration also wraps the session's `getUpdates` generator, so every poll cycle beats a `Heartbeat` (the `com.helltar:heartbeat` library), which keeps
`/tmp/health` fresh for as long as the loop turns; the image's `HEALTHCHECK` reads nothing but that file's age, and the
runner cancels the heartbeat when it shuts down. The hook belongs on the generator rather than the update consumer
because the session skips the consumer entirely when a batch comes back empty — a bot nobody writes to would otherwise
look dead within minutes. Freshness follows the loop rather than whether Telegram answers, so a brief outage upstream
does not mark every bot unhealthy over something a restart cannot fix — while a long one does, once the session's
backoff decays to a 15-minute retry interval and a restart becomes the thing that clears it. See
[Health check](../configuration.md#health-check).

## Deployment layouts

One deployment ships from this repository: the bot, a compose file and the `.env` beside it. Everything else it talks to is
somebody else's deployment — the Regolith server that runs sandboxes and publishes sites, wherever the operator put it,
reached with one URL and one token.

The bot sits on Compose's default network and publishes no port. It connects out and nothing connects in, so it runs
behind CGNAT as happily as on a public machine.

In CI the deployment has to resolve from the example file a reader starts with: `.github/workflows/image.yml` copies
`.env.example` to `.env` and runs `docker compose config`.
