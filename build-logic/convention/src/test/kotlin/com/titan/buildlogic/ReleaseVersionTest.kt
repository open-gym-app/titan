package com.titan.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag-to-version rule `release/RELEASE.md` states, pinned here so the document and the
 * build cannot drift apart without a red test: every `versionCode` Play has accepted is
 * one this function produced, and the next one has to be larger.
 */
class ReleaseVersionTest {

    @Test
    fun `no property is the development version`() {
        assertEquals(ReleaseVersion("0.0.0-dev", 1), ReleaseVersion.parse(null))
        assertFalse(ReleaseVersion.parse(null).isReleaseCandidate)
    }

    @Test
    fun `a release tag is weighted major minor patch and ends in the release slot`() {
        assertEquals(ReleaseVersion("1.0.0", 1_000_099), ReleaseVersion.parse("1.0.0"))
        assertEquals(ReleaseVersion("1.2.3", 1_020_399), ReleaseVersion.parse("1.2.3"))
        assertEquals(ReleaseVersion("0.9.0", 90_099), ReleaseVersion.parse("0.9.0"))
        assertEquals(ReleaseVersion("12.99.99", 12_999_999), ReleaseVersion.parse("12.99.99"))
    }

    @Test
    fun `a release candidate has a code of its own and the changelog of its release`() {
        val candidate = ReleaseVersion.parse("1.0.0-rc.2")

        assertEquals("1.0.0-rc.2", candidate.name)
        assertEquals(1_000_002, candidate.code)
        assertEquals(ReleaseVersion.parse("1.0.0").code, candidate.releaseCode)
        assertTrue(candidate.isReleaseCandidate)
        assertFalse(ReleaseVersion.parse("1.0.0").isReleaseCandidate)
    }

    @Test
    fun `the code is monotonic across the components and the candidates`() {
        val ordered = listOf(
            "0.0.1", "0.1.0", "0.99.99", "1.0.0-rc.1", "1.0.0-rc.2", "1.0.0-rc.98", "1.0.0",
            "1.0.1-rc.1", "1.0.1", "1.1.0", "2.0.0-rc.1", "2.0.0",
        ).map { ReleaseVersion.parse(it).code }

        assertEquals(ordered.sorted(), ordered)
        assertEquals(ordered.distinct(), ordered)
    }

    @Test
    fun `a development build never upgrades over the first release`() {
        assertTrue(ReleaseVersion.DEV.code < ReleaseVersion.parse("1.0.0").code)
    }

    @Test
    fun `a component of 100 would break the ordering and is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("1.100.0") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("1.0.100") }
    }

    @Test
    fun `a 99th candidate would take the release's code and is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("1.0.0-rc.99") }
    }

    @Test
    fun `anything that is not the documented shape is rejected`() {
        listOf(
            "v1.0.0",
            "1.0",
            "1.0.0.0",
            "01.0.0",
            "1.0.0-rc",
            "1.0.0-rc.0",
            "1.0.0-beta.1",
            "1.0.0-dev",
            "",
            " 1.0.0",
        ).forEach { tag ->
            assertThrows("'$tag' should be rejected", IllegalArgumentException::class.java) {
                ReleaseVersion.parse(tag)
            }
        }
    }
}
