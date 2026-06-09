package com.helltar.vusan.tools.sandbox

internal object SandboxToolDescriptions {

    const val CODE_EXECUTION =
        "Executes Python 3 code in an isolated sandbox to compute an exact answer or produce a chart or file, then returns its output. " +
                "Use this whenever the answer depends on real computation rather than recall: arithmetic over given numbers, date and time-zone math, probability and statistics, parsing or transforming data the user provided (JSON, CSV, logs), encoding and decoding (base64, hashing, UUID), or generating a visualization. " +
                "Prefer this over answering from memory for anything numeric, date-based, or data-shaped — do not guess a result you could compute. " +
                "When the user attaches a file (CSV, JSON, text, image, etc.), it is placed in the working directory; an `<attached_file>` note gives its exact name. Read it directly from your script (for example `pandas.read_csv(\"data.csv\")`) instead of asking the user to paste the contents. " +
                "The sandbox has no network access: never use it to fetch URLs or call APIs (use the web tools for that). " +
                "Any image the code saves (for example a matplotlib chart) or file it writes is sent to the chat automatically; the returned text is the script's `stdout` and `stderr` for you to read and explain to the user in their own language. " +
                "Available libraries: `numpy`, `pandas`, `matplotlib`, `sympy`, `scipy`, `Pillow` (PIL). " +
                "Do not use it for general knowledge, conversation, or anything unrelated to computation."

    const val CODE_EXECUTION_SOURCE =
        "A complete, self-contained Python 3 script. " +
                "It runs once with no state from previous calls, so include every import and definition. " +
                "Print results to stdout with `print(...)`. " +
                "Save a chart to a file such as `chart.png`. " +
                "Save an animation or simulation to a `.apng` file (e.g. matplotlib `FuncAnimation` saved with `PillowWriter` to `simulation.apng`) — it is delivered to the chat as a looping video, so prefer `.apng` over `.gif` to keep full color. Keep it cheap to render: a few dozen frames at a small figure size and modest dpi, or it may hit the time limit. " +
                "Write any other output files into the working directory; every file there is delivered to the user automatically, including files inside subfolders (for example images extracted from a `.zip`). Save the real file bytes — never write a placeholder that points at another path. " +
                "When transforming an attached file, save the result under a new filename — a file that keeps an input file's name counts as the user's own upload and is not delivered. " +
                "When drawing text on an image with Pillow, load a Unicode font with `ImageFont.truetype(\"/fonts/DejaVuSans.ttf\", size)` (use `/fonts/DejaVuSans-Bold.ttf` for bold) — it covers Latin and Cyrillic. Never use `ImageFont.load_default()` or OS font paths; they lack Cyrillic and render boxes. " +
                "There is no network access."
}
