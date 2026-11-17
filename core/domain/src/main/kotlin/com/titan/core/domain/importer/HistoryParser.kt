package com.titan.core.domain.importer

import com.titan.core.domain.backup.CappedStream
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.SourceTooLargeException
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.csv.MalformedCsvException
import com.titan.core.domain.model.UnitSystem
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.ZoneId

/**
 * Turns one app's export into an [ImportedHistory] — the one thing a new import source has to
 * write.
 *
 * ### Recognising the file is part of the job
 *
 * A user picks the app they are importing from and then a file, and the two do not always agree:
 * a Hevy export offered to the Strong parser, a spreadsheet of something else entirely. A parser
 * therefore says [ParseResult.NotRecognised] when the bytes are not its app's export — by the
 * header row, the column set, whatever signature the format has — *before* it tries to read
 * workouts out of them. "This is not a Strong export" is a sentence a user can act on; a list of
 * rows that failed to parse because they were never Strong rows is not.
 *
 * Once it has recognised the file, [ParseResult.Malformed] is for bytes that claim to be the
 * format and break it: a row with half its columns, a date that does not parse. Whether to refuse
 * the whole file for one bad row or skip the row is the parser's call, and the answer should be
 * the one that loses least — a single corrupt line in a ten-year export is better skipped.
 *
 * ### What a parser may assume, and what it may not
 *
 * - It reads through [ImportSource], and the engine has already capped what it will hand over:
 *   see [parseSafely]. A parser does not count bytes itself.
 * - It stops once it holds more than [ImportContext.maxSets] sets, by throwing
 *   [TooManySetsException]. A CSV parser gets that from [readAll]; one that builds its sets some
 *   other way counts them itself.
 * - It may open [ImportSource] more than once, and reads every record it does not have to hold
 *   rather than keeping it: a record held as fields costs several times the bytes it was read
 *   from, so the history is the only thing a parse should keep whole.
 * - It runs on whatever thread collects the import, which is expected to be one where blocking
 *   reads are fine — see [com.titan.core.domain.usecase.ImportHistoryUseCase].
 * - It may throw. Anything it throws is turned into a refusal rather than a crash, because the
 *   input is a file nothing vouches for and a parser that missed one shape of it must not take
 *   the app down. That is a safety net, not a contract: a parser that knows what is wrong should
 *   say so with [ParseResult.Malformed], which is what tells the user.
 * - It does not validate against Titan's rules — bounds, dense numbering, superset shape,
 *   duplicates already on the device. [ImportPlanner] applies those once, for every source.
 */
interface HistoryParser {

    /** Reads [source] as this parser's app export, in [context]. */
    fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult
}

/**
 * What a parser needs to know about the device that the file does not say.
 *
 * @property zone the zone a timestamp without one is read in. Most apps export local wall-clock
 *   times ("2024-03-02 18:04:11"), and the zone the user trained in is, for nearly everybody, the
 *   one their phone is in now.
 * @property unitSystem the unit a weight or a distance with no unit beside it is read in. Several
 *   apps write the bare number in whatever the user had chosen, and the user's choice here is the
 *   best evidence of what that was.
 * @property maxSets the most sets the parse may hold before it gives up with
 *   [TooManySetsException]. The engine sets it from its own limit, [MAX_IMPORT_SETS], so that a
 *   history too large to import is refused while it is read rather than after it has been held.
 */
data class ImportContext(
    val zone: ZoneId,
    val unitSystem: UnitSystem,
    val maxSets: Int = NO_SET_LIMIT,
)

/**
 * A parse that has read more than [limitSets] sets, thrown to stop it before the rest of the file
 * is held as well. [parseSafely] turns it into [ParseOutcome.TooManySets].
 */
class TooManySetsException(val limitSets: Int) : RuntimeException("the file holds more than $limitSets sets")

/** Throws [TooManySetsException] when [sets] is past [ImportContext.maxSets]. */
internal fun ImportContext.checkSetCount(sets: Int) {
    if (sets > maxSets) throw TooManySetsException(maxSets)
}

/** What [HistoryParser.parse] answers. */
sealed interface ParseResult {

    /** The file was this parser's format, and this is what it held. */
    data class Parsed(val history: ImportedHistory) : ParseResult

    /** The file is not an export of this parser's app. */
    data object NotRecognised : ParseResult

    /**
     * The file is this parser's format and cannot be read as it.
     *
     * @property reason for a log and a bug report, in English. The screen shows its own sentence.
     */
    data class Malformed(val reason: String) : ParseResult
}

/**
 * One pass over the rows of a CSV export after its header, building a history as it goes.
 *
 * A parser that has recognised its format by the header hands every later record to one of
 * these and answers what it built; [readAll] is that loop, so every CSV parser turns a break in
 * the CSV into [ParseResult.Malformed] the same way, and stops at the set limit the same way.
 */
internal interface RecordReading {

    /** How many sets the records read so far hold. */
    val setCount: Int

    /** Takes in one record, already split into fields. */
    fun read(record: List<String>)

    /** What the records read so far amount to. */
    fun result(): ParseResult
}

/**
 * Feeds every record [reader] has left to [this] and answers its [RecordReading.result], or
 * [ParseResult.Malformed] naming the line when the CSV breaks in a way no row can be recovered
 * from.
 *
 * @throws TooManySetsException as soon as the records read hold more than
 *   [ImportContext.maxSets] sets, so the rest of the file is never held.
 */
