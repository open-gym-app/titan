package com.titan.core.domain.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceBundleParserTest {

    private fun bundle(
        vararg entries: String,
        version: Int = 1,
    ) = """{"version":$version,"templates":[${entries.joinToString(",")}]}"""

    private fun entry(
        id: String = "mp-a",
        rating: String = "4.5",
        minutes: String = "45",
    ): String {
        val head = """{"remote_id":"$id","title":" Title ","author":"Ann","description":"Desc","""
        val tags = """"tags":["Legs"," legs ","","Barbell"],"download_count":10,"""
        val tail = """"average_rating":$rating,"duration_minutes":$minutes,"created_at":123}"""
        return head + tags + tail
    }

    private fun assertRefused(text: String) {
        assertThrows(BundleFormatException::class.java) { MarketplaceBundleParser.parse(text) }
    }

    @Test
    fun `a well formed bundle becomes templates with trimmed text and cleaned tags`() {
        val templates = MarketplaceBundleParser.parse(bundle(entry(), entry(id = "mp-b")))

        assertEquals(listOf("mp-a", "mp-b"), templates.map { it.id })
        with(templates.first()) {
            assertEquals("Title", title)
            assertEquals(listOf("Legs", "Barbell"), tags)
            assertEquals(10, downloadCount)
            assertEquals(4.5, averageRating, 0.0)
            assertEquals(45, durationMinutes)
            assertEquals(123L, createdAt)
        }
    }

    @Test
    fun `optional fields default and unknown keys are ignored`() {
        val minimal = """{"remote_id":"x","title":"T","duration_minutes":30,"created_at":1,"future_field":true}"""

        val template = MarketplaceBundleParser.parse(bundle(minimal)).single()

        assertEquals("", template.author)
        assertEquals(emptyList<String>(), template.tags)
        assertEquals(0, template.downloadCount)
    }

    @Test
    fun `an empty bundle is valid`() {
        assertTrue(MarketplaceBundleParser.parse(bundle()).isEmpty())
    }

    @Test
    fun `another version is refused`() = assertRefused(bundle(entry(), version = 2))

    @Test
    fun `text that is not JSON is refused`() = assertRefused("not json")

    @Test
    fun `a missing required key is refused`() = assertRefused("""{"version":1,"templates":[{"title":"T"}]}""")

    @Test
    fun `a blank remote id is refused`() = assertRefused(bundle(entry(id = " ")))

    @Test
    fun `a blank title is refused`() =
        assertRefused(bundle("""{"remote_id":"x","title":" ","duration_minutes":30,"created_at":1}"""))

    @Test
    fun `an out of range rating is refused rather than skipped`() {
        assertRefused(bundle(entry(), entry("b", rating = "7")))
    }

    @Test
    fun `a non positive duration is refused`() = assertRefused(bundle(entry(minutes = "0")))

    @Test
    fun `a repeated remote id is refused`() = assertRefused(bundle(entry(), entry()))

    @Test
    fun `the root must be an object and templates an array of objects`() {
        assertRefused("[]")
        assertRefused("""{"version":1,"templates":{}}""")
        assertRefused("""{"version":1}""")
        assertRefused("""{"version":1,"templates":[1]}""")
    }

    @Test
    fun `a missing, null or non numeric version is refused`() {
        assertRefused("""{"templates":[]}""")
        assertRefused("""{"version":null,"templates":[]}""")
        assertRefused("""{"version":"one","templates":[]}""")
        assertRefused("""{"version":[1],"templates":[]}""")
    }

    @Test
    fun `fields of the wrong type are refused`() {
        val base = """"remote_id":"x","title":"T","duration_minutes":30,"created_at":1"""
        assertRefused(bundle("""{"remote_id":5,"title":"T","duration_minutes":30,"created_at":1}"""))
        assertRefused(bundle("""{$base,"author":7}"""))
        assertRefused(bundle("""{$base,"download_count":"many"}"""))
        assertRefused(bundle("""{$base,"average_rating":"high"}"""))
        assertRefused(bundle("""{"remote_id":"x","title":"T","duration_minutes":"long","created_at":1}"""))
        assertRefused(bundle("""{"remote_id":"x","title":"T","duration_minutes":30,"created_at":"now"}"""))
        assertRefused(bundle("""{$base,"tags":"legs"}"""))
        assertRefused(bundle("""{$base,"tags":[1]}"""))
    }

    @Test
    fun `required fields may not be absent`() {
        assertRefused(bundle("""{"remote_id":"x","title":"T","created_at":1}"""))
        assertRefused(bundle("""{"remote_id":"x","title":"T","duration_minutes":30}"""))
        assertRefused(bundle("""{"remote_id":"x","duration_minutes":30,"created_at":1}"""))
        assertRefused(bundle("""{"remote_id":"x","title":{},"duration_minutes":30,"created_at":1}"""))
    }
}
