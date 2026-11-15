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
            neck = entity.neck,
            shoulders = entity.shoulders,
            chest = entity.chest,
            waist = entity.waist,
            hips = entity.hips,
            arm = entity.arm,
            forearm = entity.forearm,
            thigh = entity.thigh,
            calf = entity.calf,
            bodyFat = entity.bodyFat,
            notes = entity.notes,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    fun toEntity(measurement: BodyMeasurement): BodyMeasurementEntity =
        BodyMeasurementEntity(
            id = measurement.id,
            weight = measurement.weight,
            neck = measurement.neck,
            shoulders = measurement.shoulders,
            chest = measurement.chest,
            waist = measurement.waist,
            hips = measurement.hips,
            arm = measurement.arm,
            forearm = measurement.forearm,
            thigh = measurement.thigh,
            calf = measurement.calf,
            bodyFat = measurement.bodyFat,
            notes = measurement.notes,
            createdAt = measurement.createdAt,
            updatedAt = measurement.updatedAt,
        )
}