internal fun RecordReading.readAll(
    reader: CsvReader,
    context: ImportContext,
): ParseResult =
    try {
        reader.records().forEach { record ->
            read(record)
            context.checkSetCount(setCount)
        }
        result()
    } catch (failure: MalformedCsvException) {
        ParseResult.Malformed(failure.message.orEmpty())
    }

/**
 * Rows that cannot be read before every date in the file has been seen. A year-last date is read
 * in the order the file's own dates prove ([SourceValues.dayMonthOrder]), so the first row cannot
 * be read until the last date has been.
 */
internal interface DatedRows {

    /** The cell of [record] that holds its year-last date, or null when it has none. */
    fun date(record: List<String>): String?

    /** A reading of the rows, with their dates read in [order]. */
    fun reading(order: DayMonthOrder?): RecordReading
}

/**
 * Reads [rows] from [source] in two passes: the first, over [first], collects nothing but the
 * order of the dates, and the second reads the rows in that order as they arrive.
 *
 * Reading the file twice costs a second pass over bytes the first has already decoded. Holding
 * every row until the last date had been read would cost far more: a row held as a list of
 * strings is several times the bytes it was read from, so the rows of a file well inside
 * [MAX_SOURCE_BYTES] would need more memory than the history they make.
 *
 * @param first the reader the parser recognised the file with, positioned after the header.
 */
internal fun readInTwoPasses(
    source: ImportSource,
    first: CsvReader,
    rows: DatedRows,
    context: ImportContext,
): ParseResult {
    val order = try {
        SourceValues.dayMonthOrder(first.records().map(rows::date).asIterable())
    } catch (failure: MalformedCsvException) {
        return ParseResult.Malformed(failure.message.orEmpty())
    }
    return source.open().use { stream ->
        val second = CsvReader.open(stream, first.delimiter)
        second.readRecord()
        rows.reading(order).readAll(second, context)
    }
}

/**
 * The largest file an import from another app will read: 32 MiB.
 *
 * Sized against the history the app commits to holding rather than against what parses quickly.
 * `LargeHistoryBenchmarkTest`'s power user has 120,000 sets, and a CSV export spends about a
 * hundred bytes a set — so twelve megabytes, and this leaves well over twice that. The bytes are
 * read as a stream and never held; what bounds the memory an import holds is [MAX_IMPORT_SETS].
 */
const val MAX_SOURCE_BYTES: Long = 32L * 1024 * 1024

/**
 * A set limit that never refuses a history: a set count is an [Int], so none is larger than this.
 */
const val NO_SET_LIMIT: Int = Int.MAX_VALUE

/**
 * The most sets one import will hold: 500,000, about four times `LargeHistoryBenchmarkTest`'s
 * power user.
 *
 * Memory follows sets, not bytes. The whole history is held before the first row is written,
 * because duplicates, exercise matching and the replay's order need all of it, and a parsed set
 * costs about 120 bytes. The plan reuses those sets rather than copying them (see
 * [PlannedWorkoutExercise]), so 500,000 sets stay near 60 MB. [MAX_SOURCE_BYTES] would not bound
 * that on its own: a file of very short rows fits more than a million sets under it. An
 * `OutOfMemoryError` is not something the import catches, so a parser stops as soon as it has read
 * one set too many ([ImportContext.maxSets]), before the rest of the file is held.
 */
const val MAX_IMPORT_SETS: Int = 500_000

/**
 * Runs [this] parser over [source] with every byte counted and every failure typed.
 *
 * What makes "a hostile or malformed file fails safely" true for every parser at once, rather
 * than for each one that remembered. The bytes are capped at [maxBytes]; an [IOException] is the
 * file not being readable rather than being wrong; and anything else a parser throws — an index
 * out of bounds on a short row, a number that would not parse — is a file the parser did not
 * expect, reported as [ParseResult.Malformed]. A parse past [ImportContext.maxSets] is
 * [ParseOutcome.TooManySets]. Cancellation is not a failure and is rethrown.
 */
@Suppress("TooGenericExceptionCaught")
internal fun HistoryParser.parseSafely(
    source: ImportSource,
    context: ImportContext,
    maxBytes: Long,
): ParseOutcome =
    try {
        ParseOutcome.Read(parse(ImportSource { CappedStream(source.open(), maxBytes) }, context))
    } catch (failure: SourceTooLargeException) {
        ParseOutcome.TooLarge(failure.limitBytes)
    } catch (failure: TooManySetsException) {
        ParseOutcome.TooManySets(failure.limitSets)
    } catch (failure: IOException) {
        ParseOutcome.Unreadable(failure.message.orEmpty())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: RuntimeException) {
        // The safety net the interface promises: see `HistoryParser`. A parser bug on a file
        // nobody vouches for is a refused file, not a crashed app.
        ParseOutcome.Read(ParseResult.Malformed(failure.message ?: failure::class.java.simpleName))
    }

/** [parseSafely]'s answer: the parser's own, or the reason it never got to give one. */
internal sealed interface ParseOutcome {

    data class Read(val result: ParseResult) : ParseOutcome

    data class TooLarge(val limitBytes: Long) : ParseOutcome

    data class TooManySets(val limitSets: Int) : ParseOutcome

    data class Unreadable(val reason: String) : ParseOutcome
}
