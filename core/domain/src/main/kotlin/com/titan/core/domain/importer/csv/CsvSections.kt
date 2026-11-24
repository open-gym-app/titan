package com.titan.core.domain.importer.csv

import java.util.Locale

/**
 * A file of several CSV tables, each filed under a marker line that names its section.
 *
 * Some apps export their whole database as one file rather than one file per table: a line such
 * as `### EXERCISE LOGS ######` opens a section, a line of `#` alone closes it, and inside it
 * every table brings its own header row, because a section may hold rows of several shapes and
 * an app adds columns to one shape without touching the others. [CsvReader] reads such a file as
 * well as any other; this is what turns its records back into the tables they were written as,
 * so a parser looks a table up by section and by the columns its header holds.
 *
 * ### What it reads
 *
 * - **A marker** is a record of one field that starts with `###`. What follows the hashes, with
 *   any trailing hashes and space trimmed, is the section's name; nothing at all is the end of
 *   the current section. A `###` line inside a quoted field that spans lines is part of that
 *   field, never a marker, which is why this reads records rather than lines; a record that is
 *   nothing but one quoted `###` field is read as a marker, since the quotes are gone by then.
 * - **A header** is whatever record the caller's `isHeader` says is one. Blank lines, which
 *   exporters put between tables, never reach this: [CsvReader] skips them. So a header has to
 *   be told apart from a row by its content, and the caller knows what its format's headers look
 *   like. A header that repeats in the middle of a table starts a new table with the same columns.
 * - **A row** belongs to the last header of its section. A row before any header of its section,
 *   or outside every section, has no columns to be read by and is left out.
 *
 * The whole file is held, because a format filed this way is a database whose tables refer to
 * each other, and a row cannot be read until the rows it refers to have been. It is bounded by the
 * engine's byte cap on the file and by [CsvReader]'s limits on any one record, and each row is
 * held as a [PackedRecord] rather than as the list of strings it was read as.
 */
class CsvSections private constructor(
    /** Every section, in file order. Two sections of the same name are both kept. */
    val sections: List<CsvSection>,
) {
    /** Every table of every section called [name], matched trimmed and without regard to case. */
    fun tables(name: String): List<CsvTable> {
        val key = name.key()
        return sections.filter { it.name.key() == key }.flatMap(CsvSection::tables)
    }

    companion object {

        private val MARKER = Regex("#{3,}\\s*([^#]*?)\\s*#*")

        /**
         * The name a marker [record] opens a section under, `""` for a marker that closes one, or
         * null when [record] is not a marker at all.
         */
        fun marker(record: List<String>): String? =
            record.singleOrNull()?.trim()?.let(MARKER::matchEntire)?.groupValues?.get(1)

        /**
         * The sections [reader] holds from its next record on, or null when that record is not a
         * marker, or is not CSV at all: a plain table, or a file that is not a table, rather than
         * a sectioned file that breaks.
         *
         * @param isHeader whether a record inside a section is a header row rather than a row of
         *   data; see the class doc.
         * @throws MalformedCsvException when a record past the first breaks the CSV.
         */
        fun read(
            reader: CsvReader,
            isHeader: (List<String>) -> Boolean,
        ): CsvSections? {
            val first = try {
                reader.readRecord()
            } catch (_: MalformedCsvException) {
                null
            }
            val opening = first?.let(::marker)?.takeIf(String::isNotEmpty) ?: return null

            val sections = mutableListOf<CsvSection>()
            var section: SectionDraft? = SectionDraft(opening)
            reader.records().forEach { record ->
                val name = marker(record)
                when {
                    name != null -> {
                        section?.let { sections += it.toSection() }
                        section = name.takeIf(String::isNotEmpty)?.let(::SectionDraft)
                    }

                    isHeader(record) -> section?.header(record)

                    else -> section?.row(record)
                }
            }
            section?.let { sections += it.toSection() }
            return CsvSections(sections)
        }

        private fun String.key(): String = trim().lowercase(Locale.ROOT)
    }

    /** A section as it is read, its tables so far. */
    private class SectionDraft(private val name: String) {

        private val tables = mutableListOf<CsvTable>()
        private var header: CsvHeader? = null
        private var rows = mutableListOf<List<String>>()

        fun header(record: List<String>) {
            close()
            header = CsvHeader(record)
        }

        fun row(record: List<String>) {
            if (header != null) rows += PackedRecord(record)
        }

        fun toSection(): CsvSection {
            close()
            return CsvSection(name, tables.toList())
        }

        private fun close() {
            header?.let { tables += CsvTable(it, rows) }
            header = null
            rows = mutableListOf()
        }
    }
}

/**
 * One section of [CsvSections].
 *
 * @property name what its marker named it, trimmed.
 * @property tables its tables, in file order: one per header row, each with the rows after it.
 */
data class CsvSection(
    val name: String,
    val tables: List<CsvTable>,
)

/** One header row of a section, and the rows read by it, in file order. */
class CsvTable(
    val header: CsvHeader,
    val rows: List<List<String>>,
)

/**
 * A record's fields held as one string and the offsets where each ends, for a record that has to
 * be kept until the whole file has been read.
 *
 * A record as [CsvReader] answers it is a list of strings, and each string costs its own object
 * and array on top of its characters. For the short fields an export is made of, that is several
 * times the bytes the row was read from, which is what decides whether a whole file can be held.
 * Packed, a row costs its characters, one string and one offset per field. A field is cut out of
 * the string again each time it is read, which a parser does a handful of times per row.
 */
internal class PackedRecord(fields: List<String>) :
    AbstractList<String>(),
    RandomAccess {

    private val text: String = fields.joinToString(separator = "")

    private val ends: IntArray = IntArray(fields.size).also { ends ->
        var end = 0
        fields.forEachIndexed { index, field ->
            end += field.length
            ends[index] = end
        }
    }

    override val size: Int get() = ends.size

    override fun get(index: Int): String {
        if (index !in ends.indices) throw IndexOutOfBoundsException("index $index, size $size")
        return text.substring(if (index == 0) 0 else ends[index - 1], ends[index])
    }
}
