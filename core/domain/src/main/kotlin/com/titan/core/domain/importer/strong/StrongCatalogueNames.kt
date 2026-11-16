package com.titan.core.domain.importer.strong

import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES

/**
 * Strong's built-in exercise names that are known to be a row of Titan's seeded catalogue under
 * another name — the table [com.titan.core.domain.importer.SuffixedExerciseNames] tries first.
 *
 * Most of them Hevy's library spells the same way, and those are [SUFFIXED_CATALOGUE_NAMES],
 * which also says what earns a name its entry and what keeps one out. What is listed here is
 * Strong's own spelling of a movement, or a movement Hevy's library does not have: "Front Squat
 * (Barbell)" where Hevy writes "Front Squat", "Pec Deck (Machine)" where it writes "Butterfly
 * (Pec Deck)".
 *
 * "Chest Press (Machine)" stays out, because Strong's could be a selectorised or a plate-loaded
 * press and the catalogue has a row for each. Two Strong names share a target only where they
 * are the same lift under two names: "Overhead Press (Barbell)" and "Strict Military Press
 * (Barbell)" are both the standing barbell press, and "Lat Pulldown (Cable)" is performed with
 * the wide grip "Lat Pulldown - Wide Grip (Cable)" names.
 *
 * `StrongCatalogueNamesTest` resolves every entry, shared ones included, against the seed itself.
 */
internal val STRONG_CATALOGUE_NAMES: Map<String, String> = SUFFIXED_CATALOGUE_NAMES + mapOf(
    // Barbell
    "Front Squat (Barbell)" to "Front Barbell Squat",
    "Zercher Squat (Barbell)" to "Zercher Squats",
    "Stiff Leg Deadlift (Barbell)" to "Stiff-Legged Barbell Deadlift",
    "Rack Pull (Barbell)" to "Rack Pulls",
    "Strict Military Press (Barbell)" to "Standing Military Press",
    "Bent Over Row - Underhand (Barbell)" to "Reverse Grip Bent-Over Rows",
    // Dumbbell
    "Reverse Fly (Dumbbell)" to "Reverse Flyes",
    "Bent Over One Arm Row (Dumbbell)" to "One-Arm Dumbbell Row",
    "Incline Row (Dumbbell)" to "Dumbbell Incline Row",
    "Concentration Curl (Dumbbell)" to "Concentration Curls",
    "Incline Curl (Dumbbell)" to "Incline Dumbbell Curl",
    "Seated Palms Up Wrist Curl (Dumbbell)" to "Seated Dumbbell Palms-Up Wrist Curl",
    "Stiff Leg Deadlift (Dumbbell)" to "Stiff-Legged Dumbbell Deadlift",
    // Cable
    "Lat Pulldown - Wide Grip (Cable)" to "Wide-Grip Lat Pulldown",
    "Lat Pulldown - Underhand (Cable)" to "Underhand Cable Pulldowns",
    "Seated Row (Cable)" to "Seated Cable Rows",
    "Reverse Fly (Cable)" to "Cable Rear Delt Fly",
    // Machine
    "Shoulder Press (Machine)" to "Machine Shoulder (Military) Press",
    "Pec Deck (Machine)" to "Butterfly",
    "Reverse Fly (Machine)" to "Reverse Machine Flyes",
    "Hip Abductor (Machine)" to "Thigh Abductor",
    "Hip Adductor (Machine)" to "Thigh Adductor",
    "Calf Press on Leg Press" to "Calf Press On The Leg Press Machine",
    "Rowing (Machine)" to "Rowing, Stationary",
    // Smith machine
    "Standing Calf Raise (Smith Machine)" to "Smith Machine Calf Raise",
    // Bodyweight
    "Back Extension" to "Hyperextensions (Back Extensions)",
    // Cardio
    "Cycling (Indoor)" to "Bicycling, Stationary",
    "Elliptical Machine" to "Elliptical Trainer",
)
