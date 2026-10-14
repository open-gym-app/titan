package com.titan.core.domain.model

/**
 * Whether Health Connect can be used on this device right now.
 *
 * Anything but [AVAILABLE] hides the sync and import controls: an option that cannot work is
 * not shown, rather than shown disabled.
 */
enum class HealthAvailability {
    AVAILABLE,

    /** Android older than 9 (API 28), or a device that cannot run Health Connect at all. */
    UNSUPPORTED,

    /** Health Connect is supported but not installed (or needs an update). */
    NOT_INSTALLED,
}
