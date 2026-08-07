package com.opengym.core.domain.model

/**
 * @property name what the user calls this session, e.g. "Push Day - Upper". Seeded
 *   from the template's name when the session is started from one, and editable
 *   afterwards. Null - not `""` - for a session started empty, which has genuinely
 *   not been named yet; the UI falls back to a date-derived title in that case, and
 *   collapsing the two states into a default would make "unnamed" unrecoverable.
 */
data class WorkoutSession(
    val id: Long = 0,
    val templateId: Long? = null,
    val name: String? = null,
    val completedAt: Long? = null,
    val isInProgress: Boolean = true,
    val lastActiveAt: Long,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
