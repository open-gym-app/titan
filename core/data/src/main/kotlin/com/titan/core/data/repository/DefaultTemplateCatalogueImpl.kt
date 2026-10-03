package com.titan.core.data.repository

import android.content.Context
import com.titan.core.data.db.DefaultTemplates
import com.titan.core.data.db.dao.ExerciseDao
import com.titan.core.domain.model.DefaultTemplate
import com.titan.core.domain.repository.DefaultTemplateCatalogue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [DefaultTemplates], named from this app's string resources and resolved against this
 * database's catalogue — the same definitions the seed wrote on first launch.
 *
 * Resolved on every call rather than once and kept. Restoring has to name the defaults in the
 * language the phone is in *now*, and the catalogue's ids are only stable for the life of a
 * database, which a wipe or a restore ends; one indexed read per call is cheaper than knowing
 * when either has happened.
 */
class DefaultTemplateCatalogueImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exerciseDao: ExerciseDao,
) : DefaultTemplateCatalogue {

    override suspend fun getAll(): List<DefaultTemplate> {
        val keys = DefaultTemplates.all.flatMap { spec -> spec.exercises.map { it.key } }.toSet()
        val ids = exerciseDao.getSeededByName(keys.map { it.name })
            .associate { (it.name to it.equipmentType) to it.id }

        return DefaultTemplates.all.mapNotNull { spec ->
            val drafts = spec.toDrafts { key -> ids[key.name to key.equipmentType.name] }
                ?: return@mapNotNull null
            DefaultTemplate(
                name = context.getString(spec.nameRes),
                category = spec.category,
                exercises = drafts,
            )
        }
    }
}
