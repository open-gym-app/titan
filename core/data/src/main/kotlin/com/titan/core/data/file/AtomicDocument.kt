package com.titan.core.data.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One text document in the app's private storage, replaced whole or not at all.
 *
 * The app writes two of these — the in-progress workout and the half-built template — and both
 * are *drafts* in the sense PERSISTENCE.md gives the word: a snapshot of a screen rather than a
 * record of anything that happened, deliberately outside the schema, and rewritten far too often
 * to be worth a migration ladder. What they have in common is the reason this class exists:
 * both are written while the user is doing the thing they describe, so a process kill landing
 * inside a write is not a remote possibility, and the failure mode of writing the live document
 * in place is a truncated one — a workout, or an evening's template, lost to half a JSON object.
 *
 * ### Why the write goes through a scratch file
 *
 * The bytes go to `<name>.tmp` first, are forced to disk, and are then moved onto the document
 * with [StandardCopyOption.ATOMIC_MOVE]. A reader concurrent with that, or one arriving after a
 * kill mid-write, sees the whole previous document or the whole new one. A leftover scratch file
 * is harmless: nothing reads it, and the next write truncates it.
 *
 * The [FileOutputStream.getFD] sync is what makes the rename mean something. A move that lands
 * before the scratch file's own bytes do would publish an empty document, and on a process kill
 * the page cache is not the thing that decides.
 *
 * The scratch file sits *beside* the document rather than in a cache directory, so the move onto
 * it stays a rename within one filesystem: `ATOMIC_MOVE` across mount points degrades into a
 * copy, which is the thing this is here to avoid.
 *
 * ### Why the encoding happens inside the lock
 *
 * [write] takes a *function* rather than a string. The [Mutex] covers two concurrent writers
 * sharing one scratch path, which is what keeps the rename atomic per document rather than
 * merely atomic per rename; `kotlinx`'s mutex is FIFO, so producing the text inside it makes the
 * order the documents land the order [write] was called in. Encoding outside would leave a
 * caller free to encode first, lose the lock race, and then overwrite the newer document with
 * its older one.
 *
 * ### Why every read failure is null
 *
 * An absent file and an unreadable one are the same answer to every caller here: there is no
 * draft to restore. Decoding is the caller's, and it has the same rule — see
 * `ActiveSessionFormat.decode`.
 *
 * @param directory where the document lives. The app's `filesDir` in production; the two
 *   repositories pass it rather than taking a `Context` each.
 * @param name the document's file name. The scratch file is this plus [SCRATCH_SUFFIX], which is
 *   what makes a rename of one a rename of both.
 * @param ioDispatcher what the file work runs on. Every method switches to it, so no caller has
 *   to remember to.
 */
class AtomicDocument(
    private val directory: File,
    private val name: String,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Serialises writers against each other; see the note on the scratch file. */
    private val writeLock = Mutex()

    private val document: File get() = File(directory, name)

    private val scratch: File get() = File(directory, name + SCRATCH_SUFFIX)

    /** Replaces the document with what [text] produces, atomically. */
    suspend fun write(text: () -> String) {
        withContext(ioDispatcher) {
            writeLock.withLock { writeAtomically(text()) }
        }
    }

    /** What the document holds, or **null** when there is none or it cannot be read. */
    suspend fun read(): String? =
        withContext(ioDispatcher) {
            try {
                document.takeIf { it.exists() }?.readText()
            } catch (_: IOException) {
                null
            }
        }

    /**
     * Removes the document, and the scratch file with it.
     *
     * The scratch file is dropped because a write killed between the bytes landing and the
     * rename leaves one behind, and a document the user has discarded should not go on costing
     * disk until the next one is written.
     */
    suspend fun delete() {
        withContext(ioDispatcher) {
            writeLock.withLock {
                document.delete()
                scratch.delete()
            }
        }
    }

    private fun writeAtomically(text: String) {
        FileOutputStream(scratch).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        Files.move(
            scratch.toPath(),
            document.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {

        /**
         * What the scratch file's name adds to the document's.
         *
         * Public because `:app`'s `BackupRulesTest` derives the scratch paths it expects
         * Android's backup rules to exclude from the document names, rather than from literals
         * of its own — see PERSISTENCE.md.
         */
        const val SCRATCH_SUFFIX = ".tmp"
    }
}
