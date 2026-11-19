package com.titan.core.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.WorkoutActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The mapping between the domain's session and Health Connect's record, without a provider.
 *
 * What the provider does with the record — stores it, hands it back, upserts on the client id — is
 * the instrumented `HealthConnectAdapterTest`'s, which needs a device. Everything decided on this
 * side of the IPC is decided here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HealthConnectRecordsTest {

    private val paris = ZoneId.of("Europe/Paris")

    // 2026-07-01T08:00:00Z, during summer time: Paris is UTC+2.
    private val start = 1_782_892_800_000L
    private val session = HealthConnectSession(
        startedAt = start,
        endedAt = start + 3_600_000L,
        activity = WorkoutActivity.WEIGHTLIFTING,
        title = "Push day",
        notes = "felt strong",
    )

    @Test
    fun `a session is written with its span, its zone, its type and its text`() {
        val record = session.toRecord(sessionId = 42L, version = 7L, zone = paris)

        assertEquals(Instant.ofEpochMilli(start), record.startTime)
        assertEquals(Instant.ofEpochMilli(start + 3_600_000L), record.endTime)
        assertEquals(ZoneOffset.ofHours(2), record.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(2), record.endZoneOffset)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING, record.exerciseType)
        assertEquals("Push day", record.title)
        assertEquals("felt strong", record.notes)
    }

    @Test
    fun `a session is written under a client id and version that make a rewrite an upsert`() {
        val metadata = session.toRecord(sessionId = 42L, version = 7L, zone = paris).metadata

        assertEquals("titan-session-42-$start", metadata.clientRecordId)
        assertEquals(7L, metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, metadata.recordingMethod)
    }

    @Test
    fun `a later workout given a reused id is written under a client id of its own`() {
        val first = session.toRecord(sessionId = 1L, version = 7L, zone = paris).metadata
        val reused = session.copy(startedAt = start + 86_400_000L, endedAt = start + 90_000_000L)
            .toRecord(sessionId = 1L, version = 8L, zone = paris)
            .metadata

        assertNotEquals(first.clientRecordId, reused.clientRecordId)
    }

    @Test
    fun `every activity survives the trip out and back`() {
        WorkoutActivity.entries.forEach { activity ->
            assertEquals(activity, activity.toExerciseType().toWorkoutActivity())
        }
    }

    @Test
    fun `the variants Health Connect splits by place are folded into one activity`() {
        assertEquals(WorkoutActivity.RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL.toWorkoutActivity())
        assertEquals(WorkoutActivity.BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY.toWorkoutActivity())
        assertEquals(
            WorkoutActivity.SWIMMING,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER.toWorkoutActivity(),
        )
        assertEquals(WorkoutActivity.ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE.toWorkoutActivity())
    }

    @Test
    fun `a type the app has no name for is imported as other`() {
        assertEquals(WorkoutActivity.OTHER, ExerciseSessionRecord.EXERCISE_TYPE_TENNIS.toWorkoutActivity())
    }

    @Test
    fun `a record read back carries its span, type and text`() {
        val read = session.toRecord(sessionId = 42L, version = 7L, zone = paris).toDomain(ownPackage = "com.other")

        assertEquals(session, read.session)
    }

    @Test
    fun `a record is Titan's own when its origin is this package`() {
        // A record built here has no origin until the provider stamps one, and its package is
        // empty; the comparison is all there is to test on this side.
        val record = session.toRecord(sessionId = 42L, version = 7L, zone = paris)
        val origin = record.metadata.dataOrigin.packageName

        assertTrue(record.toDomain(ownPackage = origin).writtenByThisApp)
        assertFalse(record.toDomain(ownPackage = "$origin.other").writtenByThisApp)
    }
}
