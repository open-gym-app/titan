package com.titan.core.domain.model

/**
 * A workout as Health Connect understands one: when it began, when it ended, and what kind it was.
 *
 * Platform-neutral on purpose — `:core:data` maps it to and from `ExerciseSessionRecord`, and
 * nothing above that line imports the Health Connect client.
 *
 * @property externalId the id the record is stored under in Health Connect, when it has one. A
 *   record this app wrote carries [externalIdFor] its session, which is both what makes a second
 *   write an update rather than a duplicate and how an import recognises its own output.
 */
data class HealthWorkout(
    val startMillis: Long,
    val endMillis: Long,
    val type: HealthWorkoutType,
    val title: String? = null,
    val notes: String? = null,
    val externalId: String? = null,
) {
    init {
        require(endMillis > startMillis) { "a workout must end after it starts: $startMillis..$endMillis" }
    }

    /** True for a record this app wrote itself. */
    val isOwn: Boolean get() = externalId?.startsWith(EXTERNAL_ID_PREFIX) == true

    companion object {
        const val EXTERNAL_ID_PREFIX = "titan-session-"

        fun externalIdFor(sessionId: Long): String = "$EXTERNAL_ID_PREFIX$sessionId"
    }
}
