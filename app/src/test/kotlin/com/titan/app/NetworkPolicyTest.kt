package com.titan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's promise to open no network connection, asserted against the files that ship it (#48).
 *
 * Two locks hold it: no module requests `android.permission.INTERNET`, and
 * `network_security_config.xml` trusts no certificate authority and refuses cleartext, so a
 * dependency that opens a socket anyway fails on its first request. Either one loosening is a
 * deliberate decision that belongs in a reviewed change to this test, not a side effect of a
 * library upgrade or a copied snippet.
 *
 * Read off disk as `BackupRulesTest` does, with the module directory as the working directory.
 */
class NetworkPolicyTest {

    @Test
    fun `manifest wires the network security config`() {
        val application = parse("src/main/AndroidManifest.xml").elements("application").single()

        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
        assertTrue(
            "usesCleartextTraffic must stay absent: the config owns that decision",
            !application.hasAttribute("android:usesCleartextTraffic"),
        )
    }

    @Test
    fun `network security config refuses cleartext and trusts no authority`() {
        val base = parse("src/main/res/xml/network_security_config.xml").elements("base-config").single()

        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val anchors = base.elements("trust-anchors").single()
        assertEquals("trust-anchors must be empty", 0, anchors.elements("certificates").size)
        assertTrue(
            "no domain may be carved out of the block",
            parse("src/main/res/xml/network_security_config.xml").elements("domain-config").isEmpty(),
        )
    }

    @Test
    fun `no module requests the internet permission`() {
        val manifests = File("..").walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.name == "AndroidManifest.xml" && it.parentFile.name == "main" }
            .toList()

        assertTrue("expected to find the project's manifests", manifests.size > 1)
        val offenders = manifests.filter { manifest ->
            parse(manifest.path).elements("uses-permission").any {
                it.getAttribute("android:name") == "android.permission.INTERNET"
            }
        }
        assertEquals("manifests requesting INTERNET: $offenders", emptyList<File>(), offenders)
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(File(path))

    private fun Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { found -> (0 until found.length).map { found.item(it) as Element } }

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { found -> (0 until found.length).map { found.item(it) as Element } }
}
