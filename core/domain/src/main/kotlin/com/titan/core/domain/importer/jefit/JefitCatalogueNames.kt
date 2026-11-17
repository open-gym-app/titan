package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.SuffixedExerciseNames

/**
 * Exercises of JEFIT's built-in library, by the name JEFIT gives them, mapped to the row of
 * Titan's seeded catalogue each one is.
 *
 * JEFIT names its exercises equipment first, with a variation in a parenthesis after
 * ("Barbell Bench Press", "Cable Tricep Pushdown (Rope)", "Machine Leg Curl (Prone)"), which
 * neither the Strong and Hevy names in `SUFFIXED_CATALOGUE_NAMES` nor the catalogue's own follow.
 * Its library is not published, so the names here are the ones seen in real exports, and a name
 * JEFIT has renamed is listed under each spelling seen ("Cable Rope Tricep Pushdown" and
 * "Cable Tricep Pushdown (Rope)"). A name the catalogue already spells the same way, once case
 * and punctuation are ignored ("Barbell Squat", "Barbell Hip Thrust", "Dumbbell Bench Press",
 * "Lying Leg Curls", "Wide Grip Lat Pulldown"), is matched by
 * [com.titan.core.domain.importer.ExerciseMatcher] without an entry and has none.
 *
 * Every entry is a decision, checked against the catalogue row's instructions, that the two names
 * are one movement with the same equipment, which [SuffixedExerciseNames] reads from the first
 * word of the JEFIT name and the matcher holds the row to. The rules for leaving a name out are
 * the shared table's, and these stay out:
 *
 * - **A different variation.** "Cable Lateral Raise" is standing, and the catalogue's is seated;
 *   "Dumbbell Romanian Deadlift" has no dumbbell row, and "Machine Calf Raise" could be the
 *   standing or the seated machine.
 * - **Different equipment.** "Barbell Lying Triceps Extension" is an EZ-bar row in the catalogue,
 *   and "T Bar Row" whichever machine the gym has.
 * - **A name that could be either of two.** "Dip" is the chest or the triceps version, "Dumbbell
 *   Pullover" bent- or straight-armed, "Rowing" a machine or a boat, "Running" a treadmill or the
 *   road.
 * - **A different logging type.** JEFIT times a plank and a side plank, and the catalogue's count
 *   reps; the matcher would refuse the match anyway.
 * - **The assisted machines.** Their weight is a counterweight, which Titan does not record.
 *
 * `JefitCatalogueNamesTest` resolves every entry against the seed itself, so a target renamed or
 * re-equipped by a catalogue update fails a test rather than silently matching nothing.
 */
internal val JEFIT_CATALOGUE_NAMES: Map<String, String> = mapOf(
    // Barbell
    "Barbell Bench Press" to "Barbell Bench Press - Medium Grip",
    "Barbell Bench Press (Close Grip)" to "Close-Grip Barbell Bench Press",
    "Barbell Incline Bench Press" to "Barbell Incline Bench Press - Medium Grip",
    "Barbell Bent Over Row" to "Bent Over Barbell Row",
    "Barbell Military Press" to "Standing Military Press",
    "Barbell Push Press" to "Push Press",
    "Barbell Romanian Deadlift" to "Romanian Deadlift",
    "Barbell Preacher Curl" to "Preacher Curl",
    "Barbell Standing Calf Raise" to "Standing Barbell Calf Raise",
    // Dumbbell
    "Dumbbell Incline Bench Press" to "Incline Dumbbell Press",
    "Dumbbell Fly" to "Dumbbell Flyes",
    "Dumbbell Seated Shoulder Press" to "Dumbbell Shoulder Press",
    "Dumbbell Lateral Raise" to "Side Lateral Raise",
    "Dumbbell Upright Row" to "Standing Dumbbell Upright Row",
    "Dumbbell One-Arm Row" to "One-Arm Dumbbell Row",
    "Dumbbell Bent-Over Row" to "Bent Over Two-Dumbbell Row",
    "Dumbbell Straight Arm Pullover" to "Straight-Arm Dumbbell Pullover",
    "Dumbbell Alternating Bicep Curl" to "Dumbbell Alternate Bicep Curl",
    "Dumbbell Hammer Curl" to "Hammer Curls",
    "Dumbbell Alternating Hammer Curl" to "Alternate Hammer Curl",
    "Dumbbell Incline Curl" to "Incline Dumbbell Curl",
    "Dumbbell Reverse Bicep Curl" to "Standing Dumbbell Reverse Curl",
    "Dumbbell Tricep Kickback" to "Tricep Dumbbell Kickback",
    "Dumbbell Lunge" to "Dumbbell Lunges",
    "Dumbbell Step-Up" to "Dumbbell Step Ups",
    "Dumbbell Stiff-Leg Deadlift" to "Stiff-Legged Dumbbell Deadlift",
    "Dumbbell Standing Calf Raise" to "Standing Dumbbell Calf Raise",
    // Cable
    "Cable Cross-Over" to "Cable Crossover",
    "Cable Incline Fly" to "Incline Cable Flye",
    "Cable Lat Pulldown (Wide Grip)" to "Wide-Grip Lat Pulldown",
    "Cable Seated Row" to "Seated Cable Rows",
    "Cable One-Arm Seated Row" to "Seated One-arm Cable Pulley Rows",
    "Cable Rope Face Pull" to "Face Pull",
    "Cable Bicep Curl" to "Standing Biceps Cable Curl",
    "Cable Rope Tricep Pushdown" to "Triceps Pushdown - Rope Attachment",
    "Cable Tricep Pushdown (Rope)" to "Triceps Pushdown - Rope Attachment",
    "Triceps Pushdown - Rope" to "Triceps Pushdown - Rope Attachment",
    "Cable Tricep Pushdown (V-Bar)" to "Triceps Pushdown - V-Bar Attachment",
    "Cable Tricep Pushdown (Reverse Grip)" to "Reverse Grip Triceps Pushdown",
    "Cable Rope Overhead Tricep Extension" to "Cable Rope Overhead Triceps Extension",
    // Machine
    "Machine Fly" to "Butterfly",
    "Machine Reverse Fly" to "Reverse Machine Flyes",
    "Machine Leg Press" to "Leg Press",
    "Machine Leg Extension" to "Leg Extensions",
    "Machine Single-Leg Extension" to "Single-Leg Leg Extension",
    "Machine Seated Leg Curl" to "Seated Leg Curl",
    "Machine Leg Curl (Prone)" to "Lying Leg Curls",
    "Machine Hip Abduction" to "Thigh Abductor",
    "Machine Hip Adduction" to "Thigh Adductor",
    "Machine Seated Calf Raise" to "Seated Calf Raise",
    "Calf Press On Leg Press" to "Calf Press On The Leg Press Machine",
    "Machine Ab Crunch" to "Ab Crunch Machine",
    // Bodyweight
    "Pull-Up" to "Pullups",
    "Push-Up" to "Pushups",
    "Bench Dip" to "Bench Dips",
    "Crunch" to "Crunches",
    // Kettlebell
    "Kettlebell Goblet Squat" to "Goblet Squat",
    // Cardio
    "Treadmill Running" to "Running, Treadmill",
)
