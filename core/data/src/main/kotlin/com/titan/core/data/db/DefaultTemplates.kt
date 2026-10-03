package com.titan.core.data.db

import androidx.annotation.StringRes
import com.titan.core.data.R
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.EquipmentType.BARBELL
import com.titan.core.domain.model.EquipmentType.BODYWEIGHT
import com.titan.core.domain.model.EquipmentType.CABLE
import com.titan.core.domain.model.EquipmentType.DUMBBELL
import com.titan.core.domain.model.EquipmentType.EZ_CURL_BAR
import com.titan.core.domain.model.EquipmentType.KETTLEBELL
import com.titan.core.domain.model.EquipmentType.MACHINE
import com.titan.core.domain.model.EquipmentType.OTHER
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateExerciseDraft

/**
 * The templates a new install starts with, so the template list is not empty on day one.
 *
 * They are inserted with the exercise catalogue when the database is created
 * ([DatabaseSeed.insertDefaultTemplates]) and are ordinary templates from then on: no column
 * marks them, and the user renames, edits and deletes them like any template they wrote.
 * `DefaultTemplateCatalogueImpl` reads the same list to put a deleted one back.
 *
 * ### Why Kotlin rather than a seed SQL file
 *
 * - **The names are string resources**, so a French phone is seeded with "Jambes" rather than
 *   "Legs". A generated SQL file can only hold one language.
 * - **Exercises are referenced by the seed's unique key** — name and equipment, on a row with
 *   no machine brand — and resolved when the template is written. Never by id: the seed's ids
 *   are positional and move whenever the catalogue is regenerated.
 *
 * ### What a default plans
 *
 * Reps only. A default cannot know the user's weights, and the session fills the weight in
 * from history once there is some. Holds, stretches and cardio plan no figure at all — a
 * `PlannedSet()` the user logs a time or a distance into. Rest is per exercise, in seconds.
 *
 * Every non-cardio exercise is a free-weight, cable or bodyweight movement, so a template works
 * in any gym; the four machines (leg press, lying leg curl, the two calf raises) are in any gym
 * that has machines. Arms covers supersets, HIIT a superset of interval sets, and Bike and Row
 * the warm-up and interval set types on endurance exercises.
 *
 * `DatabaseSeedTest` resolves every reference here against the real catalogue, which is what
 * makes an unresolvable one a failed build rather than a template quietly missing on first
 * launch.
 */
internal object DefaultTemplates {

    private const val HIIT_ROUNDS = 4
    private const val ROW_INTERVALS = 5

    // Above [all], which reads them: an object's properties initialise in declaration order.
    private val WARMUP = PlannedSet(setType = SetType.WARMUP)
    private val INTERVAL = PlannedSet(setType = SetType.INTERVAL)

