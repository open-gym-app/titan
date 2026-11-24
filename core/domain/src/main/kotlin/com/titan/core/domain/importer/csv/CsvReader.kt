package com.titan.core.domain.importer.csv

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.Locale

/**
 * Reads RFC 4180 comma-separated values one record at a time.
 *
 * Most apps export their history as CSV, and every one of them writes it a little differently: a
 * semicolon where the locale uses a decimal comma, a byte order mark from a spreadsheet round
 * trip, CRLF from one platform and LF from another, notes that run over several lines inside
 * quotes. Those are all properties of CSV rather than of an app, so they are handled
 * here once and every parser reads records instead of characters.
 *
 * ### What it accepts
 *
 * - Fields separated by [delimiter]; records ended by LF, CRLF or a lone CR.
 * - A field in double quotes may hold the delimiter, line breaks and `""` for a literal quote.
 * - A quote anywhere but the start of a field is an ordinary character, and so is anything after
 *   a closing quote and before the next delimiter. RFC 4180 calls both invalid; exporters write
 *   them anyway (`5'10"`), and refusing a ten-year history over an inch mark loses more than it
 *   protects.
 * - Blank lines are skipped, not returned as a record of one empty field.
 *
 * ### What it refuses
 *
 * A quoted field still open at the end of the input, a record longer than [maxRecordChars]
 * characters and one with more than [maxFields] fields — each a [MalformedCsvException] naming the
 * line the record began on. The two limits are what keep a hostile file from building one
 * enormous string: the byte cap on the whole file bounds the total, and these bound any single
 * record well inside it.
 *
 * Not thread-safe, and holds [reader] open until [close].
 */
