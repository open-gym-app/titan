package com.titan.core.data.healthconnect

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.WorkoutActivity
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
 * The write-then-read round trip against the real Health Connect provider: sessions (#54), and
 * weight and body fat (#350).
 *
 * The one test in `:core:data` that needs a device: the provider is a system service on Android 14
 * and an app below it, and neither can be driven from Robolectric. What the mapping decides is
 * covered on the JVM by `HealthConnectRecordsTest`; this proves the provider stores what it
 * builds, hands it back as this app's own, and upserts a rewrite rather than adding a copy.
 *
 * Android 14 or later only, where Health Connect's permissions are ordinary runtime permissions
 * the instrumentation can grant. Below it they belong to the Health Connect app and can only be
 * granted through its dialog, so the test is skipped there rather than failed.
 *
 * ```sh
 * ./gradlew :core:data:connectedDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectAdapterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val adapter = HealthConnectAdapter(context)

    /** A session id no real history on a test device will hold. */
    private val sessionId = 900_000_000L + (System.nanoTime() % 1_000_000L)

    /** An hour ago, to the millisecond, so the read-back can be compared exactly. */
    private val start = (System.currentTimeMillis() - 3_600_000L) / 1_000L * 1_000L

    @Before
    fun grantAccess() {
        assumeTrue("Health Connect permissions are grantable from Android 14", Build.VERSION.SDK_INT >= 34)
        assumeTrue(
            "Health Connect is not available on this device",
            adapter.availability() == HealthConnectAvailability.AVAILABLE,
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        adapter.permissions.forEach { automation.grantRuntimePermission(context.packageName, it) }
    }

    @After
    fun deleteWhatWasWritten() {
        if (Build.VERSION.SDK_INT < 34 || adapter.availability() != HealthConnectAvailability.AVAILABLE) return
        runBlocking {
            HealthConnectClient.getOrCreate(context).deleteRecords(
                ExerciseSessionRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(clientRecordIdFor(sessionId, start)),
            )
            HealthConnectBodyMetric.entries.forEach { adapter.deleteBodyReading(sessionId, start, it) }
        }
    }

    @Test
    fun the_grant_is_read_back_as_every_permission() =
        runBlocking {
            assertEquals(HealthConnectAccess.entries.toSet(), adapter.grantedAccess())
        }

    @Test
    fun a_written_session_is_read_back_as_this_apps_own() =
        runBlocking {
            val written = session(title = "Push day")

            adapter.writeSession(sessionId, version = 1L, session = written)
            val read = adapter.readSessions(start, start + 1).single { it.session.title == "Push day" }

            assertEquals(written, read.session)
            assertTrue("a record Titan wrote must be recognised as Titan's", read.writtenByThisApp)
        }

    @Test
    fun a_rewrite_of_the_same_session_replaces_the_record() =
        runBlocking {
            adapter.writeSession(sessionId, version = 1L, session = session(title = "Push day"))
            adapter.writeSession(sessionId, version = 2L, session = session(title = "Push day, renamed"))

            val records = HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(start),
                        Instant.ofEpochMilli(start + 1),
                    ),
                ),
            ).records.filter { it.metadata.clientRecordId == clientRecordIdFor(sessionId, start) }

            assertEquals(1, records.size)
            assertEquals("Push day, renamed", records.single().title)
            assertEquals(2L, records.single().metadata.clientRecordVersion)
        }

    @Test
    fun a_stale_rewrite_loses() =
        runBlocking {
            adapter.writeSession(sessionId, version = 5L, session = session(title = "Newer"))
            adapter.writeSession(sessionId, version = 4L, session = session(title = "Older"))

            val read = adapter.readSessions(start, start + 1).filter { it.writtenByThisApp }

            assertEquals(listOf("Newer"), read.map { it.session.title })
        }

    @Test
    fun a_written_weight_and_body_fat_are_read_back_as_this_apps_own() =
        runBlocking {
            val weight = HealthConnectBodyReading(HealthConnectBodyMetric.WEIGHT, start, 80_512_000L)
            val bodyFat = HealthConnectBodyReading(HealthConnectBodyMetric.BODY_FAT, start, 155L)

            adapter.writeBodyReading(sessionId, version = 1L, reading = weight)
            adapter.writeBodyReading(sessionId, version = 1L, reading = bodyFat)

            val weights = adapter.readBodyReadings(
                HealthConnectBodyMetric.WEIGHT,
                start,
                start + 1,
            ).filter { it.writtenByThisApp }
            val fats = adapter.readBodyReadings(
                HealthConnectBodyMetric.BODY_FAT,
                start,
                start + 1,
            ).filter { it.writtenByThisApp }
            assertEquals(listOf(weight), weights.map { it.reading })
            assertEquals(listOf(bodyFat), fats.map { it.reading })
        }

    @Test
    fun a_rewrite_of_the_same_weight_replaces_the_record() =
        runBlocking {
            adapter.writeBodyReading(
                sessionId,
                1L,
                HealthConnectBodyReading(HealthConnectBodyMetric.WEIGHT, start, 80_000_000L),
            )
            adapter.writeBodyReading(
                sessionId,
                2L,
                HealthConnectBodyReading(HealthConnectBodyMetric.WEIGHT, start, 79_500_000L),
            )

            val records = ownWeights()

            assertEquals(1, records.size)
            assertEquals(79_500.0, records.single().weight.inGrams, 0.5)
        }

    @Test
    fun a_deleted_weight_is_gone_and_a_body_fat_never_written_deletes_without_error() =
        runBlocking {
            adapter.writeBodyReading(
                sessionId,
                1L,
                HealthConnectBodyReading(HealthConnectBodyMetric.WEIGHT, start, 80_000_000L),
            )

            adapter.deleteBodyReading(sessionId, start, HealthConnectBodyMetric.WEIGHT)
            // The sync removes a figure an entry does not hold whether or not it was ever written.
            adapter.deleteBodyReading(sessionId, start, HealthConnectBodyMetric.BODY_FAT)

            assertTrue(ownWeights().isEmpty())
            assertTrue(
                HealthConnectClient.getOrCreate(context).readRecords(
                    ReadRecordsRequest(
                        BodyFatRecord::class,
                        TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 1)),
                    ),
                ).records.none { it.metadata.dataOrigin.packageName == context.packageName },
            )
        }

    private suspend fun ownWeights(): List<WeightRecord> =
        HealthConnectClient.getOrCreate(context).readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 1)),
            ),
        ).records.filter {
            it.metadata.clientRecordId == bodyClientRecordIdFor(HealthConnectBodyMetric.WEIGHT, sessionId, start)
        }

    private fun session(title: String) =
        HealthConnectSession(
            startedAt = start,
            endedAt = start + 3_000_000L,
            activity = WorkoutActivity.WEIGHTLIFTING,
            title = title,
            notes = "felt strong",
        )
}
