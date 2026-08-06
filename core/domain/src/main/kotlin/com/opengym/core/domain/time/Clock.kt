package com.opengym.core.domain.time

/**
 * Source of the current wall-clock time in epoch milliseconds UTC.
 *
 * The lifecycle use cases stamp rows with "now", which would otherwise make them
 * untestable. They take this as a *defaulted* constructor parameter so production
 * call sites still construct them from repository interfaces alone, while a test
 * can pin time to a fixed value.
 */
fun interface Clock {

    fun nowMillis(): Long

    companion object {
        /** The real clock. */
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