    val all: List<DefaultTemplateSpec> = listOf(
        template(R.string.default_template_push, TemplateCategory.HYPERTROPHY, rest = 90) {
            lift("Barbell Bench Press - Medium Grip", BARBELL, sets = 4, reps = 8)
            lift("Barbell Shoulder Press", BARBELL, sets = 3, reps = 10)
            lift("Incline Dumbbell Press", DUMBBELL, sets = 3, reps = 10)
            lift("Side Lateral Raise", DUMBBELL, sets = 3, reps = 15)
            lift("Triceps Pushdown - Rope Attachment", CABLE, sets = 3, reps = 12)
            lift("Dips - Triceps Version", BODYWEIGHT, sets = 3, reps = 10)
        },
        template(R.string.default_template_pull, TemplateCategory.HYPERTROPHY, rest = 90) {
            lift("Barbell Deadlift", BARBELL, sets = 3, reps = 5, rest = 180)
            lift("Pullups", BODYWEIGHT, sets = 3, reps = 8)
            lift("Bent Over Barbell Row", BARBELL, sets = 3, reps = 10)
            lift("Seated Cable Rows", CABLE, sets = 3, reps = 12)
            lift("Face Pull", CABLE, sets = 3, reps = 15)
            lift("Barbell Curl", BARBELL, sets = 3, reps = 12)
        },
        template(R.string.default_template_legs, TemplateCategory.HYPERTROPHY, rest = 90) {
            lift("Barbell Squat", BARBELL, sets = 4, reps = 8, rest = 150)
            lift("Romanian Deadlift", BARBELL, sets = 3, reps = 10)
            lift("Leg Press", MACHINE, sets = 3, reps = 12)
            lift("Dumbbell Lunges", DUMBBELL, sets = 3, reps = 12)
            lift("Lying Leg Curls", MACHINE, sets = 3, reps = 12)
            lift("Standing Calf Raises", MACHINE, sets = 4, reps = 15)
        },
        template(R.string.default_template_chest, TemplateCategory.HYPERTROPHY, rest = 90) {
            lift("Barbell Bench Press - Medium Grip", BARBELL, sets = 4, reps = 8)
            lift("Incline Dumbbell Press", DUMBBELL, sets = 3, reps = 10)
            lift("Dumbbell Flyes", DUMBBELL, sets = 3, reps = 12)
            lift("Cable Crossover", CABLE, sets = 3, reps = 15)
            lift("Pushups", BODYWEIGHT, sets = 3, reps = 15)
        },
        template(R.string.default_template_back, TemplateCategory.HYPERTROPHY, rest = 90) {
            lift("Barbell Deadlift", BARBELL, sets = 3, reps = 5, rest = 180)
            lift("Wide-Grip Lat Pulldown", CABLE, sets = 3, reps = 10)
            lift("Bent Over Barbell Row", BARBELL, sets = 3, reps = 10)
            lift("One-Arm Dumbbell Row", DUMBBELL, sets = 3, reps = 12)
            lift("Straight-Arm Pulldown", CABLE, sets = 3, reps = 15)
            lift("Hyperextensions (Back Extensions)", OTHER, sets = 3, reps = 15)
        },
        template(R.string.default_template_shoulders, TemplateCategory.HYPERTROPHY, rest = 75) {
            lift("Barbell Shoulder Press", BARBELL, sets = 4, reps = 8)
            lift("Side Lateral Raise", DUMBBELL, sets = 4, reps = 15)
            lift("Front Dumbbell Raise", DUMBBELL, sets = 3, reps = 12)
            lift("Reverse Flyes", DUMBBELL, sets = 3, reps = 15)
            lift("Face Pull", CABLE, sets = 3, reps = 15)
            lift("Dumbbell Shrug", DUMBBELL, sets = 3, reps = 12)
        },
        // Three supersets: a curl and a triceps movement in each pair, rest after the pair.
        template(R.string.default_template_arms, TemplateCategory.HYPERTROPHY, rest = 60) {
            lift("Barbell Curl", BARBELL, sets = 3, reps = 10, superset = 0)
            lift("EZ-Bar Skullcrusher", EZ_CURL_BAR, sets = 3, reps = 10, superset = 0)
            lift("Hammer Curls", DUMBBELL, sets = 3, reps = 12, superset = 1)
            lift("Triceps Pushdown - Rope Attachment", CABLE, sets = 3, reps = 12, superset = 1)
            lift("Incline Dumbbell Curl", DUMBBELL, sets = 3, reps = 12, superset = 2)
            lift("Standing Dumbbell Triceps Extension", DUMBBELL, sets = 3, reps = 12, superset = 2)
        },
        template(R.string.default_template_abs, TemplateCategory.GENERAL, rest = 45) {
            hold("Plank", BODYWEIGHT, sets = 3)
            lift("Crunches", BODYWEIGHT, sets = 3, reps = 20)
            lift("Hanging Leg Raise", BODYWEIGHT, sets = 3, reps = 12)
            lift("Russian Twist", BODYWEIGHT, sets = 3, reps = 20)
            lift("Dead Bug", BODYWEIGHT, sets = 3, reps = 10)
            hold("Side Bridge", BODYWEIGHT, sets = 3)
        },
        template(R.string.default_template_upper_body, TemplateCategory.STRENGTH, rest = 120) {
            lift("Barbell Bench Press - Medium Grip", BARBELL, sets = 4, reps = 6)
            lift("Bent Over Barbell Row", BARBELL, sets = 4, reps = 6)
            lift("Standing Military Press", BARBELL, sets = 3, reps = 8)
            lift("Pullups", BODYWEIGHT, sets = 3, reps = 8)
            lift("Dumbbell Bicep Curl", DUMBBELL, sets = 3, reps = 12)
            lift("Triceps Pushdown", CABLE, sets = 3, reps = 12)
        },
        template(R.string.default_template_lower_body, TemplateCategory.STRENGTH, rest = 120) {
            lift("Barbell Squat", BARBELL, sets = 4, reps = 6, rest = 180)
            lift("Romanian Deadlift", BARBELL, sets = 3, reps = 8)
            lift("Leg Press", MACHINE, sets = 3, reps = 10)
            lift("Lying Leg Curls", MACHINE, sets = 3, reps = 12)
            lift("Barbell Hip Thrust", BARBELL, sets = 3, reps = 10)
            lift("Seated Calf Raise", MACHINE, sets = 3, reps = 15)
        },
        // The lifts rest long, as a strength session does; the plank closing it does not.
        template(R.string.default_template_full_body, TemplateCategory.STRENGTH, rest = 180) {
            lift("Barbell Squat", BARBELL, sets = 3, reps = 5)
            lift("Barbell Bench Press - Medium Grip", BARBELL, sets = 3, reps = 5)
            lift("Bent Over Barbell Row", BARBELL, sets = 3, reps = 5)
            lift("Standing Military Press", BARBELL, sets = 3, reps = 8)
            lift("Barbell Deadlift", BARBELL, sets = 1, reps = 5)
            hold("Plank", BODYWEIGHT, sets = 3, rest = 60)
        },
        template(R.string.default_template_glutes, TemplateCategory.HYPERTROPHY, rest = 90) {
            lift("Barbell Hip Thrust", BARBELL, sets = 4, reps = 10)
            lift("Romanian Deadlift", BARBELL, sets = 3, reps = 10)
            lift("Bodyweight Walking Lunge", OTHER, sets = 3, reps = 12)
            lift("Single Leg Glute Bridge", BODYWEIGHT, sets = 3, reps = 12)
            lift("Glute Kickback", BODYWEIGHT, sets = 3, reps = 15)
            lift("Goblet Squat", KETTLEBELL, sets = 3, reps = 12)
        },
        template(R.string.default_template_bodyweight, TemplateCategory.GENERAL, rest = 60) {
            lift("Bodyweight Squat", BODYWEIGHT, sets = 3, reps = 15)
            lift("Pushups", BODYWEIGHT, sets = 3, reps = 12)
            lift("Inverted Row", OTHER, sets = 3, reps = 10)
            lift("Bodyweight Walking Lunge", OTHER, sets = 3, reps = 12)
            lift("Bench Dips", BODYWEIGHT, sets = 3, reps = 12)
            lift("Mountain Climbers", OTHER, sets = 3, reps = 20)
            hold("Plank", BODYWEIGHT, sets = 3)
        },
        // One round of five, four times through: a single superset of interval sets.
        template(R.string.default_template_hiit, TemplateCategory.ENDURANCE, rest = 60) {
            sets("Rope Jumping", OTHER, List(HIIT_ROUNDS) { INTERVAL }, superset = 0)
            sets("Freehand Jump Squat", BODYWEIGHT, List(HIIT_ROUNDS) { INTERVAL }, superset = 0)
            sets("Mountain Climbers", OTHER, List(HIIT_ROUNDS) { INTERVAL }, superset = 0)
            sets("Plyo Push-up", BODYWEIGHT, List(HIIT_ROUNDS) { INTERVAL }, superset = 0)
            sets("Front Box Jump", OTHER, List(HIIT_ROUNDS) { INTERVAL }, superset = 0)
        },
        template(R.string.default_template_bike, TemplateCategory.ENDURANCE, rest = null) {
            sets("Bicycling", OTHER, listOf(WARMUP, PlannedSet()))
        },
        template(R.string.default_template_row, TemplateCategory.ENDURANCE, rest = null) {
            sets("Rowing, Stationary", MACHINE, listOf(WARMUP) + List(ROW_INTERVALS) { INTERVAL } + PlannedSet())
        },
        template(R.string.default_template_stretch, TemplateCategory.MOBILITY, rest = 0) {
            hold("Cat Stretch", OTHER, sets = 2)
            hold("Child's Pose", OTHER, sets = 2)
            hold("Kneeling Hip Flexor", OTHER, sets = 2)
            hold("Standing Hamstring and Calf Stretch", OTHER, sets = 2)
            hold("All Fours Quad Stretch", BODYWEIGHT, sets = 2)
            hold("Chest And Front Of Shoulder Stretch", OTHER, sets = 2)
            hold("Shoulder Stretch", OTHER, sets = 2)
            hold("Spinal Stretch", OTHER, sets = 2)
        },
    )

