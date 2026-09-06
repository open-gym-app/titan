package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.AchievementEntity
import com.opengym.core.data.db.entity.CollectedItemEntity
import com.opengym.core.data.db.entity.QuestCompletionEntity
import com.opengym.core.data.db.entity.UserProgressEntity
import com.opengym.core.domain.backup.model.AchievementRow
import com.opengym.core.domain.backup.model.CollectedItemRow
import com.opengym.core.domain.backup.model.QuestCompletionRow
import com.opengym.core.domain.backup.model.UserProgressRow

/**
 * The four gamification tables to backup rows and back. See [CatalogueBackupMapper.kt].
 *
 * `user_progress.current_level` travels as it is stored, and is *not* recomputed from
 * [com.opengym.core.domain.gamification.XpCalculator] on the way back in. It is a stored figure
 * written by whichever curve was in force when it was last touched, and re-deriving it here
 * would make the first award after a restore report a level-up — or a level *drop* — the user
 * did not earn. The row is a record of what the device held; the curve applies to the next
 * award, not to the history.
 */
fun UserProgressEntity.toRow(): UserProgressRow =
    UserProgressRow(
        id = id,
        totalXp = totalXp,
        currentLevel = currentLevel,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        lastCountedAt = lastCountedAt,
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
        protectionRemaining = protectionRemaining,
        protectionPeriod = protectionPeriod,
        protectionConsumedAt = protectionConsumedAt,
    )

fun AchievementEntity.toRow(): AchievementRow = AchievementRow(achievementId = achievementId, unlockedAt = unlockedAt)

fun AchievementRow.toEntity(): AchievementEntity =
    AchievementEntity(achievementId = achievementId, unlockedAt = unlockedAt)

fun QuestCompletionEntity.toRow(): QuestCompletionRow =
    QuestCompletionRow(questId = questId, periodStart = periodStart, completedAt = completedAt)

fun QuestCompletionRow.toEntity(): QuestCompletionEntity =
    QuestCompletionEntity(questId = questId, periodStart = periodStart, completedAt = completedAt)

fun CollectedItemEntity.toRow(): CollectedItemRow =
    CollectedItemRow(id = id, collectibleId = collectibleId, source = source, acquiredAt = acquiredAt)

fun CollectedItemRow.toEntity(): CollectedItemEntity =
    CollectedItemEntity(id = id, collectibleId = collectibleId, source = source, acquiredAt = acquiredAt)
