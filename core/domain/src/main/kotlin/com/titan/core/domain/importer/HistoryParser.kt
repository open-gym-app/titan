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
 */
data class ImportContext(
    val zone: ZoneId,
    val unitSystem: UnitSystem,
)

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
 * the CSV into [ParseResult.Malformed] the same way.
 */
internal interface RecordReading {

    /** Takes in one record, already split into fields. */
    fun read(record: List<String>)

    /** What the records read so far amount to. */
    fun result(): ParseResult
}

/**
 * A [RecordReading] that holds every record until the last, then hands them all to [read] at
 * once, for a format whose rows cannot be read before the whole file has been seen: a year-last
 * date is read in the order the file's own dates prove, so no row can be read before every date
 * has been.
 */
internal class WholeFile(private val read: (List<List<String>>) -> ParseResult) : RecordReading {
    private val records = mutableListOf<List<String>>()

    override fun read(record: List<String>) {
        records += record
    }

    override fun result(): ParseResult = read(records)
}

/**
 * Feeds every record [reader] has left to [this] and answers its [RecordReading.result], or
 * [ParseResult.Malformed] naming the line when the CSV breaks in a way no row can be recovered
 * from.
 */
internal fun RecordReading.readAll(reader: CsvReader): ParseResult =
    try {
        reader.records().forEach(::read)
        result()
    } catch (failure: MalformedCsvException) {
        ParseResult.Malformed(failure.message.orEmpty())
    }

/**
 * The largest file an import from another app will read: 32 MiB.
 *
 * Sized against the history the app commits to holding rather than against what parses quickly.
 * `LargeHistoryBenchmarkTest`'s power user has 100,000 sets, and a CSV export spends about a
 * hundred bytes a set — so ten megabytes, and this leaves three times that. The whole history is
 * held in memory before the first row is written, because duplicates and exercise matching need
 * all of it, so the cap is what bounds that memory as well as the read.
 */
const val MAX_SOURCE_BYTES: Long = 32L * 1024 * 1024

/** A set limit that never refuses a history. */
const val NO_SET_LIMIT: Int = -1

/**
 * The most sets one import will hold, or [NO_SET_LIMIT] for no limit, which is what it is for now.
 * The figure the guard was sized for is 500,000, five times `LargeHistoryBenchmarkTest`'s power
 * user.
 *
 * [MAX_SOURCE_BYTES] bounds bytes, and memory follows sets: the parsed history and the plan
 * built from it together cost about 290 bytes a set, so a file of very short rows could fit a
 * million sets under the byte cap and need more than the heap an app is given. An
 * `OutOfMemoryError` is not something the import catches, so the count is checked right after
 * parsing, before the plan doubles the memory. At this limit the two together stay near 150 MB.
 */
const val MAX_IMPORT_SETS: Int = NO_SET_LIMIT

/**
 * Runs [this] parser over [source] with every byte counted and every failure typed.
 *
 * What makes "a hostile or malformed file fails safely" true for every parser at once, rather
 * than for each one that remembered. The bytes are capped at [maxBytes]; an [IOException] is the
 * file not being readable rather than being wrong; and anything else a parser throws — an index
 * out of bounds on a short row, a number that would not parse — is a file the parser did not
 * expect, reported as [ParseResult.Malformed]. Cancellation is not a failure and is rethrown.
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

    data class Unreadable(val reason: String) : ParseOutcome
}
