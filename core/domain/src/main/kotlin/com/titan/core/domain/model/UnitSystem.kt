package com.titan.core.domain.model

/**
 * Which units the app *displays* weights and distances in.
 *
 * A display concern and nothing more. Canonical storage stays milligrams and
 * centimetres whatever this says — see [WorkoutSet], whose contract is the reason this
 * enum can be flipped at any time without touching a single stored row. The conversion
 * belongs to the formatters in `:core:ui` (#M4-17); nothing in the domain or the data
 * layer reads this to decide what to persist.
 */
enum class UnitSystem {

    /** Kilograms and kilometres. */
    METRIC,

    /** Pounds and miles. */
    IMPERIAL,
}
