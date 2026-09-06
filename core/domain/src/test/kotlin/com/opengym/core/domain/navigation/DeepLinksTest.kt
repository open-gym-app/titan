package com.opengym.core.domain.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URIs, spelled out.
 *
 * These are pinned rather than derived because they are a *published address*: the notification
 * that carries one is posted by `:core:data` and answered by a filter in `:app`'s manifest and a
 * `navDeepLink` in `:feature:session`, and none of those three can see the other two. A change
 * that breaks the agreement between them produces a tap that does nothing, with no error
 * anywhere — so the literal is written down twice on purpose, here and in the manifest.
 */
class DeepLinksTest {

    @Test
    fun `the active session link is the scheme, the host and the id`() {
        assertEquals("opengym://session/active/42", DeepLinks.activeSession(42))
    }

    @Test
    fun `the base path is the link without its id`() {
        // What `navDeepLink(basePath = …)` is handed. Navigation appends the argument segment
        // itself, so the two have to be exactly this far apart.
        assertEquals("opengym://session/active", DeepLinks.ACTIVE_SESSION)
        assertTrue(DeepLinks.activeSession(7).startsWith("${DeepLinks.ACTIVE_SESSION}/"))
    }

    @Test
    fun `the manifest filter's scheme and host are the ones the links are built from`() {
        // `:app`'s intent filter matches on these two and nothing else, so a link that did not
        // start with them would resolve to no activity at all.
        assertTrue(DeepLinks.activeSession(1).startsWith("${DeepLinks.SCHEME}://${DeepLinks.SESSION_HOST}/"))
    }
}
