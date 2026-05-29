# Vendored fonts

Glyph-fallback fonts for matplotlib so chart labels with emoji, CJK, and uncommon
symbols (e.g. Telegram display names) render instead of showing tofu boxes. They
are injected into the Pyodide filesystem at worker warm-up (see `worker.ts`).

- `NotoEmoji.ttf` — monochrome emoji. The Agg backend cannot render the color
  Noto Color Emoji, so this is the black-and-white variant.
- `NotoSansSymbols2-Regular.ttf` — Braille and miscellaneous symbols.

CJK coverage (`NotoSansCJK-Regular.ttc`) is installed from the Alpine
`font-noto-cjk` package in the Dockerfile rather than vendored, due to its size.

All Noto fonts are © Google, licensed under the SIL Open Font License 1.1
(https://openfontlicense.org).