    private fun template(
        @StringRes nameRes: Int,
        category: TemplateCategory,
        rest: Long?,
        exercises: DefaultTemplateBuilder.() -> Unit,
    ) = DefaultTemplateSpec(
        nameRes = nameRes,
        category = category,
        exercises = DefaultTemplateBuilder(rest).apply(exercises).exercises.toList(),
    )
}

/**
 * One default template: its name as a string resource, so it is read in the device's language
 * at the moment it is written, and its exercises by catalogue key.
 */
internal data class DefaultTemplateSpec(
    @param:StringRes val nameRes: Int,
    val category: TemplateCategory,
    val exercises: List<DefaultExerciseSpec>,
) {

    /**
     * The exercises as drafts, with each reference looked up through [resolve] — or null if any
     * reference resolves to nothing, because a template with a hole where an exercise should
     * be is not the template the definition describes.
     */
    fun toDrafts(resolve: (SeedExerciseKey) -> Long?): List<TemplateExerciseDraft>? =
        exercises.map { exercise ->
            TemplateExerciseDraft(
                exerciseId = resolve(exercise.key) ?: return null,
                sets = exercise.sets,
                restDuration = exercise.restDuration,
                supersetGroup = exercise.supersetGroup,
            )
        }
}

/**
 * One exercise of a [DefaultTemplateSpec].
 *
 * @property supersetGroup in the canonical form `Supersets.normalize` produces — numbered from
 *   0, contiguous, at least two members — because the seed writes it straight into
 *   `template_exercise` without passing through the template use cases that would normalise it.
 */
