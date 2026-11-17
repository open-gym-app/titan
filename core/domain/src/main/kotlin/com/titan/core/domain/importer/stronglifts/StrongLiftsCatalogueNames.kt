package com.titan.core.domain.importer.stronglifts

import com.titan.core.domain.importer.SuffixedExerciseNames

/**
 * Exercises of StrongLifts' built-in library, by the name StrongLifts gives them, mapped to the row
 * of Titan's seeded catalogue each one is.
 *
 * StrongLifts is built around five barbell lifts, and names them as its program does, without the
 * bar: "Squat", "Bench Press", "Barbell Row", "Overhead Press" and "Deadlift". Its older exports
 * write the second word in lower case ("Bench press", "Overhead press"), which the lookup ignores.
 * The other entries are the alternatives its programs swap in and the assistance work seen in real
 * exports. Several more of those already match a catalogue row by name once case and punctuation
 * are ignored ("Pullups", "Pushups", "Barbell Curl", "Romanian Deadlift"), and are matched by
 * [com.titan.core.domain.importer.ExerciseMatcher] without an entry.
 *
 * Every entry is a decision, checked against the catalogue row's instructions, that the two names
 * are one movement with the same equipment. StrongLifts' overhead press is the standing barbell
 * press its guide teaches, so it is the catalogue's standing military press. The rules for leaving
 * a name out are the shared table's, and these stay out:
 *
 * - **A name that could be either of two.** "Dips" is the chest or the triceps version, and the
 *   catalogue has both.
 * - **A different logging type.** StrongLifts' "Planks" are held, and the catalogue's plank counts
 *   reps; the matcher would refuse the match anyway.
 * - **Nothing at all.** "Hanging Knee Raises" is not the catalogue's hanging leg raise.
 *
 * `StrongLiftsCatalogueNamesTest` resolves every entry against the seed itself, so a target renamed
 * or re-equipped by a catalogue update fails a test rather than silently matching nothing.
 */
internal val STRONGLIFTS_CATALOGUE_NAMES: Map<String, String> = mapOf(
    "Squat" to "Barbell Squat",
    "Bench Press" to "Barbell Bench Press - Medium Grip",
    "Barbell Row" to "Bent Over Barbell Row",
    "Overhead Press" to "Standing Military Press",
    "Deadlift" to "Barbell Deadlift",
    "Front Squat" to "Front Barbell Squat",
    "Incline Bench Press" to "Barbell Incline Bench Press - Medium Grip",
    "Close-Grip Bench Press" to "Close-Grip Barbell Bench Press",
    "Chinups" to "Chin-Up",
)

/**
 * The lifts of [STRONGLIFTS_CATALOGUE_NAMES] that are done with a barbell although their name does
 * not say so, as StrongLifts names its program's lifts.
 *
 * [SuffixedExerciseNames] reads the equipment from a name that starts with it ("Barbell Row"), and
 * these do not. Every one of them is a barbell lift in StrongLifts, so the equipment is known
 * anyway, and it is what [com.titan.core.domain.importer.ExerciseMatcher] holds the match to and
 * what a custom exercise would be created with. `StrongLiftsCatalogueNamesTest` holds each to a
 * barbell row of the seed.
 */
internal val STRONGLIFTS_BARBELL_LIFTS: Set<String> = setOf(
    "Squat",
    "Bench Press",
    "Overhead Press",
    "Deadlift",
    "Front Squat",
    "Incline Bench Press",
    "Close-Grip Bench Press",
)
