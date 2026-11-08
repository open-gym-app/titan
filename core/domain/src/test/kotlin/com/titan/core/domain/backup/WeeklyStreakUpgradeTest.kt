package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.AchievementRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.model.StreakProtection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * A backup written before the streak counted weeks restores without carrying the old units,
 * milestones or allowance into the new rule — see [WeeklyStreakUpgrade].
 */
class WeeklyStreakUpgradeTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `a file this build writes decodes unchanged`() {
        assertEquals(sampleDocument(), decode(json.encodeToString(ExportDocument.serializer(), sampleDocument())))
    }

    @Test
    fun `a file from before the weekly streak starts the streak again`() {
        val progress = decode(legacyDocument()).userProgress.single()

        assertEquals(0, progress.currentStreak)
        assertEquals(0, progress.longestStreak)
        assertEquals(0, progress.weekSessions)
        assertEquals(false, progress.weekGoalMet)
        // Kept: it is what stops the restored sessions from being counted a second time.
        assertEquals(sampleDocument().userProgress.single().lastCountedAt, progress.lastCountedAt)
        assertEquals(sampleDocument().userProgress.single().totalXp, progress.totalXp)
    }

    @Test
    fun `a file from before the weekly streak keeps no more protections than the allowance`() {
        val progress = decode(legacyDocument()).userProgress.single()

        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, progress.protectionRemaining)
    }

    @Test
    fun `the retired streak milestones are dropped and every other unlock is kept`() {
        val ids = decode(legacyDocument()).achievement.map { it.achievementId }

        assertTrue(ids.none { it in WeeklyStreakUpgrade.RETIRED_ACHIEVEMENT_IDS })
        assertEquals(sampleDocument().achievement.map { it.achievementId }, ids)
    }

    /**
     * The sample document as the session-based streak wrote it: no `week_sessions` or
     * `week_goal_met` on the progress row, and its two streak milestones unlocked.
     */
    private fun legacyDocument(): String {
        val withRetired = sampleDocument().let { document ->
            document.copy(
                achievement = document.achievement +
                    WeeklyStreakUpgrade.RETIRED_ACHIEVEMENT_IDS.map { AchievementRow(it, unlockedAt = 0) },
            )
        }
        val encoded = json.encodeToJsonElement(ExportDocument.serializer(), withRetired).jsonObject
        val row = encoded.getValue("user_progress").jsonArray.single().jsonObject
        val legacyRow = JsonObject(row - "week_sessions" - "week_goal_met")
        val legacy = encoded + ("user_progress" to JsonArray(listOf(legacyRow)))
        return json.encodeToString(JsonObject.serializer(), JsonObject(legacy))
    }

    private fun decode(text: String): ExportDocument {
        val decoded = ExportFormat.decode(
            object : ImportSource {
                override fun open(): InputStream = text.byteInputStream()
            },
        )
        return (decoded as ExportFormat.Decoded.Success).document
    }
}