internal data class DefaultExerciseSpec(
    val key: SeedExerciseKey,
    val sets: List<PlannedSet>,
    val restDuration: Long?,
    val supersetGroup: Int?,
)

/**
 * How a default names a seeded exercise: the catalogue's unique key, on a row with no machine
 * brand. Never an id — the seed's ids are positional and move on every regeneration.
 */
internal data class SeedExerciseKey(
    val name: String,
    val equipmentType: EquipmentType,
)

/** The receiver of [DefaultTemplates]' definitions, filling in the template's rest by default. */
internal class DefaultTemplateBuilder(private val rest: Long?) {

    val exercises = mutableListOf<DefaultExerciseSpec>()

    /** [sets] working sets of [reps] reps. */
    fun lift(
        name: String,
        equipment: EquipmentType,
        sets: Int,
        reps: Int,
        rest: Long? = this.rest,
        superset: Int? = null,
    ) = sets(name, equipment, List(sets) { PlannedSet(reps = reps) }, rest, superset)

    /** [sets] sets with no figure planned: a hold or a stretch, timed on the day. */
    fun hold(
        name: String,
        equipment: EquipmentType,
        sets: Int,
        rest: Long? = this.rest,
    ) = sets(name, equipment, List(sets) { PlannedSet() }, rest, superset = null)

    fun sets(
        name: String,
        equipment: EquipmentType,
        sets: List<PlannedSet>,
        rest: Long? = this.rest,
        superset: Int? = null,
    ) {
        exercises += DefaultExerciseSpec(
            key = SeedExerciseKey(name, equipment),
            sets = sets,
            restDuration = rest,
            supersetGroup = superset,
        )
    }
}
