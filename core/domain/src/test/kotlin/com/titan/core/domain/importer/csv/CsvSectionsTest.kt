package com.titan.core.domain.importer.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A file of several tables under section markers, read back into its tables — the shape an app
 * that exports its whole database as one file writes.
 */
class CsvSectionsTest {

    /** A header is two or more lowercase names, which no row of these samples is. */
    private fun isHeader(record: List<String>) = record.size > 1 && record.all { it.matches(Regex("[a-z_]+")) }

    private fun read(text: String): CsvSections? =
        CsvSections.read(CsvReader.open(text.byteInputStream(Charsets.UTF_8)), ::isHeader)

    private fun rows(table: CsvTable): List<Map<String, String?>> =
        table.rows.map { row -> table.header.columns.associateWith { table.header[row, it] } }

    @Test
    fun `markers open and close sections, and each header starts a table of the rows after it`() {
        val sections = checkNotNull(
            read(
                "\n### ORDERS ##########\n\nid,total\n1,10\n2,20\n\n#####################\n" +
                    "### PEOPLE ###\nid,name\n1,Ann\n\nkind,id,name\nvip,2,Bo\n###\n",
            ),
        )

        assertEquals(listOf("ORDERS", "PEOPLE"), sections.sections.map(CsvSection::name))
        val (orders) = sections.tables("orders")
        assertEquals(listOf(mapOf("id" to "1", "total" to "10"), mapOf("id" to "2", "total" to "20")), rows(orders))
        val people = sections.tables(" People ")
        assertEquals(listOf(listOf("id", "name"), listOf("kind", "id", "name")), people.map { it.header.columns })
        assertEquals(listOf(mapOf("kind" to "vip", "id" to "2", "name" to "Bo")), rows(people[1]))
        assertTrue(sections.tables("nothing").isEmpty())
    }

    @Test
    fun `a marker inside quotes is text, and a header repeated mid-table starts another`() {
        val sections = checkNotNull(
            read("### NOTES ###\nid,note\n1,\"line one\n### not a marker ###\"\nid,note\n2,b\n"),
        )

        val tables = sections.tables("NOTES")
        assertEquals(2, tables.size)
        assertEquals("line one\n### not a marker ###", tables[0].header[tables[0].rows.single(), "note"])
        assertEquals("2", tables[1].header[tables[1].rows.single(), "id"])
    }

    @Test
    fun `rows with no header to be read by, or outside every section, are left out`() {
        val sections = checkNotNull(read("### A ###\n1,stray\nid,v\n1,x\n###\n2,between\n### B ###\n3,y\n"))

        assertEquals(1, sections.tables("A").single().rows.size)
        // B holds a row and no header, so no table.
        assertEquals(listOf("A", "B"), sections.sections.map(CsvSection::name))
        assertTrue(sections.tables("B").isEmpty())
    }

    @Test
    fun `two sections of one name are both kept, and the file may end inside one`() {
        val sections = checkNotNull(read("### A ###\nid,v\n1,x\n### A ###\nid,v\n2,y"))

        assertEquals(2, sections.sections.size)
        assertEquals(2, sections.tables("A").size)
    }

    @Test
    fun `a file that does not open with a marker is not a sectioned file`() {
        assertNull(read(""))
        assertNull(read("id,total\n1,10\n### ORDERS ###\n"))
        assertNull(read("####\nid,total\n"))
        assertNull(read("\"### ORDERS ###\n"))
        assertNull(read("### A ###,b\nid,v\n"))
    }

    @Test
    fun `a marker is a lone field of hashes, with or without a name between them`() {
        assertEquals("EXERCISE LOGS", CsvSections.marker(listOf("### EXERCISE LOGS #####")))
        assertEquals("ORDERS", CsvSections.marker(listOf("  ###ORDERS  ")))
        assertEquals("", CsvSections.marker(listOf("##########")))
        assertNull(CsvSections.marker(listOf("## TOO FEW ##")))
        assertNull(CsvSections.marker(listOf("### A # B ###")))
        assertNull(CsvSections.marker(listOf("### A ###", "")))
    }

    @Test
    fun `CSV that breaks after the first marker is the reader's error, naming its line`() {
        val failure = assertThrows(MalformedCsvException::class.java) { read("### A ###\nid,v\n1,\"open\n") }

        assertEquals(3L, failure.line)
    }
}
