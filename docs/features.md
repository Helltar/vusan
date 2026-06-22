# Features

User-visible capabilities, mostly implemented as agent-callable tools under
[`src/main/kotlin/com/helltar/vusan/tools/`](../src/main/kotlin/com/helltar/vusan/tools/).
Items marked *(opt-in)* require an API key or an extra service — see
[configuration.md](configuration.md).

- **Code execution** *(opt-in)* — runs Python in an isolated sandbox (no network;
  `numpy`/`pandas`/`matplotlib`/`sympy`/`scipy`/`Pillow`) to compute exact answers, transform
  uploaded/replied files (including extracting archives), render charts, and produce
  animations/simulations (saved as `.apng`) delivered as a looping MP4 video. Any file written
  under the working directory, including in subfolders, is delivered automatically.
- **Vision** — describes a photo or image document from the current message, a replied-to
  message, or the first photo of an album; use code execution for programmatic image transforms.
- **Image generation & editing** *(opt-in)* — generates an original image from a text prompt, or
  edits the attached/replied image (PNG/JPEG/WebP) per an instruction (OpenAI `gpt-image-1.5`), and
  sends the result as a photo; the agent chooses square/portrait/landscape orientation, while editing
  defaults to keeping the original framing.
- **Voice output** *(opt-in)* — replies with an ElevenLabs-generated Telegram voice message.
- **Voice input** *(opt-in)* — transcribes incoming voice/audio messages and replied voice/audio
  context (OpenAI STT).
- **Scheduled tasks** — schedules autonomous future turns via `once`, `every <interval>`, or
  `cron <UNIX expr>` with per-user limits and stale-task skip/notice handling.
- **Durable memory** — stores personal memory per user and shared memory per group; memory
  survives chat-history clears and can be removed by id or wiped entirely for the current user.
- **Web/image/page lookup** *(opt-in)* — searches the web, searches and sends images, and
  extracts page text (Tavily).
- **YouTube video/audio** — searches or downloads YouTube videos/audio via `yt-dlp` and `ffmpeg`,
  with video size capped for Telegram; videos carry the YouTube thumbnail as the preview.
- **Telegram channels** — reads recent public channel posts from `@username` or `t.me/...`;
  can optionally run vision over post images.
- **GIFs** *(opt-in)* — searches and sends GIFs (Giphy).
- **Polls and quizzes** — creates native Telegram regular polls and quiz polls.
- **Reactions** — adds one Telegram reaction emoji to the current, replied-to, or explicitly
  addressed message.
- **Telegram replies** — sends text, media, documents, polls, and captions back to the current
  chat; can switch subsequent outputs to the user's private chat when explicitly asked.
- **File delivery** — sends generated text content as a Telegram document.
- **Currency** — looks up live ISO-4217 exchange rates.
- **Chat history control** — clears the current user's stored conversation history without
  deleting durable memory or scheduled tasks.
