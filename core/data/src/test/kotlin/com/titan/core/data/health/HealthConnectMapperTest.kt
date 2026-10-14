package com.titan.core.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectMapperTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun record(
        type: Int,
        start: Long = 1_000_000,
        end: Long = 4_000_000,
        metadata: Metadata = Metadata.manualEntry("titan-session-4", 9),
    ) = ExerciseSessionRecord(
        startTime = Instant.ofEpochMilli(start),
        startZoneOffset = null,
        endTime = Instant.ofEpochMilli(end),
        endZoneOffset = null,
        metadata = metadata,
        exerciseType = type,
        title = "Push",
        notes = "n",
    )

    @Test
    fun `a workout becomes a strength training record`() {
        val record = HealthConnectMapper.toRecord(
            HealthWorkout(
                startMillis = 1_000_000,
                endMillis = 4_000_000,
                type = HealthWorkoutType.STRENGTH_TRAINING,
                title = "Push",
                notes = "n",
                externalId = "titan-session-4",
            ),
            version = 9,
            zone = zone,
        )

        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, record.exerciseType)
        assertEquals(Instant.ofEpochMilli(1_000_000), record.startTime)
        assertEquals(Instant.ofEpochMilli(4_000_000), record.endTime)
        assertEquals(zone.rules.getOffset(record.startTime), record.startZoneOffset)
        assertEquals("Push", record.title)
        assertEquals("n", record.notes)
        assertEquals("titan-session-4", record.metadata.clientRecordId)
        assertEquals(9L, record.metadata.clientRecordVersion)
    }

    @Test
    fun `a workout with no external id is written without a client id`() {
        val record = HealthConnectMapper.toRecord(
            HealthWorkout(1_000, 2_000, HealthWorkoutType.STRENGTH_TRAINING),
            version = 1,
        )

        assertNull(record.metadata.clientRecordId)
    }

    @Test
    fun `strength training and weightlifting read back as strength`() {
        listOf(
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ).forEach {
            assertEquals(HealthWorkoutType.STRENGTH_TRAINING, HealthConnectMapper.toWorkout(record(it)).type)
        }
    }

    @Test
    fun `any other kind reads back as other, keeping what was recorded`() {
        val workout = HealthConnectMapper.toWorkout(record(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))

        assertEquals(
            HealthWorkout(1_000_000, 4_000_000, HealthWorkoutType.OTHER, "Push", "n", "titan-session-4"),
            workout,
        )
    }

    @Test
    fun `a record shorter than a millisecond is rounded up`() {
        val short = ExerciseSessionRecord(
            startTime = Instant.ofEpochSecond(5, 100),
            startZoneOffset = null,
            endTime = Instant.ofEpochSecond(5, 900),
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        )

        assertEquals(5_001L, HealthConnectMapper.toWorkout(short).endMillis)
    }
}
