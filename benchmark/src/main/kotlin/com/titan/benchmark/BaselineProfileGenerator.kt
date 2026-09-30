package com.titan.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes `app/src/main/baselineProfiles/baseline-prof.txt`.
 *
 * ART compiles a hot method the second or third time it runs it, which means the first launch
 * of an install runs the whole of startup interpreted. A Baseline Profile is a list of the
 * classes and methods worth compiling *before* they are first needed, shipped in the APK, and
 * it is the largest cheap win available to this app's cold start — the reason #42 asks for one
 * rather than for more optimisation.
 *
 * ### What the journey below is, and is not
 *
 * It is the code path a first run actually takes: the app opening, the Sessions tab and its
 * list under a thumb, and the Stats hub. It is deliberately *not* a tour of the app — a
 * profile is a budget, and every rarely-used screen added to it is compilation time and APK
 * bytes spent on a path most launches never take.
 *
 * ### Running it, and where the file goes
 *
 * `androidx.baselineprofile`, the Gradle plugin that would copy the result into `:app` on its
 * own, is deliberately not applied here — see `benchmark/build.gradle.kts`. The output lands
 * on the device and is pulled by hand; `make baseline-profile` does both steps, and
 * `benchmark/README.md` spells out what it runs.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() =
        baselineProfileRule.collect(
            packageName = TargetApp.PACKAGE_NAME,
            // A *startup* profile is the other half of this — the methods reached before the first
            // frame, written out separately so R8 can lay them together in the DEX. It is left off
            // because nothing here could consume it: feeding one to AGP means the
            // `androidx.baselineprofile` plugin and the build types it creates, which this module
            // deliberately does without. Asking for a file that would be generated and ignored
            // would only make the output look like it was being used.
            includeInStartupProfile = false,
        ) {
            // Profiled against the same history everything else here measures. A profile collected
            // over an empty database would miss every path that only runs when there is something
            // to draw — which is all of them, for a returning user.
            seedTargetHistory()

            pressHome()
            startActivityAndWait()

            val history = device.selectTab(
                tabTag = TargetApp.SESSIONS_TAB,
                contentTag = TargetApp.SESSIONS_HISTORY_LIST,
                description = "the sessions history",
            )
            device.flingThrough(history, items = PROFILED_SCROLL_ITEMS)

            device.selectTab(
                tabTag = TargetApp.STATS_TAB,
                contentTag = TargetApp.STATS_OVERVIEW,
                description = "the stats hub",
            )
            device.awaitTag(TargetApp.STATS_VOLUME_HERO, "the volume hero")
            device.awaitTag(TargetApp.STATS_OVERVIEW, "the stats hub")
                .scrollUntil(Direction.DOWN, Until.findObject(By.res(TargetApp.STATS_EXPLORE_EXERCISE)))
        }

    private companion object {
        /**
         * A shorter fling than the benchmark's thirty rows.
         *
         * The profile only needs each method on the scroll path to be *reached*; running it
         * further adds nothing to the file and adds a second to every one of the fifteen
         * iterations the rule runs while it waits for the output to stabilise.
         */
        const val PROFILED_SCROLL_ITEMS = 10
    }
}
