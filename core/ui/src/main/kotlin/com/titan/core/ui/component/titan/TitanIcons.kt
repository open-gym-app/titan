package com.titan.core.ui.component.titan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital

/**
 * The companion's line icons, drawn from the Titan Companion canvas's own paths: a 24-unit
 * viewport, stroked, tinted by the caller. Built once and kept.
 */
object TitanIcons {

    val fuel: ImageVector by lazy { icon("M4 11h16a8 8 0 0 1-16 0zM9 4.5v3M12 3.5v4M15 4.5v3") }
    val energy: ImageVector by lazy { icon("M13 3L5 14h6l-1 7 8-11h-6z") }
    val spirit: ImageVector by lazy {
        icon("M12 20s-7.5-4.6-7.5-10.3A4.2 4.2 0 0 1 12 7.3a4.2 4.2 0 0 1 7.5 2.4C19.5 15.4 12 20 12 20z")
    }

    /** The titan itself, for the bottom bar and a spar's past self. */
    val titan: ImageVector by lazy {
        icon(
            "M7 10a4.5 4.5 0 0 1 4.5-4.5h1A4.5 4.5 0 0 1 17 10v9H7zM12 3v2.5M9.5 10.5h5v2.5h-5z",
        )
    }

    /** The icon for [vital]. */
    fun of(vital: TitanVital): ImageVector =
        when (vital) {
            TitanVital.FUEL -> fuel
            TitanVital.ENERGY -> energy
            TitanVital.SPIRIT -> spirit
        }

    /** The icon for [activity]; idle and eating borrow the rest and fuel icons. */
    fun of(activity: TitanActivity): ImageVector = activities.getValue(activity)

    /** The icon for a food, by its collectible id, or the fuel bowl for one this build cannot name. */
    fun food(id: String): ImageVector = foods[id] ?: fuel

    /** The icon for [gear]. */
    fun of(gear: Gear): ImageVector = gearIcons.getValue(gear)

    /** The icon for [place]. */
    fun of(place: TitanPlace): ImageVector = places.getValue(place)

    private val activities by lazy {
        val rest = icon("M19.5 14.5A8 8 0 1 1 9.5 4.5a6.2 6.2 0 0 0 10 10z")
        mapOf(
            TitanActivity.IDLE to rest,
            TitanActivity.WALK to icon("M13 5.5a1.5 1.5 0 1 0 0-.01M12 8l-2 5 3 3v5M10 13l-2.5 7M12.5 9.5l3.5 3"),
            TitanActivity.RUN to icon("M15 4.5a1.5 1.5 0 1 0 0-.01M13 8l-3 4 3 3-2 6M10 12l-4 1M14 9l3 4h3"),
            TitanActivity.SWIM to icon(
                "M15 7.5a1.5 1.5 0 1 0 0-.01M5 12l4-3 4 2 3-1M3 17c2 0 2-1.5 4.5-1.5S9.5 17 12 17s2-1.5 4.5-1.5S18.5 17 21 17",
            ),
            TitanActivity.TRAIN to icon("M6.5 7v10M3.5 9.5v5M17.5 7v10M20.5 9.5v5M6.5 12h11"),
            TitanActivity.SPAR to icon("M4 4l10 10M20 4L10 14M7 17l-3 3M17 17l3 3M5.5 14.5l4 4M18.5 14.5l-4 4"),
            TitanActivity.REST to rest,
            TitanActivity.EAT to fuel,
        )
    }

    private val foods by lazy {
        mapOf(
            "steak" to icon("M6 7c3-3 9-3.5 12-1s2.5 8-1.5 10.5S7 19 5 16s-2-6 1-9zM10.5 11.5a1.8 1.8 0 1 0 0 .01"),
            "rice" to icon("M12 4l8 14a1 1 0 0 1-1 1.5H5A1 1 0 0 1 4 18zM8.5 14h7v5.5h-7z"),
            "shake" to icon("M9 3h6v3l2 3v12H7V9l2-3zM7 13h10"),
            "eggs" to icon("M12 3c-3.9 0-7 6.1-7 10.2a7 7 0 0 0 14 0C19 9.1 15.9 3 12 3z"),
            "oats" to fuel,
            "honey" to icon("M7 8h10l-1 12H8zM6 5h12v3H6zM12 11v5"),
            "banana" to icon(
                "M4.5 14.5c3.5 3 10.5 2 13.5-5.5l.5-4M4.5 14.5c-.8 2.6 1 4.5 4 4.5 6 0 10.5-5.5 10.5-12.5",
            ),
            "berries" to icon("M8 14a3 3 0 1 0 0 .01M16 14a3 3 0 1 0 0 .01M12 9a3 3 0 1 0 0 .01M12 6V3l3 1"),
        )
    }

    private val gearIcons by lazy {
        mapOf(
            Gear.HEADBAND to icon("M4 12a8 4 0 0 0 16 0a8 4 0 0 0-16 0zM16 15.5l2 4.5"),
            Gear.SHADES to icon("M3 10h18M5 10a3 3 0 0 0 6 0M13 10a3 3 0 0 0 6 0"),
            Gear.CROWN to icon("M4 18h16M5 18L4 7l5 4 3-6 3 6 5-4-1 11"),
            Gear.CHALK to icon("M8 4h8v6l2 10H6l2-10zM8 10h8"),
            Gear.CAP to icon("M4 15a8 8 0 0 1 16 0zM4 15h17v2.5H4z"),
            Gear.CAPE to icon("M7 4h10l3 16H4zM12 4v16"),
            Gear.SCARF to icon("M6 6h12v4H6zM14 10v9l3-2v-7"),
            Gear.MEDAL to icon("M8 3l4 7 4-7M12 18a4 4 0 1 0 0-8a4 4 0 0 0 0 8z"),
        )
    }

    private val places by lazy {
        mapOf(
            TitanPlace.TRACK to icon("M4 18L9 6h6l5 12zM12 6v3M12 12v3"),
            TitanPlace.RACK to icon("M6 3v18M18 3v18M3 9h18M4 21h4M16 21h4"),
            TitanPlace.POOL to icon(
                "M3 15c2 0 2-1.5 4.5-1.5S9.5 15 12 15s2-1.5 4.5-1.5S18.5 15 21 15" +
                    "M3 19c2 0 2-1.5 4.5-1.5S9.5 19 12 19s2-1.5 4.5-1.5S18.5 19 21 19M8 12V5a2 2 0 0 1 4 0",
            ),
            TitanPlace.RING to icon("M4 6h16v12H4zM4 10h16M4 14h16"),
            TitanPlace.SAUNA to icon("M4 10l8-6 8 6v10H4zM9 14c0 2 2 2 2 4M13 14c0 2 2 2 2 4"),
            TitanPlace.TRAIL to icon("M4 20l5-9 3 4 3-6 5 11zM15 5a1.5 1.5 0 1 0 0-.01"),
            TitanPlace.DOJO to icon("M3 9l9-5 9 5M5 9v11h14V9M9 20v-6h6v6"),
            TitanPlace.SUMMIT to icon("M3 20l7-12 4 6 2-3 5 9zM10 8V3l4 2-4 1"),
        )
    }

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private const val STROKE = 1.75f
}
