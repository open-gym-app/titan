package com.opengym.benchmark

import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/*
 * Everything the benchmarks do to the app that is not the thing being measured.
 *
 * Kept out of the benchmark classes so that each `measureBlock` reads as the interaction it
 * puts a number on and nothing else — a `measureBlock` that also has to find its way to the
 * screen is a measurement of the finding.
 */

/**
 * Puts the 1,000-session / 50,000-set history behind the app, and returns the `workout_set`
 * count the app reports back.
 *
 * Blocks until the database is ready: the call is served on a binder thread in the app process
 * (see `BenchmarkSeedProvider`) and the first one, on a fresh install, writes 50,000 rows and
 * takes the best part of a minute. Every call after that is a row count and returns at once,
 * which is what makes this safe to put in a `setupBlock` that runs once per iteration.
 *
 * Seeding through the app rather than by pushing a prepared database file is the only option
 * that works on an ordinary phone: a second process cannot write into another app's data
 * directory, and `run-as` needs a debuggable build — which is the one thing a benchmark must
 * not measure.
 */
internal fun seedTargetHistory(): Int {
    val context = InstrumentationRegistry.getInstrumentation().context
    val result = context.contentResolver.call(
        Uri.parse("content://${TargetApp.SEED_AUTHORITY}"),
        TargetApp.SEED_METHOD,
        null,
        null,
    )

    checkNotNull(result) {
        "The app did not answer on content://${TargetApp.SEED_AUTHORITY}. Install the " +
            "`benchmark` build type of :app — `BenchmarkSeedProvider` exists in no other."
    }
    return result.getInt(TargetApp.SEED_RESULT_SET_COUNT)
}

/** Waits for [selector], failing with a message that names what was missing. */
internal fun UiDevice.awaitObject(
    selector: BySelector,
    description: String,
): UiObject2 {
    wait(Until.hasObject(selector), TargetApp.UI_TIMEOUT_MS)
    return checkNotNull(findObject(selector)) {
        "$description did not appear within ${TargetApp.UI_TIMEOUT_MS}ms ($selector)"
    }
}

/** Waits for the node tagged [tag]. */
internal fun UiDevice.awaitTag(
    tag: String,
    description: String,
): UiObject2 = awaitObject(By.res(tag), description)

/**
 * Waits for [selector] *inside this node*, failing with a message that names what was missing.
 *
 * For a match that is only safe within a subtree. A device-wide `findObject` walks the whole
 * window top-down and returns the first hit, so a text selector aimed at a list row can land
 * on a bar title above the list that happens to start the same way.
 */
internal fun UiObject2.awaitDescendant(
    selector: BySelector,
    description: String,
): UiObject2 =
    checkNotNull(wait(Until.findObject(selector), TargetApp.UI_TIMEOUT_MS)) {
        "$description did not appear within ${TargetApp.UI_TIMEOUT_MS}ms ($selector)"
    }

/**
 * Presses a bottom-bar tab and waits for the pane behind it.
 *
 * The bar is only drawn on the four tab destinations, so this is also the assertion that the
 * app opened where it was expected to: a launch that landed on onboarding — which is what a
 * run against an unseeded install does — fails here rather than in the numbers.
 */
internal fun UiDevice.selectTab(
    tabTag: String,
    contentTag: String,
    description: String,
): UiObject2 {
    awaitTag(tabTag, "the $description tab").click()
    return awaitTag(contentTag, description)
}

/**
 * Opens the app on the tabs, from cold, without measuring it.
 *
 * `killProcess` and not `pressHome`: an iteration that reused the previous one's process would
 * be measuring a warm Compose runtime and a warm page cache, and the first iteration would be
 * the only honest one in the run.
 */
internal fun MacrobenchmarkScope.restartOnHome() {
    killProcess()
    startActivityAndWait()
}

/**
 * Flings [list] far enough to pass roughly [items] rows, then waits for the frames to land.
 *
 * The margin keeps the gesture off the edges, where the system's own back and navigation
 * gestures would take it instead — a fling that opens the recents screen reports beautiful
 * frame timings for a list nobody scrolled.
 *
 * `waitForIdle` afterwards because a fling keeps producing frames after the finger has left
 * the glass, and those frames are the tail the jank percentiles are made of.
 */
internal fun UiDevice.flingThrough(
    list: UiObject2,
    items: Int,
) {
    list.setGestureMargin(displayWidth / GESTURE_MARGIN_DIVISOR)
    repeat(items / ITEMS_PER_FLING) { list.fling(Direction.DOWN) }
    waitForIdle()
}

/** A twelfth of the screen, the margin UiAutomator's own samples use. */
private const val GESTURE_MARGIN_DIVISOR = 12

/**
 * How many rows one fling is *counted* as covering, which is fewer than it does.
 *
 * On the reference device (SM-A536B, 1080×2400 at 120 Hz) a session row is a fifteenth of the
 * screen, not a sixth, and a UiAutomator fling carries several screenfuls: the Perfetto traces
 * show the three flings this yields composing ~140 rows, not 30. Left at ten on purpose. The
 * acceptance criterion's 30 is a floor, and shortening the gesture would change the number
 * every recorded baseline was measured with; a fling that covers more rows says the same thing
 * about the list, only more of it.
 */
private const val ITEMS_PER_FLING = 10
