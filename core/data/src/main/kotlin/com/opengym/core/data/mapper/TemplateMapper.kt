package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.WorkoutTemplateEntity
import com.opengym.core.domain.model.WorkoutTemplate
import javax.inject.Inject

/** [WorkoutTemplateEntity] to [WorkoutTemplate] and back. */
class TemplateMapper @Inject constructor() {

    fun toDomain(entity: WorkoutTemplateEntity): WorkoutTemplate = WorkoutTemplate(
        id = entity.id,
        name = entity.name,
        icon = entity.icon,
    )

    fun toEntity(template: WorkoutTemplate): WorkoutTemplateEntity = WorkoutTemplateEntity(
        id = template.id,
        name = template.name,
        icon = template.icon,
    )
}
