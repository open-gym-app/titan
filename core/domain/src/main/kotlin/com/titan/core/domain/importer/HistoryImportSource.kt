package com.titan.core.domain.importer

import com.titan.core.domain.importer.fitbod.FitbodParser
import com.titan.core.domain.importer.fitnotes.FitNotesParser
import com.titan.core.domain.importer.hevy.HevyParser
import com.titan.core.domain.importer.jefit.JefitParser
import com.titan.core.domain.importer.plain.PlainCsvParser
import com.titan.core.domain.importer.strong.StrongParser
import com.titan.core.domain.importer.stronglifts.StrongLiftsParser

/**
 * An app a training history can be imported from: one entry per [HistoryParser] the app ships.
 *
 * The screen offers exactly these, in this order, and the user's pick is what decides which
 * parser reads the file — see [HistoryParsers]. Ordered by how many people are likely to arrive
 * from each, with Titan's own plain CSV last, because it is the way in for everybody the six apps
 * above it do not cover.
 *
 * ### The names are persisted
 *
 * An import runs in a background worker that outlives the screen, and the source it was started
 * for is stored with that work by [name], so an import the system interrupted can be offered
 * again on the next launch. Renaming a constant strands that one stored request; it changes
 * nothing else. `HistoryImportSourceTest` pins the names for that reason.
 */
enum class HistoryImportSource {

    /** Strong's workout export. */
    STRONG,

    /** Hevy's workout export and its separate body measurement export. */
    HEVY,

    /** FitNotes' two spreadsheet exports. Not its `.fitnotes` backup, which is a database. */
    FITNOTES,

    /** JEFIT's account export: workouts, routines, body stats and custom exercises in one file. */
    JEFIT,

    /** Fitbod's `WorkoutExport.csv`. */
    FITBOD,

    /** StrongLifts' CSV export, in either of the layouts the app has written. */
    STRONGLIFTS,

    /** The CSV format Titan defines itself, for every other app and for spreadsheets. */
    PLAIN_CSV,
}

/**
 * Which parser reads an export of each [HistoryImportSource].
 *
 * A port rather than a `when` at the call site so the worker that runs an import is handed its
 * parsers by the graph — and a test can hand it one that answers whatever the test needs — while
 * [SUPPORTED] keeps the real mapping an exhaustive `when`: a source added to the enum without a
 * parser is a compile error here, not a pick that fails at runtime.
 */
fun interface HistoryParsers {

    /** A parser for [source]'s exports. Parsers hold no state, so a fresh one each time is fine. */
    fun parserFor(source: HistoryImportSource): HistoryParser

    companion object {

        /** Every source, read by the parser written for it. */
        val SUPPORTED: HistoryParsers = HistoryParsers { source ->
            when (source) {
                HistoryImportSource.STRONG -> StrongParser()
                HistoryImportSource.HEVY -> HevyParser()
                HistoryImportSource.FITNOTES -> FitNotesParser()
                HistoryImportSource.JEFIT -> JefitParser()
                HistoryImportSource.FITBOD -> FitbodParser()
                HistoryImportSource.STRONGLIFTS -> StrongLiftsParser()
                HistoryImportSource.PLAIN_CSV -> PlainCsvParser()
            }
        }
    }
}
