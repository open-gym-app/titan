package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TemplateDraftFormat] round-tripping the half-built templates the editor writes.
 *
 * The point of the suite is the same as `ActiveSessionFormat`'s: what is asserted is that a draft
 * can be *written down* and read back as itself, and that a document this build cannot read is
 * refused rather than half-interpreted. A field added to the model without a serializer, or one
 * that quietly loses its value through the encoder, fails here rather than on a phone.
 */
class TemplateDraftFormatTest {

    @Test
    fun `a draft comes back as itself`() {
        val draft = fullDraft()

        assertEquals(draft, TemplateDraftFormat.decode(TemplateDraftFormat.encode(draft)))
    }

    @Test
    fun `an empty plan is a draft like any other`() {
        val draft = TemplateDraft(templateId = 0, name = "Leg day")

        assertEquals(draft, TemplateDraftFormat.decode(TemplateDraftFormat.encode(draft)))
    }

    @Test
    fun `what was typed is kept as typed, not as a number`() {
        // The whole reason the draft holds text: a half-typed figure is not a figure, and a
        // format that parsed would either round it or refuse it.
        val draft = TemplateDraft(
            templateId = 1,
            exercises = listOf(
                TemplateDraftExercise(
                    key = -1,
                    exerciseId = 10,
                    sets = listOf(TemplateDraftSet(key = 1, weight = "82.", reps = "-")),
                ),
            ),
        )

        val restored = TemplateDraftFormat.decode(TemplateDraftFormat.encode(draft))

        assertEquals("82.", restored?.exercises?.single()?.sets?.single()?.weight)
        assertEquals("-", restored?.exercises?.single()?.sets?.single()?.reps)
    }

    @Test
    fun `the document carries its version outside the draft`() {
        val encoded = TemplateDraftFormat.encode(fullDraft())

        assertEquals(true, encoded.startsWith("""{"version":${TemplateDraftFormat.VERSION},"""))
    }

    @Test
    fun `a document from a version this build does not read is refused`() {
        val encoded = TemplateDraftFormat.encode(fullDraft())
        val newer = encoded.replaceFirst(
            """"version":${TemplateDraftFormat.VERSION}""",
            """"version":${TemplateDraftFormat.VERSION + 1}""",
        )

        assertNull(TemplateDraftFormat.decode(newer))
    }

    @Test
    fun `a truncated document reads as no draft`() {
        val encoded = TemplateDraftFormat.encode(fullDraft())

        assertNull(TemplateDraftFormat.decode(encoded.take(encoded.length / 2)))
    }

    @Test
    fun `an empty document reads as no draft`() {
        assertNull(TemplateDraftFormat.decode(""))
    }

    /** A draft with something in every field, so nothing can be dropped unnoticed. */
    private fun fullDraft() =
        TemplateDraft(
            templateId = 7,
            name = "Push day",
            category = TemplateCategory.STRENGTH,
            icon = "seed/images/Barbell_Bench_Press.webp",
            unitSystem = UnitSystem.IMPERIAL,
            exercises = listOf(
                TemplateDraftExercise(
                    key = 100,
                    exerciseId = 10,
                    sets = listOf(
                        TemplateDraftSet(key = 1, setType = SetType.WARMUP, weight = "45", reps = "10"),
                        TemplateDraftSet(key = 2, weight = "185", reps = "5"),
                    ),
                    supersetGroup = 0,
                    notes = "Pause on the chest.",
                ),
                TemplateDraftExercise(key = -1, exerciseId = 11),
            ),
        )
}
