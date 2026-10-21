package com.titan.core.domain.timer

/**
 * The three positions the device's ringer switch can be in.
 *
 * A domain enum rather than `AudioManager.RINGER_MODE_*`, because the *decision* about what a
 * rest-over alert may do is a product rule and the constants are an Android detail. Whoever
 * reads the platform maps it to one of these — see `RestAlertStyle`.
 */
enum class RingerMode {

    /** The phone has been told to make no noise of any kind. */
    SILENT,

    /** No tone, but a buzz. The mode that asks for exactly what a rest-over alert is. */
    VIBRATE,

    /** Ordinary. Both. */
    NORMAL,
}

/**
 * What the end of a rest is allowed to do to a phone in a given [RingerMode].
 *
 * ### One function, two callers
 *
 * The app says "your rest is over" twice over, by two entirely different mechanisms: the
 * in-app alert plays a tone and buzzes the device itself, and the background path posts a
 * notification the system sounds on the app's behalf. Both have to reach the same answer about
 * a phone that has been silenced, and a second copy of `ringerMode != RINGER_MODE_NORMAL` in
 * the second one is the copy that goes on ringing after the first is fixed.
 *
 * So the rule is stated once, here, in the module both can reach:
 *
 * | Ringer | Tone | Vibration | Why |
 * | --- | --- | --- | --- |
 * | [RingerMode.SILENT] | no | no | A phone told to be still is not overridden from a gym. |
 * | [RingerMode.VIBRATE] | no | yes | Vibrate is the mode that asks for this. |
 * | [RingerMode.NORMAL] | yes | yes | Both, which is what a lifter across a bench needs. |
 *
 * Note the asymmetry between the two columns is the point: the buzz survives one notch further
 * down than the tone does, because vibrate mode is a request for a buzz rather than a request
 * for quiet.
 */
data class RestAlertStyle(
    val tone: Boolean,
    val vibration: Boolean,
) {

    /** Whether this alert would be noticed at all. False on a silenced phone. */
    val isAudible: Boolean get() = tone || vibration

    companion object {

        /** What a rest-over alert may do with the ringer in [mode]. See the table above. */
        fun forRingerMode(mode: RingerMode): RestAlertStyle =
            when (mode) {
                RingerMode.SILENT -> RestAlertStyle(tone = false, vibration = false)
                RingerMode.VIBRATE -> RestAlertStyle(tone = false, vibration = true)
                RingerMode.NORMAL -> RestAlertStyle(tone = true, vibration = true)
            }
    }
}
