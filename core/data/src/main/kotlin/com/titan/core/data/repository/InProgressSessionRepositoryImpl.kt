package com.titan.core.data.repository

import android.content.Context
import com.titan.core.data.di.IoDispatcher
import com.titan.core.data.file.AtomicDocument
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.ActiveSessionFormat
import com.titan.core.domain.repository.InProgressSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
 * The atomic overwrite [InProgressSessionRepository.save] owes is [AtomicDocument]'s,
 * which is also what the half-built template is written through — the two drafts are
 * the same kind of thing and had the same file-handling to get right.
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
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : InProgressSessionRepository {

    private val document = AtomicDocument(context.filesDir, DOCUMENT_NAME, ioDispatcher)

    override suspend fun save(session: ActiveSession) {
        // Encoded inside the write rather than before it, which is what makes the last
        // caller's draft the one that lands — see `AtomicDocument.write`.
        document.write { ActiveSessionFormat.encode(session) }
    }

    override suspend fun load(): ActiveSession? = document.read()?.let(ActiveSessionFormat::decode)

    override suspend fun clear() = document.delete()

    /**
     * The two file names, public because `:app` reads them.
     *
     * Both sit in `files/`, which Android's own backup carries to a new device unless a rule
     * names them — so `:app`'s `BackupRulesTest` builds the paths it expects those rules to
     * exclude out of these constants rather than out of literals of its own. A rename here
     * fails that test, which is the only signal there would ever be: the wrong outcome is a
     * half-finished workout from another phone restored over the one in front of the user,
     * and nothing about it looks like a bug until it happens.
     */
    companion object {
        const val DOCUMENT_NAME = "in-progress-session.json"

        /** The scratch file the atomic write goes through. See [AtomicDocument]. */
        const val SCRATCH_NAME = DOCUMENT_NAME + AtomicDocument.SCRATCH_SUFFIX
    }
}
