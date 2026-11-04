package com.helltar.vusan.tools.sandbox

internal object SandboxManagementToolDescriptions {

    const val SANDBOX_STATUS =
        "Reports this person's sandbox as the server keeps it: whether it is running, its memory, CPUs and disk size, how its last session ended, the date its files are deleted if it goes unused, and its snapshots with their ids and notes. " +
                "Use it when asked how long the files are kept or which snapshots exist, before restoring or deleting a snapshot, and when commands keep failing for a reason their output does not give. " +
                "How much of the disk is in use is `df -h ~` through `runCommand`."

    const val TAKE_SNAPSHOT =
        "Saves a copy of this person's whole sandbox home as a snapshot that can be restored later, with a note saying what it keeps. " +
                "Take one when the user asks to save their progress, or before a change they may want to undo, such as an upgrade, a migration or a large rewrite. " +
                "Taking it stops the sandbox for a moment and ends every process in it, so it is refused while a command is still running; let that command finish or cancel it first. " +
                "The sandbox keeps a limited number of snapshots: when it is full, show the user the list from `sandboxStatus` and delete the one they choose. " +
                "Snapshots are deleted with the sandbox when it is reset, or removed after going unused."

    const val NOTE =
        "What the snapshot keeps, in the user's terms, so the right one can be chosen later, for example `before the React upgrade`: one short line."

    const val RESTORE_SNAPSHOT =
        "Puts this person's sandbox home back exactly as a snapshot kept it. " +
                "Everything written since that snapshot was taken is lost for good, so restore only when the user asks to, and say what will be lost first. " +
                "It stops the sandbox and ends every process in it; the snapshot itself stays and can be restored again."

    const val RESTORE_SNAPSHOT_ID =
        "The id of the snapshot to restore, as `sandboxStatus` lists it."

    const val DELETE_SNAPSHOT =
        "Deletes one snapshot of this person's sandbox for good; the home and the other snapshots stay as they are. " +
                "Use it when the user asks, or to make room once the sandbox keeps as many snapshots as it may and the user has chosen which one goes."

    const val DELETE_SNAPSHOT_ID =
        "The id of the snapshot to delete, as `sandboxStatus` lists it."

    const val RESTART_SANDBOX =
        "Restarts this person's sandbox: every process in it ends, background servers and stuck jobs included, while every file stays. " +
                "Use it when leftover processes hold a port, fill the memory or the process limit, or keep the sandbox from answering, and canceling one command is not enough. " +
                "Commands still running end interrupted; the next command starts a fresh session."
}
