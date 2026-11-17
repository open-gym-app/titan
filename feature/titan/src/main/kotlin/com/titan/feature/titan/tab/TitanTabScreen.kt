package com.titan.feature.titan.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.GearEntry
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital
import com.titan.core.ui.component.EmptyState
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.TitanDialog
import com.titan.core.ui.component.TitanTabRow
import com.titan.core.ui.component.titan.TitanActivityOption
import com.titan.core.ui.component.titan.TitanEffectChip
import com.titan.core.ui.component.titan.TitanIcons
import com.titan.core.ui.component.titan.TitanItemState
import com.titan.core.ui.component.titan.TitanItemTile
import com.titan.core.ui.component.titan.TitanOptionState
import com.titan.core.ui.component.titan.TitanSprite
import com.titan.core.ui.component.titan.TitanVitalRow
import com.titan.core.ui.component.titan.foodLabel
import com.titan.core.ui.component.titan.label
import com.titan.core.ui.component.titan.needLabel
import com.titan.core.ui.component.titan.petSpeciesLabel
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import com.titan.core.ui.theme.vital
import com.titan.feature.titan.R
import com.titan.core.ui.R as UiR

@Composable
fun TitanTabRoute(
    onOpenInventory: () -> Unit,
    onOpenSpar: () -> Unit,
    viewModel: TitanTabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TitanTabScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenInventory = onOpenInventory,
        onOpenSpar = onOpenSpar,
    )
}

/**
 * The Titan tab (canvas *Titan tab*): header, stage card, "What next", vitals, supplies. With no
 * titan yet, what a titan is and the eggs waiting to hatch.
 */
@Composable
fun TitanTabScreen(
    state: TitanTabUiState,
    onEvent: (TitanTabEvent) -> Unit,
    onOpenInventory: () -> Unit,
    onOpenSpar: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (state) {
            TitanTabUiState.Loading -> Box(Modifier.fillMaxSize())
            is TitanTabUiState.NoTitan -> NoTitan(state, onEvent)
            is TitanTabUiState.Titan -> TitanHome(state, onEvent, onOpenInventory, onOpenSpar)
        }
    }
}

@Composable
private fun TitanHome(
    state: TitanTabUiState.Titan,
    onEvent: (TitanTabEvent) -> Unit,
    onOpenInventory: () -> Unit,
    onOpenSpar: () -> Unit,
) {
    val name = state.name ?: petSpeciesLabel(state.speciesId)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TitanTheme.spacing.screen)
            .padding(bottom = TitanTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        Header(
            title = stringResource(R.string.titan_tab_title),
            renameLabel = stringResource(R.string.titan_rename, name),
            onRename = { onEvent(TitanTabEvent.StartRename) },
        )
        StageCard(state, name)
        WhatNext(state, onEvent, onOpenSpar)
        Vitals(state)
        Supplies(state, name, onEvent, onOpenInventory)
    }
    state.rename?.let { RenameDialog(it, name, onEvent) }
}

@Composable
private fun Header(
    title: String,
    renameLabel: String,
    onRename: () -> Unit,
    showRename: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showRename) {
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.Edit, contentDescription = renameLabel)
            }
        }
    }
}

@Composable
private fun StageCard(
    state: TitanTabUiState.Titan,
    name: String,
) {
    val status = stringResource(state.activity.status())
    val note = stringResource(state.activity.note())
    val noteLine = state.minutesLeft?.let {
        stringResource(
            UiR.string.badge_with_qualifier,
            note,
            pluralStringResource(UiR.plurals.titan_minutes_left, it, it),
        )
    } ?: note
    TitanCard {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.titan_header, name, state.level).uppercase(),
                    style = TitanTheme.typography.eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.titan_power, state.power).uppercase(),
                    style = TitanTheme.typography.eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TitanSprite(
                activity = state.activity,
                head = state.head,
                face = state.face,
                contentDescription = stringResource(R.string.titan_stage_description, name, status),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(STAGE_HEIGHT.dp)
                    .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Text(
                        noteLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.lastEffect?.let { effect ->
                    effect.moved.firstOrNull { it == TitanVital.FUEL }?.let { TitanEffectChip(it, effect.change(it)) }
                }
            }
        }
    }
}

