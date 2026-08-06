package com.opengym.core.domain.repository

import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSessionFormat

/**
 * In-memory [InProgressSessionRepository] for domain tests, standing in for whatever
 * `:core:data` ends up storing the draft in.
 *
 * It holds the **encoded document**, not the [ActiveSession] object, which is the one
 * thing about it worth explaining. Keeping the object would make every test that uses
 * this fake pass whether or not the draft can actually be written down — and a field
 * that cannot survive a round trip is precisely the bug this contract exists to
 * prevent. Storing the JSON means every save/load in every test exercises
 * [ActiveSessionFormat] for free, so a `@Transient` added in a hurry fails a test about
 * something else rather than shipping.
 *
 * Not thread-safe, and not trying to be: the atomicity [save] owes in production is a
 * statement about a process dying mid-write, which a `var` cannot fail to satisfy.
 */
class FakeInProgressSessionRepository(initial: ActiveSession? = null) : InProgressSessionRepository {

    private var document: String? = initial?.let(ActiveSessionFormat::encode)

    /** Number of times [save] was called, to catch writes that should not have happened. */
    var saveCount = 0
        private set

    /** Number of times [clear] was called, including the ones that had nothing to clear. */
    var clearCount = 0
        private set

    override suspend fun save(session: ActiveSession) {
        saveCount++
        document = ActiveSessionFormat.encode(session)
    }

    override suspend fun load(): ActiveSession? = document?.let(ActiveSessionFormat::decode)

    override suspend fun clear() {
        clearCount++
        document = null
    }

    /** The raw stored document, for tests that assert on the format rather than the value. */
    val stored: String? get() = document

    /**
     * Replaces the stored document with [text] without going through [save], so a test
     * can stage the corrupt or stale draft a real store could hand back.
     */
    fun corrupt(text: String) {
        document = text
    }
}
