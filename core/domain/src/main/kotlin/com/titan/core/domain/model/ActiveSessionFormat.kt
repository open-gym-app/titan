package com.titan.core.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The wire format of an [ActiveSession] draft: kotlinx.serialization JSON, defined
 * here in the domain so the layer that stores it does not get to invent one.
 *
 * The format is deliberately a *string*, not a data-layer type. Whether the draft ends
 * up in a file, in DataStore or in a one-row table is
 * [com.titan.core.domain.repository.InProgressSessionRepository]'s implementation's
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
 * [decode] takes that literally: it reads the envelope with the session left as an
 * uninterpreted [JsonElement], checks the version, and only then decodes the session
 * against *this* build's model. A future document whose session cannot be parsed by
 * this build is therefore refused for the reason it should be — the version — rather
 * than incidentally, by the decoder tripping over a shape it does not recognise. Today
 * both paths return null and nothing can tell them apart; the day an up-converter is
 * added, the version is what it will dispatch on, and it has to be legible before the
 * session is.
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
     * [com.titan.core.domain.timer.RestTimerSnapshot] would make an existing draft
     * decode into something other than what was saved — a renamed or retyped field, a
     * default that changes meaning. Adding a field with a default does not need a bump;
     * `ignoreUnknownKeys` and `encodeDefaults` already make that case survivable in
     * both directions.
     */
    const val VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        // Already the library default. Spelled out because this is a *persisted* format:
        // a discriminator that changed with a library upgrade would silently stop every
        // stored draft's rest timer from decoding.
        classDiscriminator = "type"
    }

    /** The envelope as it is written: the session encoded against this build's model. */
    private val envelopeSerializer = Envelope.serializer(ActiveSession.serializer())

    /** The envelope as it is read: the session left uninterpreted until the version is checked. */
    private val rawEnvelopeSerializer = Envelope.serializer(JsonElement.serializer())

    /** [session] as a JSON document, stamped with [VERSION]. */
    fun encode(session: ActiveSession): String = json.encodeToString(envelopeSerializer, Envelope(VERSION, session))

    /**
     * The draft [text] holds, or **null** when it does not hold one this build can
     * read — malformed JSON, a version other than [VERSION], or a document that decodes
     * into a value the model's own invariants refuse (an index past the end of the
     * exercise list, a negative paused duration).
     *
     * Null rather than an exception, because every one of those cases means the same
     * thing to the caller: there is no draft to restore. That is exactly the answer
     * [com.titan.core.domain.repository.InProgressSessionRepository.load] gives for
     * an empty store, and a corrupt draft must not be the reason the app cannot start.
     *
     * The version is read before the session is interpreted, which is what makes the
     * envelope worth having; see the note on this object.
     */
    fun decode(text: String): ActiveSession? =
        try {
            val envelope = json.decodeFromString(rawEnvelopeSerializer, text)
            when (envelope.version) {
                VERSION -> json.decodeFromJsonElement(ActiveSession.serializer(), envelope.session)
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            // Covers both halves of "unreadable": [SerializationException] *is* an
            // IllegalArgumentException, and a model invariant refusing the decoded
            // values throws a plain one from the constructor the decoder calls.
            null
        }

    /**
     * The versioned wrapper. Private because the version is this object's to manage.
     *
     * Generic in its payload so the same declaration serves both directions: [encode]
     * instantiates it at [ActiveSession], [decode] at [JsonElement] so that a session it
     * cannot parse does not stop it reading the version.
     */
    @Serializable
    private data class Envelope<T>(
        val version: Int,
        val session: T,
    )
}