@Composable
private fun WhatNext(
    state: TitanTabUiState.Titan,
    onEvent: (TitanTabEvent) -> Unit,
    onOpenSpar: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg)) {
        SectionHeading(stringResource(R.string.titan_what_next))
        WHAT_NEXT.chunked(COLUMNS_WHAT_NEXT).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                row.forEach { activity ->
                    val optionState = when {
                        activity == state.activity -> TitanOptionState.Now
                        activity.energyCost > state.vitals.energy -> TitanOptionState.Unaffordable(activity.energyCost)
                        else -> TitanOptionState.Available(activity.energyCost)
                    }
                    TitanActivityOption(
                        activity = activity,
                        state = optionState,
                        onClick = {
                            if (activity == TitanActivity.SPAR) {
                                onOpenSpar()
                            } else {
                                onEvent(
                                    TitanTabEvent.Choose(activity),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Vitals(state: TitanTabUiState.Titan) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg)) {
        SectionHeading(stringResource(R.string.titan_vitals))
        TitanCard {
            Column(modifier = Modifier.padding(horizontal = TitanTheme.spacing.card)) {
                TitanVital.entries.forEachIndexed { index, vital ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TitanVitalRow(vital, state.vitals[vital], alert = state.alert == vital)
                }
            }
        }
    }
}

@Composable
private fun Supplies(
    state: TitanTabUiState.Titan,
    name: String,
    onEvent: (TitanTabEvent) -> Unit,
    onOpenInventory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg)) {
        SectionHeading(stringResource(R.string.titan_supplies)) {
            TextButton(onClick = onOpenInventory, modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)) {
                Text(stringResource(R.string.titan_inventory).uppercase(), style = TitanTheme.typography.eyebrow)
            }
        }
        TitanTabRow(
            labels = listOf(
                R.string.titan_tab_food,
                R.string.titan_tab_gear,
                R.string.titan_tab_habitat,
            ).map { stringResource(it) },
            selectedIndex = state.tab.ordinal,
            onSelect = { onEvent(TitanTabEvent.SelectTab(SuppliesTab.entries[it])) },
        )
        val tiles: List<@Composable (Modifier) -> Unit> = when (state.tab) {
            SuppliesTab.FOOD -> state.pantry.map { entry -> { modifier -> FoodTile(entry, onEvent, modifier) } }

            SuppliesTab.GEAR -> state.gear.map { entry -> { modifier -> GearTile(entry, onEvent, modifier) } }

            SuppliesTab.HABITAT -> state.places.map { (place, owned) ->
                { modifier ->
                    PlaceTile(
                        place,
                        owned,
                        modifier,
                    )
                }
            }
        }
        tiles.chunked(COLUMNS_SUPPLIES).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                repeat(COLUMNS_SUPPLIES - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Text(
            when (state.tab) {
                SuppliesTab.FOOD -> stringResource(R.string.titan_hint_food, name)
                SuppliesTab.GEAR -> stringResource(R.string.titan_hint_gear, name)
                SuppliesTab.HABITAT -> stringResource(R.string.titan_hint_habitat)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FoodTile(
    entry: PantryEntry,
    onEvent: (TitanTabEvent) -> Unit,
    modifier: Modifier,
) {
    val fuel = TitanTheme.vital(TitanVital.FUEL)
    TitanItemTile(
        icon = TitanIcons.food(entry.food.id),
        name = foodLabel(entry.food.id),
        detail = "×${entry.count}",
        spokenDetail = pluralStringResource(R.plurals.titan_food_left, entry.count, entry.count),
        state = when {
            entry.count == 0 -> TitanItemState.EMPTY
            entry.isNew -> TitanItemState.NEW
            else -> TitanItemState.STOCKED
        },
        container = fuel.container,
        content = fuel.content,
        onClick = { if (entry.count > 0) onEvent(TitanTabEvent.Feed(entry.food.id)) },
        modifier = modifier,
    )
}

@Composable
private fun GearTile(
    entry: GearEntry,
    onEvent: (TitanTabEvent) -> Unit,
    modifier: Modifier,
) {
    val detail = when {
        entry.worn -> stringResource(UiR.string.titan_item_worn)
        entry.owned -> stringResource(R.string.titan_gear_wear)
        else -> entry.gear.condition.needLabel()
    }
    TitanItemTile(
        icon = TitanIcons.of(entry.gear),
        name = entry.gear.label(),
        detail = detail,
        spokenDetail = detail,
        state = when {
            entry.worn -> TitanItemState.WORN
            entry.owned -> TitanItemState.STOCKED
            else -> TitanItemState.EMPTY
        },
        container = MaterialTheme.colorScheme.surfaceContainerHighest,
        content = MaterialTheme.colorScheme.onSurface,
        onClick = { if (entry.owned) onEvent(TitanTabEvent.ToggleGear(entry.gear.key)) },
        modifier = modifier,
    )
}

@Composable
private fun PlaceTile(
    place: TitanPlace,
    owned: Boolean,
    modifier: Modifier,
) {
    val energy = TitanTheme.vital(TitanVital.ENERGY)
    val detail = if (owned) stringResource(UiR.string.titan_owned) else place.condition.needLabel()
    TitanItemTile(
        icon = TitanIcons.of(place),
        name = place.label(),
        detail = detail,
        spokenDetail = detail,
        state = if (owned) TitanItemState.STOCKED else TitanItemState.EMPTY,
        container = energy.container,
        content = energy.content,
        onClick = {},
        modifier = modifier,
    )
}

@Composable
private fun RenameDialog(
    rename: TitanTabUiState.RenameState,
    name: String,
    onEvent: (TitanTabEvent) -> Unit,
) {
    val error = when (rename.refusal) {
        PetNicknameResult.TooLong -> stringResource(R.string.titan_rename_too_long)
        PetNicknameResult.ControlCharacters -> stringResource(R.string.titan_rename_control)
        else -> null
    }
    TitanDialog(
        onDismissRequest = { onEvent(TitanTabEvent.CancelRename) },
        confirmButton = {
            TextButton(
                onClick = { onEvent(TitanTabEvent.SaveRename) },
            ) { Text(stringResource(R.string.titan_rename_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(TitanTabEvent.CancelRename) },
            ) { Text(stringResource(R.string.titan_rename_cancel)) }
        },
        title = { Text(stringResource(R.string.titan_rename_title, name)) },
        text = {
            OutlinedTextField(
                value = rename.draft,
                onValueChange = { onEvent(TitanTabEvent.EditRename(it)) },
                label = { Text(stringResource(R.string.titan_rename_field)) },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.titan_rename_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun NoTitan(
    state: TitanTabUiState.NoTitan,
    onEvent: (TitanTabEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        Header(title = stringResource(R.string.titan_tab_title), renameLabel = "", onRename = {}, showRename = false)
        EmptyState(
            title = stringResource(R.string.titan_none_title),
            description = stringResource(R.string.titan_none_body),
        )
        if (state.eggs.isNotEmpty()) {
            SectionHeading(stringResource(R.string.titan_eggs))
            state.eggs.forEach { egg ->
                EggRow(
                    egg,
                    enabled = !state.hatching,
                    onHatch = { onEvent(TitanTabEvent.Hatch(egg.id)) },
                )
            }
        }
    }
}

@Composable
private fun EggRow(
    egg: CollectedItem,
    enabled: Boolean,
    onHatch: () -> Unit,
) {
    val found = formatSessionDate(egg.acquiredAt.toEpochMilli())
    TitanCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TitanTheme.spacing.card),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            Icon(TitanIcons.food("eggs"), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.titan_egg), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.titan_egg_found, found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onHatch, enabled = enabled) { Text(stringResource(R.string.titan_hatch)) }
        }
    }
}

private fun TitanActivity.status(): Int =
    when (this) {
        TitanActivity.IDLE -> R.string.titan_status_idle
        TitanActivity.WALK -> R.string.titan_status_walk
        TitanActivity.RUN -> R.string.titan_status_run
        TitanActivity.SWIM -> R.string.titan_status_swim
        TitanActivity.TRAIN -> R.string.titan_status_train
        TitanActivity.SPAR -> R.string.titan_status_spar
        TitanActivity.REST -> R.string.titan_status_rest
        TitanActivity.EAT -> R.string.titan_status_eat
    }

private fun TitanActivity.note(): Int =
    when (this) {
        TitanActivity.IDLE -> R.string.titan_note_idle
        TitanActivity.WALK -> R.string.titan_note_walk
        TitanActivity.RUN -> R.string.titan_note_run
        TitanActivity.SWIM -> R.string.titan_note_swim
        TitanActivity.TRAIN -> R.string.titan_note_train
        TitanActivity.SPAR -> R.string.titan_note_spar
        TitanActivity.REST -> R.string.titan_note_rest
        TitanActivity.EAT -> R.string.titan_note_eat
    }

private const val STAGE_HEIGHT = 190
private const val COLUMNS_WHAT_NEXT = 3
private const val COLUMNS_SUPPLIES = 4
