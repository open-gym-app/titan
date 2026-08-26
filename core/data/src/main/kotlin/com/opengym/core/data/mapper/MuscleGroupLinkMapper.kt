package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.projection.ExerciseMuscleGroupLinkRow
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import javax.inject.Inject

/**
 * [MuscleGroupEntity] and the [ExerciseMuscleGroupLinkRow] projection to their
 * domain shapes.
 *
 * Both live here because the link is a muscle group plus the join row's
 * `is_primary` flag: splitting them across two mappers would mean the one that
 * builds a link either duplicates the group mapping or injects the other for a
 * single field copy.
 */
class MuscleGroupLinkMapper @Inject constructor() {

    fun toDomain(entity: MuscleGroupEntity): MuscleGroup =
        MuscleGroup(
            id = entity.id,
            name = entity.name,
            icon = entity.icon,
        )

    fun toDomain(row: ExerciseMuscleGroupLinkRow): ExerciseMuscleGroupLink =
        ExerciseMuscleGroupLink(
            muscleGroup = MuscleGroup(
                id = row.muscleGroupId,
                name = row.muscleGroupName,
                icon = row.muscleGroupIcon,
            ),
            isPrimary = row.isPrimary,
        )
}
