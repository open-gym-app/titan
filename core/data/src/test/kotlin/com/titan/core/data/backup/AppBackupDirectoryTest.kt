package com.titan.core.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which root the backups live under on which Android version, and the move that follows an
 * install from one to the other (#259).
 *
 * Robolectric at two levels, because the rule *is* the SDK level: 28 for Android 9, where the
 * external files directory is readable by any app with storage access, and the project's usual
 * 34 for a current phone. The external files directory is a real temporary directory at both,
 * separate from `filesDir`, which is what makes a move between them observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AppBackupDirectoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val internal: File get() = File(context.filesDir, "backups")
    private val external: File get() = File(requireNotNull(context.getExternalFilesDir(null)), "backups")

    @Before
    fun emptyBothRoots() {
        internal.deleteRecursively()
        external.deleteRecursively()
    }

    @Test
    @Config(sdk = [28])
    fun `below Android 10 backups are in internal storage`() {
        assertEquals(internal, AppBackupDirectory(context).resolve())
    }

    @Test
    fun `from Android 10 backups are in the external files directory`() {
        assertEquals(external, AppBackupDirectory(context).resolve())
    }

    @Test
    fun `from Android 10 a device with no external volume keeps them in internal storage`() {
        val noExternalVolume = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }

        assertEquals(internal, AppBackupDirectory(noExternalVolume).resolve())
    }

    /**
     * The upgrade from a build that wrote to shared storage on Android 9: every finished backup,
     * manual and automatic, comes in; a scratch file an interrupted export left is deleted
     * rather than moved, since it is a truncated history; anything else stays where it is.
     */
    @Test
    @Config(sdk = [28])
    fun `below Android 10 an older install's backups are moved out of shared storage`() {
        write(external, "titan-auto-2026-11-01.json", "auto")
        write(external, "titan-backup-2026-11-09-1432.json", "manual")
        write(external, "titan-auto-2026-11-08.json.tmp", "partial")
        write(external, "notes.txt", "not ours")

        val directory = AppBackupDirectory(context).resolve()

        assertEquals(listOf("titan-auto-2026-11-01.json", "titan-backup-2026-11-09-1432.json"), names(directory))
        assertEquals("auto", File(directory, "titan-auto-2026-11-01.json").readText())
        assertEquals("manual", File(directory, "titan-backup-2026-11-09-1432.json").readText())
        assertEquals(listOf("notes.txt"), names(external))
    }

    /** A shared-storage directory with nothing left in it is not left behind. */
    @Test
    @Config(sdk = [28])
    fun `the emptied shared-storage directory is removed`() {
        write(external, "titan-auto-2026-11-01.json", "auto")

        AppBackupDirectory(context).resolve()

        assertFalse(external.exists())
    }

    /**
     * Once per process — one instance is one process, since the graph holds a singleton — and
     * the next process finishes what is left, without disturbing what has already moved.
     */
    @Test
    @Config(sdk = [28])
    fun `the move runs once per process and again harmlessly in the next`() {
        write(external, "titan-auto-2026-11-01.json", "first")
        val process = AppBackupDirectory(context)
        process.resolve()

        write(external, "titan-auto-2026-11-08.json", "second")
        assertEquals(listOf("titan-auto-2026-11-01.json"), names(process.resolve()))

        val nextProcess = AppBackupDirectory(context)
        assertEquals(
            listOf("titan-auto-2026-11-01.json", "titan-auto-2026-11-08.json"),
            names(nextProcess.resolve()),
        )
        assertEquals("first", File(internal, "titan-auto-2026-11-01.json").readText())
        assertFalse(external.exists())
    }

    /** The copy already in place is the one the app has been using; the old one only goes. */
    @Test
    @Config(sdk = [28])
    fun `a backup already in place is kept over the copy in shared storage`() {
        write(internal, "titan-auto-2026-11-01.json", "in place")
        write(external, "titan-auto-2026-11-01.json", "left behind")

        AppBackupDirectory(context).resolve()

        assertEquals("in place", File(internal, "titan-auto-2026-11-01.json").readText())
        assertFalse(external.exists())
    }

    /**
     * A phone that moves to Android 10 with its backups in internal storage: the directory the
     * app reads changes, so the files follow it rather than sitting where no export lists them
     * and "Delete all data" does not look.
     */
    @Test
    fun `from Android 10 backups left in internal storage follow the directory`() {
        write(internal, "titan-backup-2026-11-09-1432.json", "manual")

        val directory = AppBackupDirectory(context).resolve()

        assertEquals(external, directory)
        assertEquals("manual", File(directory, "titan-backup-2026-11-09-1432.json").readText())
        assertFalse(internal.exists())
    }

    @Test
    fun `nothing to move is not a failure, and creates nothing`() {
        AppBackupDirectory(context).resolve()

        assertFalse(internal.exists())
        assertFalse(external.exists())
    }

    private fun write(
        directory: File,
        name: String,
        contents: String,
    ) {
        directory.mkdirs()
        File(directory, name).writeText(contents)
    }

    private fun names(directory: File): List<String> = directory.list().orEmpty().sorted()
}
