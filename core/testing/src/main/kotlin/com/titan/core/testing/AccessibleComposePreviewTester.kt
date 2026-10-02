package com.titan.core.testing

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.AndroidComposePreviewTester
import com.github.takahirom.roborazzi.ComposePreviewTester
import com.github.takahirom.roborazzi.ComposePreviewTester.TestParameter.JUnit4TestParameter.AndroidPreviewJUnit4TestParameter
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeCaptureOption
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * The tester behind every generated `@Preview` test: a screenshot, and the Accessibility Test
 * Framework over the same frame.
 *
 * ### Why the previews
 *
 * The acceptance criterion is *"Accessibility Scanner reports zero errors on all twenty
 * screens"*, asserted per screen and not spot-checked. The previews are already that
 * inventory — every screen in every state it can be drawn in, scanned per module by the
 * Roborazzi plugin — so running the Scanner's own checks over each capture is the criterion
 * as a build gate, with no test anybody has to remember to write. A screen added tomorrow is
 * checked the moment it has a preview, which it needs for its screenshot anyway.
 *
 * ### How it fits into the capture
 *
 * Roborazzi composes the preview into an activity, waits for it to settle, screenshots it and
 * closes the activity. The check has to happen *between* settling and closing, while the
 * window still exists; [AccessibilityCheckOption] is a capture option appended after
 * Roborazzi's own, so its `beforeCapture` runs once the clock has advanced and the tree is
 * idle. Before the screenshot rather than after: a failure then names the preview and stops,
 * instead of being thrown from a `finally` over a screenshot mismatch it would mask.
 *
 * Which also means the check runs when the capture does, and not otherwise — under
 * `recordRoborazziDebug` and `verifyRoborazziDebug`, which is `make test` and CI, and not under
 * a bare `./gradlew test`, which never composes a preview at all. Same rule as the screenshots
 * themselves, for the same reason; see CONTRIBUTING.md.
 *
 * Named in `AndroidComposeConventionPlugin` as the plugin's `testerQualifiedClassName`, and
 * instantiated by the generated test through its no-argument constructor.
 */
@OptIn(ExperimentalRoborazziApi::class)
class AccessibleComposePreviewTester : ComposePreviewTester<AndroidPreviewJUnit4TestParameter> {

    /**
     * Scanning is Roborazzi's, untouched: what the plugin was told to scan, it scans, and the
     * only thing this class changes is what happens once a preview is composed.
     */
    override fun testParameters(): List<AndroidPreviewJUnit4TestParameter> =
        AndroidComposePreviewTester().testParameters()

    override fun test(testParameter: AndroidPreviewJUnit4TestParameter) {
        // A tester per test rather than one held in a field: the capturer needs the rule the
        // generated test built for *this* parameter, which is the one that owns the activity
        // the preview is about to be composed into.
        AndroidComposePreviewTester(capturer = AccessibilityCheckingCapturer(testParameter.composeTestRule))
            .test(testParameter)
    }
}

/**
 * Roborazzi's default capture with [AccessibilityCheckOption] appended to its options, so the
 * check sees the composed preview and the screenshot is taken of the same frame.
 */
@OptIn(ExperimentalRoborazziApi::class)
private class AccessibilityCheckingCapturer(private val composeTestRule: ComposeContentTestRule) :
    AndroidComposePreviewTester.Capturer {

    override fun capture(parameter: AndroidComposePreviewTester.CaptureParameter) {
        parameter.preview.captureRoboImage(
            filePath = parameter.filePath,
            roborazziOptions = parameter.roborazziOptions,
            roborazziComposeOptions = parameter.roborazziComposeOptions
                .builder()
                .addOption(AccessibilityCheckOption(composeTestRule))
                .build(),
        )
    }
}

/**
 * Runs [AccessibilityChecks] over the whole composed window just before it is screenshotted.
 *
 * Appended last, so Roborazzi's own capture option — the one that advances the test clock a
 * frame and waits for idle — has already run: a check over a tree mid-composition would report
 * the placeholder a `LaunchedEffect` was about to replace.
 */
@OptIn(ExperimentalRoborazziApi::class)
private class AccessibilityCheckOption(private val composeTestRule: ComposeContentTestRule) :
    RoborazziComposeCaptureOption {

    override fun beforeCapture() {
        composeTestRule.assertAllRootsAccessible()
    }

    override fun afterCapture() = Unit
}
