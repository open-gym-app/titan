package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.domain.model.Tag
import javax.inject.Inject

/** [TagEntity] to [Tag] and back. Unscoped and stateless, as [ExerciseMapper]. */
class TagMapper @Inject constructor() {

    fun toDomain(entity: TagEntity): Tag = Tag(
        id = entity.id,
        name = entity.name,
        color = entity.color,
        isSystem = entity.isSystem,
    )

    fun toEntity(tag: Tag): TagEntity = TagEntity(
        id = tag.id,
        name = tag.name,
        color = tag.color,
        isSystem = tag.isSystem,
    )
}
