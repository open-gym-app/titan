package com.titan.core.domain.model

/**
 * Which units the app *displays* weights and distances in.
 *
 * A display concern. Canonical storage stays milligrams and centimetres whatever this says —
 * see [WorkoutSet], whose contract is the reason this enum can be flipped at any time without
 * touching a single stored row. The conversion belongs to the formatters in `:core:ui` (#M4-17).
 * The one place it reaches what is stored is a figure typed or imported with no unit of its own
 * — a template draft's weight, a bare number in another app's export — which is read in the
 * unit the user displays, as the best evidence of what was meant; see
 * [com.titan.core.domain.importer.ImportContext.unitSystem].
 */
enum class UnitSystem {

    /** Kilograms and kilometres. */
    METRIC,

    /** Pounds and miles. */
    IMPERIAL,
}
