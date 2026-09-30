package com.titan.core.data.repository

import android.content.Context
import com.titan.core.data.di.IoDispatcher
import com.titan.core.data.file.AtomicDocument
import com.titan.core.domain.model.TemplateDraft
import com.titan.core.domain.model.TemplateDraftFormat
import com.titan.core.domain.repository.TemplateDraftRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The half-built template, as one JSON file in the app's private storage.
 *
 * A file rather than a table, and the argument is [InProgressSessionRepositoryImpl]'s: a draft is
 * a snapshot of a screen rather than a record of anything, it is rewritten as the user types, and
 * it is worthless the moment the template is saved — none of which is worth a migration ladder.
 * PERSISTENCE.md names both documents together for that reason. The document itself is the
 * domain's, encoded by [TemplateDraftFormat]; nothing here reads inside it.
 *
 * @see AtomicDocument for the write that survives being killed partway through.
 */
@Singleton
class TemplateDraftRepositoryImpl @Inject constructor(
    @param:ApplicationContext context: Context,
    @param:IoDispatcher ioDispatcher: CoroutineDispatcher,
) : TemplateDraftRepository {

    private val document = AtomicDocument(context.filesDir, DOCUMENT_NAME, ioDispatcher)

    override suspend fun save(draft: TemplateDraft) {
        // Encoded inside the write rather than before it, so the last caller's draft is the one
        // that lands — see `AtomicDocument.write`.
        document.write { TemplateDraftFormat.encode(draft) }
    }

    override suspend fun load(): TemplateDraft? = document.read()?.let(TemplateDraftFormat::decode)

    override suspend fun clear() = document.delete()

    /**
     * The two file names, public for the reason `InProgressSessionRepositoryImpl`'s are: they sit
     * in `files/`, which Android's own backup carries to a new device unless a rule names them,
     * and `:app`'s `BackupRulesTest` builds the excluded paths out of these constants rather than
     * out of literals of its own.
     */
    companion object {
        const val DOCUMENT_NAME = "template-draft.json"

        /** The scratch file the atomic write goes through. See [AtomicDocument]. */
        const val SCRATCH_NAME = DOCUMENT_NAME + AtomicDocument.SCRATCH_SUFFIX
    }
}
