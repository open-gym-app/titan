package com.titan.core.domain.importer.hevy

import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES

/**
 * Hevy's built-in exercise names that are known to be a row of Titan's seeded catalogue under
 * another name — the table [com.titan.core.domain.importer.SuffixedExerciseNames] tries first.
 *
 * Most of the names Hevy shares with Strong are [SUFFIXED_CATALOGUE_NAMES], which also says what
 * earns a name its entry and what keeps one out. What is listed here is Hevy's own spelling:
 * names with no parenthesis ("Front Squat", "Dumbbell Row", "Triceps Rope Pushdown"), a variation
 * in the name rather than after it ("Rear Delt Reverse Fly (Dumbbell)", "Seated Incline Curl
 * (Dumbbell)"), and the movements Strong's library does not have.
 *
 * Three decisions differ from Strong's, because Hevy's library draws the line elsewhere:
 *
 * - **"Chest Press (Machine)"** is the catalogue's selectorised "Machine Bench Press". Strong's
 *   name could be either machine; Hevy files the plate-loaded press separately, as
 *   "Iso-Lateral Chest Press (Machine)", which stays out because no catalogue row is an
 *   independent-arm press.
 * - **"Dumbbell Row"** is the one-arm row on a bench; Hevy's two-dumbbell row is "Bent Over Row
 *   (Dumbbell)", a shared entry.
 * - **"Straight Leg Deadlift"** is Strong's "Stiff Leg Deadlift (Barbell)" under the other name
 *   the lift goes by, and lands on the same row.
 *
 * Hevy's app writes its library's names in the app's language ("Bankdrücken (Langhantel)"), and
 * this table holds the English ones only. A translated name is not mapped: its parenthesis names
 * no equipment this parser knows, so it is matched by its whole name or created as a custom
 * exercise the user can rename. Hevy's website export always writes English names.
 *
 * `HevyCatalogueNamesTest` resolves every entry, shared ones included, against the seed itself.
 */
internal val HEVY_CATALOGUE_NAMES: Map<String, String> = SUFFIXED_CATALOGUE_NAMES + mapOf(
    // Barbell
    "Front Squat" to "Front Barbell Squat",
    "Zercher Squat" to "Zercher Squats",
    "Rack Pull" to "Rack Pulls",
    "Straight Leg Deadlift" to "Stiff-Legged Barbell Deadlift",
    "Deadlift (Trap bar)" to "Trap Bar Deadlift",
    "Glute Bridge (Barbell)" to "Barbell Glute Bridge",
    "EZ Bar Biceps Curl" to "EZ-Bar Curl",
    "Seated Wrist Curl (Barbell)" to "Seated Palm-Up Barbell Wrist Curl",
    "Seated Wrist Extension (Barbell)" to "Seated Palms-Down Barbell Wrist Curl",
    // Dumbbell
    "Dumbbell Row" to "One-Arm Dumbbell Row",
    "Chest Supported Incline Row (Dumbbell)" to "Dumbbell Incline Row",
    "Rear Delt Reverse Fly (Dumbbell)" to "Reverse Flyes",
    "Decline Chest Fly (Dumbbell)" to "Decline Dumbbell Flyes",
    "Seated Lateral Raise (Dumbbell)" to "Seated Side Lateral Raise",
    "Seated Incline Curl (Dumbbell)" to "Incline Dumbbell Curl",
    "Seated Incline Hammer Curl (Dumbbell)" to "Incline Hammer Curls",
    "Concentration Curl" to "Concentration Curls",
    "Seated Palms Up Wrist Curl" to "Seated Dumbbell Palms-Up Wrist Curl",
    "Triceps Kickback (Dumbbell)" to "Tricep Dumbbell Kickback",
    "Floor Press (Dumbbell)" to "Dumbbell Floor Press",
    "Dumbbell Step Up" to "Dumbbell Step Ups",
    // Cable
    "Seated Cable Row - V Grip (Cable)" to "Seated Cable Rows",
    "Triceps Rope Pushdown" to "Triceps Pushdown - Rope Attachment",
    "Cable Fly Crossovers" to "Cable Crossover",
    "Low Cable Fly Crossovers" to "Low Cable Crossover",
    "Rear Delt Reverse Fly (Cable)" to "Cable Rear Delt Fly",
    "Reverse Grip Lat Pulldown (Cable)" to "Underhand Cable Pulldowns",
    "Straight Arm Lat Pulldown (Cable)" to "Straight-Arm Pulldown",
    "Reverse Curl (Cable)" to "Reverse Cable Curl",
    "Shrug (Cable)" to "Cable Shrugs",
    // Machine
    "Chest Press (Machine)" to "Machine Bench Press",
    "Seated Shoulder Press (Machine)" to "Machine Shoulder (Military) Press",
    "Butterfly (Pec Deck)" to "Butterfly",
    "Rear Delt Reverse Fly (Machine)" to "Reverse Machine Flyes",
    "Hip Abduction (Machine)" to "Thigh Abductor",
    "Hip Adduction (Machine)" to "Thigh Adductor",
    "Seated Dip Machine" to "Dip Machine",
    "Knee Raise Parallel Bars" to "Knee/Hip Raise On Parallel Bars",
    "Rowing Machine" to "Rowing, Stationary",
    // Smith machine
    "Bent Over Row (Smith Machine)" to "Smith Machine Bent Over Row",
    "Decline Bench Press (Smith Machine)" to "Smith Machine Decline Press",
    "Standing Calf Raise (Smith)" to "Smith Machine Calf Raise",
    // Bodyweight and other kit
    "Back Extension (Hyperextension)" to "Hyperextensions (Back Extensions)",
    "Incline Push Ups" to "Incline Push-Up",
    "Walking Lunge" to "Bodyweight Walking Lunge",
    "Glute Bridge" to "Butt Lift (Bridge)",
    "Plate Front Raise" to "Front Plate Raise",
    "Band Pullaparts" to "Band Pull Apart",
    "Lateral Raise (Band)" to "Lateral Raise - With Bands",
)
