package com.titan.core.data.repository

import android.content.Context
import android.content.res.Configuration
import com.titan.core.data.db.DefaultTemplates
import com.titan.core.data.db.dao.ExerciseDao
import com.titan.core.domain.model.DefaultTemplate
import com.titan.core.domain.model.DefaultTemplateName
import com.titan.core.domain.repository.DefaultTemplateCatalogue
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DefaultTemplates], named from this app's string resources and resolved against this
 * database's catalogue — the same definitions the seed wrote on first launch.
 *
 * The name in the current language and the exercise ids are read on every call rather than
 * once and kept. Restoring has to name the defaults in the language the phone is in *now*,
 * and the catalogue's ids are only stable for the life of a database, which a wipe or a
 * restore ends; one indexed read per call is cheaper than knowing when either has happened.
 * The names in every shipped language are the exception, read once: they are fixed by the
 * resources in the APK and cannot change while the process lives.
 */
@Singleton
class DefaultTemplateCatalogueImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exerciseDao: ExerciseDao,
) : DefaultTemplateCatalogue {

    // One configuration context per language, so a lookup per default per language is all the
    // work, and none of it is repeated after the first read.
    private val shippedNames: List<Set<String>> by lazy {
        val localised = SHIPPED_LANGUAGES.map { tag ->
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
            )
        }
        DefaultTemplates.all.map { spec -> localised.mapTo(mutableSetOf()) { it.getString(spec.nameRes) } }
    }

    override fun getNames(): List<DefaultTemplateName> =
        DefaultTemplates.all.zip(shippedNames) { spec, names ->
            val current = context.getString(spec.nameRes)
            DefaultTemplateName(current = current, everyLanguage = names + current)
        }

    override suspend fun getAll(): List<DefaultTemplate> {
        val resolve = DefaultTemplates.resolverOver(exerciseDao.getSeededByName(DefaultTemplates.exerciseNames))

        return DefaultTemplates.all.zip(getNames()).mapNotNull { (spec, name) ->
            val drafts = spec.toDrafts(resolve) ?: return@mapNotNull null
            DefaultTemplate(name = name, category = spec.category, exercises = drafts)
        }
    }

    internal companion object {

        /**
         * The languages `:core:data` translates the defaults into, as BCP 47 tags — the
         * `values-*` directories beside `values/`, which is English. A language missing here
         * is one whose seeded defaults look deleted after a switch away from it;
         * `DefaultTemplateCatalogueImplTest` holds the list to the directories.
         */
        val SHIPPED_LANGUAGES = listOf("en", "fr", "es", "pt-BR")
    }
}
