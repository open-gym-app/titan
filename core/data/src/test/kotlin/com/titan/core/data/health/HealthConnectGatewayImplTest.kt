package com.titan.core.data.health

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthPermissions
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectGatewayImplTest {

    private val permissions = mockk<PermissionController>()
    private val client = mockk<HealthConnectClient> {
        every { permissionController } returns permissions
    }

    private fun gateway(
        sdkInt: Int = 34,
        status: Int = HealthConnectClient.SDK_AVAILABLE,
    ) = HealthConnectGatewayImpl(sdkInt, { status }, { client }, { 777L })

    private fun page(
        records: List<ExerciseSessionRecord>,
        token: String?,
    ) = ReadRecordsResponse(records, token)

    private fun record(start: Long) =
        ExerciseSessionRecord(
            startTime = Instant.ofEpochMilli(start),
            startZoneOffset = null,
            endTime = Instant.ofEpochMilli(start + 1_000),
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        )

    @Test
    fun `the domain's permission strings are the client's`() {
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            ),
            HealthPermissions.REQUIRED,
        )
    }

    @Test
    fun `below android 9 health connect is unsupported and the status is never asked`() {
        val gateway = HealthConnectGatewayImpl(27, { error("not asked") }, { error("not built") }, { 0 })

        assertEquals(HealthAvailability.UNSUPPORTED, gateway.availability())
    }

    @Test
    fun `the sdk status maps to availability`() {
        assertEquals(HealthAvailability.AVAILABLE, gateway().availability())
        assertEquals(
            HealthAvailability.NOT_INSTALLED,
            gateway(status = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED).availability(),
        )
        assertEquals(
            HealthAvailability.UNSUPPORTED,
            gateway(status = HealthConnectClient.SDK_UNAVAILABLE).availability(),
        )
    }

    @Test
    fun `a platform with no health connect service is unsupported rather than a crash`() {
        val gateway = HealthConnectGatewayImpl(
            34,
            { throw RemoteException("No service published for: healthconnect") },
            { error("not built") },
            { 0 },
        )

        assertEquals(HealthAvailability.UNSUPPORTED, gateway.availability())
    }

    @Test
    fun `permissions are held only when both are granted`() =
        runTest {
            val read = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
            val write = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

            coEvery { permissions.getGrantedPermissions() } returns setOf(read)
            assertFalse(gateway().hasPermissions())

            coEvery { permissions.getGrantedPermissions() } returns setOf(read, write, "other")
            assertTrue(gateway().hasPermissions())
        }

    @Test
    fun `a write inserts one record carrying the client id and version`() =
        runTest {
            val inserted = slot<List<ExerciseSessionRecord>>()
            coEvery { client.insertRecords(capture(inserted)) } returns InsertRecordsResponse(listOf("id"))

            gateway().write(
                HealthWorkout(1_000, 2_000, HealthWorkoutType.STRENGTH_TRAINING, externalId = "titan-session-1"),
            )

            val record = inserted.captured.single()
            assertEquals("titan-session-1", record.metadata.clientRecordId)
            assertEquals(777L, record.metadata.clientRecordVersion)
        }

    @Test
    fun `reads follow the page token to the end`() =
        runTest {
            val requests = mutableListOf<ReadRecordsRequest<ExerciseSessionRecord>>()
            coEvery { client.readRecords(capture(requests)) } returnsMany listOf(
                page(listOf(record(1_000)), "next"),
                page(listOf(record(5_000)), null),
            )

            val found = gateway().readBetween(0, 10_000)

            assertEquals(listOf(1_000L, 5_000L), found.map { it.startMillis })
            assertEquals(listOf(null, "next"), requests.map { it.pageToken })
            coVerify(exactly = 2) { client.readRecords(any<ReadRecordsRequest<ExerciseSessionRecord>>()) }
        }

    @Test
    fun `every client failure becomes a health connect exception`() =
        runTest {
            val failures = listOf(SecurityException("revoked"), IOException("gone"), IllegalStateException("bad"))
            for (failure in failures) {
                coEvery { permissions.getGrantedPermissions() } throws failure

                val thrown = assertThrows(HealthConnectException::class.java) {
                    kotlinx.coroutines.runBlocking { gateway().hasPermissions() }
                }
                assertEquals(failure, thrown.cause)
            }
        }
}
