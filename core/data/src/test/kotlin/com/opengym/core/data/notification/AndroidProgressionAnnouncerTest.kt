package com.opengym.core.data.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.gamification.Collectibles
import com.opengym.core.domain.model.Collectible
import com.opengym.core.domain.model.DropSource
import com.opengym.core.domain.model.Rarity
import com.opengym.core.domain.model.SessionProgression
import com.opengym.core.domain.model.StreakProtection
import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakState
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app says about a workout's payoff, and — more of these — when it says nothing.
 *
 * The two rules under test are the two the issue leads with. Every fact in the body comes off
 * the [SessionProgression] and nothing is recomputed, which is why there is not a repository in
 * this file to fake; and nothing is posted while the user is looking at the summary screen that
 * is already celebrating the same thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidProgressionAnnouncerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = RecordingNotifier()
    private val foreground = FakeForegroundState(isInForeground = false)

    private val announcer = AndroidProgressionAnnouncer(context, notifier, foreground)

    // --- the streak save ----------------------------------------------------

    @Test
    fun `a streak save names the streak it saved and what is left to spend`() =
        runTest {
            announcer.announce(progression(streak = protectionSpent(days = 12, remaining = 2)))

            val note = notifier.showing
            assertEquals(NotificationKind.PROGRESSION, note?.kind)
            assertEquals("Streak protection used", note?.title)
            assertEquals("Saved your 12-day streak. 2 protections left this month.", note?.body)
        }

    @Test
    fun `the counts are plurals, not numbers pushed into a sentence`() =
        runTest {
            // "1 protections left" is the failure a concatenated count always eventually
            // ships, and #41's languages have more categories than English does.
            announcer.announce(progression(streak = protectionSpent(days = 1, remaining = 1)))

            assertEquals("Saved your 1-day streak. 1 protection left this month.", notifier.showing?.body)
        }

    @Test
    fun `a streak that spent no protection is not news`() =
        runTest {
            // `streak` is non-null on nearly every session — it carries the standing allowance
            // whether or not this one moved it — so asking for the update is not the same as
            // asking whether anything happened.
            val advanced = StreakUpdate(
                result = StreakResult.Incremented(newStreak = 3),
                state = StreakState(currentStreak = 3, protection = StreakProtection(remaining = 2)),
            )

            announcer.announce(progression(streak = advanced))

            assertTrue(notifier.log.isEmpty())
        }

    // --- the loot drop ------------------------------------------------------

    @Test
    fun `a drop names the item and its rarity`() =
        runTest {
            announcer.announce(progression(loot = Collectibles.PET_EGG))

            val note = notifier.showing
            assertEquals("New loot unlocked", note?.title)
            // The rarity in words rather than the wireframe's glyph, which a screen reader says
            // nothing about — see #40.
            assertEquals("You found a Pet egg · Rare.", note?.body)
        }

    @Test
    fun `a drop this build has no catalogue entry for is still announced`() =
        runTest {
            // A newer version's collectible. It is the user's loot either way, so it is named
            // generically rather than left out of the sentence.
            val unknown = Collectible(id = "mystery_box", rarity = Rarity.EPIC, source = DropSource.SESSION_FINISHED)

            announcer.announce(progression(loot = unknown))

            assertEquals("You found a new collectible · Epic.", notifier.showing?.body)
        }

    // --- both at once -------------------------------------------------------

    @Test
    fun `a session that saves a streak and drops loot posts one notification`() =
        runTest {
            announcer.announce(
                progression(
                    streak = protectionSpent(days = 12, remaining = 2),
                    loot = Collectibles.PET_EGG,
                ),
            )

            // One, not two. Two posts for one finished workout reads as an app talking to
            // itself, and there are at most two facts — which is why this is a combined body
            // rather than a notification group.
            assertEquals(1, notifier.posted.size)
            assertEquals("Streak saved, and loot found", notifier.showing?.title)
            assertEquals(
                "Saved your 12-day streak. 2 protections left this month. You found a Pet egg · Rare.",
                notifier.showing?.body,
            )
        }

    // --- silence ------------------------------------------------------------

    @Test
    fun `nothing is said about a session that moved neither`() =
        runTest {
            announcer.announce(progression())

            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `nothing is said about a streak save the user is watching`() =
        runTest {
            // The summary screen's own banner is celebrating it on the frame this returns.
            foreground.isInForeground = true

            announcer.announce(progression(streak = protectionSpent(days = 12, remaining = 2)))

            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `nothing is said about a drop the user is watching`() =
        runTest {
            // Same rule, and the case that actually happens: the recap's loot card.
            foreground.isInForeground = true

            announcer.announce(progression(loot = Collectibles.PET_EGG))

            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `the progression preference is what decides whether anything is posted`() =
        runTest {
            // `Notifier` owns that gate — see #176. What matters here is that this path goes
            // through it rather than around it.
            notifier.outcome = NotificationOutcome.SUPPRESSED_BY_PREFERENCE

            announcer.announce(progression(loot = Collectibles.PET_EGG))

            assertTrue(notifier.posted.isEmpty())
        }

    // --- where it lands -----------------------------------------------------

    @Test
    fun `tapping lands on the quest log`() =
        runTest {
            announcer.announce(progression(loot = Collectibles.PET_EGG))

            // The one screen that draws both of the things this announces: the recent loot, and
            // what is left of the month's allowance.
            assertEquals("opengym://progression/quests", notifier.showing?.deepLink)
        }

    private fun progression(
        streak: StreakUpdate? = null,
        loot: Collectible? = null,
    ) = SessionProgression(sessionId = SESSION_ID, streak = streak, lootDropped = loot)

    private fun protectionSpent(
        days: Int,
        remaining: Int,
    ) = StreakUpdate(
        result = StreakResult.Protected(protectionsRemaining = remaining),
        state = StreakState(
            currentStreak = days,
            longestStreak = days,
            protection = StreakProtection(remaining = remaining),
        ),
    )

    /** The process lifecycle, under the test's control. */
    private class FakeForegroundState(var isInForeground: Boolean) : AppForegroundState {
        override suspend fun isInForeground(): Boolean = isInForeground
    }

    private companion object {
        const val SESSION_ID = 7L
    }
}
