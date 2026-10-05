package com.titan.core.data.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.FinishOrigin
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakResult
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationOutcome
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
 * this file to fake; and nothing is posted for a finish whose recap is already celebrating the
 * same thing.
 *
 * That second rule is asked of the [FinishOrigin] the finish came with and of nothing else —
 * there is no process-lifecycle reading here to drive, because a finish from the shade is
 * announced whether or not the app happens to be on screen at the time. It is the only report
 * that one gets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidProgressionAnnouncerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = RecordingNotifier()

    private val announcer = AndroidProgressionAnnouncer(context, notifier)

    // --- the streak save ----------------------------------------------------

    @Test
    fun `a streak save names the streak it saved and what is left to spend`() =
        runTest {
            announcer.announce(progression(streak = protectionSpent(weeks = 12, remaining = 0)), FROM_THE_SHADE)

            val note = notifier.showing
            assertEquals(NotificationKind.PROGRESSION, note?.kind)
            assertEquals("Streak protection used", note?.title)
            assertEquals("Saved your 12-week streak. 0 protections left this month.", note?.body)
        }

    @Test
    fun `the counts are plurals, not numbers pushed into a sentence`() =
        runTest {
            // "1 protections left" is the failure a concatenated count always eventually
            // ships, and #41's languages have more categories than English does.
            announcer.announce(progression(streak = protectionSpent(weeks = 2, remaining = 1)), FROM_THE_SHADE)

            assertEquals("Saved your 2-week streak. 1 protection left this month.", notifier.showing?.body)
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

            announcer.announce(progression(streak = advanced), FROM_THE_SHADE)

            assertTrue(notifier.log.isEmpty())
        }

    // --- the loot drop ------------------------------------------------------

    @Test
    fun `a drop names the item and its rarity`() =
        runTest {
            announcer.announce(progression(loot = Collectibles.PET_EGG), FROM_THE_SHADE)

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

            announcer.announce(progression(loot = unknown), FROM_THE_SHADE)

            assertEquals("You found a new collectible · Epic.", notifier.showing?.body)
        }

    // --- both at once -------------------------------------------------------

    @Test
    fun `a session that saves a streak and drops loot posts one notification`() =
        runTest {
            announcer.announce(
                progression(
                    streak = protectionSpent(weeks = 12, remaining = 0),
                    loot = Collectibles.PET_EGG,
                ),
                FROM_THE_SHADE,
            )

            // One, not two. Two posts for one finished workout reads as an app talking to
            // itself, and there are at most two facts — which is why this is a combined body
            // rather than a notification group.
            assertEquals(1, notifier.posted.size)
            assertEquals("Streak saved, and loot found", notifier.showing?.title)
            assertEquals(
                "Saved your 12-week streak. 0 protections left this month. You found a Pet egg · Rare.",
                notifier.showing?.body,
            )
        }

    // --- silence ------------------------------------------------------------

    @Test
    fun `nothing is said about a session that moved neither`() =
        runTest {
            announcer.announce(progression(), FROM_THE_SHADE)

            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `nothing is said about a streak save a recap is about to celebrate`() =
        runTest {
            // END on the workout screen: the summary's own banner is celebrating this on the
            // frame the finish returns, and a notification beside it is the app talking over
            // itself.
            announcer.announce(
                progression(streak = protectionSpent(weeks = 12, remaining = 0)),
                FinishOrigin.WORKOUT_SCREEN,
            )

            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `nothing is said about a drop a recap is about to celebrate`() =
        runTest {
            // Same rule, and the case that actually happens: the recap's loot card.
            announcer.announce(progression(loot = Collectibles.PET_EGG), FinishOrigin.WORKOUT_SCREEN)

            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `the progression preference is what decides whether anything is posted`() =
        runTest {
            // `Notifier` owns that gate — see #176. What matters here is that this path goes
            // through it rather than around it.
            notifier.outcome = NotificationOutcome.SUPPRESSED_BY_PREFERENCE

            announcer.announce(progression(loot = Collectibles.PET_EGG), FROM_THE_SHADE)

            // Both halves, because `posted` alone would be empty for an announcer that composed
            // nothing at all — which is the bug this test would otherwise sit through.
            assertEquals(1, notifier.attempted.size)
            assertTrue(notifier.posted.isEmpty())
        }

    // --- where it lands -----------------------------------------------------

    @Test
    fun `tapping lands on the quest log`() =
        runTest {
            announcer.announce(progression(loot = Collectibles.PET_EGG), FROM_THE_SHADE)

            // The one screen that draws both of the things this announces: the recent loot, and
            // what is left of the month's allowance.
            assertEquals("titan://progression/quests", notifier.showing?.deepLink)
        }

    private fun progression(
        streak: StreakUpdate? = null,
        loot: Collectible? = null,
    ) = SessionProgression(sessionId = SESSION_ID, streak = streak, lootDropped = loot)

    private fun protectionSpent(
        weeks: Int,
        remaining: Int,
    ) = StreakUpdate(
        result = StreakResult.Protected(newStreak = weeks, protectionsRemaining = remaining),
        state = StreakState(
            currentStreak = weeks,
            longestStreak = weeks,
            protection = StreakProtection(remaining = remaining),
        ),
    )

    private companion object {
        const val SESSION_ID = 7L

        /**
         * The origin every case above uses: "Finish now", pressed in the shade.
         *
         * It is the only one that announces anything at all, so it is what a case about
         * *wording* has to be given. The two that assert silence name their own.
         */
        val FROM_THE_SHADE = FinishOrigin.NOTIFICATION_ACTION
    }
}
