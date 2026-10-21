package com.titan.core.data.mapper

import com.titan.core.data.db.entity.WorkoutTemplateEntity
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.WorkoutTemplate
import javax.inject.Inject

/**
 * [WorkoutTemplateEntity] to [WorkoutTemplate] and back.
 *
 * There is no estimated duration to map: it is computed from the template's exercises
 * by [com.titan.core.domain.template.TemplateDuration] and no column holds it.
 */
class TemplateMapper @Inject constructor() {

    fun toDomain(entity: WorkoutTemplateEntity): WorkoutTemplate =
        WorkoutTemplate(
            id = entity.id,
            name = entity.name,
            // Null for a category this build does not know — a value from a newer version
            // or an import. The column is nullable anyway, so an unreadable category reads
            // as an uncategorised template rather than making the whole row unreadable.
            category = decodeEnum<TemplateCategory>(entity.category),
            icon = entity.icon,
        )

    fun toEntity(template: WorkoutTemplate): WorkoutTemplateEntity =
        WorkoutTemplateEntity(
            id = template.id,
            name = template.name,
            category = template.category?.name,
            icon = template.icon,
        )
}
