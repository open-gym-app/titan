package com.titan.core.domain.model

/** One of the three things the titan needs. Declared in the order a tie between them resolves. */
enum class TitanVital {

    /** What it has eaten. Falls over time, and only a feed raises it. */
    FUEL,

    /** What it has left to spend on activities. Refills while it rests. */
    ENERGY,

    /** How much it is enjoying itself. Rises as activities finish, falls slowly otherwise. */
    SPIRIT,
}

/**
 * The titan's three vitals, each a whole number in `0..`[MAX].
 *
 * The constructor refuses a value outside the range; [clamped] is the way in for one that has
 * not been checked, which is every value a restored row or an arithmetic step produces.
 */
data class TitanVitals(
    val fuel: Int,
    val energy: Int,
    val spirit: Int,
) {

    init {
        require(fuel in 0..MAX) { "fuel must be in 0..$MAX, was $fuel" }
        require(energy in 0..MAX) { "energy must be in 0..$MAX, was $energy" }
        require(spirit in 0..MAX) { "spirit must be in 0..$MAX, was $spirit" }
    }

    /** The value of [vital]. */
    operator fun get(vital: TitanVital): Int =
        when (vital) {
            TitanVital.FUEL -> fuel
            TitanVital.ENERGY -> energy
            TitanVital.SPIRIT -> spirit
        }

    /** These vitals with [delta] added to [vital], held inside `0..`[MAX]. */
    fun plus(
        vital: TitanVital,
        delta: Int,
    ): TitanVitals {
        val moved = (this[vital].toLong() + delta).coerceIn(0, MAX.toLong()).toInt()
        return when (vital) {
            TitanVital.FUEL -> copy(fuel = moved)
            TitanVital.ENERGY -> copy(energy = moved)
            TitanVital.SPIRIT -> copy(spirit = moved)
        }
    }

    /** True when [vital] is below [LOW_BELOW]. */
    fun isLow(vital: TitanVital): Boolean = this[vital] < LOW_BELOW

    companion object {

        /** The most any vital can be. */
        const val MAX = 100

        /** A vital under this is low. */
        const val LOW_BELOW = 25

        /** What a newly hatched titan starts on: fed, rested, and curious. */
        val START = TitanVitals(fuel = 80, energy = MAX, spirit = 60)

        /** Vitals from values nobody has checked, each pulled into `0..`[MAX] rather than refused. */
        fun clamped(
            fuel: Int,
            energy: Int,
            spirit: Int,
        ): TitanVitals =
            TitanVitals(
                fuel = fuel.coerceIn(0, MAX),
                energy = energy.coerceIn(0, MAX),
                spirit = spirit.coerceIn(0, MAX),
            )
    }
}
