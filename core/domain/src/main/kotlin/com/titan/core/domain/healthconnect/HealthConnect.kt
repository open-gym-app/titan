package com.titan.core.domain.healthconnect

import com.titan.core.domain.model.WorkoutActivity

/**
 * Android's Health Connect, as far as Titan uses it: exercise sessions, body weight and body fat,
 * each written and read.
 *
 * An interface here and an implementation in `:core:data` for the reason every platform service
 * in the domain is one (compare [com.titan.core.domain.notification.NotificationPermission]):
 * the rules about *when* to write and *what* counts as a duplicate are business logic and are
 * tested on the JVM, while the client that talks to the Health Connect provider is IPC that only
 * a device can answer.
 *
 * ### There is no "sync on" switch besides the permission
 *
 * Whether finished sessions are written is whether [HealthConnectAccess.WRITE_EXERCISE] is
 * granted, and nothing else — and the same holds for a weight or a body fat and its own
 * permission. A preference beside it would be a second answer to the same question, and the two
 * disagree the moment the user revokes access in Health Connect's own settings — which they
 * can, at any time, without this app hearing about it. So every operation asks [grantedAccess]
 * at the moment it runs, and disconnecting is [revokeAccess] — or, where [canRevokeAccess] is
 * false, turning the permissions off in Health Connect.
 */
interface HealthConnect {

    /** Whether Health Connect can be used on this device at all. Cheap, and asked every time. */
    fun availability(): HealthConnectAvailability

    /**
     * The platform permission names this integration asks for, as one set.
     *
     * Opaque strings to the domain: they exist so the screen can hand them to the system's
     * permission dialog, which is the one place they are needed and the one module that may not
     * see the Health Connect client. Every name here maps to one [HealthConnectAccess].
     */
    val permissions: Set<String>

    /** Which of the six this app holds right now. Empty when Health Connect is not available. */
    suspend fun grantedAccess(): Set<HealthConnectAccess>

    /**
     * Writes, or rewrites, the record for one Titan session.
     *
     * Keyed by [sessionId] together with the session's start, so a second write for the same
     * session replaces the first rather than adding a copy: Health Connect upserts by the client
     * record id, and keeps whichever write carries the higher [version]. A version that grows
     * with every write — the sync passes the time of the write — is what makes a retry
     * harmless and the latest write win. The start is part of the key because an id can be
     * given out again — after "delete all data", or a restore over a reinstall — and a new
     * workout under an old number must not replace the record of the one that had it.
     *
     * @throws Exception whatever the provider throws — a revoked permission, a quota, a provider
     *   that went away. Callers decide whether that is worth reporting.
     */
    suspend fun writeSession(
        sessionId: Long,
        version: Long,
        session: HealthConnectSession,
    )

    /**
     * Every exercise session record that starts in `[startedFrom, startedUntil)`, from every app,
     * this one included — telling them apart is [HealthConnectRecord.writtenByThisApp]'s job.
     *
     * @throws Exception whatever the provider throws, as [writeSession] does.
     */
    suspend fun readSessions(
        startedFrom: Long,
        startedUntil: Long,
    ): List<HealthConnectRecord>

    /**
     * Writes, or rewrites, one figure of one Titan body measurement.
     *
     * Keyed by [measurementId] together with the reading's [HealthConnectBodyReading.measuredAt],
     * for [writeSession]'s reason: an id can be given out again, and the time a measurement was
     * taken is fixed for the life of its row — the form shows it and does not edit it. Health
     * Connect keeps the write with the higher [version], so an edit, which carries a later one,
     * replaces the record rather than adding a copy.
     *
     * @throws Exception whatever the provider throws, as [writeSession] does.
     */
    suspend fun writeBodyReading(
        measurementId: Long,
        version: Long,
        reading: HealthConnectBodyReading,
    )

    /**
     * Removes the record [writeBodyReading] wrote for this figure of this measurement, if there is
     * one. Nothing else: Health Connect lets an app delete only what it wrote itself.
     *
     * @throws Exception whatever the provider throws, as [writeSession] does.
     */
    suspend fun deleteBodyReading(
        measurementId: Long,
        measuredAt: Long,
        metric: HealthConnectBodyMetric,
    )

    /**
     * Every [metric] record measured in `[measuredFrom, measuredUntil)`, from every app, this one
     * included — as [readSessions], [HealthConnectBodyRecord.writtenByThisApp] tells them apart.
     *
     * @throws Exception whatever the provider throws, as [writeSession] does.
     */
    suspend fun readBodyReadings(
        metric: HealthConnectBodyMetric,
        measuredFrom: Long,
        measuredUntil: Long,
    ): List<HealthConnectBodyRecord>

    /**
     * Whether [revokeAccess] takes effect at once, and so whether this app can disconnect itself.
     *
     * False from Android 14, where giving permissions back only takes effect when the process
     * dies: until then every read of the grant still says it is held, and the sync keeps writing.
     * A "Disconnect" that changes nothing on screen is worse than none, so there the user turns
     * access off in Health Connect's own permission page instead.
     */
    val canRevokeAccess: Boolean

    /**
     * Gives back everything this app was granted. Records already written stay where they are.
     *
     * @throws IllegalStateException when [canRevokeAccess] is false.
     */
    suspend fun revokeAccess()
}