class CsvReader(
    private val reader: Reader,
    val delimiter: Char = ',',
    private val maxRecordChars: Int = MAX_RECORD_CHARS,
    private val maxFields: Int = MAX_FIELDS,
) : Closeable {

    /** The 1-based line the record [readRecord] last returned began on, for error messages. */
    var recordLine: Long = 0
        private set

    private var line: Long = 1
    private var pushedBack: Int = NONE

    /**
     * The next record's fields, or null at the end of the input.
     *
     * @throws MalformedCsvException see the class doc.
     * @throws java.io.IOException if [reader] fails.
     */
    fun readRecord(): List<String>? {
        while (true) {
            val record = readRawRecord() ?: return null
            if (record.size == 1 && record[0].isEmpty()) continue
            return record
        }
    }

    /**
     * The next record as a [CsvHeader] — the header, when nothing has been read yet — or null
     * when there is none, including when it is not CSV at all: to a parser recognising its
     * format, that is a file that is not an export rather than a broken one.
     *
     * @throws java.io.IOException if [reader] fails.
     */
    fun readHeader(): CsvHeader? =
        try {
            readRecord()?.let(::CsvHeader)
        } catch (_: MalformedCsvException) {
            null
        }

    /** Every remaining record, read lazily. */
    fun records(): Sequence<List<String>> = generateSequence { readRecord() }

    override fun close() = reader.close()

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun readRawRecord(): List<String>? {
        var c = next()
        if (c == EOF) return null
        recordLine = line

        val fields = ArrayList<String>()
        val field = StringBuilder()
        var started = false
        var quoted = false
        var chars = 0

        fun endField() {
            if (fields.size == maxFields) {
                throw MalformedCsvException(recordLine, "a record has more than $maxFields fields")
            }
            fields += field.toString()
            field.setLength(0)
            started = false
        }

        while (true) {
            if (c == EOF) {
                if (quoted) throw MalformedCsvException(recordLine, "a quoted field is never closed")
                endField()
                return fields
            }
            if (++chars > maxRecordChars) {
                throw MalformedCsvException(recordLine, "a record is longer than $maxRecordChars characters")
            }
            val char = c.toChar()
            when {
                quoted && char == QUOTE -> {
                    val after = next()
                    if (after == QUOTE.code) {
                        field.append(QUOTE)
                    } else {
                        quoted = false
                        c = after
                        continue
                    }
                }

                quoted -> {
                    if (char == LF || (char == CR && peek() != LF.code)) line++
                    field.append(char)
                }

                char == QUOTE && !started -> {
                    quoted = true
                    started = true
                }

                char == delimiter -> endField()

                char == CR || char == LF -> {
                    if (char == CR && peek() == LF.code) next()
                    line++
                    endField()
                    return fields
                }

                else -> {
                    field.append(char)
                    started = true
                }
            }
            c = next()
        }
    }

    private fun next(): Int {
        val pending = pushedBack
        if (pending != NONE) {
            pushedBack = NONE
            return pending
        }
        return reader.read()
    }

    private fun peek(): Int {
        if (pushedBack == NONE) pushedBack = reader.read()
        return pushedBack
    }

    companion object {

        /**
         * 256 Ki characters: a note pasted from somewhere else is long, and this is still a
         * thousand times longer than any real row.
         */
        const val MAX_RECORD_CHARS = 256 * 1024

        /** No export has a hundred columns; ten times that is a file that is not a table. */
        const val MAX_FIELDS = 1024

        /** The delimiters [open] chooses between, the first winning a tie. */
        val CANDIDATE_DELIMITERS = listOf(',', ';', '\t', '|')

        private const val QUOTE = '"'
        private const val CR = '\r'
        private const val LF = '\n'
        private const val BOM = '﻿'
        private const val EOF = -1
        private const val NONE = -2

        /** How far [open] looks for the end of the first line. */
        private const val SNIFF_CHARS = 64 * 1024

        /**
         * A reader over [stream]'s UTF-8 text, past any byte order mark, splitting on
         * [delimiter] — or on whichever of [CANDIDATE_DELIMITERS] the first line uses most, when
         * that is null.
         *
         * Sniffing exists because the same app writes `, ` in one locale and `;` in another, and
         * the header is the one line every export of a format has in common.
         */
        fun open(
            stream: InputStream,
            delimiter: Char? = null,
        ): CsvReader {
            val buffered = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            buffered.mark(1)
            if (buffered.read() != BOM.code) buffered.reset()
            val chosen = delimiter ?: run {
                buffered.mark(SNIFF_CHARS)
                val firstLine = buildString {
                    while (length < SNIFF_CHARS) {
                        val c = buffered.read()
                        if (c == EOF || c == CR.code || c == LF.code) break
                        append(c.toChar())
                    }
                }
                buffered.reset()
                sniffDelimiter(firstLine)
            }
            return CsvReader(buffered, chosen)
        }

        /**
         * The candidate delimiter [line] holds most of outside quotes; `, ` when it holds none.
         */
        fun sniffDelimiter(line: String): Char {
            val counts = IntArray(CANDIDATE_DELIMITERS.size)
            var quoted = false
            line.forEach { char ->
                if (char == QUOTE) {
                    quoted = !quoted
                } else if (!quoted) {
                    val index = CANDIDATE_DELIMITERS.indexOf(char)
                    if (index >= 0) counts[index]++
                }
            }
            val best = counts.indices.maxBy { counts[it] }
            return if (counts[best] == 0) CANDIDATE_DELIMITERS.first() else CANDIDATE_DELIMITERS[best]
        }
    }
}

/**
 * A CSV header row, for reading a record's fields by column name rather than by position.
 *
 * Apps add, drop and reorder columns between versions — Strong has done all three — so a parser
 * that indexed by position would break on the next release of an app it does not control. Names
 * are matched trimmed and without regard to case; a name that appears twice resolves to its first
 * column.
 */
class CsvHeader(columns: List<String>) {

    /** The header's names, trimmed. */
    val columns: List<String> = columns.map(String::trim)

    private val positions: Map<String, Int> = buildMap {
        this@CsvHeader.columns.forEachIndexed { index, name -> putIfAbsent(name.key(), index) }
    }

