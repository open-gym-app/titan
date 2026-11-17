package com.titan.core.domain.importer.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * The CSV every source parser reads through. Each case is a shape a real exporter writes, or one a
 * hostile file could, so a parser built on this never has to handle either itself.
 */
class CsvReaderTest {

    private fun read(
        text: String,
        delimiter: Char = ',',
    ): List<List<String>> = CsvReader(StringReader(text), delimiter).use { it.records().toList() }

    private fun open(text: String): CsvReader = CsvReader.open(text.byteInputStream(Charsets.UTF_8))

    @Test
    fun `plain fields split on the delimiter, one record per line`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), read("a,b,c\n1,2,3\n"))
    }

    @Test
    fun `the last record needs no line break`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), read("a,b\n1,2"))
    }

    @Test
    fun `CRLF, LF and a lone CR all end a record`() {
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d")), read("a\r\nb\nc\rd"))
    }

    @Test
    fun `empty fields are kept, including a trailing one`() {
        assertEquals(listOf(listOf("", "x", "")), read(",x,"))
    }

    @Test
    fun `a quoted field may hold the delimiter, a doubled quote and line breaks`() {
        val records = read("note,set\n\"heavy, slow \"\"grinder\"\"\nfelt good\",1\n")

        assertEquals(listOf("heavy, slow \"grinder\"\nfelt good", "1"), records[1])
    }

    @Test
    fun `an empty quoted field is an empty string`() {
        assertEquals(listOf(listOf("", "b")), read("\"\",b"))
    }

    @Test
    fun `a quote inside an unquoted field is an ordinary character`() {
        // Height written in feet and inches — invalid by RFC 4180, written by exporters anyway.
        assertEquals(listOf(listOf("5'10\"", "x")), read("5'10\",x"))
    }

    @Test
    fun `text after a closing quote joins the field rather than failing the file`() {
        assertEquals(listOf(listOf("ab cd", "x")), read("\"ab\" cd,x"))
    }

    @Test
    fun `blank lines are skipped, not returned as a record of one empty field`() {
        assertEquals(listOf(listOf("a"), listOf("b")), read("a\n\n\r\n\nb\n"))
    }

    @Test
    fun `a quoted field left open fails the file, naming the line the record began on`() {
        val failure = assertThrows(MalformedCsvException::class.java) {
            read("a,b\n1,2\n3,\"never closed\n4,5\n")
        }

        assertEquals(3L, failure.line)
    }

    @Test
    fun `the record line counts line breaks inside earlier quoted fields`() {
        CsvReader(StringReader("a\n\"one\ntwo\nthree\"\nb\n")).use { reader ->
            reader.readRecord()
            reader.readRecord()
            reader.readRecord()

            assertEquals(5L, reader.recordLine)
        }
    }

    @Test
    fun `a record with too many fields is refused rather than built`() {
        val failure = assertThrows(MalformedCsvException::class.java) {
            CsvReader(StringReader("a,b,c,d\n"), maxFields = 3).use { it.readRecord() }
        }

        assertEquals(1L, failure.line)
    }

    @Test
    fun `a record longer than the limit is refused rather than built`() {
        assertThrows(MalformedCsvException::class.java) {
            CsvReader(StringReader("\"" + "x".repeat(100) + "\""), maxRecordChars = 50).use { it.readRecord() }
        }
    }

    @Test
    fun `open skips a byte order mark`() {
        open("﻿Date,Reps\n2024,5\n").use { reader ->
            assertEquals(listOf("Date", "Reps"), reader.readRecord())
        }
    }

    @Test
    fun `open sniffs the delimiter the header uses most`() {
        open("Date;Workout Name;Weight\n2024-01-01;Legs;100,5\n").use { reader ->
            assertEquals(';', reader.delimiter)
            reader.readRecord()
            // The decimal comma stays in the field: it is not this file's delimiter.
            assertEquals(listOf("2024-01-01", "Legs", "100,5"), reader.readRecord())
        }
    }

    @Test
    fun `sniffing ignores delimiters inside quotes`() {
        assertEquals(';', CsvReader.sniffDelimiter("\"a,b,c,d\";e;f"))
    }

    @Test
    fun `sniffing a line with no candidate falls back to a comma`() {
        assertEquals(',', CsvReader.sniffDelimiter("Date"))
    }

    @Test
    fun `an explicit delimiter is used as given`() {
        assertEquals(listOf(listOf("a", "b")), read("a\tb", delimiter = '\t'))
    }

    @Test
    fun `an empty input has no records`() {
        assertNull(CsvReader(StringReader("")).readRecord())
    }

    @Test
    fun `readHeader is the first record as a header, and null for no records or no CSV at all`() {
        val reader = open("Date,Reps\n2024-01-01,5\n")

        assertEquals("5", reader.readHeader()?.get(reader.readRecord()!!, "reps"))
        assertNull(open("").readHeader())
        assertNull(open("\"never closed\nDate,Reps\n").readHeader())
    }

    // ------------------------------------------------------------------ header

    @Test
    fun `the header reads a column by name, trimmed and regardless of case`() {
        val header = CsvHeader(listOf(" Date ", "Exercise Name", "Reps"))
        val record = listOf("2024-01-01", " Squat ", "5")

        assertEquals("Squat", header[record, "exercise name"])
        assertEquals(1, header.indexOf("EXERCISE NAME"))
    }

    @Test
    fun `an absent column, a short record and a blank cell all read as null`() {
        val header = CsvHeader(listOf("Date", "Reps", "Notes"))

        assertNull(header[listOf("2024", "5", "x"), "Weight"])
        assertNull(header[listOf("2024"), "Reps"])
        assertNull(header[listOf("2024", "5", "   "), "Notes"])
    }

    @Test
    fun `a duplicated column name resolves to its first column`() {
        val header = CsvHeader(listOf("Notes", "Reps", "Notes"))

        assertEquals("first", header[listOf("first", "5", "second"), "Notes"])
    }

    @Test
    fun `containsAll is how a parser recognises its format`() {
        val header = CsvHeader(listOf("Date", "Workout Name", "Exercise Name"))

        assertTrue(header.containsAll(listOf("date", "exercise name")))
        assertFalse(header.containsAll(listOf("date", "set order")))
    }

    @Test
    fun `a qualified column is found by the name before its parenthesis, with what the parenthesis says`() {
        val header = CsvHeader(listOf("Weight Unit", " Weight (lbs) ", "Distance", "Reps"))

        assertEquals(QualifiedColumn("Weight (lbs)", "lbs"), header.qualified("weight"))
        assertEquals(QualifiedColumn("Distance", qualifier = null), header.qualified("Distance"))
        assertEquals("120", header[listOf("lbs", "120", "", "5"), header.qualified("Weight")!!.name])
    }

    @Test
    fun `a qualified lookup takes the first column that fits and finds nothing that only starts the same`() {
        val header = CsvHeader(listOf("Duration (sec)", "Duration", "Workout Duration"))

        assertEquals(QualifiedColumn("Duration (sec)", "sec"), header.qualified("Duration"))
        assertNull(header.qualified("Workout"))
        assertNull(CsvHeader(listOf("Weight Unit")).qualified("Weight"))
    }

    @Test
    fun `a qualified lookup reads the last parenthesis, and a long run of spaces does not hold it up`() {
        val spaces = " ".repeat(100_000)
        val columns = listOf("Weight${spaces}x", "e1RM  (KG)", "Weight (kg) (avg)", "Reps ($spaces lbs$spaces)")
        val header = CsvHeader(columns)

        assertEquals(QualifiedColumn("e1RM  (KG)", "KG"), header.qualified("e1rm"))
        assertEquals(QualifiedColumn("Weight (kg) (avg)", "avg"), header.qualified("Weight (kg)"))
        assertEquals("lbs", header.qualified("Reps")?.qualifier)
        assertNull(header.qualified("Weight"))
    }

    @Test
    fun `a suffixed lookup finds every snake_case column with the name before the underscore`() {
        val header = CsvHeader(listOf("weight_kg", "reps", " Weight_LBS ", "weighted", "weight_", "distance_km"))

        assertEquals(
            listOf(QualifiedColumn("weight_kg", "kg"), QualifiedColumn("Weight_LBS", "LBS")),
            header.suffixed("weight"),
        )
        assertEquals(listOf(QualifiedColumn("distance_km", "km")), header.suffixed(" Distance "))
        assertTrue(header.suffixed("reps").isEmpty())
        assertTrue(header.suffixed("we").isEmpty())
    }

    @Test
    fun `every qualified column is found, in header order, for a figure carried in two units`() {
        val header = CsvHeader(listOf("Date", "Weight (kg)", "Weight (lbs)", "Weight Unit", "Reps"))

        assertEquals(
            listOf(QualifiedColumn("Weight (kg)", "kg"), QualifiedColumn("Weight (lbs)", "lbs")),
            header.qualifiedColumns("weight"),
        )
        assertEquals(listOf(QualifiedColumn("Reps", qualifier = null)), header.qualifiedColumns("Reps"))
        assertTrue(header.qualifiedColumns("Distance").isEmpty())
    }

    @Test
    fun `a record split at an unquoted delimiter is put back together in the column named`() {
        val header = CsvHeader(listOf("Date", "Exercise", "Category", "Weight"))

        assertEquals(
            listOf("2026-10-09", "Curl, EZ Bar, Wide", "Biceps", "30.0"),
            header.rejoined(listOf("2026-10-09", "Curl", " EZ Bar", " Wide", "Biceps", "30.0"), "exercise", ','),
        )
        assertEquals(
            listOf("a;b", "c", "d", "e"),
            header.rejoined(listOf("a", "b", "c", "d", "e"), "Date", ';'),
        )
    }

    @Test
    fun `a record no longer than the header, or a column the header lacks, leaves the record alone`() {
        val header = CsvHeader(listOf("Date", "Exercise", "Category"))
        val short = listOf("2026-10-09", "Curl")
        val long = listOf("2026-10-09", "Curl", "EZ Bar", "Biceps")

        assertEquals(short, header.rejoined(short, "Exercise", ','))
        assertEquals(long, header.rejoined(long, "Comment", ','))
    }
}
