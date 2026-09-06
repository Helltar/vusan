package com.helltar.vusan.tools.workspace

internal object WorkspaceToolDescriptions {

    const val RUN_COMMAND =
        "Runs `bash` on this person's own persistent Linux machine: a real shell, with a home directory that keeps everything between messages, days and chats. " +
                "Reach for it whenever a request is work rather than conversation — building or fixing a project, converting or inspecting a file, crunching numbers, producing a document, checking that something actually runs. " +
                "It is not a scratch pad: installed dependencies, virtual environments, cloned repositories and downloaded tools stay where you left them, so a project can be picked up weeks later. " +
                "Never assume what is installed — check, and install what the task needs into the home directory when you can. " +
                "There is no `sudo` and the system image is read-only, so a missing system package is something to report rather than work around. " +
                "The machine reaches the public internet for downloads and package installs, and nothing private. " +
                "Each command starts at the workspace root in a fresh shell; use `cd project && ...` explicitly, and `~/.profile` for setup that must survive. " +
                "A long command returns a job ID and keeps running; collect it with `readWorkspaceCommand` rather than waiting. " +
                "Attached files are copied into `inbox/` before the command; the tool result gives their exact paths. " +
                "Use `sendFromWorkspace` to deliver finished files to the user."

    const val COMMAND =
        "The command interpreted by `bash`; use `writeWorkspaceFile` for substantial file contents."

    const val TIMEOUT_SECONDS =
        "Execution time limit in seconds; omit it for the default."

    const val READ_COMMAND =
        "Reads a workspace command's status and the next part of its combined stdout and stderr. " +
                "Use after `runCommand` returns a running job, or to retrieve output that did not fit. " +
                "Use the returned `nextOffset` for the next read to avoid repeating output. " +
                "An empty `jobId` lists recent commands in this workspace."

    const val READ_JOB_ID =
        "The job ID to read, or an empty string to list recent commands."

    const val OFFSET =
        "Byte offset from the previous result's `nextOffset`; defaults to `0`."

    const val WAIT_SECONDS =
        "Seconds to wait for a running command, from `0` to `20`; defaults to `10`."

    const val CANCEL_COMMAND =
        "Stops a running workspace command and all processes in its container, including background servers. " +
                "Files remain intact and the next command starts a fresh container. " +
                "Use when work should be abandoned or a process is stuck."

    const val JOB_ID =
        "The job ID returned by a workspace tool."

    const val WRITE_FILE =
        "Writes a complete UTF-8 text file in the workspace, creating missing parent directories. " +
                "Use for source code, configuration, and documents; an existing file is replaced. " +
                "Files are not delivered to the user until `sendFromWorkspace` is called."

    const val WRITE_PATH =
        "Path relative to the workspace root, for example `project/main.py`; symlinks are refused."

    const val WRITE_CONTENT =
        "The complete file contents, not a patch or fragment."

    const val DELETE_FILE =
        "Deletes one exact file or directory from this person's workspace; directories are removed recursively. " +
                "Use to remove unwanted files, including when storage pressure has paused commands and uploads. " +
                "This stops background processes; cancel any running command first. " +
                "Deletion is permanent, so select only paths the user wants removed. " +
                "A final symlink is removed without following it; parent symlinks are refused."

    const val DELETE_PATH =
        "An exact path relative to the workspace root, such as `project/build`; no globs or workspace root."

    const val RESET_WORKSPACE =
        "Empties this person's workspace completely and starts it over as a new, empty home. " +
                "Use when the user asks to wipe or reset their workspace, or when a workspace cannot start because its storage is unusable. " +
                "Every file, project and installed dependency is removed permanently and cannot be recovered afterwards. " +
                "Use `deleteWorkspaceFile` instead when only some files should go. " +
                "Cancel any running command first."

    const val SEND_FILES =
        "Sends finished files from the workspace to the chat: images as photos, videos as videos, and other files as documents. " +
                "Files may come from this person's other chats; send only files requested for the current chat. " +
                "For a multi-file project, create and send an archive. " +
                "At most 10 files and 50 MB total per call; file-transfer paths must not contain symlinks."

    const val SEND_PATHS =
        "Paths relative to the workspace root, for example `project/result.zip`."
}
