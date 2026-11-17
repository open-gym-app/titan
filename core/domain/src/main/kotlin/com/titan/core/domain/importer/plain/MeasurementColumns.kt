package com.titan.core.domain.importer.plain

import com.titan.core.domain.importer.DayMonthOrder
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceLengthUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.model.BodyMeasurement
import kotlin.math.roundToInt

/**
 * The format's body measurement file: one row per measurement, `measurement_date` and optionally
 * `measurement_time`, then `weight`, `body_fat`, the nine circumferences Titan records and `notes`.
 *
 * Each row is one [BodyMeasurement], taken at the row's start as a workout's is read ([startOf]),
 * so a re-import is a duplicate of every measurement, and two rows on one day without a time are
 * one measurement and a duplicate the planner counts. The weight is read as a workout file's is:
 * in the unit its cell, its row's `weight_unit`, its header or the user's setting names, in that
 * order. A circumference is in its row's `length_unit`, else its header's unit, else the length the
 * user measures a body in. `body_fat` is a percentage, with or without its `%`.
 *
 * A row whose date does not read is left out. A row with a figure that is not a number, or a unit
 * Titan does not read, is passed on with nothing recorded, for the planner to refuse and count,
 * and so is a row that records nothing at all.
 */
internal class MeasurementColumns private constructor(
    header: CsvHeader,
    private val context: ImportContext,
    private val weightColumn: UnitColumn<SourceWeightUnit>?,
    private val sites: Map<Site, UnitColumn<SourceLengthUnit>>,
) : PlainCsvLayout {

    override val problem: String? =
        (listOfNotNull(weightColumn) + sites.values).firstNotNullOfOrNull(UnitColumn<*>::problem)

    private val cells = PlainCells(header)

    override fun read(records: List<List<String>>): ParseResult {
        val order = SourceValues.dayMonthOrder(records.map { cells[it, MEASUREMENT_DATE] })
        val measurements = records.mapNotNull { measurement(it, order) }
        return if (measurements.isEmpty() && records.isNotEmpty()) {
            ParseResult.Malformed("none of the file's ${records.size} rows has a measurement_date that can be read")
        } else {
            ParseResult.Parsed(ImportedHistory(bodyMeasurements = measurements))
        }
    }

    /** The measurement [record] holds, or null when its date does not read. */
    private fun measurement(
        record: List<String>,
        order: DayMonthOrder?,
    ): BodyMeasurement? {
        val takenAt = startOf(cells[record, MEASUREMENT_DATE], cells[record, MEASUREMENT_TIME], context.zone, order)
            ?: return null
        val empty = BodyMeasurement(createdAt = takenAt, updatedAt = takenAt)

        val weightText = cells[record, weightColumn]
        val weight = cells.weight(record, weightColumn, SourceWeightUnit.of(context.unitSystem))
        val bodyFatText = cells[record, BODY_FAT]
        val bodyFat = SourceValues.decimal(bodyFatText?.removeSuffix(PERCENT))
        val siteTexts = sites.mapValues { (_, column) -> cells[record, column] }
        val millimetres = siteTexts.mapValues { (site, text) ->
            SourceValues.decimal(text)?.let { figure -> lengthUnit(record, site)?.toMillimetres(figure) }
        }
        val unreadable = (weightText != null && weight == null) ||
            (bodyFatText != null && bodyFat == null) ||
            siteTexts.any { (site, text) -> text != null && millimetres[site] == null }
        if (unreadable) return empty

        fun mm(site: Site): Int? = millimetres[site]?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
        return empty.copy(
            weight = weight,
            bodyFat = bodyFat?.let { (it * BODY_FAT_SCALE).roundToInt() },
            neck = mm(Site.NECK),
            shoulders = mm(Site.SHOULDERS),
            chest = mm(Site.CHEST),
            waist = mm(Site.WAIST),
            hips = mm(Site.HIPS),
            arm = mm(Site.ARM),
            forearm = mm(Site.FOREARM),
            thigh = mm(Site.THIGH),
            calf = mm(Site.CALF),
            notes = SourceValues.note(cells[record, NOTES]),
        )
    }

    /**
     * The unit [record]'s circumference at [site] is in: its row's `length_unit`, which every
     * circumference on the row is in, else its header's, else the length the user measures a body
     * in. Null when its row names one Titan does not read.
     */
    private fun lengthUnit(
        record: List<String>,
        site: Site,
    ): SourceLengthUnit? {
        val label = cells[record, LENGTH_UNIT]
            ?: return sites[site]?.unit ?: SourceLengthUnit.of(context.unitSystem)
        return SourceLengthUnit.parse(label)
    }

    companion object {

        /** [header]'s layout, or null when it is not the format's measurement file. */
        fun of(
            header: CsvHeader,
            context: ImportContext,
        ): MeasurementColumns? =
            if (header.plainColumn(MEASUREMENT_DATE) != null) {
                MeasurementColumns(
                    header = header,
                    context = context,
                    weightColumn = header.unitColumn(WEIGHT, SourceWeightUnit::parse),
                    sites = Site.entries.mapNotNull { site -> header.siteColumn(site)?.let { site to it } }.toMap(),
                )
            } else {
                null
            }
    }
}

/** The column for [site]'s circumference, with the unit its header names; null when the header has none. */
private fun CsvHeader.siteColumn(site: Site): UnitColumn<SourceLengthUnit>? =
    unitColumn(site.column, SourceLengthUnit::parse)

/** A body site Titan records, and the format's column for it. */
private enum class Site(val column: String) {
    NECK("neck"),
    SHOULDERS("shoulders"),
    CHEST("chest"),
    WAIST("waist"),
    HIPS("hips"),
    ARM("arm"),
    FOREARM("forearm"),
    THIGH("thigh"),
    CALF("calf"),
}

private const val MEASUREMENT_DATE = "measurement_date"
private const val MEASUREMENT_TIME = "measurement_time"
private const val BODY_FAT = "body_fat"
private const val LENGTH_UNIT = "length_unit"
private const val PERCENT = "%"

/** Body fat is written in percent (`15.5`); Titan stores tenths of one. */
private const val BODY_FAT_SCALE = 10
