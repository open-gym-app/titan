package com.titan.core.domain.warmup

import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.MechanicType
import com.titan.core.domain.model.MobilityExercise
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.SessionMovement

/*
 * The catalogue the warm-up tests plan against: a handful of muscles, four working lifts and a
 * dozen mobility movements, all with the shapes the seed gives them — one prime mover each, a
 * `DYNAMIC` flag on the movements that travel, and a mechanic on the lifts.
 *
 * Small on purpose. The planner's decisions are "which muscles does this session touch" and
 * "which of these movements match", and a fixture of 873 rows would prove those with numbers
 * nobody can check by reading.
 */

val CHEST = MuscleGroup(id = 1, name = "CHEST")
val TRICEPS = MuscleGroup(id = 2, name = "TRICEPS")
val SHOULDERS = MuscleGroup(id = 3, name = "SHOULDERS")
val QUADRICEPS = MuscleGroup(id = 4, name = "QUADRICEPS")
val HAMSTRINGS = MuscleGroup(id = 5, name = "HAMSTRINGS")

/** A weighted compound lift: what the ramp-up is built for. */
fun lift(
    id: Long,
    name: String,
    primary: MuscleGroup,
    secondary: List<MuscleGroup> = emptyList(),
    mechanic: MechanicType? = MechanicType.COMPOUND,
    equipment: EquipmentType = EquipmentType.BARBELL,
    workingWeight: Long? = null,
    type: ExerciseType = ExerciseType.STRENGTH,
    discipline: Discipline = Discipline.STRENGTH,
    sessionExerciseId: Long = id,
): SessionMovement =
    SessionMovement(
        exercise = Exercise(
            id = id,
            name = name,
            type = type,
            discipline = discipline,
            equipmentType = equipment,
            mechanicType = mechanic,
        ),
        muscleGroups = links(primary, secondary),
        workingWeight = workingWeight,
        sessionExerciseId = sessionExerciseId,
    )

/** One mobility movement of the catalogue, dynamic or static. */
fun mobility(
    id: Long,
    name: String,
    primary: MuscleGroup,
    isDynamic: Boolean,
    discipline: Discipline = Discipline.STRETCHING,
): MobilityExercise =
    MobilityExercise(
        exercise = Exercise(id = id, name = name, type = ExerciseType.ISOMETRIC, discipline = discipline),
        primaryMuscleGroupIds = setOf(primary.id),
        isDynamic = isDynamic,
    )

fun links(
    primary: MuscleGroup,
    secondary: List<MuscleGroup> = emptyList(),
): List<ExerciseMuscleGroupLink> =
    listOf(ExerciseMuscleGroupLink(muscleGroup = primary, isPrimary = true)) +
        secondary.map { ExerciseMuscleGroupLink(muscleGroup = it, isPrimary = false) }

/**
 * Two dynamic movements and two static stretches per muscle, so every test has a *choice* to
 * make: with one candidate each there is nothing for the ordering or the coverage rule to do.
 */
val CATALOGUE: List<MobilityExercise> = buildList {
    var id = 100L
    listOf(CHEST, TRICEPS, SHOULDERS, QUADRICEPS, HAMSTRINGS).forEach { muscle ->
        val name = muscle.name.lowercase()
        add(mobility(id++, "$name swing", muscle, isDynamic = true))
        add(mobility(id++, "$name circle", muscle, isDynamic = true))
        add(mobility(id++, "$name stretch", muscle, isDynamic = false))
        add(mobility(id++, "$name hold", muscle, isDynamic = false))
    }
    // A plyometric movement that the seed leaves untagged — it is neither a warm-up candidate
    // (not dynamic) nor a cool-down one (not a stretch), and no test should ever see it.
    add(mobility(id, "depth drop", QUADRICEPS, isDynamic = false, discipline = Discipline.PLYOMETRICS))
}

/** The muscle a suggested movement moves, for asserting on a pick without naming rows. */
fun MobilityExercise.muscleId(): Long = primaryMuscleGroupIds.single()
