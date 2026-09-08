package com.opengym.app

import com.opengym.core.data.backup.AutoBackupFiles
import com.opengym.core.data.di.DATABASE_NAME
import com.opengym.core.data.di.DATASTORE_DIRECTORY
import com.opengym.core.data.repository.InProgressSessionRepositoryImpl
import com.opengym.feature.settings.backup.BackupViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Android auto-backup boundary, asserted against the files that ship it (#180).
 *
 * What this guards is drift, and there are two kinds of it. The first is the two rule files
 * disagreeing: the platform reads `data_extraction_rules.xml` on Android 12 and above and
 * `backup_rules.xml` below that, and ignores the other one, so an exclusion added to one
 * file alone is a hole nobody sees on a current phone. The second is a persisted file being
 * renamed or added without the rules following, which puts the user's training history back
 * on Google's servers silently — no build failure, no crash, and nothing visible until a
 * reinstall restores a database over one somebody was using.
 *
 * These are the shipped XML files read off disk rather than resources read through
 * Robolectric, because the manifest is half of what is under test and there is no public API
 * that reads `android:dataExtractionRules` back off an `ApplicationInfo`. Gradle runs a test
 * task with the module directory as its working directory, which is what the paths are
 * relative to.
 */
class BackupRulesTest {

    /**
     * Every path the app persists outside `no_backup/`, as the rule files spell it.
     *
     * **Read off the production constants rather than copied beside them**, which is the
     * difference between this list guarding drift and merely restating it. A hand-written
     * literal here would keep passing through a rename of the file it names — the rules would
     * stop matching anything, no build would fail, and the symptom would be a user's training
     * history on Google's servers with nothing visible until a reinstall wrote a stale copy
     * over a live one. The one string still spelled out is the domain, which is the platform's
     * word and not ours.
     *
     * `no_backup/` is exempt by the platform, so the auto-backup schedule store
     * (`AutoBackupModule`) and WorkManager's own database are deliberately absent — they are
     * excluded by where they live rather than by a line here.
     */
    private val persistedPaths = listOf(
        // The database, and the sidecars that make restoring it corrupting rather than merely
        // stale. SQLite derives all three from the database's own name, so they are derived
        // here too. `-journal` as well as the WAL pair because Room's journal mode is
        // `AUTOMATIC`: WAL on most devices, `TRUNCATE` on a low-RAM one, where the sidecar
        // carries that name instead.
        "database" to DATABASE_NAME,
        "database" to "$DATABASE_NAME-wal",
        "database" to "$DATABASE_NAME-shm",
        "database" to "$DATABASE_NAME-journal",
        // The settings store, excluded as the directory DataStore owns rather than as the one
        // file inside it: it writes a `.tmp` sibling on every commit, and a second store put
        // here later would otherwise travel.
        "file" to DATASTORE_DIRECTORY,
        // The in-progress workout draft and its scratch file.
        "file" to InProgressSessionRepositoryImpl.DOCUMENT_NAME,
        "file" to InProgressSessionRepositoryImpl.SCRATCH_NAME,
        // Exported and automatic backups, in both domains the directory can land in: under the
        // app's external files directory normally, and under `files/` on a device where
        // `getExternalFilesDir` is null and the writers fall back. "Deleted on uninstall" is
        // only true while both lines are here.
        "file" to AutoBackupFiles.BACKUP_DIRECTORY,
        "external" to AutoBackupFiles.BACKUP_DIRECTORY,
    )

    @Test
    fun `manifest wires both rule files`() {
        val application = parse("src/main/AndroidManifest.xml").elements("application").single()

        assertEquals("true", application.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
    }

    @Test
    fun `cloud backup excludes every persisted path`() {
        assertEquals(persistedPaths, excludesIn("data-extraction-rules", "cloud-backup"))
    }

    @Test
    fun `device transfer excludes every persisted path`() {
        assertEquals(persistedPaths, excludesIn("data-extraction-rules", "device-transfer"))
    }

    @Test
    fun `the pre-Android 12 rules exclude the same paths`() {
        assertEquals(persistedPaths, excludesIn("full-backup-content"))
    }

    /**
     * The backup directory is named twice in the app, and this is what makes the pair a
     * contract.
     *
     * `:feature:settings` may not depend on `:core:data` (docs/ARCHITECTURE.md), so the manual
     * export and the weekly automatic one cannot share a constant: `BackupViewModel` spells the
     * directory and so does [AutoBackupFiles], and a comment asking nobody to change one copy
     * is not a contract. `:app` depends on both, which is the whole reason it exists, so the
     * assertion belongs here. Were the two to drift, [persistedPaths] would go on excluding
     * whichever one it reads and the other writer would keep filling a directory that travels.
     */
    @Test
    fun `both writers agree on the directory the rules exclude`() {
        assertEquals(AutoBackupFiles.BACKUP_DIRECTORY, BackupViewModel.BACKUP_DIRECTORY)
    }

    /**
     * An `<include>` anywhere would change what the excludes mean: naming one path in a
     * domain makes that domain include-only, so a file added later is backed up or not
     * depending on a rule written about something else entirely. Both files are exclusion
     * lists over the platform default, and this is what keeps them that way.
     */
    @Test
    fun `negative - neither file includes anything`() {
        for (root in listOf("data-extraction-rules", "full-backup-content")) {
            assertTrue(root, document(root).elements("include").isEmpty())
        }
    }

    private fun excludesIn(
        root: String,
        section: String? = null,
    ): List<Pair<String, String>> {
        val document = document(root)
        val scope = section?.let { document.elements(it).single() } ?: document
        return scope.elements("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }
    }

    private fun document(root: String): Element =
        parse(
            when (root) {
                "data-extraction-rules" -> "src/main/res/xml/data_extraction_rules.xml"
                else -> "src/main/res/xml/backup_rules.xml"
            },
        ).also { assertEquals(root, it.tagName) }

    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        // Namespace-unaware on purpose: it is what makes the manifest's `android:`-prefixed
        // attributes readable by the name they are written under.
        return DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.elements(tagName: String): List<Element> =
        getElementsByTagName(tagName).let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }
}
