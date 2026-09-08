package com.opengym.core.ui.state

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/**
 * A [LocalDate] in saved screen state, as its epoch day.
 *
 * `java.time` types carry no serializer of their own, and a screen state that holds a date
 * needs one to be saveable at all — the sessions tab's date filter is the case that asked for
 * it. Epoch day rather than ISO text because it is what a date *is* to everything downstream:
 * one number, ordered, with no locale, no time zone and no parse to get wrong.
 *
 * Deliberately not a general-purpose date format. Nothing persisted long-term uses this — the
 * database stores instants as epoch millis (PERSISTENCE.md) and the export envelope has its own
 * spelling. This is for state that is written by one build and read back by the same one; see
 * [savedScreenState] for why that is always true of saved instance state.
 *
 * Apply it at the field, with `@Serializable(with = LocalDateSerializer::class)`, so that a
 * state class holding a date says so where the date is rather than through a serializers module
 * that has to be threaded to every call site.
 */
object LocalDateSerializer : KSerializer<LocalDate> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.opengym.LocalDate", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: LocalDate,
    ) = encoder.encodeLong(value.toEpochDay())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.ofEpochDay(decoder.decodeLong())
}
