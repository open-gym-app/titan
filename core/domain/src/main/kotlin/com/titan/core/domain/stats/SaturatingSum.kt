package com.titan.core.domain.stats

/**
 * This plus [other], held at `Long.MAX_VALUE` or `Long.MIN_VALUE` where the sum would leave a
 * `Long`.
 *
 * The in-memory twin of the `CAST(TOTAL(…) AS INTEGER)` every aggregate in `WorkoutSetDao`
 * uses. Every total the app adds up is exact many times over for a set `SetValidator` admits,
 * but a restored backup can hold a set past those bounds, and a wrapped sum would read as
 * negative work. Holding it at the bound instead keeps a figure computed here equal to the
 * one SQLite gives for the same rows.
 */
fun Long.saturatingPlus(other: Long): Long =
    try {
        Math.addExact(this, other)
    } catch (_: ArithmeticException) {
        if (this < 0) Long.MIN_VALUE else Long.MAX_VALUE
    }

/**
 * This times [other], held at the `Long` bound of the product's sign where it would leave a
 * `Long`, for [saturatingPlus]'s reason.
 */
fun Long.saturatingTimes(other: Long): Long =
    try {
        Math.multiplyExact(this, other)
    } catch (_: ArithmeticException) {
        if ((this < 0) == (other < 0)) Long.MAX_VALUE else Long.MIN_VALUE
    }

/** The sum of [selector] over these elements, each addition by [saturatingPlus]; 0 when empty. */
inline fun <T> Iterable<T>.sumOfSaturating(selector: (T) -> Long): Long =
    fold(0L) { total, element -> total.saturatingPlus(selector(element)) }
