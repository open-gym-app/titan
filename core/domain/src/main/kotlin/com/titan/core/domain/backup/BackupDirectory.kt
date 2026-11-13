package com.titan.core.domain.backup

import java.io.File

/**
 * Where this device keeps its backup files: every manual export and every automatic backup.
 *
 * ### Why this is a port
 *
 * Three places need the directory — the weekly backup and "Delete all data" in `:core:data`,
 * and the export and its share sheet on `:feature:settings`'s backup screen — and the rule that
 * picks it depends on the Android version (#259). A feature module may not depend on
 * `:core:data` (docs/ARCHITECTURE.md), so without a port each side would spell the rule itself,
 * and a rule written twice drifts the first time it changes. `:core:data` implements it once,
 * and the graph hands the same instance to every caller.
 *
 * ### What the implementation promises
 *
 * - The directory is app-specific storage that no other app can read on that Android version,
 *   and that Android deletes on uninstall.
 * - Any backup an older build left somewhere else on this device has been moved here before
 *   [resolve] returns the first time in a process. A caller may therefore list, write or delete
 *   in it without knowing the history.
 * - The directory is not created: whoever writes into it does that, so a device that never
 *   exports grows no empty directory.
 */
fun interface BackupDirectory {

    /**
     * The directory, ready to list or write into once created.
     *
     * The first call in a process may move files on disk, so it belongs on a background thread.
     * Later calls are cheap.
     */
    fun resolve(): File

    /**
     * Every `backups/` a backup can have been written to, [resolve]'s first.
     *
     * For "Delete all data", which has to find every copy: [resolve] answers for the volume
     * mounted *now*, so it would miss a backup written to the internal fallback while the
     * external volume was away. Defaults to [resolve] alone for an implementation with one root.
     */
    fun everyDirectory(): List<File> = listOf(resolve())

    companion object {

        /**
         * `backups/`, the directory's own name under whichever root [resolve] picks.
         *
         * Public because Android's backup rules must exclude it under both roots, and `:app`'s
         * `BackupRulesTest` builds the paths it expects out of this constant rather than a
         * copy of it.
         */
        const val NAME = "backups"
    }
}
