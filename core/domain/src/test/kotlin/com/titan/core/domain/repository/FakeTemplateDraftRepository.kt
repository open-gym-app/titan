package com.titan.core.domain.repository

import com.titan.core.domain.model.TemplateDraft
import com.titan.core.domain.model.TemplateDraftFormat

/**
 * In-memory [TemplateDraftRepository] for domain tests, standing in for whatever `:core:data`
 * ends up storing the draft in.
 *
 * [FakeInProgressSessionRepository]'s shape, and it holds the **encoded document** for that
 * one's reason: keeping the object would make every test pass whether or not the draft can
 * actually be written down, and a field that cannot survive a round trip is precisely the bug
 * this contract exists to prevent. Storing the JSON means every save/load exercises
 * [TemplateDraftFormat] for free.
 */
class FakeTemplateDraftRepository(initial: TemplateDraft? = null) : TemplateDraftRepository {

    private var document: String? = initial?.let(TemplateDraftFormat::encode)

    /** Number of times [save] was called, to catch writes that should not have happened. */
    var saveCount = 0
        private set

    /** Number of times [clear] was called, including the ones that had nothing to clear. */
    var clearCount = 0
        private set

    override suspend fun save(draft: TemplateDraft) {
        saveCount++
        document = TemplateDraftFormat.encode(draft)
    }

    override suspend fun load(): TemplateDraft? = document?.let(TemplateDraftFormat::decode)

    override suspend fun clear() {
        clearCount++
        document = null
    }

    /** The raw stored document, for tests that assert on the format rather than the value. */
    val stored: String? get() = document

    /**
     * Replaces the stored document with [text] without going through [save], so a test can
     * stage the corrupt or stale draft a real store could hand back.
     */
    fun corrupt(text: String) {
        document = text
    }
}
