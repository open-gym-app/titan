package com.titan.app.store

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.usecase.NextLoadUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Five months of a plausible lifter's training, written into a fresh database the way the app
 * would have written it.
 *
 * ### Who the lifter is
 *
 * An intermediate on a push/pull/legs split three evenings a week, built entirely from the
 * default templates (#213) so the history and the template list agree: every session names the
 * template it was started from and lays its exercises out in that template's order, with the
 * template's number of sets. About one planned day in ten is missed. A weekly weigh-in drifts
 * down by a couple of kilograms, with a tape and a body-fat reading at the start of each month.
 *
 * ### The lifter does what the app suggests
 *
 * Every exercise's load and reps come from [nextLoad] — the same next-target line (#216) the
 * active session draws — asked before each session, over the history written so far. So the
 * progression is the app's own double progression rather than a curve invented here, and the
 * target the store screenshot shows on the next workout is one this history really earns.
 *
 * The lifter is not a machine: the last set falls a rep short now and then, and the first
 * session at a new weight sometimes misses the bottom of the range — which the engine answers
 * with a deload, the way it would for a real one. A first session with no history starts at the
 * exercise's [Lift.startKg] and the bottom of the range. Bodyweight movements stop at a
 * [Lift.maxReps] a real person reaches, however many more the engine asks for.
 *
 * ### Why rows and the real cascade, rather than a hand-written JSON file
 *
 * Personal records, XP, the streak, achievements, quests and loot are all *derived* from the
 * sets, by `SessionCompletionPipeline`. A hand-written file would have to restate every rule
 * those engines apply and would drift the day one of them changed. So the sessions, exercises
 * and sets are inserted as finished rows and [pipeline] is run over each one, in order, at the
 * moment it finished — which is what `FinishSessionUseCase` does at the end of a real workout.
 *
 * The workout lifecycle use cases are not used to write the sets because they stamp rows with
 * the wall clock; inserting the rows directly is what lets every timestamp be the demo's own.
 *
 * ### Deterministic
 *
 * Every decision is drawn from [random], seeded by the caller, and every timestamp is derived
 * from the last training day. Two runs produce the same rows with the same ids, which is what
 * lets `DemoDatasetTest` hold the committed export to the generator byte for byte.
 */
internal class DemoHistory(
    private val exercises: ExerciseRepository,
    private val templates: TemplateRepository,
    private val sessions: SessionRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val nextLoad: NextLoadUseCase,
    private val pipeline: SessionCompletionPipeline,
    private val random: Random,
) {

    /**
     * Writes [SESSION_COUNT] finished sessions ending on [lastTrainingDay], and the weigh-ins
     * that go with them.
     *
     * @param templateNames the three default templates' names in the language the database was
     *   seeded in, push first — the seed localises them, so they are the only handle a caller
     *   has on which template is which.
     * @param repRange the range the user's goal targets, which decides where a first session
     *   starts and where a missed set lands.
     */
    suspend fun write(
        templateNames: List<String>,
        repRange: RepRange,
        lastTrainingDay: LocalDate,
    ) {
        val rotation = templateNames.map { name -> templates.getAll().first().single { it.name == name } }
        val days = trainingDays(lastTrainingDay)

        // Assigned so that the last day is the first template of the rotation, whatever the
        // count: the day after the dataset ends is then the second, which is the workout the
        // store screenshots show in progress.
        days.forEachIndexed { index, day ->
            writeSession(rotation[Math.floorMod(index - (days.size - 1), rotation.size)], day, repRange)
        }
        writeWeighIns(from = days.first(), to = lastTrainingDay)
    }

    /**
     * The [SESSION_COUNT] days trained, oldest first: Monday, Wednesday and Friday, counted back
     * from [last], with about one in ten skipped. [last] itself is always trained.
     */
    private fun trainingDays(last: LocalDate): List<LocalDate> {
        val days = ArrayDeque<LocalDate>()
        var day = last
        while (days.size < SESSION_COUNT) {
            val planned = day.dayOfWeek in TRAINING_DAYS
            if (planned && (day == last || random.nextDouble() >= SKIP_PROBABILITY)) days.addFirst(day)
            day = day.minusDays(1)
        }
        return days.toList()
    }

    private suspend fun writeSession(
        template: WorkoutTemplate,
        day: LocalDate,
        repRange: RepRange,
    ) {
        val planned = templates.getExercises(template.id).first().sortedBy { it.position }
        val catalogue = exercises.getByIds(planned.map { it.exerciseId }).associateBy(Exercise::id)

        // Evenings, on the quarter hour give or take: people start when they get there.
        var clock = day.atTime(START_HOUR, 0).toInstant(ZoneOffset.UTC).toEpochMilli() +
            random.nextInt(START_SPREAD_MINUTES / FIVE_MINUTES) * FIVE_MINUTES * MINUTE
        val startedAt = clock

        val sessionId = sessions.insert(
            WorkoutSession(
                templateId = template.id,
                name = template.name,
                isInProgress = false,
                lastActiveAt = startedAt,
                createdAt = startedAt,
                updatedAt = startedAt,
            ),
        )
        val exerciseIds = sessions.insertExercises(
            sessionId = sessionId,
            exercises = planned.map { SessionExerciseDraft(exerciseId = it.exerciseId, templateExerciseId = it.id) },
            createdAt = startedAt,
        )

        clock += WARM_UP_MINUTES * MINUTE
        val sets = mutableListOf<WorkoutSet>()
        planned.zip(exerciseIds).forEach { (templateExercise, sessionExerciseId) ->
            val performed = perform(
                exercise = catalogue.getValue(templateExercise.exerciseId),
                setCount = templateExercise.sets.size,
                sessionId = sessionId,
                sessionExerciseId = sessionExerciseId,
                repRange = repRange,
                from = clock,
            )
            sets += performed.sets
            clock = performed.endsAt + CHANGEOVER_SECONDS * SECOND
        }
        sessions.insertSets(sets)

        val finishedAt = sets.last().completedAt + COOL_DOWN_MINUTES * MINUTE
        sessions.update(
            sessions.getById(sessionId).first()!!.copy(
                completedAt = finishedAt,
                lastActiveAt = finishedAt,
                updatedAt = finishedAt,
            ),
        )
        pipeline(sessionId, Instant.ofEpochMilli(finishedAt))
    }

    /** One exercise's sets, and when the rest after the last of them ends. */
    private class Performed(
        val sets: List<WorkoutSet>,
        val endsAt: Long,
    )

    /**
     * [setCount] sets of [exercise] at what [nextLoad] suggests for it, the first set finishing
     * a set's length after [from].
     */
    private suspend fun perform(
        exercise: Exercise,
        setCount: Int,
        sessionId: Long,
        sessionExerciseId: Long,
        repRange: RepRange,
        from: Long,
    ): Performed {
        val lift = LIFTS[exercise.name] ?: error("No starting point for \"${exercise.name}\": add it to LIFTS")
        val target = nextLoad(exercise.id, sessionId).first()
        val weight = if (target == null) lift.startMilligrams else target.weight
        val reps = minOf(target?.reps ?: repRange.min, lift.maxReps)
        // The first session at a heavier weight is where a set is missed outright.
        val missed = target?.reason == SuggestionReason.INCREASE && random.nextInt(MISS_ODDS) == 0

        var clock = from
        val sets = List(setCount) { index ->
            clock += lift.secondsPerSet * SECOND
            val completedAt = clock
            clock += (lift.restSeconds + random.nextInt(-REST_JITTER_SECONDS, REST_JITTER_SECONDS)) * SECOND
            WorkoutSet(
                sessionExerciseId = sessionExerciseId,
                setNumber = index + 1,
                reps = when {
                    index < setCount - 1 -> reps

                    missed -> repRange.min - 1

                    // A rep short, but never out of the range: that would be a miss.
                    else -> maxOf(reps - shortfall(), minOf(reps, repRange.min))
                },
                weight = weight,
                completedAt = completedAt,
                isCompleted = true,
                createdAt = completedAt,
                updatedAt = completedAt,
            )
        }
        return Performed(sets, endsAt = clock)
    }

    /** Usually nothing; now and then the last set of an exercise is a rep short. */
    private fun shortfall(): Int = if (random.nextInt(SHORTFALL_ODDS) == 0) 1 else 0

    /**
     * A Monday-morning weigh-in for every week of the history, with the tape and the callipers
     * out on the first Monday of each month.
     */
    private suspend fun writeWeighIns(
        from: LocalDate,
        to: LocalDate,
    ) {
        val mondays = generateSequence(from.with(DayOfWeek.MONDAY)) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(to) }
            .toList()
        mondays.forEachIndexed { index, monday ->
            val progress = index.toDouble() / (mondays.size - 1)
            val at = monday.atTime(WEIGH_IN_HOUR, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val monthly = monday.dayOfMonth <= DAYS_IN_A_WEEK
            val jitter = random.nextInt(-WEIGHT_JITTER_GRAMS, WEIGHT_JITTER_GRAMS + 1)
            bodyMeasurements.insert(
                BodyMeasurement(
                    weight = (lerp(BODY_WEIGHT_START_GRAMS, BODY_WEIGHT_END_GRAMS, progress) + jitter)
                        .roundToScale() * MILLIGRAMS_PER_GRAM,
                    waist = if (monthly) lerp(WAIST_START_MM, WAIST_END_MM, progress).roundToLong().toInt() else null,
                    bodyFat = if (monthly) lerp(BODY_FAT_START, BODY_FAT_END, progress).roundToLong().toInt() else null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }
    }

    /**
     * Where one exercise starts, how far a bodyweight one goes, and how long a set and the rest
     * after it take. Everything after the first session is [nextLoad]'s.
     *
     * @property startKg the first session's load, or null for a bodyweight movement logged by
     *   reps alone.
     */
    private class Lift(
        val startKg: Double?,
        val maxReps: Int = Int.MAX_VALUE,
        val restSeconds: Int = ACCESSORY_REST_SECONDS,
        val secondsPerSet: Int = ACCESSORY_SET_SECONDS,
    ) {
        val startMilligrams: Long? get() = startKg?.let { (it * MILLIGRAMS_PER_KG).roundToLong() }
    }

    companion object {

        /** "~60 sessions", as #46 asks: about five months of a three-day split. */
        const val SESSION_COUNT = 60

        private val TRAINING_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        private const val SKIP_PROBABILITY = 0.1
        private const val MISS_ODDS = 8
        private const val SHORTFALL_ODDS = 5

        private const val START_HOUR = 17
        private const val START_SPREAD_MINUTES = 90
        private const val FIVE_MINUTES = 5
        private const val WARM_UP_MINUTES = 6
        private const val COOL_DOWN_MINUTES = 4
        private const val CHANGEOVER_SECONDS = 90
        private const val REST_JITTER_SECONDS = 20
        private const val COMPOUND_REST_SECONDS = 150
        private const val ACCESSORY_REST_SECONDS = 90
        private const val COMPOUND_SET_SECONDS = 50
        private const val ACCESSORY_SET_SECONDS = 40

        private const val DAYS_IN_A_WEEK = 7
        private const val WEIGH_IN_HOUR = 7
        private const val BODY_WEIGHT_START_GRAMS = 81_600.0
        private const val BODY_WEIGHT_END_GRAMS = 79_100.0
        private const val WEIGHT_JITTER_GRAMS = 300
        private const val SCALE_RESOLUTION_GRAMS = 100
        private const val WAIST_START_MM = 880.0
        private const val WAIST_END_MM = 850.0
        private const val BODY_FAT_START = 185.0
        private const val BODY_FAT_END = 162.0

        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val MILLIGRAMS_PER_GRAM = 1_000L
        private const val MILLIGRAMS_PER_KG = 1_000_000.0

        private fun compound(startKg: Double) =
            Lift(startKg, restSeconds = COMPOUND_REST_SECONDS, secondsPerSet = COMPOUND_SET_SECONDS)

        /**
         * Every exercise the push, pull and legs defaults name, by the catalogue's name. A
         * default that gains an exercise fails the generator loudly rather than writing a
         * session without it.
         */
        private val LIFTS = mapOf(
            // Push
            "Barbell Bench Press - Medium Grip" to compound(60.0),
            "Barbell Shoulder Press" to compound(37.5),
            "Incline Dumbbell Press" to Lift(20.0),
            "Side Lateral Raise" to Lift(8.0),
            "Triceps Pushdown - Rope Attachment" to Lift(20.0),
            "Dips - Triceps Version" to Lift(null, maxReps = 15),
            // Pull
            "Barbell Deadlift" to compound(100.0),
            "Pullups" to Lift(null, maxReps = 12),
            "Bent Over Barbell Row" to compound(55.0),
            "Seated Cable Rows" to Lift(50.0),
            "Face Pull" to Lift(15.0),
            "Barbell Curl" to Lift(25.0),
            // Legs
            "Barbell Squat" to compound(80.0),
            "Romanian Deadlift" to compound(70.0),
            "Leg Press" to Lift(140.0),
            "Dumbbell Lunges" to Lift(16.0),
            "Lying Leg Curls" to Lift(35.0),
            "Standing Calf Raises" to Lift(60.0),
        )

        private fun lerp(
            from: Double,
            to: Double,
            progress: Double,
        ): Double = from + (to - from) * progress

        /** To the nearest 100 g, the resolution a bathroom scale reads to. */
        private fun Double.roundToScale(): Long = (this / SCALE_RESOLUTION_GRAMS).roundToLong() * SCALE_RESOLUTION_GRAMS
    }
}
