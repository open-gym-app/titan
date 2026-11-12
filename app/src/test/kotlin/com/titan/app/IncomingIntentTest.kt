package com.titan.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import androidx.test.core.app.ApplicationProvider
import com.titan.core.ui.state.SCREEN_STATE_KEY
import com.titan.feature.settings.navigation.BackupDestination
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an intent from outside the app can reach through `MainActivity` (#255).
 *
 * The activity is exported, and Navigation reads its own deep-link extras off whatever intent
 * starts it — `deepLinkIds` to pick a destination, `deepLinkExtras` to seed its arguments, and so
 * its `SavedStateHandle` — with no check on the sender. Built by hand, those open screens that have
 * no `titan://` link at all, with saved state the sender chose. The one built here is the worst the
 * audit found: the backup screen with a restore pending, whose dialog offers to replace every
 * workout on the device with a file the user never picked.
 *
 * ### What is asserted, and why not the dialog itself
 *
 * The launch is the real one — `MainActivity`, created with the intent as sent — and what is
 * asserted is the intent the activity holds once `onCreate` has run, which is the only one the
 * graph ever reads. Composing the shell over it and waiting for Home is the obvious test and does
 * not terminate: the real activity's composition never goes idle under Robolectric, so the compose
 * rule's first read waits forever, and the JVM is left hanging for whatever runs after it — the
 * same failure `DeepLinkNavigationTest` records for driving a launch intent through the graph.
 * That the dialog does not appear is checked on a device: see the PR's manual steps.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class IncomingIntentTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    private val context: Context = ApplicationProvider.getApplicationContext()

    // --- the launch -----------------------------------------------------------

    @Test
    fun `navigation's own extras are gone before the graph can read them`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, navigationExtrasToBackup()).create().get()

        assertNull(activity.intent.extras)
        assertNull(activity.intent.data)
    }

    @Test
    fun `a link that arrives while the app is running is handed on without its extras`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val handed = mutableListOf<Intent>()
        controller.get().addOnNewIntentListener { handed += it }

        controller.newIntent(navigationExtrasToBackup().setAction(Intent.ACTION_VIEW).setData(LINK.toUri()))

        val incoming = handed.single()
        assertEquals(LINK.toUri(), incoming.data)
        assertNull(incoming.extras)
        assertNull(controller.get().intent.extras)
    }

    // --- the copy Navigation is handed ----------------------------------------

    @Test
    fun `a link keeps its action and its data and nothing else`() {
        val sent = navigationExtrasToBackup()
            .setAction(Intent.ACTION_VIEW)
            .setData(LINK.toUri())

        val kept = sent.forNavigation(keepLink = true)

        assertEquals(Intent.ACTION_VIEW, kept.action)
        assertEquals(LINK.toUri(), kept.data)
        assertNull(kept.extras)
        assertNull(kept.component)
    }

    @Test
    fun `a link under any action but VIEW is dropped`() {
        // The manifest admits a `titan://` link only on VIEW, and Navigation would match one under
        // any action — so an explicit intent naming the activity with SEND would be a way round
        // the filter.
        val sent = Intent(Intent.ACTION_SEND, LINK.toUri())

        assertNull(sent.forNavigation(keepLink = true).data)
    }

    @Test
    fun `a recreated activity does not open its launch link a second time`() {
        val sent = Intent(Intent.ACTION_VIEW, LINK.toUri())

        assertNull(sent.forNavigation(keepLink = false).data)
    }

    // --- helpers ----------------------------------------------------------------

    /**
     * The intent a hostile app would send: no action, no link, and Navigation's own extras naming
     * the backup screen with a restore already pending.
     *
     * The ids are the ones Navigation gives each destination: the root graph is built with no
     * route and so has none, and a type-safe destination's id is the hash of its serial name.
     */
    private fun navigationExtrasToBackup(): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(
                NavController.KEY_DEEP_LINK_IDS,
                intArrayOf(ROOT_GRAPH_ID, serializer<BackupDestination>().descriptor.serialName.hashCode()),
            )
            .putExtra(
                NavController.KEY_DEEP_LINK_EXTRAS,
                bundleOf(SCREEN_STATE_KEY to """{"pendingRestoreUri":"content://elsewhere/backup.json"}"""),
            )

    private companion object {
        const val ROOT_GRAPH_ID = 0

        const val LINK = "titan://session/new"
    }
}
