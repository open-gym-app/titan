package com.titan.feature.settings.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.backup.BackupDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The intent the export hands to the share sheet, and the URI inside it.
 *
 * ### Why this is worth a test rather than a look at the device
 *
 * Because the ways it breaks are silent, and two of them are invisible *on a phone*.
 *
 * - A `FileProvider` whose `file_paths.xml` does not cover the directory the export writes to
 *   throws at the share, a second after a successful backup, with a message about a
 *   "configured root" that means nothing to anybody.
 * - An intent without `FLAG_GRANT_READ_URI_PERMISSION` opens a sheet that works, offers Drive,
 *   and hands Drive a URI it cannot read — which you only find out by picking an app.
 * - An intent without `FLAG_ACTIVITY_NEW_TASK` works from an `Activity` and crashes from
 *   anywhere else. In the app the receiver *is* an activity, so a device can never show it.
 *   This test found it.
 *
 * - A `file_paths.xml` that serves one root and not the other fails only on the Android
 *   versions that write there: internal storage on Android 8 to 10, where no file manager can
 *   reach a backup and the share is one of only two ways out, and the external files directory
 *   from Android 11 (#259).
 *
 * The provider is declared in this module's manifest, so the unit-test manifest carries it as
 * `com.titan.feature.settings.test.fileprovider` — exactly `"$packageName.fileprovider"`,
 * the authority [shareBackup] derives. AGP does that substitution, so this pins it too: an
 * `applicationIdSuffix` that stopped being followed would fail here.
 *
 * What it cannot see is *which apps answer* — a property of the phone, not of the code. On the
 * test device it is twelve, Google Drive and Dropbox among them.
 *
 * ### One test method, on purpose
 *
 * `FileProvider` caches its resolved roots in a static keyed by authority, and Robolectric
 * hands each test *method* a fresh temporary directory. Split across methods, the first one to
 * run seeds that cache and every later one asks about a file under a root that no longer
 * matches — a harness artefact with no device counterpart, since a real external files
 * directory does not move under a running process. So the facts below are asserted about one
 * share from each root, in one method.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSharingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `sharing a backup offers the exported file to another app, readably`() {
        val file = writeBackup(requireNotNull(context.getExternalFilesDir(null)), DOCUMENT)

        context.shareBackup(file)

        val chooser = requireNotNull(shadowOf(context as Application).nextStartedActivity) {
            "sharing should have started an activity"
        }
        assertEquals("the user must be asked which app to send it to", Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(
            "a chooser started from a non-activity context needs FLAG_ACTIVITY_NEW_TASK, and " +
                "this is a Context extension: it must work from any of them",
            chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )

        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertTrue(
            "the share must grant read access to whichever app the user picks",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )

        val uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        // A `file://` URI handed to another app has thrown `FileUriExposedException` since
        // Android 7, and the throw lands in *this* process: the export would take a whole
        // backup successfully and then crash the app giving it away.
        assertEquals("a backup travels as a content URI, never as a file one", "content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)

        // The assertion that earns the test: reading the URI back proves `file_paths.xml`
        // covers the directory backups are written to, that the declared authority is the
        // one the code derives, and that the file is *served* rather than merely named. A
        // provider scoped to the wrong root produces a URI that looks perfectly good and
        // cannot be opened.
        assertEquals(DOCUMENT, read(uri))

        // The same for a backup in internal storage, where Android 8 to 10 keep them.
        val internal = writeBackup(context.filesDir, INTERNAL_DOCUMENT)
        context.shareBackup(internal)
        val internalChooser = requireNotNull(shadowOf(context).nextStartedActivity)
        val internalSend = requireNotNull(internalChooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(
            INTERNAL_DOCUMENT,
            read(requireNotNull(internalSend.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))),
        )
    }

    private fun read(uri: Uri): String {
        val received = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        assertNotNull("the receiving app must be able to open $uri", received)
        return requireNotNull(received)
    }

    /**
     * Writes a backup under `backups/` in [root], one of the two roots `BackupDirectory` picks
     * between, and returns it.
     *
     * The directory name is the shared constant; the root is spelled here on purpose, since
     * which roots the provider serves is the question.
     */
    private fun writeBackup(
        root: File,
        contents: String,
    ): File {
        val directory = File(root, BackupDirectory.NAME)
        directory.mkdirs()
        return File(directory, FILE_NAME).apply { writeText(contents) }
    }

    private companion object {
        const val FILE_NAME = "titan-backup-2026-09-06-1432.json"
        const val DOCUMENT = """{"schema_version":1,"exported_at":1,"app_version":"1.0"}"""
        const val INTERNAL_DOCUMENT = """{"schema_version":1,"exported_at":2,"app_version":"1.0"}"""
    }
}
