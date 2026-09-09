package com.opengym.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateDraft
import com.opengym.core.domain.model.TemplateDraftExercise
import com.opengym.core.domain.model.TemplateDraftFormat
import com.opengym.core.domain.model.TemplateDraftSet
import com.opengym.core.domain.model.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [TemplateDraftRepositoryImpl] against a real file in a Robolectric app's private storage.
 *
 * Robolectric rather than a temporary folder for `InProgressSessionRepositoryImplTest`'s reason:
 * the class takes the application context on purpose, where the draft lives is part of what is
 * being asserted, and a hand-supplied directory would test a path production never uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TemplateDraftRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var repository: TemplateDraftRepositoryImpl

    private val document: File get() = File(context.filesDir, "template-draft.json")
    private val scratch: File get() = File(context.filesDir, "template-draft.json.tmp")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The real IO dispatcher rather than a TestDispatcher: what is under test is a file on
        // disk, and `concurrent saves` below needs writers that genuinely overlap.
        repository = TemplateDraftRepositoryImpl(context, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        document.delete()
        scratch.delete()
    }

    @Test
    fun `load returns null when nothing has ever been saved`() =
        runBlocking {
            assertNull(repository.load())
        }

    @Test
    fun `a saved draft comes back whole`() =
        runBlocking {
            val draft = draft()

            repository.save(draft)

            assertEquals(draft, repository.load())
        }

    @Test
    fun `save writes the document the domain defines`() =
        runBlocking {
            val draft = draft()

            repository.save(draft)

            // The format is the domain's; all this owes is storing it verbatim.
            assertEquals(draft, TemplateDraftFormat.decode(document.readText()))
        }

    @Test
    fun `save overwrites the previous draft rather than appending to it`() =
        runBlocking {
            repository.save(draft(name = "Push day"))
            repository.save(draft(name = "Pull day"))

            assertEquals("Pull day", repository.load()?.name)
        }

    @Test
    fun `save leaves no scratch file behind`() =
        runBlocking {
            repository.save(draft())

            assertFalse("the scratch file should have been renamed away", scratch.exists())
        }

    @Test
    fun `concurrent saves leave one whole draft, not a mixture`() =
        runBlocking {
            val names = List(WRITERS) { "Draft $it" }

            names.map { name -> async(Dispatchers.IO) { repository.save(draft(name = name)) } }.awaitAll()

            val stored = repository.load()
            assertNotNull("a half-written document would not decode at all", stored)
            assertTrue("expected one of the drafts whole, got ${stored?.name}", stored?.name in names)
        }

    @Test
    fun `clear discards the draft`() =
        runBlocking {
            repository.save(draft())

            repository.clear()

            assertNull(repository.load())
        }

    @Test
    fun `clearing when there was never a draft is not an error`() =
        runBlocking {
            repository.clear()

            assertNull(repository.load())
        }

    @Test
    fun `clear removes a scratch file a killed save left behind`() =
        runBlocking {
            scratch.writeText("half a document")

            repository.clear()

            assertFalse(scratch.exists())
        }

    @Test
    fun `a truncated document reads as no draft`() =
        runBlocking {
            repository.save(draft())
            val whole = document.readText()
            document.writeText(whole.take(whole.length / 2))

            assertNull(repository.load())
        }

    @Test
    fun `a draft can be saved again after being cleared`() =
        runBlocking {
            repository.save(draft())
            repository.clear()

            repository.save(draft(name = "Leg day"))

            assertEquals("Leg day", repository.load()?.name)
        }

    /** A draft with something in every field, so nothing can be dropped unnoticed. */
    private fun draft(name: String = "Push day") =
        TemplateDraft(
            templateId = 7,
            name = name,
            category = TemplateCategory.STRENGTH,
            icon = "seed/images/Barbell_Bench_Press.webp",
            unitSystem = UnitSystem.METRIC,
            exercises = listOf(
                TemplateDraftExercise(
                    key = 100,
                    exerciseId = 10,
                    sets = listOf(
                        TemplateDraftSet(key = 1, setType = SetType.WARMUP, weight = "20", reps = "10"),
                        TemplateDraftSet(key = 2, weight = "82.5", reps = "5"),
                    ),
                    supersetGroup = 0,
                    restDuration = 120,
                    notes = "Pause on the chest.",
                ),
                TemplateDraftExercise(key = -1, exerciseId = 11),
            ),
        )

    private companion object {
        /** Enough overlapping writers that an unserialised rename would be seen. */
        const val WRITERS = 20
    }
}