    /** Whether the header has a column called [name]. */
    operator fun contains(name: String): Boolean = name.key() in positions

    /** Whether the header has every column in [names] — the usual way a parser recognises its format. */
    fun containsAll(names: Iterable<String>): Boolean = names.all(::contains)

    /** The position of the column called [name], or null if there is none. */
    fun indexOf(name: String): Int? = positions[name.key()]

    /**
     * [record]'s value in the column called [name], trimmed, or null when the header has no such
     * column, the record is too short to reach it, or the value is blank.
     *
     * Blank and absent are one answer because no export this was designed against distinguishes
     * them: an empty `Weight` cell and no `Weight` column both mean the set recorded no weight.
     */
    operator fun get(
        record: List<String>,
        name: String,
    ): String? = indexOf(name)?.let(record::getOrNull)?.trim()?.takeIf(String::isNotEmpty)

    /**
     * The first column called [name] with or without a qualifier in parentheses after it —
     * `Weight`, `Weight (kg)`, `Weight (lbs)` — or null when the header has none.
     *
     * Exports put a column's unit in its header as often as anywhere, and change it with the
     * user's settings, so one app writes `Weight (kg)` for one user and `Weight (lbs)` for the
     * next. Looking the column up by what precedes the parenthesis finds it either way, and the
     * qualifier is what says which unit the figures are in.
     */
    fun qualified(name: String): QualifiedColumn? {
        val wanted = name.key()
        return columns.firstNotNullOfOrNull { column ->
            val parts = QUALIFIED.matchEntire(column)
            when {
                column.key() == wanted -> QualifiedColumn(column, qualifier = null)

                parts != null && parts.groupValues[1].key() == wanted -> QualifiedColumn(
                    column,
                    parts.groupValues[2].trim(),
                )

                else -> null
            }
        }
    }

    /**
     * Every column called [name] followed by an underscore and a qualifier — `weight_kg`,
     * `weight_lbs` — in header order, each with the qualifier after the underscore.
     *
     * The snake_case counterpart of [qualified], for the exports whose headers are identifiers
     * rather than labels. It answers every match rather than the first because such exports
     * name the unit in place of a separate column, and a file that carries the same figure in
     * two units (`weight_kg` beside `weight_lbs`) leaves the parser to choose between them.
     */
    fun suffixed(name: String): List<QualifiedColumn> {
        val prefix = name.trim() + SUFFIX_SEPARATOR
        return columns.mapNotNull { column ->
            val qualifier = column.substring(minOf(prefix.length, column.length)).trim()
            val matches = column.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
            if (matches && qualifier.isNotEmpty()) QualifiedColumn(column, qualifier) else null
        }
    }

    private fun String.key(): String = trim().lowercase(Locale.ROOT)

    private companion object {
        val QUALIFIED = Regex("(.*?)\\s*\\(([^()]*)\\)")
        const val SUFFIX_SEPARATOR = '_'
    }
}

/**
 * A column [CsvHeader.qualified] or [CsvHeader.suffixed] found.
 *
 * @property name the column's full name, as [CsvHeader.get] reads it.
 * @property qualifier what its header held in parentheses — `kg` in `Weight (kg)` — or after the
 *   underscore — `kg` in `weight_kg` — or null when it held none.
 */
data class QualifiedColumn(
    val name: String,
    val qualifier: String?,
)

/**
 * The input is not CSV this reader can make sense of.
 *
 * An [IllegalArgumentException] rather than an [java.io.IOException]: the bytes were read, and
 * they are wrong. A parser that lets it escape has its file reported as malformed, which is what
 * it is — see [com.titan.core.domain.importer.HistoryParser].
 *
 * @property line the 1-based line the offending record began on.
 */
class MalformedCsvException(
    val line: Long,
    reason: String,
) : IllegalArgumentException("line $line: $reason")
