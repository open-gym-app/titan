package com.titan.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The data behind the open source licences screen (#208), asserted against the resource the
 * build actually packages rather than the file the plugin writes to `build/generated/`.
 *
 * Two things have already gone wrong here silently, and this test exists so neither can again:
 *
 * - The AboutLibraries Gradle plugin registering nothing. Its `13.x` line hooks into AGP's
 *   legacy variant API, which AGP 9 no longer exposes, and on that pairing it logs a warning
 *   and generates no resource at all — the build passes, and `produceLibraries()` throws
 *   `Resources.NotFoundException` the first time somebody opens the screen. The lookup below
 *   is the same one the runtime does, by name, and fails the build instead.
 * - Licences without text. `offlineMode` stops the plugin fetching the SPDX text, and a row
 *   whose licence has no text does nothing when tapped — the acceptance criterion is a dialog
 *   with the full text. The text is checked in under `config/aboutlibraries/licenses/`, and
 *   every licence the graph pulls in has to have a file there.
 *
 * Parsed with `org.json` rather than `Libs.Builder` because `:app` does not compile against
 * AboutLibraries' runtime — the screen lives in `:feature:settings` — and a test dependency
 * added for a parser would be more than the assertion is worth.
 */
@RunWith(RobolectricTestRunner::class)
class OpenSourceLicencesDataTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the licence data is packaged as a raw resource`() {
        assertNotEquals(
            "raw/aboutlibraries is missing: the AboutLibraries plugin generated nothing for " +
                "this variant, and the screen would crash on open",
            0,
            rawResourceId(),
        )
    }

    @Test
    fun `every library ships with a licence, and every licence with its text`() {
        val json = context.resources.openRawResource(rawResourceId()).bufferedReader().use { it.readText() }
        val root = JSONObject(json)

        val libraries = root.getJSONArray("libraries")
        assertTrue("the dependency graph produced no libraries", libraries.length() > 0)

        val licences = root.getJSONObject("licenses")
        val withoutLicence = (0 until libraries.length())
            .map { libraries.getJSONObject(it) }
            .filter { it.getJSONArray("licenses").length() == 0 }
            .map { it.getString("uniqueId") }
        assertTrue("libraries with no licence at all: $withoutLicence", withoutLicence.isEmpty())

        val withoutText = licences.keys().asSequence()
            .filter { licences.getJSONObject(it).optString("content").isBlank() }
            .toList()
        assertTrue(
            "licences with no text — add a file under config/aboutlibraries/licenses/: $withoutText",
            withoutText.isEmpty(),
        )
        assertFalse(licences.keys().asSequence().toList().isEmpty())
    }

    /** The same by-name lookup `produceLibraries()` performs; see `OpenSourceLicencesScreen`. */
    private fun rawResourceId(): Int = context.resources.getIdentifier("aboutlibraries", "raw", context.packageName)
}
