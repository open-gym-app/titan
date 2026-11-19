package com.titan.core.ui.component.titan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital
import com.titan.core.ui.R

/*
 * The titan's vocabulary, labelled once in `:core:ui` because the tab, the dock, the sheet, the
 * and the inventory all draw it — the rule DomainLabels.kt states. Each id this
 * build cannot name has an "unknown" answer rather than a throw.
 */

@Composable
@ReadOnlyComposable
fun TitanVital.label(): String =
    stringResource(
        when (this) {
            TitanVital.FUEL -> R.string.titan_vital_fuel
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

/** The slot's name, in sentence case. */
@Composable
@ReadOnlyComposable
fun GearSlot.label(): String =
    stringResource(
        when (this) {
            GearSlot.HEAD -> R.string.titan_slot_head
            GearSlot.FACE -> R.string.titan_slot_face
            GearSlot.HANDS -> R.string.titan_slot_hands
            GearSlot.NECK -> R.string.titan_slot_neck
            GearSlot.BACK -> R.string.titan_slot_back
            GearSlot.CHEST -> R.string.titan_slot_chest
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
