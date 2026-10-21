package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.AchievementEntity
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.db.entity.QuestCompletionEntity
import com.titan.core.data.db.entity.UserProgressEntity

/**
 * Whole-table reads, inserts and deletes over the four gamification tables that point at
 * nothing. See [CatalogueBackupDao].
 *
 * `achievement` and `collected_item` carry ids from the domain's own catalogues rather than
 * from a table, and neither is validated against the running build: docs/PERSISTENCE.md is
 * explicit that a row naming a milestone or a collectible this version does not recognise is an
 * unlock from a newer one, not a corrupt row, and a restore must preserve it. These queries
 * read and write the column as it stands and have no opinion about its contents.
 */
interface GamificationBackupDao {

    @Query("SELECT * FROM user_progress ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readUserProgress(
        limit: Int,
        offset: Int,
    ): List<UserProgressEntity>

    @Insert
    suspend fun insertUserProgress(rows: List<UserProgressEntity>)

    @Query("DELETE FROM user_progress")
    suspend fun deleteUserProgress()

    @Query("SELECT * FROM achievement ORDER BY achievement_id LIMIT :limit OFFSET :offset")
    suspend fun readAchievements(
        limit: Int,
        offset: Int,
    ): List<AchievementEntity>

    @Insert
    suspend fun insertAchievements(rows: List<AchievementEntity>)

    @Query("DELETE FROM achievement")
    suspend fun deleteAchievements()

    @Query("SELECT * FROM quest_completion ORDER BY quest_id, period_start LIMIT :limit OFFSET :offset")
    suspend fun readQuestCompletions(
        limit: Int,
        offset: Int,
    ): List<QuestCompletionEntity>

    @Insert
    suspend fun insertQuestCompletions(rows: List<QuestCompletionEntity>)

    @Query("DELETE FROM quest_completion")
    suspend fun deleteQuestCompletions()

    @Query("SELECT * FROM collected_item ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readCollectedItems(
        limit: Int,
        offset: Int,
    ): List<CollectedItemEntity>

    @Insert
    suspend fun insertCollectedItems(rows: List<CollectedItemEntity>)

    @Query("DELETE FROM collected_item")
    suspend fun deleteCollectedItems()
}
