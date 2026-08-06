package com.opengym.core.data.repository

import android.content.Context
import com.opengym.core.data.di.IoDispatcher
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSessionFormat
import com.opengym.core.domain.repository.InProgressSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-progress workout draft, as one JSON file in the app's private storage.
 *
 * A file rather than a table because the draft is deliberately outside the schema
 * (PERSISTENCE.md): it is a snapshot of a screen, not a record of a workout, and it
 * is rewritten on every logged set — far too often to be worth a migration ladder,
 * and pointless to keep once the session is closed out. The document itself is the
 * domain's, encoded by [ActiveSessionFormat]; nothing here reads inside it.
 *
 * ### Why the write goes through a scratch file
 *
 * [save] owes an atomic overwrite, and that is a real requirement rather than a
 * formality: it is called often enough that a process kill landing inside a write
 * is not a remote possibility, and the failure mode of writing the live document in
 * place is a truncated one — a workout lost to half a JSON object. So the bytes go
 * to [SCRATCH_NAME] first, are forced to disk, and are then moved onto
 * [DOCUMENT_NAME] with [StandardCopyOption.ATOMIC_MOVE]. A reader concurrent with
 * that, or one arriving after a kill mid-write, sees the whole previous draft or
 * the whole new one. A leftover scratch file is harmless: nothing reads it, and the
 * next save truncates it.
 *
 * The [Mutex] covers the other half — two concurrent [save]s share one scratch
 * path, so serialising them is what keeps the rename atomic *per draft* rather than
 * merely atomic per rename. Encoding happens *inside* it for the same reason the
 * write does: the contract says the last write wins, and `kotlinx` [Mutex] is FIFO,
 * so holding it across the encode makes the order the drafts land the order [save]
 * was called in. Encoding outside would leave a caller free to encode first, lose
 * the lock race, and then overwrite the newer draft with its older one.
 *
 * ### Why every failure is null
 *
 * [load] returns null for an absent file, an unreadable one and a document this
 * build cannot decode alike, per the interface contract. All three mean "no draft
 * to restore", the caller does the same thing in each, and a draft that cannot be
 * read must never be the reason the app fails to start — the sets are in
 * `workout_set` either way.
 */
@Singleton
class InProgressSessionRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : InProgressSessionRepository {

    /** Serialises writers against each other; see the note on the scratch file. */
    private val writeLock = Mutex()

    private val document: File get() = File(context.filesDir, DOCUMENT_NAME)

    private val scratch: File get() = File(context.filesDir, SCRATCH_NAME)

    override suspend fun save(session: ActiveSession) {
        withContext(ioDispatcher) {
            writeLock.withLock { writeAtomically(ActiveSessionFormat.encode(session)) }
        }
    }

    override suspend fun load(): ActiveSession? = withContext(ioDispatcher) {
        val text = try {
            document.takeIf { it.exists() }?.readText()
        } catch (_: IOException) {
            // Unreadable is indistinguishable from absent to a caller, by contract.
            null
        }
        text?.let(ActiveSessionFormat::decode)
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            writeLock.withLock {
                document.delete()
                // A save killed between the write and the rename leaves this behind;
                // dropping it here keeps a discarded session from costing disk until
                // the next workout starts.
                scratch.delete()
            }
        }
    }

    /**
     * Replaces the document with [text] in a way that survives being killed partway.
     *
     * The [FileOutputStream.getFD] sync is what makes the rename mean something: a
     * move that lands before the scratch file's own bytes do would publish an empty
     * document, and on a process kill the page cache is not the thing that decides.
     */
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

    private companion object {
        const val DOCUMENT_NAME = "in-progress-session.json"

        /**
         * Sits next to the document rather than in a cache directory so the move onto
         * it stays a rename within one filesystem — `ATOMIC_MOVE` across mount points
         * degrades into a copy, which is the thing this is here to avoid.
         */
        const val SCRATCH_NAME = "in-progress-session.json.tmp"
    }
}
