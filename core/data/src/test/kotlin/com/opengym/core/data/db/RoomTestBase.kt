package com.opengym.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.data.db.entity.BodyWeightLogEntity
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.entity.SessionExerciseEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.WorkoutSessionEntity
import com.opengym.core.data.db.entity.WorkoutSetEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base for DAO tests: builds a fresh in-memory [OpenGymDatabase] on a real
 * (Robolectric) SQLite for each test, with foreign-key enforcement enabled so
 * cascade/restrict/set-null behaviour can be asserted. Also provides entity
 * factories with valid defaults so individual tests only specify what they care
 * about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
abstract class RoomTestBase {

    protected lateinit var db: OpenGymDatabase

    @Before
    fun setUpDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenGymDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .build()
    }

    @After
    fun tearDownDatabase() {
        db.close()
    }

    // --- entity factories -------------------------------------------------

    protected fun exercise(
        name: String = "Bench Press",
        exerciseType: String = "STRENGTH",
        equipmentType: String? = "BARBELL",
        machineBrand: String? = null,
    ) = ExerciseEntity(
        name = name,
        exerciseType = exerciseType,
        equipmentType = equipmentType,
        machineBrand = machineBrand,
    )

    protected fun muscleGroup(name: String = "CHEST") = MuscleGroupEntity(name = name)

    protected fun tag(name: String = "PUSH", isSystem: Boolean = false) =
        TagEntity(name = name, isSystem = isSystem)

    protected fun template(name: String = "Push Day") = WorkoutTemplateEntity(name = name)

    protected fun templateExercise(
        templateId: Long,
        exerciseId: Long,
        position: Int,
    ) = TemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, position = position)

    protected fun session(
        templateId: Long? = null,
        isInProgress: Boolean = true,
        lastActiveAt: Long = 0,
        createdAt: Long = 0,
    ) = WorkoutSessionEntity(
        templateId = templateId,
        isInProgress = isInProgress,
        lastActiveAt = lastActiveAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    protected fun sessionExercise(
        sessionId: Long,
        exerciseId: Long,
        position: Int,
        templateExerciseId: Long? = null,
    ) = SessionExerciseEntity(
        sessionId = sessionId,
        exerciseId = exerciseId,
        position = position,
        templateExerciseId = templateExerciseId,
        createdAt = 0,
        updatedAt = 0,
    )

    protected fun workoutSet(
        sessionExerciseId: Long,
        setNumber: Int,
        isCompleted: Boolean = true,
        completedAt: Long = 0,
    ) = WorkoutSetEntity(
        sessionExerciseId = sessionExerciseId,
        setNumber = setNumber,
        isCompleted = isCompleted,
        completedAt = completedAt,
        createdAt = 0,
        updatedAt = 0,
    )

    protected fun personalRecord(
        exerciseId: Long,
        workoutSetId: Long,
        prType: String = "MAX_WEIGHT",
        achievedAt: Long = 0,
    ) = PersonalRecordEntity(
        exerciseId = exerciseId,
        workoutSetId = workoutSetId,
        prType = prType,
        achievedAt = achievedAt,
    )

    protected fun bodyWeightLog(weight: Long = 80_000_000, createdAt: Long = 0) =
        BodyWeightLogEntity(weight = weight, createdAt = createdAt, updatedAt = createdAt)
}
