package com.titan.core.data.mapper

import com.titan.core.data.db.entity.AchievementEntity
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.db.entity.QuestCompletionEntity
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.domain.backup.model.AchievementRow
import com.titan.core.domain.backup.model.CollectedItemRow
import com.titan.core.domain.backup.model.QuestCompletionRow
import com.titan.core.domain.backup.model.UserProgressRow

/**
 * The four gamification tables to backup rows and back. See [CatalogueBackupMapper.kt].
 *
 * `user_progress.current_level` travels as it is stored, and is *not* recomputed from
 * [com.titan.core.domain.gamification.XpCalculator] on the way back in. It is a stored figure
 * written by whichever curve was in force when it was last touched, and re-deriving it here
 * would make the first award after a restore report a level-up — or a level *drop* — the user
 * did not earn. The row is a record of what the device held; the curve applies to the next
 * award, not to the history.
 *
 * The one exception is a value no build writes, which the restore [repaired]s on the way in:
 * a total the curve cannot price, a level outside the curve, a negative count, an allowance
 * outside the monthly one, a period that is not a month. Kept, each of them throws on every
 * launch. See `docs/export-schema.md` for the list.
 */
fun UserProgressEntity.toRow(): UserProgressRow =
    UserProgressRow(
        id = id,
        totalXp = totalXp,
        currentLevel = currentLevel,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        lastCountedAt = lastCountedAt,
        weekSessions = weekSessions,
        weekGoalMet = weekGoalMet,
        protectionRemaining = protectionRemaining,
        protectionPeriod = protectionPeriod,
        protectionConsumedAt = protectionConsumedAt,
    )

fun UserProgressRow.toEntity(): UserProgressEntity =
    UserProgressEntity(
        id = id,
        totalXp = totalXp,
        currentLevel = currentLevel,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        lastCountedAt = lastCountedAt,
        // Never null here: `ExportFormat.decode` upgrades a file that lacks the column.
        weekSessions = weekSessions ?: 0,
        weekGoalMet = weekGoalMet,
        protectionRemaining = protectionRemaining,
        protectionPeriod = protectionPeriod,
        protectionConsumedAt = protectionConsumedAt,
    ).repaired()

fun AchievementEntity.toRow(): AchievementRow = AchievementRow(achievementId = achievementId, unlockedAt = unlockedAt)

fun AchievementRow.toEntity(): AchievementEntity =
    AchievementEntity(achievementId = achievementId, unlockedAt = unlockedAt)

fun QuestCompletionEntity.toRow(): QuestCompletionRow =
    QuestCompletionRow(questId = questId, periodStart = periodStart, completedAt = completedAt)

fun QuestCompletionRow.toEntity(): QuestCompletionEntity =
    QuestCompletionEntity(questId = questId, periodStart = periodStart, completedAt = completedAt)

fun CollectedItemEntity.toRow(): CollectedItemRow =
    CollectedItemRow(
        id = id,
        collectibleId = collectibleId,
        source = source,
        acquiredAt = acquiredAt,
        sessionId = sessionId,
    )

fun CollectedItemRow.toEntity(): CollectedItemEntity =
    CollectedItemEntity(
        id = id,
        collectibleId = collectibleId,
        source = source,
        acquiredAt = acquiredAt,
        sessionId = sessionId,
    )
