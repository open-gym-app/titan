package com.opengym.core.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils.matchesCheck
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils.matchesElements
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.anything
import org.hamcrest.Matchers.not
import org.hamcrest.TypeSafeMatcher

/**
 * The Accessibility Test Framework, as this project runs it.
 *
 * ### What this is
 *
 * ATF is the library behind Google's Accessibility Scanner: the same `TouchTargetSizeCheck`,
 * `SpeakableTextPresentCheck`, `DuplicateSpeakableTextCheck`, `DuplicateClickableBoundsCheck`
 * and `TraversalOrderCheck` the app on the phone runs are classes on this test classpath, and
 * Roborazzi points them at a composed Robolectric window. So a preview that passes here is a
 * screen the Scanner has, in effect, already seen — and one that fails here fails before the
 * phone is reached. Every generated `@Preview` test runs it through
 * [AccessibleComposePreviewTester]; a test that drives a screen into a state no preview shows
 * calls [assertAccessible] or [assertAllRootsAccessible] on what it has.
 *
 * ### What it does not replace
 *
 * A device. Robolectric draws with real layout and real text measurement, but its font
 * metrics at large scales, its window insets and its TalkBack are not the phone's, and the
 * Scanner's *warnings* — low-contrast text below its threshold, a touch target one dp short
 * of Material's — are a judgement about pixels this runtime only approximates. The device pass
 * in docs/ACCESSIBILITY.md is still the result; this is what keeps the result from rotting.
 *
 * ### What is accepted, and where that is written down
 *
 * [ACCEPTED_DEVIATIONS] is the complete list of findings the project has decided to live with.
 * Each is scoped to one check on one component by its test tag, so it cannot quietly grow to
 * cover the next thing; each has its reason in docs/ACCESSIBILITY.md, which is the document a
 * reviewer reads. Adding to the list means adding to the document.
 */
@OptIn(ExperimentalRoborazziApi::class)
object AccessibilityChecks {

    /**
     * The checks the device Scanner runs, at its own severity: an ATF `ERROR` fails the test and
     * a `WARNING` is logged. The acceptance criterion is *zero errors*, and holding the JVM to a
     * stricter bar than the phone would fail the build on the approximation rather than on the
     * app.
     *
     * @param suppressions findings a particular test accepts on top of [ACCEPTED_DEVIATIONS] —
     *   for a state it drives the UI into that the document covers. Rarely the right tool.
     */
    fun options(
        suppressions: Matcher<in AccessibilityViewCheckResult> = nothing(),
    ): RoborazziATFAccessibilityCheckOptions =
        RoborazziATFAccessibilityCheckOptions(
            checker = RoborazziATFAccessibilityChecker(
                preset = AccessibilityCheckPreset.LATEST,
                suppressions = anyOf(ACCEPTED_DEVIATIONS, suppressions),
            ),
            failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
        )

    /** Suppress nothing. Hamcrest's "not anything", named for what it means here. */
    fun nothing(): Matcher<in AccessibilityViewCheckResult> = not(anything())

    /**
     * The training calendar's day cells, and their size alone.
     *
     * A month is seven columns, a phone's card is about 300dp wide, and seven 48dp targets need
     * 372: the cells come out at 40dp and there is no layout that changes it short of a
     * calendar with fewer days in a week. A trained day's tap filters the list below it to that
     * day, which the date filter also does at full size, so the shortcut is kept at the size
     * the grid allows. The tag is `OpenGymTestTags.CALENDAR_DAY`, restated here because this
     * module knows no other.
     */
    private val CALENDAR_DAY_SIZE: Matcher<AccessibilityViewCheckResult> = allOf(
        matchesCheck(TouchTargetSizeCheck::class.java),
        matchesElements(hasTestTag("calendar:day")),
    )

    /** Every finding the project accepts. See docs/ACCESSIBILITY.md § Accepted deviations. */
    val ACCEPTED_DEVIATIONS: Matcher<AccessibilityViewCheckResult> = anyOf(CALENDAR_DAY_SIZE)

    /** A Compose `testTag`, as ATF sees it on the node the finding is about. */
    private fun hasTestTag(tag: String): Matcher<ViewHierarchyElement> =
        object : TypeSafeMatcher<ViewHierarchyElement>() {
            override fun matchesSafely(item: ViewHierarchyElement): Boolean = item.testTag?.toString() == tag

            override fun describeTo(description: Description) {
                description.appendText("an element with test tag ").appendValue(tag)
            }
        }
}

/**
 * Fails the test on any ATF error under this node — what a screen test calls on `onRoot()`
 * after driving the UI somewhere no preview goes: a row swiped into its delete state, a filter
 * applied, a field left invalid.
 *
 * The previews are checked without anybody asking; this is for the states between them.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteraction.assertAccessible(
    suppressions: Matcher<in AccessibilityViewCheckResult> = AccessibilityChecks.nothing(),
) {
    checkRoboAccessibility(roborazziATFAccessibilityCheckOptions = AccessibilityChecks.options(suppressions))
}

/**
 * [assertAccessible] over every window the test has composed.
 *
 * `onRoot()` assumes one, and a screen with a dialog or a bottom sheet open has two — the
 * activity's and the popup's — each with a Compose view of its own that ATF has to be handed
 * separately. A screen test that has opened a dialog calls this rather than checking the root
 * it can see, since the root it can see is the one *behind* the dialog.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteractionsProvider.assertAllRootsAccessible(
    suppressions: Matcher<in AccessibilityViewCheckResult> = AccessibilityChecks.nothing(),
) {
    val roots = onAllNodes(isRoot())
    roots.fetchSemanticsNodes().indices.forEach { index -> roots[index].assertAccessible(suppressions) }
}
