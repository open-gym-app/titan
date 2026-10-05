package com.titan.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.R
import com.titan.core.data.db.DefaultTemplates
import com.titan.core.data.db.TitanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The names [DefaultTemplateCatalogueImpl] recognises a default by. What it resolves the
 * exercises to is `DatabaseSeedTest`'s, against the seeded catalogue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DefaultTemplateCatalogueImplTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TitanDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val catalogue = DefaultTemplateCatalogueImpl(context, db.exerciseDao(), Dispatchers.Unconfined)

    @After
    fun tearDown() = db.close()

    @Test
    fun `the shipped languages are the ones this module translates`() {
        // Gradle runs unit tests from the module directory.
        val translated = File("src/main/res").list().orEmpty()
            .filter { it.startsWith("values-") }
            .map { Locale.forLanguageTag(it.removePrefix("values-").replace("-r", "-")).toLanguageTag() }

        assertEquals(
            (translated + "en").sorted(),
            DefaultTemplateCatalogueImpl.SHIPPED_LANGUAGES.sorted(),
        )
    }

    @Test
    fun `a default is known by its name in every shipped language`() =
        runBlocking {
            val index = DefaultTemplates.all.indexOfFirst { it.nameRes == R.string.default_template_legs }
            val legs = catalogue.getNames()[index]

            assertEquals("Legs", legs.current)
            assertEquals(setOf("Legs", "Jambes", "Piernas", "Pernas"), legs.everyLanguage)
        }

    @Test
    fun `every default's names include the current one`() =
        runBlocking {
            val names = catalogue.getNames()

            assertEquals(DefaultTemplates.all.size, names.size)
            assertTrue(names.all { it.current in it.everyLanguage })
        }

    @Test
    fun `no name belongs to two defaults`() =
        runBlocking {
            // A translation that collided with another default's name would make deleting one of the
            // two invisible to the restore, which would see the other and call it present.
            val owners = catalogue.getNames()
                .flatMapIndexed { index, name -> name.everyLanguage.map { it.trim().lowercase(Locale.ROOT) to index } }
                .distinct()
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }

            assertEquals(emptyMap<String, List<Int>>(), owners)
        }
}
