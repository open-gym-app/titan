package com.titan.core.domain.healthconnect

import com.titan.core.domain.model.WorkoutActivity

/**
 * Android's Health Connect, as far as Titan uses it: exercise sessions, written and read.
 *
 * An interface here and an implementation in `:core:data` for the reason every platform service
 * in the domain is one (compare [com.titan.core.domain.notification.NotificationPermission]):
 * the rules about *when* to write and *what* counts as a duplicate are business logic and are
 * tested on the JVM, while the client that talks to the Health Connect provider is IPC that only
 * a device can answer.
 *
 * ### There is no "sync on" switch besides the permission
 *
 * Whether finished sessions are written is whether [HealthConnectAccess.WRITE] is granted, and
 * nothing else. A preference beside it would be a second answer to the same question, and the
 * two disagree the moment the user revokes access in Health Connect's own settings — which they
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

    /** Which of the two this app holds right now. Empty when Health Connect is not available. */
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

/** The two halves of the permission set, as the domain reasons about them. */
enum class HealthConnectAccess {

    /** Read exercise sessions — what [com.titan.core.domain.usecase.ImportFromHealthConnectUseCase] needs. */
    READ,

    /** Write exercise sessions — what [com.titan.core.domain.usecase.SyncToHealthConnectUseCase] needs. */
    WRITE,
}

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
