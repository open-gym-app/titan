package com.titan.feature.progression.body

import com.titan.core.domain.model.BodyMeasurement
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * The measurements the body tests are written against, in the canonical units the schema
 * stores: milligrams for a weight, millimetres for a circumference, tenths of a percent for
 * body fat. Named helpers rather than literals, because `78_400_000` and `78_400_000_000` are
 * a thousandfold apart and indistinguishable at a glance.
 */
/** Kilograms in the canonical storage unit, milligrams. */
fun bodyKg(value: Long): Long = value * 1_000_000L

/** Kilograms with a tenth, in milligrams: `78.4` is `bodyKg(78, 4)`. */
fun bodyKg(
    whole: Long,
    tenths: Long,
): Long = whole * 1_000_000L + tenths * 100_000L

/** Centimetres in the canonical storage unit, millimetres. */
fun bodyCm(value: Int): Int = value * 10

/** One entry, taken at midday on [day] so the zone it is read back in cannot move its date. */
@Suppress("LongParameterList")
fun measurementOn(
    day: LocalDate,
    id: Long = day.toEpochDay(),
    weight: Long? = null,
    chest: Int? = null,
    waist: Int? = null,
    arm: Int? = null,
    thigh: Int? = null,
    bodyFat: Int? = null,
    notes: String? = null,
): BodyMeasurement {
    val takenAt = middayMillis(day)
    return BodyMeasurement(
        id = id,
        weight = weight,
        chest = chest,
        waist = waist,
        arm = arm,
        thigh = thigh,
        bodyFat = bodyFat,
        notes = notes,
        createdAt = takenAt,
        updatedAt = takenAt,
    )
}

/** Midday on [date] in the device's zone — the zone the screen derives "today" in. */
fun middayMillis(date: LocalDate): Long =
    date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
