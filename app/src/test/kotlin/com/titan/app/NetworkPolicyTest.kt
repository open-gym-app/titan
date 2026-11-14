package com.titan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's promise to open no network connection, asserted against the files that ship it (#48).
 *
 * Two locks hold it. The first is the INTERNET permission: no manifest in the tree requests it,
 * and the app manifest strips it from every library's manifest at merge time, because a
 * dependency that declares it would otherwise grant it with no diff here. The second is
 * `network_security_config.xml`, which trusts no certificate authority and refuses cleartext, so
 * a socket opened anyway fails on its first request. Either one loosening is a deliberate
 * decision that belongs in a reviewed change to this test, not a side effect of a library upgrade
 * or a copied snippet.
 *
 * Read off disk as `BackupRulesTest` does, with the module directory as the working directory.
 */
class NetworkPolicyTest {

    @Test
    fun `manifest wires the network security config`() {
        val application = parse("src/main/AndroidManifest.xml").elements("application").single()

        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
        assertFalse(
            "usesCleartextTraffic must stay absent: the config owns that decision",
            application.hasAttribute("android:usesCleartextTraffic"),
        )
    }

    /**
     * Checked across the whole file rather than on `base-config` alone: a `<certificates>` under
     * `debug-overrides` or a `domain-config` trusts an authority just as well, and only in the
     * build it was written for.
     */
    @Test
    fun `network security config refuses cleartext and trusts no authority`() {
        val config = parse("src/main/res/xml/network_security_config.xml")
        val base = config.elements("base-config").single()

        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals("trust-anchors must be present to replace the system set", 1, base.elements("trust-anchors").size)
        assertTrue("no authority may be trusted anywhere", config.elements("certificates").isEmpty())
        assertTrue("no domain may be carved out of the block", config.elements("domain-config").isEmpty())
    }

    @Test
    fun `the app manifest strips internet from every dependency`() {
        val removed = parse("src/main/AndroidManifest.xml").elements("uses-permission").filter {
            it.getAttribute("android:name") == INTERNET && it.getAttribute("tools:node") == "remove"
        }

        assertEquals(1, removed.size)
    }

    /**
     * Every source set, not only `main`: a build type's manifest merges over `main` and outranks
     * its `tools:node="remove"`, so INTERNET requested in `src/debug` would reach that build.
     */
    @Test
    fun `no module requests the internet permission`() {
        val offenders = projectManifests().filter { manifest ->
            parse(manifest.path).elements("uses-permission").any {
                it.getAttribute("android:name") == INTERNET && it.getAttribute("tools:node") != "remove"
            }
        }
        assertEquals("manifests requesting INTERNET: $offenders", emptyList<File>(), offenders)
    }

    /**
     * Not a network lock, but the same promise read off the same files: the gym directory is
     * the user's own typing (#50). `NearbyGymsUseCase` is handed the point it measures from and
     * a gym's pin is optional, so nothing needs the device's position — and asking for it is a
     * decision that belongs in a reviewed change to this test.
     */
    @Test
    fun `no module requests a location permission`() {
        val offenders = projectManifests().filter { manifest ->
            parse(manifest.path).elements("uses-permission").any {
                it.getAttribute("android:name") in LOCATION_PERMISSIONS && it.getAttribute("tools:node") != "remove"
            }
        }

        assertEquals("manifests requesting a location permission: $offenders", emptyList<File>(), offenders)
    }

    private fun projectManifests(): List<File> {
        val root = File("..").canonicalFile
        val manifests = root.walkTopDown()
            .onEnter { it == root || (it.name != "build" && !it.name.startsWith(".")) }
            .filter { it.name == "AndroidManifest.xml" && it.parentFile?.parentFile?.name == "src" }
            .toList()

        assertTrue("expected to find the project's manifests", manifests.size > 1)
        return manifests
    }

    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        // Namespace-unaware so the `android:` and `tools:` attributes read by the names they are
        // written under.
        return DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.elements(tagName: String): List<Element> =
        getElementsByTagName(tagName).let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }

    private companion object {
        const val INTERNET = "android.permission.INTERNET"
        val LOCATION_PERMISSIONS = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        )
    }
}
