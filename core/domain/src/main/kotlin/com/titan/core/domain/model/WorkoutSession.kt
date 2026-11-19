package com.titan.core.domain.model

/**
 * @property name what the user calls this session, e.g. "Push Day - Upper". Seeded
 *   from the template's name when the session is started from one, and editable
 *   afterwards. Null - not `""` - for a session started empty, which has genuinely
 *   not been named yet; the UI falls back to a date-derived title in that case, and
 *   collapsing the two states into a default would make "unnamed" unrecoverable.
 * @property calories what the workout burned, in kilocalories. Estimated when the session is
 *   finished, if the user has not given a figure by then — see
 *   [com.titan.core.domain.stats.CalorieEstimate] — and replaced by whatever the user or their
 *   watch reports on the recap. Null for a session with nothing to estimate from.
 * @property rating how the workout went as a whole, from 1 (rough) to 5 (great). A figure for
 *   the session rather than for any set, which is what RPE already is; null until the user gives
 *   one. Both are bounded by `SessionEffortValidator`, not by the schema.
 * @property healthConnectId the Health Connect record this session was imported from, or **null**
 *   for one logged in Titan. An imported session is a finished row with no exercises and no sets:
 *   what another app recorded is a type, a title and two times, and nothing in it says which
 *   lifts were done. It shows in the history and counts as a day trained, and earns nothing —
 *   it never passed through `FinishSessionUseCase`, so no cascade ran for it, and the lifetime
 *   counts the achievements and quests read leave it out. See
 *   [com.titan.core.domain.usecase.ImportFromHealthConnectUseCase].
 * @property healthConnectActivity what Health Connect said an imported session was, or **null**
 *   for one logged in Titan, whose activity is worked out from its exercises instead
 *   ([WorkoutActivity.of]). Stored because an imported session has no exercises to work it out
 *   from, and the import's duplicate check compares it.
 */
data class WorkoutSession(
    val id: Long = 0,
    val templateId: Long? = null,
    val name: String? = null,
    val completedAt: Long? = null,
    val isInProgress: Boolean = true,
    val lastActiveAt: Long,
    val notes: String? = null,
    val calories: Int? = null,
    val rating: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val healthConnectId: String? = null,
    val healthConnectActivity: WorkoutActivity? = null,
) {

    /** Whether this session came from Health Connect rather than being logged here. */
    val isImported: Boolean get() = healthConnectId != null

    companion object {

        /**
         * A name as typed, turned into a name as stored: trimmed, and blank collapsed to
         * `null`.
         *
         * The whole design rests on `null` and `""` being different states — `null` is what
         * the UI's date-derived fallback keys off — so a screen handing over an untouched
         * field must not persist a session titled with nothing at all.
         *
         * Here rather than in the one use case that first needed it, because the name is set
         * in three places now: before the session starts
         * ([com.titan.core.domain.usecase.StartSessionUseCase]), during it and after it
         * ([com.titan.core.domain.usecase.RenameSessionUseCase]). Three copies of one rule
         * is how a session ends up named `" "` from whichever screen forgot.
         */
        fun normalizeName(raw: String?): String? = raw?.trim()?.takeIf(String::isNotEmpty)
    }
}
