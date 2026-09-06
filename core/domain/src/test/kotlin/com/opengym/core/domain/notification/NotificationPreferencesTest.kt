package com.opengym.core.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a fresh install wants to hear, and the rule that turns two facts into a rendered state.
 *
 * Both are product decisions rather than mechanics: which notification a user gets without
 * asking for it, and whether somebody who has not been prompted is shown as blocked. Asserting
 * them here keeps them from being re-decided by accident in a settings screen or a data mapper.
 */
class NotificationPreferencesTest {

    @Test
    fun `a fresh install wants the rest timer and nothing else`() {
        // Everything is off except the one notification the user has already asked for by
        // starting a rest. A default-on notification is a decision they did not make.
        assertEquals(setOf(NotificationKind.REST_TIMER), NotificationPreferences().enabled)
    }

    @Test
    fun `a fresh install has not been asked for the permission`() {
        assertFalse(NotificationPreferences().permissionRequested)
    }

    @Test
    fun `turning a kind on and off again leaves the others alone`() {
        val preferences = NotificationPreferences()
            .with(NotificationKind.REMINDERS, enabled = true)
            .with(NotificationKind.SESSION, enabled = true)
            .with(NotificationKind.SESSION, enabled = false)

        assertTrue(NotificationKind.REMINDERS in preferences)
        assertFalse(NotificationKind.SESSION in preferences)
        assertTrue(NotificationKind.REST_TIMER in preferences)
    }

    @Test
    fun `turning on a kind that is already on changes nothing`() {
        val preferences = NotificationPreferences()

        assertEquals(preferences, preferences.with(NotificationKind.REST_TIMER, enabled = true))
    }

    @Test
    fun `allowed notifications are granted, whatever else is true`() {
        NotificationKind.entries.forEach { _ ->
            listOf(true, false).forEach { required ->
                listOf(true, false).forEach { asked ->
                    assertEquals(
                        NotificationPermissionState.GRANTED,
                        NotificationPermissionState.of(
                            allowed = true,
                            runtimePermissionRequired = required,
                            alreadyRequested = asked,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `a prompt that has never been raised is not a refusal`() {
        // The distinction Android will not make for us: `shouldShowRequestPermissionRationale`
        // reads the same before the first prompt as it does after a refusal. Without this, a
        // fresh install renders its notification rows as blocked having said nothing.
        assertEquals(
            NotificationPermissionState.NOT_REQUESTED,
            NotificationPermissionState.of(
                allowed = false,
                runtimePermissionRequired = true,
                alreadyRequested = false,
            ),
        )
    }

    @Test
    fun `a prompt that has been raised and left us without the permission is a refusal`() {
        assertEquals(
            NotificationPermissionState.DENIED,
            NotificationPermissionState.of(
                allowed = false,
                runtimePermissionRequired = true,
                alreadyRequested = true,
            ),
        )
    }

    @Test
    fun `with no runtime permission to ask for, not allowed means denied`() {
        // Android 12 and below: notifications were granted at install and the user has since
        // turned them off by hand. There is no prompt to raise, only system settings to visit.
        assertEquals(
            NotificationPermissionState.DENIED,
            NotificationPermissionState.of(
                allowed = false,
                runtimePermissionRequired = false,
                alreadyRequested = false,
            ),
        )
    }

    @Test
    fun `only granted counts as granted`() {
        assertTrue(NotificationPermissionState.GRANTED.isGranted)
        assertFalse(NotificationPermissionState.NOT_REQUESTED.isGranted)
        assertFalse(NotificationPermissionState.DENIED.isGranted)
    }
}
