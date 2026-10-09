package com.helltar.vusan.tools.sandbox

internal object SandboxToolDescriptions {

    const val RUN_COMMAND =
        "Runs `bash` on this person's own persistent Linux machine: a real shell, with a home directory that keeps everything between messages, days and chats. " +
                "Reach for it whenever a request is work rather than conversation — building or fixing a project, converting or inspecting a file, crunching numbers, producing a document, checking that something actually runs. " +
                "It is not a scratch pad: installed dependencies, virtual environments, cloned repositories and downloaded tools stay where you left them, so a project can be picked up weeks later. " +
                "Never assume what is installed — check, and install what the task needs into the home directory when you can. " +
                "There is no `sudo` and the system image is read-only, so a missing system package is something to report rather than work around. " +
                "The home is a fixed-size disk: `No space left on device` means it is full, and the fix is to delete what is no longer needed. " +
                "The machine reaches the public internet for downloads and package installs, and nothing private. " +
                "Each command is a fresh shell started at the home, and nothing is sourced before it, `~/.profile` included: put `cd project && ...` and any environment setup into the command itself. " +
                "A long command returns a job ID and keeps running; collect it with `readSandboxCommand` rather than waiting. " +
                "Attached files, and the files this turn's other tools made, are copied into the turn's own directory under `turns/` before the command; the tool result gives their exact paths. " +
                "Use `sendFromSandbox` to deliver finished files to the user."

    const val COMMAND =
        "The command interpreted by `bash`; use `writeSandboxFile` for substantial file contents, `readSandboxFile` to read a file and `editSandboxFile` to change part of one."

    const val TIMEOUT_SECONDS =
        "Execution time limit in seconds; omit it for the default."

    const val READ_COMMAND =
        "Reads a sandbox command's status and the next part of its combined stdout and stderr. " +
                "Use after `runCommand` returns a running job, or to retrieve output that did not fit. " +
                "Use the returned `nextOffset` for the next read to avoid repeating output. " +
                "An empty `jobId` lists recent commands in this sandbox."

    const val READ_JOB_ID =
        "The job ID to read, or an empty string to list recent commands."

    const val OFFSET =
        "Byte offset from the previous result's `nextOffset`; defaults to `0`."

    const val WAIT_SECONDS =
        "Seconds to wait for a running command, from `0` to `20`; defaults to `10`."

    const val CANCEL_COMMAND =
        "Stops a running sandbox command and every process it started, background servers included. " +
                "Files remain intact, and other commands in the sandbox keep running. " +
                "Use when work should be abandoned or a process is stuck."

    const val JOB_ID =
        "The job ID returned by a sandbox tool."

    const val WRITE_FILE =
        "Writes a complete UTF-8 text file in the sandbox, creating missing parent directories. " +
                "Use for new source code, configuration, and documents; an existing file is replaced. " +
                "To change part of an existing file, use `editSandboxFile` instead of rewriting it. " +
                "Files are not delivered to the user until `sendFromSandbox` is called."

    const val WRITE_PATH =
        "Path relative to the home, for example `project/main.py`."

    const val WRITE_CONTENT =
        "The complete file contents, not a patch or fragment. " +
                "A label (`#4`) instead writes the whole text that earlier result holds."

    const val READ_FILE =
        "Reads a UTF-8 text file from the sandbox, with line numbers, optionally one range of lines. " +
                "Use it before editing a file, to check what a command wrote, or to quote a file in an answer. " +
                "A long file comes back in parts: the result says where it stopped, and the next call continues from that line. " +
                "Binary files and files over 2 MB are refused; inspect those with `runCommand`."

    const val READ_PATH =
        "Path relative to the home, for example `project/index.html`."

    const val READ_FROM_LINE =
        "First line to read, counted from `1`; defaults to the start of the file."

    const val READ_LINE_COUNT =
        "How many lines to read from there; `0`, the default, reads to the end of the file or to the size cap."

    const val EDIT_FILE =
        "Replaces one exact passage of a sandbox text file with another, leaving the rest of the file as it is. " +
                "The passage must match the file exactly, whitespace and indentation included, and must occur once — include a line before and after it to make it unique, or set `replaceAll` to change every occurrence. " +
                "Read the file first; the result says how many occurrences were replaced. " +
                "Use it for every change to an existing file instead of rewriting the file with `writeSandboxFile`."

    const val EDIT_PATH =
        "Path relative to the home, for example `project/index.html`."

    const val EDIT_FIND =
        "The exact text to replace, copied from the file: several lines are fine, and it must not be empty."

    const val EDIT_REPLACE =
        "The text to put in its place; empty removes the passage."

    const val EDIT_REPLACE_ALL =
        "`true` replaces every occurrence of the passage; `false`, the default, requires it to occur exactly once."

    const val RESET_SANDBOX =
        "Empties this person's sandbox completely and starts it over as a new, empty home. " +
                "Use when the user asks to wipe or reset their sandbox, or when its home is beyond saving. " +
                "Anything still running in it is stopped. " +
                "Every file, project and installed dependency is removed permanently and cannot be recovered afterward. " +
                "A site published from it is taken down with it and its address is never served again, so say so before resetting while a site is up. " +
                "Use `rm` through `runCommand` instead when only some files should go."

    const val SEND_FILES =
        "Sends finished files from the sandbox to the chat: images as photos, GIFs as animations, videos as videos, and other files as documents, unless `sendAs` asks for something else. " +
                "Files may come from this person's other chats; send only files requested for the current chat. " +
                "For a multi-file project, create and send an archive. " +
                "At most 10 files and 50 MB total per call."

    const val SEND_PATHS =
        "Paths relative to the home, for example `project/result.zip`."

    const val SEND_AS =
        "Optional; leave empty to go by extension. " +
                "`document` sends every file of the call as a plain file, for a photo or video the user wants uncompressed or as a file. " +
                "`animation` sends a `.gif` or a soundless `.mp4` as a looping GIF. " +
                "A GIF from the chat arrives as an `.mp4`, so return an edited one as an H.264 `.mp4` without audio (`-an`, `-pix_fmt yuv420p`) with `animation`, rather than converting it to `.gif`."
}
