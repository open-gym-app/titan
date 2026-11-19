package com.titan.core.ui.component.titan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.gamification.LevelTitles
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.ui.R

/*
 * The titan's vocabulary, labelled once in `:core:ui` because the tab, the dock, the sheet, the
 * inventory and the spar screen all draw it — the rule DomainLabels.kt states. Each id this
 * build cannot name has an "unknown" answer rather than a throw.
 */

@Composable
@ReadOnlyComposable
fun TitanVital.label(): String =
    stringResource(
        when (this) {
            TitanVital.FUEL -> R.string.titan_vital_fuel
            TitanVital.ENERGY -> R.string.titan_vital_energy
            TitanVital.SPIRIT -> R.string.titan_vital_spirit
        },
    )

@Composable
@ReadOnlyComposable
fun TitanActivity.label(): String =
    stringResource(
        when (this) {
            TitanActivity.IDLE -> R.string.titan_activity_idle
            TitanActivity.WALK -> R.string.titan_activity_walk
            TitanActivity.RUN -> R.string.titan_activity_run
            TitanActivity.SWIM -> R.string.titan_activity_swim
            TitanActivity.TRAIN -> R.string.titan_activity_train
            TitanActivity.SPAR -> R.string.titan_activity_spar
            TitanActivity.REST -> R.string.titan_activity_rest
            TitanActivity.EAT -> R.string.titan_activity_eat
        },
    )

@Composable
@ReadOnlyComposable
fun Gear.label(): String =
    stringResource(
        when (this) {
            Gear.HEADBAND -> R.string.titan_gear_headband
            Gear.SHADES -> R.string.titan_gear_shades
            Gear.CROWN -> R.string.titan_gear_crown
            Gear.CHALK -> R.string.titan_gear_chalk
            Gear.CAP -> R.string.titan_gear_cap
            Gear.CAPE -> R.string.titan_gear_cape
            Gear.SCARF -> R.string.titan_gear_scarf
            Gear.MEDAL -> R.string.titan_gear_medal
        },
    )

@Composable
@ReadOnlyComposable
fun TitanPlace.label(): String =
    stringResource(
        when (this) {
            TitanPlace.TRACK -> R.string.titan_place_track
            TitanPlace.RACK -> R.string.titan_place_rack
            TitanPlace.POOL -> R.string.titan_place_pool
            TitanPlace.RING -> R.string.titan_place_ring
            TitanPlace.SAUNA -> R.string.titan_place_sauna
            TitanPlace.TRAIL -> R.string.titan_place_trail
            TitanPlace.DOJO -> R.string.titan_place_dojo
            TitanPlace.SUMMIT -> R.string.titan_place_summit
        },
    )

/** A food's name, by its collectible id, or "Unknown food" for one this build cannot name. */
@Composable
@ReadOnlyComposable
fun foodLabel(id: String): String =
    stringResource(
        when (id) {
            "steak" -> R.string.titan_food_steak
            "rice" -> R.string.titan_food_rice
            "shake" -> R.string.titan_food_shake
            "eggs" -> R.string.titan_food_eggs
            "oats" -> R.string.titan_food_oats
            "honey" -> R.string.titan_food_honey
            "banana" -> R.string.titan_food_banana
            "berries" -> R.string.titan_food_berries
            else -> R.string.titan_food_unknown
        },
    )

/** A titan's species name, by its id, or "Unknown titan" for one this build cannot name. */
@Composable
@ReadOnlyComposable
fun petSpeciesLabel(id: String): String =
    stringResource(
        when (id) {
            "iron_cub" -> R.string.titan_species_iron_cub
            "chalk_sprite" -> R.string.titan_species_chalk_sprite
            "plate_tortoise" -> R.string.titan_species_plate_tortoise
            "kettle_ram" -> R.string.titan_species_kettle_ram
            "barbell_drake" -> R.string.titan_species_barbell_drake
            "atlas" -> R.string.titan_species_atlas
            else -> R.string.titan_species_unknown
        },
    )

/** What a locked place or piece of gear needs: "Level 20", "100 km", "8-week streak", "Legend rank". */
@Composable
fun UnlockCondition.needLabel(): String =
    when (this) {
        UnlockCondition.FromHatch -> stringResource(R.string.titan_owned)

        is UnlockCondition.Level ->
            if (level == LevelTitles.LEGEND.fromLevel) {
                stringResource(R.string.titan_need_legend)
            } else {
                stringResource(R.string.titan_need_level, level)
            }

        is UnlockCondition.DistanceKm -> stringResource(R.string.titan_need_km, kilometres)

        is UnlockCondition.StreakWeeks -> pluralStringResource(R.plurals.titan_need_streak, weeks, weeks)

        is UnlockCondition.SparsWon -> pluralStringResource(R.plurals.titan_need_spars, count, count)
    }
