# Vendored fonts

Glyph-fallback fonts for matplotlib so chart labels with emoji, CJK, and uncommon
symbols (e.g. Telegram display names) render instead of showing tofu boxes. They
are injected into the Pyodide filesystem at worker warm-up (see `worker.ts`).

- `NotoEmoji.ttf` — monochrome emoji, used both by matplotlib's font fallback
  and by the Pillow `draw_text` helper in `worker.ts`. The black-and-white
  variant: neither the Agg backend nor this Pyodide FreeType build (no CBDT)
  can render the color Noto Color Emoji.
- `NotoSansSymbols2-Regular.ttf` — Braille and miscellaneous symbols.

CJK coverage (`NotoSansCJK-Regular.ttc`) is installed from the Alpine
`font-noto-cjk` package in the Dockerfile rather than vendored, due to its size.

All Noto fonts are © Google, licensed under the SIL Open Font License 1.1
(https://openfontlicense.org).