/** The three answers Health Connect's own status check gives, in the app's words. */
enum class HealthConnectAvailability {

    /** Installed, up to date, and usable. */
    AVAILABLE,

    /**
     * The device could run Health Connect, but the provider is missing or too old. Android 9 to
     * 13, where Health Connect is an app from the Play Store rather than part of the system.
     */
    NOT_INSTALLED,

    /** Never on this device: below Android 9, or a build Health Connect does not support. */
    UNSUPPORTED,
}

/**
 * The permission set, one value per platform permission, as the domain reasons about them.
 *
 * One per permission rather than a read and a write for everything, because Health Connect's
 * dialog lists each data type on its own switch: a user can share their workouts and keep their
 * weight to themselves, and every operation has to ask about the one type it touches.
 */
enum class HealthConnectAccess {

    /** Read exercise sessions — what [com.titan.core.domain.usecase.ImportFromHealthConnectUseCase] needs. */
    READ_EXERCISE,

    /** Write exercise sessions — what [com.titan.core.domain.usecase.SyncToHealthConnectUseCase] needs. */
    WRITE_EXERCISE,

    /** Read body weight — half of what [com.titan.core.domain.usecase.ImportBodyFromHealthConnectUseCase] can use. */
    READ_WEIGHT,

    /** Write body weight — half of what [com.titan.core.domain.usecase.SyncBodyToHealthConnectUseCase] can use. */
    WRITE_WEIGHT,

    /** Read body fat — the other half of what the body import can use. */
    READ_BODY_FAT,

    /** Write body fat — the other half of what the body sync can use. */
    WRITE_BODY_FAT,
}

/**
 * The two figures of a [com.titan.core.domain.model.BodyMeasurement] that Health Connect has a
 * record type for: `WeightRecord` and `BodyFatRecord`. The circumferences have none, and stay on
 * this phone.
 */
enum class HealthConnectBodyMetric {

    /** Body weight, in milligrams, as [com.titan.core.domain.model.BodyMeasurement.weight]. */
    WEIGHT,

    /** Body fat, in tenths of a percent, as [com.titan.core.domain.model.BodyMeasurement.bodyFat]. */
    BODY_FAT,
    ;

    /** The permission that reads this figure. */
    val readAccess: HealthConnectAccess
        get() = when (this) {
            WEIGHT -> HealthConnectAccess.READ_WEIGHT
            BODY_FAT -> HealthConnectAccess.READ_BODY_FAT
        }

    /** The permission that writes this figure. */
    val writeAccess: HealthConnectAccess
        get() = when (this) {
            WEIGHT -> HealthConnectAccess.WRITE_WEIGHT
            BODY_FAT -> HealthConnectAccess.WRITE_BODY_FAT
        }
}

/**
 * One figure at one instant, in the shape Health Connect holds it: a weight or a body fat, and
 * when it was measured.
 *
 * @property measuredAt epoch milliseconds.
 * @property value in the figure's canonical unit: milligrams for [HealthConnectBodyMetric.WEIGHT],
 *   tenths of a percent for [HealthConnectBodyMetric.BODY_FAT].
 */
data class HealthConnectBodyReading(
    val metric: HealthConnectBodyMetric,
    val measuredAt: Long,
    val value: Long,
)

/**
 * A body reading read back from Health Connect.
 *
 * @property id Health Connect's own id for the record.
 * @property writtenByThisApp whether Titan wrote it — a copy of a measurement already in the
 *   local history, and never something to import.
 */
data class HealthConnectBodyRecord(
    val id: String,
    val reading: HealthConnectBodyReading,
    val writtenByThisApp: Boolean,
)

/**
 * One exercise session, in the shape it has in Health Connect: a span, a type and two pieces of
 * text. Everything Titan knows besides — exercises, sets, weights — has no place in an
 * `ExerciseSessionRecord`, and no place in this either.
 *
 * @property startedAt epoch milliseconds.
 * @property endedAt epoch milliseconds, strictly after [startedAt]: Health Connect refuses a
 *   session that ends where it starts.
 */
data class HealthConnectSession(
    val startedAt: Long,
    val endedAt: Long,
    val activity: WorkoutActivity,
    val title: String? = null,
    val notes: String? = null,
) {
    init {
        require(endedAt > startedAt) { "a session must end after it starts, was $startedAt..$endedAt" }
    }
}

/**
 * A session read back from Health Connect.
 *
 * @property id Health Connect's own id for the record, unique across every app that writes there.
 * @property writtenByThisApp whether Titan is the app that wrote it — which makes it a copy of a
 *   session already in the local history, and never something to import.
 */
data class HealthConnectRecord(
    val id: String,
    val session: HealthConnectSession,
    val writtenByThisApp: Boolean,
)

/**
 * [HealthConnect.availability], with a status check that throws read as "not on this phone".
 *
 * The check asks the package manager, which can throw; the use cases catch that into their
 * `Result`, and a screen that crashed on it instead would be the one place it surfaced. Every
 * screen that hides a row where Health Connect is missing asks through this.
 */
fun HealthConnect.availabilityOrUnsupported(): HealthConnectAvailability =
    try {
        availability()
    } catch (_: RuntimeException) {
        HealthConnectAvailability.UNSUPPORTED
    }
