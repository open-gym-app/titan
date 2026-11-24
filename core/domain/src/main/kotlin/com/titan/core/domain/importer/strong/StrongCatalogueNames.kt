package com.titan.core.domain.importer.strong

/**
 * Strong's built-in exercise names that are known to be a row of Titan's seeded catalogue under
 * another name — the table [com.titan.core.domain.importer.SuffixedExerciseNames] tries first.
 *
 * Every entry is a decision, checked against the catalogue row's instructions, that the two
 * names are one movement with the same equipment. Being left out is the safe failure: an
 * exercise this table does not list is still matched when its name before the parenthesis is a
 * catalogue row with the same equipment, or else created as a custom exercise the user can see
 * and rename. Being listed wrongly is the dangerous one, because it files a lift's history and
 * records under another movement without anybody noticing. So a Strong name stays out when the
 * catalogue has only a near miss for it:
 *
 * - **A different variation.** "Overhead Press (Dumbbell)" is standing, and the catalogue's
 *   dumbbell shoulder press is seated; "Lateral Raise (Cable)" is not the catalogue's seated one.
 * - **Different equipment.** "Skullcrusher (Barbell)" is an EZ-bar row in the catalogue, and
 *   "Romanian Deadlift (Dumbbell)" has no dumbbell row to match.
 * - **A name that could be either of two.** "Triceps Extension (Cable)" is overhead or lying,
 *   "Chest Press (Machine)" is a selectorised or a plate-loaded press, and "Cable Kickback" is a
 *   triceps or a glute exercise.
 * - **Nothing at all.** "Pendlay Row (Barbell)", "Bulgarian Split Squat", "Running".
 *
 * Two Strong names share a target only where they are the same lift under two names: "Overhead
 * Press (Barbell)" and "Strict Military Press (Barbell)" are both the standing barbell press,
 * and "Lat Pulldown (Cable)" is performed with the wide grip "Lat Pulldown - Wide Grip (Cable)"
 * names.
 *
 * `StrongCatalogueNamesTest` resolves every entry against the seed itself, so a target renamed
 * or re-equipped by a catalogue update fails a test rather than silently matching nothing.
 */
