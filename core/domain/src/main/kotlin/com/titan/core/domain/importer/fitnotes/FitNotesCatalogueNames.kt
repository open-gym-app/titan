package com.titan.core.domain.importer.fitnotes

import com.titan.core.domain.importer.SuffixedExerciseNames

/**
 * The exercises FitNotes puts in a new install's library, by the name FitNotes gives them, mapped
 * to the row of Titan's seeded catalogue each one is.
 *
 * FitNotes names its exercises in plain words, with the equipment inside the name ("Flat Barbell
 * Bench Press", "Seated Leg Curl Machine") rather than in a parenthesis after it, so few of its
 * names are among the Strong and Hevy names in `SUFFIXED_CATALOGUE_NAMES`. The few that are, once
 * punctuation is ignored ("Pull Up", "Crunch", "Leg Extension Machine" as "Leg Extension
 * (Machine)"), name the same row in both tables, and this table repeats them rather than taking
 * in the shared one for their sake. A name the catalogue already spells
 * the same way, once case and punctuation are ignored ("Barbell Squat", "Chin Up",
 * "Running (Treadmill)"), is matched by [com.titan.core.domain.importer.ExerciseMatcher] without
 * an entry and has none.
 *
 * Every entry is a decision, checked against the catalogue row's instructions, that the two names
 * are one movement with the same equipment. Where FitNotes leaves the equipment unsaid, its
 * library names the other variants explicitly ("Dumbbell Row", "Seated Dumbbell Press"), so the
 * unqualified name is the barbell lift: "Deadlift" is the catalogue's "Barbell Deadlift". The
 * rules for leaving a name out are the shared table's, and these stay out:
 *
 * - **Plate-loaded machines.** "Hammer Strength Shoulder Press" and its chest press and row are
 *   not the catalogue's selectorised machines.
 * - **A name that could be either of two.** "Lying Triceps Extension" is a barbell or an EZ-bar
 *   lift, "Rear Delt Dumbbell Raise", "Behind The Neck Barbell Press" and "Dumbbell Overhead
 *   Triceps Extension" seated or not, "Dumbbell Preacher Curl" one arm or two, "Cable Overhead
 *   Triceps Extension" a rope or a bar, and "T-Bar Row" and "Machine Shrug" whichever machine
 *   the gym has.
 * - **A different logging type.** FitNotes times a plank with a weight, which the planner reads
 *   as isometric, and the catalogue's "Plank" and "Side Bridge" count reps; the matcher would
 *   refuse the match anyway.
 * - **Nothing at all.** "Log Press", "Pendlay Row", "Neutral Chin Up", "Dragon Flag",
 *   "Hanging Knee Raise", "Walking", "Swimming", "Running (Outdoor)".
 *
 * FitNotes writes its library in English whatever the phone's language, so an exercise in another
 * language is one the user created or renamed. Those are left to the matcher's own name
 * comparison and otherwise become custom exercises. `FitNotesCatalogueNamesTest` resolves every
 * entry against the seed itself, so a target renamed or re-equipped by a catalogue update fails
 * a test rather than silently matching nothing.
 *
 * Read through [SuffixedExerciseNames], which also reads the equipment a user's own
 * "Curl (Cable)" names.
 */
internal val FITNOTES_CATALOGUE_NAMES: Map<String, String> = mapOf(
    // Shoulders
    "Overhead Press" to "Standing Military Press",
    "Lateral Dumbbell Raise" to "Side Lateral Raise",
    "Seated Dumbbell Lateral Raise" to "Seated Side Lateral Raise",
    "Cable Face Pull" to "Face Pull",
    "Smith Machine Overhead Press" to "Smith Machine Overhead Shoulder Press",
    // Triceps
    "V-Bar Push Down" to "Triceps Pushdown - V-Bar Attachment",
    "Rope Push Down" to "Triceps Pushdown - Rope Attachment",
    "Parallel Bar Triceps Dip" to "Dips - Triceps Version",
    "Ring Dip" to "Ring Dips",
    // Biceps
    "Dumbbell Curl" to "Dumbbell Bicep Curl",
    "Seated Incline Dumbbell Curl" to "Incline Dumbbell Curl",
    "Seated Machine Curl" to "Machine Bicep Curl",
    "Dumbbell Hammer Curl" to "Hammer Curls",
    "Cable Curl" to "Standing Biceps Cable Curl",
    "EZ-Bar Preacher Curl" to "Preacher Curl",
    "Dumbbell Concentration Curl" to "Concentration Curls",
    // Chest
    "Flat Barbell Bench Press" to "Barbell Bench Press - Medium Grip",
    "Flat Dumbbell Bench Press" to "Dumbbell Bench Press",
    "Incline Barbell Bench Press" to "Barbell Incline Bench Press - Medium Grip",
    "Incline Dumbbell Bench Press" to "Incline Dumbbell Press",
    "Flat Dumbbell Fly" to "Dumbbell Flyes",
    "Incline Dumbbell Fly" to "Incline Dumbbell Flyes",
    "Seated Machine Fly" to "Butterfly",
    // Back
    "Deadlift" to "Barbell Deadlift",
    "Pull Up" to "Pullups",
    "Dumbbell Row" to "One-Arm Dumbbell Row",
    "Barbell Row" to "Bent Over Barbell Row",
    "Lat Pulldown" to "Wide-Grip Lat Pulldown",
    "Seated Cable Row" to "Seated Cable Rows",
    "Straight-Arm Cable Pushdown" to "Straight-Arm Pulldown",
    "Rack Pull" to "Rack Pulls",
    // Legs
    "Barbell Front Squat" to "Front Barbell Squat",
    "Leg Extension Machine" to "Leg Extensions",
    "Seated Leg Curl Machine" to "Seated Leg Curl",
    "Lying Leg Curl Machine" to "Lying Leg Curls",
    "Standing Calf Raise Machine" to "Standing Calf Raises",
    "Seated Calf Raise Machine" to "Seated Calf Raise",
    "Donkey Calf Raise" to "Donkey Calf Raises",
    "Barbell Calf Raise" to "Standing Barbell Calf Raise",
    "Stiff-Legged Deadlift" to "Stiff-Legged Barbell Deadlift",
    // Abs
    "Ab-Wheel Rollout" to "Ab Roller",
    "Crunch" to "Crunches",
    "Crunch Machine" to "Ab Crunch Machine",
    // Cardio
    "Cycling" to "Bicycling",
    "Stationary Bike" to "Bicycling, Stationary",
    "Rowing Machine" to "Rowing, Stationary",
)
