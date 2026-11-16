package com.titan.core.domain.validation

import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym

/**
 * Shape checks on a gym, run before it is persisted — the counterpart of
 * [BodyMeasurementValidator] for the gym directory, and a check a caller runs rather than a
 * gate the write path forces it through.
 *
 * A gym with no coordinates is valid: the pin is optional, see [Gym].
 *
 * Reports *all* violations in one [ValidationResult.Invalid], so a form with a missing name and
 * a mistyped latitude shows both.
 */
object GymValidator {

    fun validate(gym: Gym): ValidationResult {
        val violations = buildList {
            if (gym.name.isBlank()) add("name must not be blank")
            if (gym.address.isBlank()) add("address must not be blank")
            gym.coordinates?.let { addAll(coordinateViolations(it)) }
            gym.equipmentTags.forEach { tag ->
                if (tag.isBlank()) {
                    add("an equipment tag must not be blank")
                } else if (tag.any { it == '\n' || it == '\r' }) {
                    add("an equipment tag must be a single line, was \"${tag.replace('\n', ' ').replace('\r', ' ')}\"")
                }
            }
        }

        return if (violations.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(violations.joinToString("; "))
        }
    }

    /** NaN and the infinities fail the range test on their own, so there is no separate check. */
    private fun coordinateViolations(coordinates: Coordinates): List<String> =
        buildList {
            if (coordinates.latitude !in -Coordinates.LATITUDE_MAX..Coordinates.LATITUDE_MAX) {
                add("latitude must be within -90..90 degrees, was ${coordinates.latitude}")
            }
            if (coordinates.longitude !in -Coordinates.LONGITUDE_MAX..Coordinates.LONGITUDE_MAX) {
                add("longitude must be within -180..180 degrees, was ${coordinates.longitude}")
            }
        }
}
