package com.titan.core.domain.importer.fitbod

import com.titan.core.domain.importer.SuffixedExerciseNames

/**
 * Exercises of Fitbod's built-in library, by the name Fitbod gives them, mapped to the row of
 * Titan's seeded catalogue each one is.
 *
 * Fitbod names its exercises in plain words, the equipment first when it names one ("Dumbbell
 * Incline Fly", "Machine Rear Delt Fly", "Cable Face Pull") and left out for the barbell and
 * bodyweight classics ("Back Squat", "Deadlift", "Push Up"). Its library is not published, so the
 * names here are the ones seen in real exports. About ninety more of those already match a row
 * by name once case and punctuation are ignored ("Barbell Curl", "Romanian Deadlift", "Chin Up",
 * "Hammer Curls", "Running - Treadmill"), and are matched by
 * [com.titan.core.domain.importer.ExerciseMatcher] without an entry.
 *
 * A few of these names are also in the Strong and Hevy, FitNotes or JEFIT tables ("Push Up",
 * "Dumbbell Row", "Machine Fly"), and name the same row there. This table repeats them rather than
 * taking in another app's table for their sake, because the rest of that table is another
 * library's vocabulary, and a name that meant one movement there could mean another here.
 *
 * Every entry is a decision, checked against the catalogue row's instructions, that the two names
 * are one movement with the same equipment, which [SuffixedExerciseNames] reads from the first
 * word of the Fitbod name and the matcher holds the row to. Where Fitbod leaves the equipment
 * unsaid, its library names the other variants explicitly ("Kettlebell Front Squat", "Dumbbell
 * Stiff Legged Deadlift"), so the unqualified name is the barbell lift. The rules for leaving a
 * name out are the shared table's, and these stay out:
 *
 * - **A name Fitbod uses beside a more specific one.** "Lat Pulldown" sits beside "Wide Grip Lat
 *   Pulldown", "Hip Thrust" beside "Barbell Hip Thrust", and "Bent Over Row" beside "Bent Over
 *   Barbell Row", so the plain name is not the specific row.
 * - **A different variation or equipment.** "Cable Lateral Raise" is standing, and the
 *   catalogue's is seated; "Dumbbell Goblet Squat" is a kettlebell row in the catalogue, and
 *   "Dumbbell Romanian Deadlift" has none.
 * - **A name that could be either of two.** "Skullcrusher" is a barbell or an EZ-bar lift,
 *   "Dumbbell Pullover" bent- or straight-armed, "Leg Curl" lying or seated, "Cable Tricep
 *   Pushdown" any attachment, "Tricep Kickback" a dumbbell or a cable, "Rowing" a machine or a
 *   boat.
 * - **A different logging type.** Fitbod times a plank, a side bridge and a stability-ball hip
 *   bridge, and the catalogue's count reps; the matcher would refuse the match anyway.
 * - **Plate-loaded machines.** "Hammerstrength Chest Press" and its incline and decline are not
 *   the catalogue's selectorised machines.
 *
 * `FitbodCatalogueNamesTest` resolves every entry against the seed itself, so a target renamed or
 * re-equipped by a catalogue update fails a test rather than silently matching nothing.
 */
internal val FITBOD_CATALOGUE_NAMES: Map<String, String> = mapOf(
    // Barbell
    "Back Squat" to "Barbell Squat",
    "Front Squat" to "Front Barbell Squat",
    "Deadlift" to "Barbell Deadlift",
    "Stiff-Legged Deadlift" to "Stiff-Legged Barbell Deadlift",
    "Barbell Bench Press" to "Barbell Bench Press - Medium Grip",
    "Barbell Incline Bench Press" to "Barbell Incline Bench Press - Medium Grip",
    "Barbell Decline Bench Press" to "Decline Barbell Bench Press",
    "Close-Grip Bench Press" to "Close-Grip Barbell Bench Press",
    // Dumbbell
    "Dumbbell Incline Bench Press" to "Incline Dumbbell Press",
    "Dumbbell Decline Bench Press" to "Decline Dumbbell Bench Press",
    "Dumbbell Fly" to "Dumbbell Flyes",
    "Dumbbell Incline Fly" to "Incline Dumbbell Flyes",
    "Dumbbell Front Raise" to "Front Dumbbell Raise",
    "Dumbbell Lateral Raise" to "Side Lateral Raise",
    "Dumbbell Upright Row" to "Standing Dumbbell Upright Row",
    "Dumbbell Row" to "One-Arm Dumbbell Row",
    "Dumbbell Bent Over Row" to "Bent Over Two-Dumbbell Row",
    "Dumbbell Curl" to "Dumbbell Bicep Curl",
    "Reverse Dumbbell Curl" to "Standing Dumbbell Reverse Curl",
    "Incline Hammer Curl" to "Incline Hammer Curls",
    "Concentration Curl" to "Concentration Curls",
    "Dumbbell Skullcrusher" to "Lying Dumbbell Tricep Extension",
    "Dumbbell Lunge" to "Dumbbell Lunges",
    "Dumbbell Stiff Legged Deadlift" to "Stiff-Legged Dumbbell Deadlift",
    // Cable
    "Cable Bicep Curl" to "Standing Biceps Cable Curl",
    "Cable Face Pull" to "Face Pull",
    "Cable Pull Through" to "Pull Through",
    "Cable Crossover Fly" to "Cable Crossover",
    "Cable Upright Row" to "Upright Cable Row",
    "Seated Cable Row" to "Seated Cable Rows",
    // Machine
    "Machine Fly" to "Butterfly",
    "Machine Rear Delt Fly" to "Reverse Machine Flyes",
    "Machine Shoulder Press" to "Machine Shoulder (Military) Press",
    "Machine Leg Press" to "Leg Press",
    "Leg Extension" to "Leg Extensions",
    "Lying Hamstrings Curl" to "Lying Leg Curls",
    "Machine Hip Abductor" to "Thigh Abductor",
    "Machine Thigh Abductor" to "Thigh Abductor",
    "Machine Hip Adductor" to "Thigh Adductor",
    "Machine Thigh Adductor" to "Thigh Adductor",
    "Seated Machine Calf Press" to "Seated Calf Raise",
    // Bodyweight
    "Push Up" to "Pushups",
    "Pull Up" to "Pullups",
    "V-Bar Pull Up" to "V-Bar Pullup",
    "Tricep Dip" to "Dips - Triceps Version",
    "Bench Dip" to "Bench Dips",
    "Air Squats" to "Bodyweight Squat",
    "Back Extensions" to "Hyperextensions (Back Extensions)",
    "Mountain Climber" to "Mountain Climbers",
    "Oblique Crunch" to "Oblique Crunches",
    "Bicycle Crunch" to "Air Bike",
    "Ab Rollout" to "Ab Roller",
)
