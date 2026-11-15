package com.titan.feature.progression.body

import androidx.annotation.StringRes
import com.titan.core.domain.model.BodyMeasurement
import com.titan.feature.progression.R

/**
 * A place the tape goes round, and the one column of [BodyMeasurement] it is stored in.
 *
 * The form, the parts card, the history row and the latest readings all treat the nine
 * circumferences alike, so they all walk this list rather than each naming nine fields. Adding
 * a site is then one entry here and two strings per locale, instead of a field forgotten in one
 * of four places, which is how a measurement gets saved and never shown.
 *
 * Declared head to foot, which is the order every one of those surfaces lists them in.
 *
 * @property isCore whether the parts card lists this site before anything has been measured
 *   there — see `MeasurementsCard`.
 */
enum class BodySite(
    @StringRes val label: Int,
    @StringRes val field: Int,
    val isCore: Boolean,
    private val read: (BodyMeasurement) -> Int?,
    private val write: BodyMeasurement.(Int?) -> BodyMeasurement,
) {
    NECK(R.string.body_part_neck, R.string.body_field_neck, false, BodyMeasurement::neck, { copy(neck = it) }),
    SHOULDERS(
        R.string.body_part_shoulders,
        R.string.body_field_shoulders,
        false,
        BodyMeasurement::shoulders,
        { copy(shoulders = it) },
    ),
    CHEST(R.string.body_part_chest, R.string.body_field_chest, true, BodyMeasurement::chest, { copy(chest = it) }),
    WAIST(R.string.body_part_waist, R.string.body_field_waist, true, BodyMeasurement::waist, { copy(waist = it) }),
    HIPS(R.string.body_part_hips, R.string.body_field_hips, false, BodyMeasurement::hips, { copy(hips = it) }),
    ARM(R.string.body_part_arm, R.string.body_field_arm, true, BodyMeasurement::arm, { copy(arm = it) }),
    FOREARM(
        R.string.body_part_forearm,
        R.string.body_field_forearm,
        false,
        BodyMeasurement::forearm,
        { copy(forearm = it) },
    ),
    THIGH(R.string.body_part_thigh, R.string.body_field_thigh, true, BodyMeasurement::thigh, { copy(thigh = it) }),
    CALF(R.string.body_part_calf, R.string.body_field_calf, false, BodyMeasurement::calf, { copy(calf = it) }),
    ;

    /** What [measurement] recorded here, in millimetres, or null if it did not. */
    fun of(measurement: BodyMeasurement): Int? = read(measurement)

    /** [measurement] with this site set to [millimetres]; null clears it. */
    fun on(
        measurement: BodyMeasurement,
        millimetres: Int?,
    ): BodyMeasurement = measurement.write(millimetres)
}
