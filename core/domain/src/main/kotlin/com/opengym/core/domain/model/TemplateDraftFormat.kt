package com.opengym.core.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The wire format of a [TemplateDraft]: kotlinx.serialization JSON, defined here in the domain
 * so the layer that stores it does not get to invent one.
 *
 * [ActiveSessionFormat]'s shape, decision for decision, because it is the same problem: a
 * snapshot of a screen written outside the schema, read back by a build that may not be the one
 * that wrote it, and disposable enough that refusing a document is better than misreading one.
 * The envelope is `{"version": N, "draft": {...}}`, the version sits outside the payload so that
 * reading it never depends on the shape of what it describes, and nothing here up-converts.
 *
 * What differs is what a refusal costs. Losing a session draft costs the user their place in a
 * workout whose sets are in the database either way; losing this costs them the template they
 * were building, which is nowhere else. That is an argument for bumping [VERSION] rarely and for
 * `ignoreUnknownKeys` doing the work instead — not for reading a document this build does not
 * understand, which would put a plan the user did not write in front of them.
 */
object TemplateDraftFormat {

    /**
     * The version stamped into every document [encode] writes and demanded by every document
     * [decode] reads.
     *
     * Bump it when a change to [TemplateDraft], [TemplateDraftExercise] or [TemplateDraftSet]
     * would make an existing draft decode into something other than what was saved — a renamed
     * or retyped field, a default that changes meaning. Adding a field with a default does not
     * need a bump; `ignoreUnknownKeys` and `encodeDefaults` already make that case survivable in
     * both directions.
     */
    const val VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val envelopeSerializer = Envelope.serializer(TemplateDraft.serializer())

    private val rawEnvelopeSerializer = Envelope.serializer(JsonElement.serializer())

    /** [draft] as a JSON document, stamped with [VERSION]. */
    fun encode(draft: TemplateDraft): String = json.encodeToString(envelopeSerializer, Envelope(VERSION, draft))

    /**
     * The draft [text] holds, or **null** when it does not hold one this build can read —
     * malformed JSON, a version other than [VERSION], or a document that decodes into a value
     * the model's own invariants refuse.
     *
     * Null rather than an exception, because every one of those cases means the same thing to
     * the caller: there is no draft to restore, and the editor opens on what the database holds.
     */
    fun decode(text: String): TemplateDraft? =
        try {
            val envelope = json.decodeFromString(rawEnvelopeSerializer, text)
            when (envelope.version) {
                VERSION -> json.decodeFromJsonElement(TemplateDraft.serializer(), envelope.draft)
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            // `SerializationException` *is* an `IllegalArgumentException`, and a model invariant
            // refusing the decoded values throws a plain one from the constructor the decoder
            // calls. Both are "there is no draft here".
            null
        }

    /**
     * The versioned wrapper. Private because the version is this object's to manage.
     *
     * Generic in its payload so the same declaration serves both directions: [encode]
     * instantiates it at [TemplateDraft], [decode] at [JsonElement] so that a draft it cannot
     * parse does not stop it reading the version.
     */
    @Serializable
    private data class Envelope<T>(
        val version: Int,
        val draft: T,
    )
}
