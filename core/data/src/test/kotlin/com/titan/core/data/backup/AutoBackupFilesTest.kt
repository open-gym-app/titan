package com.titan.core.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Naming and retention: the two rules that decide what a user is left holding three weeks in.
 *
 * Robolectric rather than a temporary folder, because the directory is derived from
 * `getExternalFilesDir` and *which* directory it is — app-specific storage, deleted on
 * uninstall — is half of what the class is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutoBackupFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = AutoBackupFiles(context)

    @Before
    fun emptyTheDirectory() {
        files.directory.deleteRecursively()
    }

    /** Tests run pinned to UTC — see the root build script — so the stamp is the clock's own reading. */
    @Test
    fun `a run is named for the day it was taken`() {
        assertEquals("titan-auto-2026-04-26.json", files.fileFor(SUNDAY).name)
    }

    /**
     * Two runs on one day are one file, on purpose.
     *
     * A retry after a failure, or a schedule that fires twice because the constraints finally
     * cleared, must overwrite the day rather than leave two copies of the same data behind and
     * push a genuinely older backup out of the retention window.
     */
    @Test
    fun `two runs on the same day name the same file`() {
        assertEquals(files.fileFor(SUNDAY).name, files.fileFor(SUNDAY + HOUR_MILLIS).name)
    }

    @Test
    fun `the scratch file is a sibling that is not itself a backup`() {
        val destination = files.fileFor(SUNDAY)
        val scratch = files.scratchFor(destination)

        assertEquals(destination.parentFile, scratch.parentFile)
        assertEquals("${destination.name}.tmp", scratch.name)
    }

    /** The directory is created by the thing about to write into it, not by asking where it is. */
    @Test
    fun `asking for the scratch file creates the directory`() {
        assertFalse(files.directory.exists())

        files.scratchFor(files.fileFor(SUNDAY))

        assertTrue(files.directory.isDirectory)
    }

    @Test
    fun `the newest backups are listed first`() {
        write("titan-auto-2026-04-12.json")
        write("titan-auto-2026-04-26.json")
        write("titan-auto-2026-04-19.json")

        assertEquals(
            listOf("titan-auto-2026-04-26.json", "titan-auto-2026-04-19.json", "titan-auto-2026-04-12.json"),
            files.existing().map { it.name },
        )
    }

    /**
     * A manual export is not one of these, and a half-written run is not either.
     *
     * The prefixes differ for exactly this reason: retention must never delete a backup the
     * user took deliberately to make room for a scheduled one.
     */
    @Test
    fun `only finished automatic backups are listed`() {
        write("titan-auto-2026-04-26.json")
        write("titan-backup-2026-04-26-1432.json")
        write("titan-auto-2026-04-27.json.tmp")
        write("notes.txt")

        assertEquals(listOf("titan-auto-2026-04-26.json"), files.existing().map { it.name })
    }

    @Test
    fun `pruning keeps the newest and deletes the rest`() {
        listOf(
            "titan-auto-2026-04-05.json",
            "titan-auto-2026-04-12.json",
            "titan-auto-2026-04-19.json",
            "titan-auto-2026-04-26.json",
        ).forEach(::write)

        assertEquals(1, files.prune(keep = 3))
        assertEquals(
            listOf("titan-auto-2026-04-26.json", "titan-auto-2026-04-19.json", "titan-auto-2026-04-12.json"),
            files.existing().map { it.name },
        )
    }

    @Test
    fun `pruning leaves a manual export alone`() {
        write("titan-backup-2026-01-01-0900.json")
        write("titan-auto-2026-04-26.json")

        files.prune(keep = 0)

        assertTrue(File(files.directory, "titan-backup-2026-01-01-0900.json").exists())
        assertEquals(emptyList<String>(), files.existing().map { it.name })
    }

    /**
     * A run killed between writing its scratch file and renaming it leaves a partial backup the
     * size of the history, and the next run only reuses the name if it falls on the same day.
     * Pruning takes those with it (#257) — this feature's own, and nothing else's: the manual
     * export's scratch file may be one the backup screen is writing right now.
     */
    @Test
    fun `pruning deletes the scratch files a killed run left, and no one else's`() {
        write("titan-auto-2026-04-19.json.tmp")
        write("titan-auto-2026-04-26.json")
        write("titan-backup-2026-04-26-1432.json.tmp")
        write("notes.tmp")

        assertEquals(1, files.prune(keep = 3))

        assertEquals(
            listOf("notes.tmp", "titan-auto-2026-04-26.json", "titan-backup-2026-04-26-1432.json.tmp"),
            files.directory.list().orEmpty().sorted(),
        )
    }

    /** Fewer than the limit is not a reason to delete anything, and neither is an empty directory. */
    @Test
    fun `pruning under the limit deletes nothing`() {
        write("titan-auto-2026-04-26.json")

        assertEquals(0, files.prune(keep = 3))
        assertEquals(1, files.existing().size)

        files.directory.deleteRecursively()
        assertEquals(0, files.prune(keep = 3))
    }

    private fun write(name: String) {
        files.directory.mkdirs()
        File(files.directory, name).writeText("{}")
    }

    private companion object {

        /** 2026-04-26 00:00 UTC. */
        const val SUNDAY = 1_777_161_600_000L

        const val HOUR_MILLIS = 3_600_000L
    }
}
