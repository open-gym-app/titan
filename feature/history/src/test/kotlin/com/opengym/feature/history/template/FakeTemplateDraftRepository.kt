package com.opengym.feature.history.template

import com.opengym.core.domain.model.TemplateDraft
import com.opengym.core.domain.model.TemplateDraftFormat
import com.opengym.core.domain.repository.TemplateDraftRepository

/**
 * The template draft slot, in memory.
 *
 * It holds the **encoded document** rather than the object, which is the one thing about it
 * worth explaining: keeping the object would make every test here pass whether or not the draft
 * can actually be written down, and a field that cannot survive a round trip is precisely the
 * bug this store exists to prevent. Storing the JSON means every save and load in every test
 * exercises [TemplateDraftFormat] for free.
 */
internal class FakeTemplateDraftRepository(initial: TemplateDraft? = null) : TemplateDraftRepository {

    private var document: String? = initial?.let(TemplateDraftFormat::encode)

    /** Number of times [clear] was called, including the ones that had nothing to clear. */
    var clearCount = 0
        private set

    /** Set to have every [save] fail, which the editor is required to survive in silence. */
    var savesFail = false

    override suspend fun save(draft: TemplateDraft) {
        if (savesFail) error("the draft store is not writable")
        document = TemplateDraftFormat.encode(draft)
    }

    override suspend fun load(): TemplateDraft? = document?.let(TemplateDraftFormat::decode)

    override suspend fun clear() {
        clearCount++
        document = null
    }

    /** The stored draft as a value, for assertions that are about what was written. */
    val saved: TemplateDraft? get() = document?.let(TemplateDraftFormat::decode)
}
