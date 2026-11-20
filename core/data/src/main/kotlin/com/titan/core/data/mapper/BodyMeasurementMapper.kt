package com.titan.core.data.mapper

import com.titan.core.data.db.entity.BodyMeasurementEntity
import com.titan.core.domain.model.BodyMeasurement
import javax.inject.Inject

/**
 * [BodyMeasurementEntity] to and from [BodyMeasurement].
 *
 * A straight field-for-field copy in both directions, with no enum column to decode and no
 * unit conversion: the entity and the model are both in canonical units, and turning
 * milligrams into kilograms is the UI's job. Lean mass crosses in neither direction — it is
 * derived from the two fields that do, so there is nothing to map.
 */
class BodyMeasurementMapper @Inject constructor() {

    fun toDomain(entity: BodyMeasurementEntity): BodyMeasurement =
        BodyMeasurement(
            id = entity.id,
            weight = entity.weight,
            chest = entity.chest,
            waist = entity.waist,
            arm = entity.arm,
            thigh = entity.thigh,
            bodyFat = entity.bodyFat,
            notes = entity.notes,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            fromHealthConnect = entity.fromHealthConnect,
        )

    fun toEntity(measurement: BodyMeasurement): BodyMeasurementEntity =
        BodyMeasurementEntity(
            id = measurement.id,
            weight = measurement.weight,
            chest = measurement.chest,
            waist = measurement.waist,
            arm = measurement.arm,
            thigh = measurement.thigh,
            bodyFat = measurement.bodyFat,
            notes = measurement.notes,
            createdAt = measurement.createdAt,
            updatedAt = measurement.updatedAt,
            fromHealthConnect = measurement.fromHealthConnect,
        )
}
