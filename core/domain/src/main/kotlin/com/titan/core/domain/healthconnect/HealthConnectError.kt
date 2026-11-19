package com.titan.core.domain.healthconnect

/**
 * Every way a Health Connect operation can be refused before it reaches the provider.
 *
 * Returned inside a [Result], as [com.titan.core.domain.model.SessionError] is, so a screen can
 * `when` over the two it has something to say about. A failure *from* the provider — a quota, a
 * provider that crashed — arrives as whatever it threw, because there is nothing more specific
 * to tell the user than "that did not work".
 */
sealed class HealthConnectError(message: String) : IllegalStateException(message) {

    /** Health Connect is not usable on this device — see [HealthConnectAvailability]. */
    data class Unavailable(val availability: HealthConnectAvailability) :
        HealthConnectError("Health Connect is not available: $availability")

    /** The permission the operation needs has not been granted, or has been revoked since. */
    data class NotPermitted(val access: HealthConnectAccess) :
        HealthConnectError("Health Connect access $access has not been granted")
}
