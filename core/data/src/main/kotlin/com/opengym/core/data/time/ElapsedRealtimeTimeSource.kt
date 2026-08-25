package com.opengym.core.data.time

import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Elapsed time as Android measures it, including the time the device spent asleep.
 *
 * The reading behind [com.opengym.core.domain.timer.RestTimerController]'s countdown, and the
 * one place in the app that knows there is more than one kind of "how long has it been".
 * Three clocks are available and only this one is right for a rest:
 *
 * | Reading | Counts sleep | Survives a reboot | Steps with the wall clock |
 * | --- | --- | --- | --- |
 * | `System.currentTimeMillis` — [com.opengym.core.domain.time.Clock] | yes | yes | **yes** |
 * | `System.nanoTime` — [TimeSource.Monotonic] | **no** | no | no |
 * | `SystemClock.elapsedRealtime` — this | yes | no | no |
 *
 * A rest is an *interval*, so a clock that an NTP sync or a flight across a timezone can step
 * is out: the first column is what disqualifies the wall clock. `TimeSource.Monotonic` — the
 * controller's own default, and the right default for a pure-Kotlin module that cannot import
 * this — is `System.nanoTime`, which stops while the device is in deep sleep. A phone locked
 * and pocketed between two sets is exactly that, so a countdown measured against it comes back
 * owing the whole rest.
 *
 * Not a substitute for the persisted deadline, which is a wall-clock instant and has to be:
 * this reading resets to zero on reboot, so it cannot mean anything to a process that was not
 * running when it was taken. See [com.opengym.core.domain.timer.RestTimerSnapshot], which
 * makes the same comparison from the other side.
 */
object ElapsedRealtimeTimeSource : TimeSource {

    override fun markNow(): TimeMark = Mark(SystemClock.elapsedRealtime())

    /**
     * One reading, and the arithmetic against a later one.
     *
     * Millisecond resolution, which is what `elapsedRealtime` gives and three orders of
     * magnitude finer than the second a rest is drawn to.
     */
    private class Mark(private val readingMillis: Long) : TimeMark {

        override fun elapsedNow(): Duration = (SystemClock.elapsedRealtime() - readingMillis).milliseconds
    }
}
