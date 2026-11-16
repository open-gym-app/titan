package com.titan.core.domain.importer

/**
 * Exercise names that Strong's and Hevy's built-in libraries both use, mapped to the row of
 * Titan's seeded catalogue each one is — the part of their mapping tables the two apps share.
 *
 * Both apps name their built-in exercises "Movement (Equipment)", and most of their common lifts
 * carry the same string in both: Hevy's "Bench Press (Barbell)" is Strong's, and is the
 * catalogue's "Barbell Bench Press - Medium Grip" for the same reasons. Every entry here appears
 * in both libraries, so it is written once and each app's table adds only the names its library
 * spells its own way (`STRONG_CATALOGUE_NAMES`, `HEVY_CATALOGUE_NAMES`). A name that one of the
 * apps uses for a different movement would belong in that app's table instead; none here does.
 *
 * Every entry is a decision, checked against the catalogue row's instructions, that the two
 * names are one movement with the same equipment. Being left out is the safe failure: an
 * exercise no table lists is still matched when its name before the parenthesis is a catalogue
 * row with the same equipment (see [SuffixedExerciseNames]), or else created as a custom
 * exercise the user can see and rename. Being listed wrongly is the dangerous one, because it
 * files a lift's history and records under another movement without anybody noticing. So a name
 * stays out when the catalogue has only a near miss for it:
 *
 * - **A different variation.** "Overhead Press (Dumbbell)" is standing, and the catalogue's
 *   dumbbell shoulder press is seated; "Lateral Raise (Cable)" is not the catalogue's seated one.
 * - **Different equipment.** "Skullcrusher (Barbell)" is an EZ-bar row in the catalogue, and
 *   "Romanian Deadlift (Dumbbell)" has no dumbbell row to match.
 * - **A name that could be either of two.** "Triceps Extension (Cable)" is overhead or lying,
 *   and "Cable Kickback" is a triceps or a glute exercise.
 * - **Nothing at all.** "Pendlay Row (Barbell)", "Bulgarian Split Squat", "Running".
 *
 * `StrongCatalogueNamesTest` and `HevyCatalogueNamesTest` resolve every entry, through each
 * app's table, against the seed itself, so a target renamed or re-equipped by a catalogue update
 * fails a test rather than silently matching nothing.
 */
internal val SUFFIXED_CATALOGUE_NAMES: Map<String, String> = mapOf(
    // Barbell
    "Bench Press (Barbell)" to "Barbell Bench Press - Medium Grip",
    "Bench Press - Close Grip (Barbell)" to "Close-Grip Barbell Bench Press",
    "Bench Press - Wide Grip (Barbell)" to "Wide-Grip Barbell Bench Press",
    "Incline Bench Press (Barbell)" to "Barbell Incline Bench Press - Medium Grip",
    "Decline Bench Press (Barbell)" to "Decline Barbell Bench Press",
    "Squat (Barbell)" to "Barbell Squat",
    "Deadlift (Barbell)" to "Barbell Deadlift",
    "Overhead Press (Barbell)" to "Standing Military Press",
    "Seated Overhead Press (Barbell)" to "Barbell Shoulder Press",
    "Bent Over Row (Barbell)" to "Bent Over Barbell Row",
    "Hip Thrust (Barbell)" to "Barbell Hip Thrust",
    "Lunge (Barbell)" to "Barbell Lunge",
    "Shrug (Barbell)" to "Barbell Shrug",
    "Bicep Curl (Barbell)" to "Barbell Curl",
    "Reverse Curl (Barbell)" to "Reverse Barbell Curl",
    "Upright Row (Barbell)" to "Upright Barbell Row",
    "Standing Calf Raise (Barbell)" to "Standing Barbell Calf Raise",
    // Dumbbell
    "Bench Press (Dumbbell)" to "Dumbbell Bench Press",
    "Incline Bench Press (Dumbbell)" to "Incline Dumbbell Press",
    "Decline Bench Press (Dumbbell)" to "Decline Dumbbell Bench Press",
    "Seated Overhead Press (Dumbbell)" to "Dumbbell Shoulder Press",
    "Arnold Press (Dumbbell)" to "Arnold Dumbbell Press",
    "Chest Fly (Dumbbell)" to "Dumbbell Flyes",
    "Incline Chest Fly (Dumbbell)" to "Incline Dumbbell Flyes",
    "Lateral Raise (Dumbbell)" to "Side Lateral Raise",
    "Front Raise (Dumbbell)" to "Front Dumbbell Raise",
    "Bent Over Row (Dumbbell)" to "Bent Over Two-Dumbbell Row",
    "Shrug (Dumbbell)" to "Dumbbell Shrug",
    "Upright Row (Dumbbell)" to "Standing Dumbbell Upright Row",
    "Bicep Curl (Dumbbell)" to "Dumbbell Bicep Curl",
    "Hammer Curl (Dumbbell)" to "Hammer Curls",
    "Reverse Curl (Dumbbell)" to "Standing Dumbbell Reverse Curl",
    "Skullcrusher (Dumbbell)" to "Lying Dumbbell Tricep Extension",
    "Squat (Dumbbell)" to "Dumbbell Squat",
    "Lunge (Dumbbell)" to "Dumbbell Lunges",
    "Standing Calf Raise (Dumbbell)" to "Standing Dumbbell Calf Raise",
    "Side Bend (Dumbbell)" to "Dumbbell Side Bend",
    // Cable
    "Lat Pulldown (Cable)" to "Wide-Grip Lat Pulldown",
    "Bicep Curl (Cable)" to "Standing Biceps Cable Curl",
    "Hammer Curl (Cable)" to "Cable Hammer Curls - Rope Attachment",
    "Front Raise (Cable)" to "Front Cable Raise",
    "Upright Row (Cable)" to "Upright Cable Row",
    "Cable Pull Through" to "Pull Through",
    // Machine
    "Leg Extension (Machine)" to "Leg Extensions",
    "Lying Leg Curl (Machine)" to "Lying Leg Curls",
    "Bicep Curl (Machine)" to "Machine Bicep Curl",
    "Preacher Curl (Machine)" to "Machine Preacher Curls",
    "Triceps Extension (Machine)" to "Machine Triceps Extension",
    "Standing Calf Raise (Machine)" to "Standing Calf Raises",
    "Iso-Lateral Row (Machine)" to "Leverage Iso Row",
    "Crunch (Machine)" to "Ab Crunch Machine",
    // Smith machine
    "Bench Press (Smith Machine)" to "Smith Machine Bench Press",
    "Incline Bench Press (Smith Machine)" to "Smith Machine Incline Bench Press",
    "Squat (Smith Machine)" to "Smith Machine Squat",
    "Overhead Press (Smith Machine)" to "Smith Machine Overhead Shoulder Press",
    // Bodyweight
    "Pull Up" to "Pullups",
    "Push Up" to "Pushups",
    "Triceps Dip" to "Dips - Triceps Version",
    "Chest Dip" to "Dips - Chest Version",
    "Bench Dip" to "Bench Dips",
    "Crunch" to "Crunches",
    "Oblique Crunch" to "Oblique Crunches",
    "Ab Wheel" to "Ab Roller",
    "Mountain Climber" to "Mountain Climbers",
    "Handstand Push Up" to "Handstand Push-Ups",
    "Squat (Bodyweight)" to "Bodyweight Squat",
    // Cardio
    "Cycling" to "Bicycling",
)
