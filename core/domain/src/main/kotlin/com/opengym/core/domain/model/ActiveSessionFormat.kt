package com.opengym.core.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire format of an [ActiveSession] draft: kotlinx.serialization JSON, defined
 * here in the domain so the layer that stores it does not get to invent one.
 *
 * The format is deliberately a *string*, not a data-layer type. Whether the draft ends
 * up in a file, in DataStore or in a one-row table is
 * [com.opengym.core.domain.repository.InProgressSessionRepository]'s implementation's
 * business; all it needs from here is [encode] and [decode], and nothing in this file
 * knows that any of those exist.
 *
 * ### The envelope
 *
 * The document is `{"version": N, "session": {...}}`. The version is outside the
 * session so that reading it never depends on the shape of what it describes, which is
 * the point of having it: a draft written by a build that laid the fields out
 * differently is *rejected*, not misread. PERSISTENCE.md requires the same of the
 * export envelope, for the same reason — a Room migration ladder upgrades a database
 * file, not a JSON document, and nothing here up-converts.
 *
 * Rejecting is cheap precisely because a draft is disposable. It is a snapshot of a
 * workout in progress, not a record of one — the session rows are the record — so
 * losing one costs the user the exercise they were on and their rest countdown, and an
 * app version that can read a draft it does not understand is a worse trade than that.
 *
 * ### Defaults and unknown fields
 *
 * `encodeDefaults` is on, so every field is written even when it holds its default.
 * A draft is small, written rarely, and read by a human when something has gone wrong;
 * a complete document is worth more than the bytes it saves. `ignoreUnknownKeys` is on
 * as the matching tolerance in the other direction — within a single [VERSION], a field
 * dropped from the model does not make an existing draft unreadable.
 */
object ActiveSessionFormat {

    /**
     * The version stamped into every document [encode] writes and demanded by every
     * document [decode] reads.
     *
     * Bump it when a change to [ActiveSession], [ActiveExercise], [ActiveSet] or
     * [com.opengym.core.domain.timer.RestTimerSnapshot] would make an existing draft
     * decode into something other than what was saved — a renamed or retyped field, a
     * default that changes meaning. Adding a field with a default does not need a bump;
     * `ignoreUnknownKeys` and `encodeDefaults` already make that case survivable in
     * both directions.
     */
    const val VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    /** [session] as a JSON document, stamped with [VERSION]. */
    fun encode(session: ActiveSession): String = json.encodeToString(Envelope(VERSION, session))

    /**
     * The draft [text] holds, or **null** when it does not hold one this build can
     * read — malformed JSON, a version other than [VERSION], or a document that decodes
     * into a value the model's own invariants refuse (an index past the end of the
     * exercise list, a negative paused duration).
     *
     * Null rather than an exception, because every one of those cases means the same
     * thing to the caller: there is no draft to restore. That is exactly the answer
     * [com.opengym.core.domain.repository.InProgressSessionRepository.load] gives for
     * an empty store, and a corrupt draft must not be the reason the app cannot start.
     */
    fun decode(text: String): ActiveSession? =
        try {
            json.decodeFromString<Envelope>(text).takeIf { it.version == VERSION }?.session
        } catch (_: IllegalArgumentException) {
            // Covers both halves of "unreadable": [SerializationException] *is* an
            // IllegalArgumentException, and a model invariant refusing the decoded
            // values throws a plain one from the constructor the decoder calls.
            null
        }

    /** The versioned wrapper. Private because the version is this object's to manage. */
    @Serializable
    private data class Envelope(val version: Int, val session: ActiveSession)
}
