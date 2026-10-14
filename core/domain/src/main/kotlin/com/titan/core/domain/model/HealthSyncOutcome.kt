package com.titan.core.domain.model

/** What a sync or import did. [Skipped] is not a failure: the user's setup said not to. */
sealed interface HealthSyncOutcome {

    /** One finished session was written to Health Connect. */
    data object Synced : HealthSyncOutcome

    /** [imported] workouts were added; [skipped] were already here, ours, or not strength work. */
    data class Imported(
        val imported: Int,
        val skipped: Int,
    ) : HealthSyncOutcome

    data class Skipped(val reason: HealthSkipReason) : HealthSyncOutcome
}

enum class HealthSkipReason {
    DISABLED,
    UNAVAILABLE,
    NO_PERMISSION,
    SESSION_NOT_FOUND,
    SESSION_NOT_FINISHED,
}