internal val STRONG_CATALOGUE_NAMES: Map<String, String> = mapOf(
    // Barbell
    "Bench Press (Barbell)" to "Barbell Bench Press - Medium Grip",
    "Bench Press - Close Grip (Barbell)" to "Close-Grip Barbell Bench Press",
    "Bench Press - Wide Grip (Barbell)" to "Wide-Grip Barbell Bench Press",
    "Incline Bench Press (Barbell)" to "Barbell Incline Bench Press - Medium Grip",
    "Decline Bench Press (Barbell)" to "Decline Barbell Bench Press",
    "Squat (Barbell)" to "Barbell Squat",
    "Front Squat (Barbell)" to "Front Barbell Squat",
    "Zercher Squat (Barbell)" to "Zercher Squats",
    "Deadlift (Barbell)" to "Barbell Deadlift",
    "Stiff Leg Deadlift (Barbell)" to "Stiff-Legged Barbell Deadlift",
    "Rack Pull (Barbell)" to "Rack Pulls",
    "Overhead Press (Barbell)" to "Standing Military Press",
    "Strict Military Press (Barbell)" to "Standing Military Press",
    "Seated Overhead Press (Barbell)" to "Barbell Shoulder Press",
    "Bent Over Row (Barbell)" to "Bent Over Barbell Row",
    "Bent Over Row - Underhand (Barbell)" to "Reverse Grip Bent-Over Rows",
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
    "Reverse Fly (Dumbbell)" to "Reverse Flyes",
    "Bent Over One Arm Row (Dumbbell)" to "One-Arm Dumbbell Row",
    "Bent Over Row (Dumbbell)" to "Bent Over Two-Dumbbell Row",
    "Incline Row (Dumbbell)" to "Dumbbell Incline Row",
    "Shrug (Dumbbell)" to "Dumbbell Shrug",
    "Upright Row (Dumbbell)" to "Standing Dumbbell Upright Row",
    "Bicep Curl (Dumbbell)" to "Dumbbell Bicep Curl",
    "Hammer Curl (Dumbbell)" to "Hammer Curls",
    "Concentration Curl (Dumbbell)" to "Concentration Curls",
    "Incline Curl (Dumbbell)" to "Incline Dumbbell Curl",
    "Reverse Curl (Dumbbell)" to "Standing Dumbbell Reverse Curl",
    "Seated Palms Up Wrist Curl (Dumbbell)" to "Seated Dumbbell Palms-Up Wrist Curl",
    "Skullcrusher (Dumbbell)" to "Lying Dumbbell Tricep Extension",
    "Squat (Dumbbell)" to "Dumbbell Squat",
    "Lunge (Dumbbell)" to "Dumbbell Lunges",
    "Stiff Leg Deadlift (Dumbbell)" to "Stiff-Legged Dumbbell Deadlift",
    "Standing Calf Raise (Dumbbell)" to "Standing Dumbbell Calf Raise",
    "Side Bend (Dumbbell)" to "Dumbbell Side Bend",
    // Cable
    "Lat Pulldown (Cable)" to "Wide-Grip Lat Pulldown",
    "Lat Pulldown - Wide Grip (Cable)" to "Wide-Grip Lat Pulldown",
    "Lat Pulldown - Underhand (Cable)" to "Underhand Cable Pulldowns",
    "Seated Row (Cable)" to "Seated Cable Rows",
    "Bicep Curl (Cable)" to "Standing Biceps Cable Curl",
    "Hammer Curl (Cable)" to "Cable Hammer Curls - Rope Attachment",
    "Front Raise (Cable)" to "Front Cable Raise",
    "Reverse Fly (Cable)" to "Cable Rear Delt Fly",
    "Upright Row (Cable)" to "Upright Cable Row",
    "Cable Pull Through" to "Pull Through",
    // Machine
    "Leg Extension (Machine)" to "Leg Extensions",
    "Lying Leg Curl (Machine)" to "Lying Leg Curls",
    "Shoulder Press (Machine)" to "Machine Shoulder (Military) Press",
    "Bicep Curl (Machine)" to "Machine Bicep Curl",
    "Preacher Curl (Machine)" to "Machine Preacher Curls",
    "Triceps Extension (Machine)" to "Machine Triceps Extension",
    "Pec Deck (Machine)" to "Butterfly",
    "Reverse Fly (Machine)" to "Reverse Machine Flyes",
    "Hip Abductor (Machine)" to "Thigh Abductor",
    "Hip Adductor (Machine)" to "Thigh Adductor",
    "Standing Calf Raise (Machine)" to "Standing Calf Raises",
    "Calf Press on Leg Press" to "Calf Press On The Leg Press Machine",
    "Iso-Lateral Row (Machine)" to "Leverage Iso Row",
    "Crunch (Machine)" to "Ab Crunch Machine",
    "Rowing (Machine)" to "Rowing, Stationary",
    // Smith machine
    "Bench Press (Smith Machine)" to "Smith Machine Bench Press",
    "Incline Bench Press (Smith Machine)" to "Smith Machine Incline Bench Press",
    "Squat (Smith Machine)" to "Smith Machine Squat",
    "Overhead Press (Smith Machine)" to "Smith Machine Overhead Shoulder Press",
    "Standing Calf Raise (Smith Machine)" to "Smith Machine Calf Raise",
    // Bodyweight
    "Pull Up" to "Pullups",
    "Push Up" to "Pushups",
    "Triceps Dip" to "Dips - Triceps Version",
    "Chest Dip" to "Dips - Chest Version",
    "Bench Dip" to "Bench Dips",
    "Crunch" to "Crunches",
    "Oblique Crunch" to "Oblique Crunches",
    "Back Extension" to "Hyperextensions (Back Extensions)",
    "Ab Wheel" to "Ab Roller",
    "Mountain Climber" to "Mountain Climbers",
    "Handstand Push Up" to "Handstand Push-Ups",
    "Squat (Bodyweight)" to "Bodyweight Squat",
    // Cardio
    "Cycling" to "Bicycling",
    "Cycling (Indoor)" to "Bicycling, Stationary",
    "Elliptical Machine" to "Elliptical Trainer",
)
