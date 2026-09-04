package com.helltar.vusan.tools.workspace

internal object WorkspaceToolDescriptions {

    const val RUN_COMMAND =
        "Runs a shell command in this person's own Linux workspace and returns its output. " +
                "The workspace is a real home directory that persists between messages and between days, so use it for anything that has files: writing and running programs, building a multi-file project, converting or editing media, inspecting data, and picking work back up that was started earlier. " +
                "Preinstalled: `python3` with `pip`, `node` with `npm`, `git`, `curl`, `wget`, `ffmpeg`, `imagemagick`, `pandoc`, `sqlite3`, `jq`, `ripgrep`, `zip` and a C/C++ toolchain. " +
                "The workspace has internet access, so `pip install --user`, `npm install`, and downloads work, and what they install stays for next time because the workspace is the home directory. " +
                "There is no `sudo` and no system package manager: install into the workspace instead of into the system. " +
                "Each command starts in the workspace root and keeps no working directory or shell variables from the last one, so use absolute paths or chain them as `cd project && npm run build`. " +
                "A command that must outlive its run has to be started with `setsid`, for example `setsid npm run dev >dev.log 2>&1 &`. " +
                "Files written here are NOT in the chat: call `sendFromWorkspace` for every file the user should receive. " +
                "A file the user attached to their message is placed in `inbox/` under its own name. " +
                "Long output is cut short and the whole log is kept in the workspace, so read the part you need with `grep` or `tail` rather than rerunning the command."

    const val COMMAND =
        "The shell command to run, interpreted by `bash`. " +
                "Chain several steps with `&&` when they belong to one action. " +
                "Prefer `writeWorkspaceFile` over a heredoc for creating a file: quoting a long file inside a command is where its content gets corrupted."

    const val TIMEOUT_SECONDS =
        "How long the command may run before it is killed, in seconds. " +
                "Leave it out for the default. " +
                "Raise it for a build, an install, or an encode, and prefer a background command with `setsid` over a very long wait."

    const val WRITE_FILE =
        "Writes a text file into the workspace, creating the directories in its path. " +
                "Use this for every source file, config, or document you author — it keeps the content exactly as written, which a shell heredoc does not. " +
                "The file is not in the chat until `sendFromWorkspace` sends it."

    const val WRITE_PATH =
        "Where to write, relative to the workspace root, for example `game/index.html`. " +
                "Missing directories are created. " +
                "An existing file at this path is replaced."

    const val WRITE_CONTENT =
        "The complete contents of the file. " +
                "Write the whole file, not a fragment: this replaces what was there."

    const val SEND_FILES =
        "Sends files from the workspace to the chat, which is the only way the user receives them. " +
                "Images arrive as photos, videos as videos, everything else as a document. " +
                "Send the finished result rather than intermediate files, and for a project of many files send a `zip` you built with a command."

    const val SEND_PATHS =
        "Paths relative to the workspace root, for example `game/index.html`. " +
                "At most 10 files per call."
}
