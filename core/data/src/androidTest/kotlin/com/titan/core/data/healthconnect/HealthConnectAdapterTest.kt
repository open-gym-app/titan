package com.titan.core.data.healthconnect

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
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
 * The write-then-read round trip against the real Health Connect provider (#54).
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
                clientRecordIdsList = listOf(clientRecordIdFor(sessionId)),
            )
        }
    }

    @Test
    fun the_grant_is_read_back_as_both_halves() =
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
            ).records.filter { it.metadata.clientRecordId == clientRecordIdFor(sessionId) }

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

    private fun session(title: String) =
        HealthConnectSession(
            startedAt = start,
            endedAt = start + 3_000_000L,
            activity = WorkoutActivity.WEIGHTLIFTING,
            title = title,
            notes = "felt strong",
        )
}
