package com.titan.core.ui.format

import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.WorkoutSet
import java.util.Locale

/*
 * Fixtures shared by the formatter tests.
 *
 * The formatters are locale-sensitive by design, so every assertion on a literal string has
 * to pin the locale it expects — see [withLocale]. The rest is the small amount of domain a
 * formatter test needs to show that its output feeds `SetValidator`.
 */

/**
 * Runs [block] with [locale] as the default, restoring the previous one afterwards.
 *
 * Not because the formatters should be locale-blind — they should not; a French user gets a
 * decimal comma and that is correct — but because a test asserting on `22.5` would otherwise
 * fail under a CI machine set to `fr-FR`, for the one reason that is not a bug.
 *
 * `Locale.setDefault` sets both the display and the formatting category, which is what the
 * formatters read.
 */
fun withLocale(
    locale: Locale,
    block: () -> Unit,
) {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    try {
        block()
    } finally {
        Locale.setDefault(previous)
    }
}

/**
 * The locales every formatter is asserted under: the one the app is written in and the two
 * it is translated into.
 *
 * Three rather than one because a formatter that is right in English is not thereby right:
 * `fr-FR` moves the decimal separator and translates the inch, `pt-BR` puts a `de` between a
 * month and its year and groups thousands with a full stop. A suite that only ever ran under
 * English would have passed every version of this package, including the ones that built a
 * date by hand.
 *
 * `en-US` rather than `en-GB` for the same reason the screenshot references are recorded in
 * it — see `WriteRobolectricConfig` — and because it is the one of the three that writes its
 * date month-first, which is what a hand-built join gets wrong.
 */
val FORMATTING_LOCALES: List<Locale> = listOf(
    Locale.US,
    Locale.FRANCE,
    Locale.forLanguageTag("pt-BR"),
)

/**
 * A completed strength set carrying [weight] milligrams and [distance] centimetres, so a
 * parsed value can be handed to `SetValidator` the way a form would hand it over.
 */
fun strengthSet(
    weight: Long? = null,
    distance: Long? = null,
): WorkoutSet =
    WorkoutSet(
        sessionExerciseId = 1,
        setNumber = 1,
        reps = 8,
        weight = weight,
        distance = distance,
        setType = SetType.NORMAL,
        completedAt = 0,
        isCompleted = true,
        createdAt = 0,
        updatedAt = 0,
    )

/**
 * The weights a gym can actually load, in [unitSystem]'s own units — the "realistic gym
 * values" the round-trip property is asserted over.
 *
 * Built from the plate grid rather than from arbitrary numbers, because a round trip that
 * held for 37.913 kg and failed for 82.5 kg would pass a random-value test and fail every
 * user. It runs from an empty bar up past a heavy deadlift, on the finest step a
 * well-stocked gym can make.
 */
fun loadableWeights(unitSystem: UnitSystem): List<Double> {
    val step = unitSystem.smallestPlateIncrement
    val steps = (unitSystem.roundTripCeiling / step).toInt()
    return (0..steps).map { it * step }
}

/** Past any lift a user logs: 300 kg, or 660 lb. */
private val UnitSystem.roundTripCeiling: Double
    get() = when (this) {
        UnitSystem.METRIC -> 300.0
        UnitSystem.IMPERIAL -> 660.0
    }
