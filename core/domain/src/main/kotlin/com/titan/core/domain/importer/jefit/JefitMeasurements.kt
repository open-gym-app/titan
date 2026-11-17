package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.model.BodyMeasurement
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Turns the PROFILE section, JEFIT's body stats, into body measurements.
 *
 * One row is one day's stats: `mydate` the day, `weight` in the account's weight unit,
 * `fatpercent` in percent, and `chest`, `arms`, `waist`, `calves`, `hips`, `thighs`, `shoulders`,
 * `neck` and `forearms` in its length unit. JEFIT writes `0` for a figure not recorded, and
 * `height`, which Titan has no column for, is not imported.
 *
 * A row's `logTime` is not when the stats were taken: JEFIT has been seen writing one day's stats
 * twice, once at that day's midnight where the export was made and once when they were synced.
 * So a day's stats start at noon in the user's zone, as a workout with no time of day does (see
 * [SourceValues.localDateAtNoon]), and the rows of one day are one measurement, a later
 * `logTime`'s figure replacing an earlier one's.
 *
 * A row whose date does not read is left out. A figure that is not a number is left out of its
 * measurement; a day all of whose figures are unreadable is passed on as an empty measurement,
 * which the planner refuses and counts. A row that records nothing — the all-zero row JEFIT
 * creates with the account — is not a measurement.
 */
internal class JefitMeasurements(
    tables: JefitTables,
    private val units: JefitUnits,
    private val zone: ZoneId,
) {
    private val rows = tables.rows(JefitSection.PROFILE, JefitColumn.DATE)

    /**
     * The measurements PROFILE holds, one a day, in the order of each day's earliest `logTime`:
     * rows are read oldest first, a row without one before every other and file order breaking
     * ties, so that a later row's figure replaces an earlier one's.
     */
    fun read(): List<BodyMeasurement> {
        val days = LinkedHashMap<Long, DayDraft>()
        rows.sortedByOnce { it.whole(JefitColumn.LOG_TIME) ?: Long.MIN_VALUE }
            .forEach { row ->
                val takenAt = SourceValues.localDateAtNoon(row[JefitColumn.DATE], zone) ?: return@forEach
                val day = days.getOrPut(takenAt) { DayDraft(takenAt) }
                Site.entries.forEach { site ->
                    val text = row[site.column] ?: return@forEach
                    val figure = SourceValues.decimal(text)
                    when {
                        figure == null -> day.unreadable = true
                        figure > 0.0 -> day.figures[site] = convert(site, figure)
                    }
                }
            }
        return days.values.mapNotNull(DayDraft::toMeasurement)
    }

    /** [figure] in [site]'s unit in Titan: milligrams, tenths of a percent, millimetres. */
    private fun convert(
        site: Site,
        figure: Double,
    ): Long =
        when (site) {
            Site.WEIGHT -> units.weight.toMilligrams(figure)
            Site.BODY_FAT -> (figure * BODY_FAT_SCALE).roundToLong()
            else -> units.length.toMillimetres(figure)
        }

    /** What one PROFILE column records, as a field of [BodyMeasurement]. */
    private enum class Site(val column: String) {
        WEIGHT(JefitColumn.WEIGHT),
        BODY_FAT(JefitColumn.BODY_FAT),
        NECK(JefitColumn.NECK),
        SHOULDERS(JefitColumn.SHOULDERS),
        CHEST(JefitColumn.CHEST),
        WAIST(JefitColumn.WAIST),
        HIPS(JefitColumn.HIPS),
        ARM(JefitColumn.ARMS),
        FOREARM(JefitColumn.FOREARMS),
        THIGH(JefitColumn.THIGHS),
        CALF(JefitColumn.CALVES),
    }

    private class DayDraft(private val takenAt: Long) {

        val figures = HashMap<Site, Long>()
        var unreadable = false

        /** The day's measurement; an empty one when only unreadable figures were recorded, else null when none. */
        fun toMeasurement(): BodyMeasurement? {
            if (figures.isEmpty() && !unreadable) return null
            fun int(site: Site): Int? = figures[site]?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            return BodyMeasurement(
                weight = figures[Site.WEIGHT],
                neck = int(Site.NECK),
                shoulders = int(Site.SHOULDERS),
                chest = int(Site.CHEST),
                waist = int(Site.WAIST),
                hips = int(Site.HIPS),
                arm = int(Site.ARM),
                forearm = int(Site.FOREARM),
                thigh = int(Site.THIGH),
                calf = int(Site.CALF),
                bodyFat = int(Site.BODY_FAT),
                createdAt = takenAt,
                updatedAt = takenAt,
            )
        }
    }

    private companion object {
        /** JEFIT writes body fat in percent (`15.5`); Titan stores tenths of one. */
        const val BODY_FAT_SCALE = 10
    }
}
