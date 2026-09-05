package com.helltar.vusan.tools.workspace

internal object WorkspaceToolDescriptions {
    const val RUN_COMMAND =
        "Runs `bash` in this person's persistent workspace; use it to work with files, programs, data, documents, media, or an existing project. " +
                "The same person shares one workspace across private and group chats, while conversation histories stay separate. " +
                "Files and dependencies under the home directory survive messages and container restarts. " +
                "Check the tools and versions needed for the task; install dependencies locally when practical, or report what is missing. " +
                "There is no `sudo`, system installation, interactive terminal, or input on stdin. " +
                "Each command starts at the workspace root with a fresh shell; use `cd project && ...` when needed. " +
                "A long command returns a job ID while it continues; use `readWorkspaceCommand` to collect its output and exit status. " +
                "Run long builds normally without shell backgrounding, and choose a timeout that covers the work. " +
                "Attached files are copied into `inbox/`; the tool result gives their exact paths. " +
                "Use `sendFromWorkspace` to deliver files to the user."

    const val COMMAND = "The command interpreted by `bash`; use `writeWorkspaceFile` for substantial file contents."
    const val TIMEOUT_SECONDS = "Execution time limit in seconds; omit it for the default."

    const val READ_COMMAND =
        "Reads a workspace command's status and the next part of its combined stdout and stderr. " +
                "Use after `runCommand` returns a running job, or to retrieve output that did not fit. " +
                "Use the returned `nextOffset` for the next read to avoid repeating output. " +
                "An empty `jobId` lists recent commands in this workspace."
    const val JOB_ID = "The job ID returned by a workspace tool."
    const val READ_JOB_ID = "The job ID to read, or an empty string to list recent commands."
    const val OFFSET = "Byte offset from the previous result's `nextOffset`; defaults to `0`."
    const val WAIT_SECONDS = "Seconds to wait for a running command, from `0` to `20`; defaults to `10`."

    const val CANCEL_COMMAND =
        "Stops a running workspace command and all processes in its container, including background servers. " +
                "Files remain intact and the next command starts a fresh container. " +
                "Use when work should be abandoned or a process is stuck."

    const val WRITE_FILE =
        "Writes a complete UTF-8 text file in the workspace, creating missing parent directories. " +
                "Use for source code, configuration, and documents; an existing file is replaced. " +
                "Files are not delivered to the user until `sendFromWorkspace` is called."
    const val WRITE_PATH = "Path relative to the workspace root, for example `project/main.py`; symlinks are refused."
    const val WRITE_CONTENT = "The complete file contents, not a patch or fragment."

    const val SEND_FILES =
        "Sends finished files from the workspace to the chat: images as photos, videos as videos, and other files as documents. " +
                "Files may come from this person's other chats; send only files requested for the current chat. " +
                "For a multi-file project, create and send an archive. " +
                "At most 10 files and 50 MB total per call; file-transfer paths must not contain symlinks."
    const val SEND_PATHS = "Paths relative to the workspace root, for example `project/result.zip`."
}
