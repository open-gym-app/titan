package com.titan.core.data.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.health.ImportFromHealthConnectUseCase
import com.titan.core.domain.health.SyncToHealthConnectUseCase
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthPermissions
import com.titan.core.domain.model.HealthSyncOutcome
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import com.titan.core.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The Health Connect round trip, on a device: write a finished session, read it back, and prove
 * a second write and a second import add nothing.
 *
 * Needs Health Connect installed (API 34+ ships it; earlier releases need the app) — without it
 * every test is skipped, not failed. The two exercise permissions are granted through
 * UiAutomation so no dialog has to be driven. Not run by the JVM suite: there is no device there.
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectRoundTripTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = HealthConnectGatewayImpl(
        sdkInt = Build.VERSION.SDK_INT,
        sdkStatus = { HealthConnectClient.getSdkStatus(context) },
        client = { HealthConnectClient.getOrCreate(context) },
        nowMillis = System::currentTimeMillis,
    )
    private val db = Room.inMemoryDatabaseBuilder(context, TitanDatabase::class.java).build()
    private val sessions = SessionRepositoryImpl(db, SessionMapper(), SessionExerciseMapper(), WorkoutSetMapper())
    private val toggles = object : FeatureToggles {
        override fun observe(feature: M8Feature): Flow<Boolean> = flowOf(true)

        override suspend fun isEnabled(feature: M8Feature) = true

        override suspend fun setEnabled(
            feature: M8Feature,
            enabled: Boolean,
        ) = Unit
    }
    private val start = System.currentTimeMillis() - 2 * HOUR

    @Before
    fun setUp() {
        assumeTrue(gateway.availability() == HealthAvailability.AVAILABLE)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        HealthPermissions.REQUIRED.forEach { automation.grantRuntimePermission(context.packageName, it) }
        assumeTrue(runBlocking { gateway.hasPermissions() })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun foundAt(startMillis: Long): List<HealthWorkout> =
        runBlocking { gateway.readBetween(startMillis - MINUTE, startMillis + MINUTE) }
            .filter { it.startMillis == startMillis }

    @Test
    fun a_written_workout_reads_back_and_a_rewrite_updates_it_in_place() {
        val workout = HealthWorkout(
            startMillis = start,
            endMillis = start + HOUR,
            type = HealthWorkoutType.STRENGTH_TRAINING,
            title = "Round trip",
            externalId = HealthWorkout.externalIdFor(start),
        )

        runBlocking { gateway.write(workout) }
        runBlocking { gateway.write(workout) }

        val found = foundAt(start).single()
        assertEquals("Round trip", found.title)
        assertEquals(HealthWorkoutType.STRENGTH_TRAINING, found.type)
        assertTrue(found.isOwn)
    }

    @Test
    fun a_finished_session_is_synced_and_a_foreign_workout_is_imported_once() {
        val sessionId = runBlocking {
            sessions.insert(
                WorkoutSession(
                    name = "Synced",
                    completedAt = start + HOUR,
                    isInProgress = false,
                    lastActiveAt = start + HOUR,
                    createdAt = start,
                    updatedAt = start + HOUR,
                ),
            )
        }

        val synced = runBlocking { SyncToHealthConnectUseCase(sessions, gateway, toggles)(sessionId) }
        assertEquals(HealthSyncOutcome.Synced, synced.getOrThrow())
        assertEquals("Synced", foundAt(start).single().title)

        val foreignStart = start - 3 * HOUR
        runBlocking {
            HealthConnectClient.getOrCreate(context).insertRecords(
                listOf(
                    ExerciseSessionRecord(
                        startTime = Instant.ofEpochMilli(foreignStart),
                        startZoneOffset = null,
                        endTime = Instant.ofEpochMilli(foreignStart + HOUR),
                        endZoneOffset = null,
                        metadata = Metadata.manualEntry(),
                        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                        title = "From elsewhere",
                    ),
                ),
            )
        }

        val import = ImportFromHealthConnectUseCase(sessions, gateway, toggles) { System.currentTimeMillis() }
        val first = runBlocking { import() }.getOrThrow() as HealthSyncOutcome.Imported
        val second = runBlocking { import() }.getOrThrow() as HealthSyncOutcome.Imported

        assertTrue(first.imported >= 1)
        assertEquals(0, second.imported)
        val local = runBlocking { sessions.getAll().first() }
        assertEquals(1, local.count { it.createdAt == foreignStart })
        assertEquals(1, local.count { it.createdAt == start })
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
