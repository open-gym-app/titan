package com.titan.feature.titan.tab

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearEntry
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import com.titan.core.ui.theme.TitanTheme
import java.time.Instant

// ─── Previews ───────────────────────────────────────────────────────────────────────────

internal fun previewTitan(tab: SuppliesTab = SuppliesTab.FOOD) =
    TitanTabUiState.Titan(
        petId = 1,
        speciesId = "iron_cub",
        name = "Brick",
        level = 14,
        power = 48,
        activity = TitanActivity.TRAIN,
        vitals = TitanVitals(fuel = 22, spirit = 55),
        alert = TitanVital.FUEL,
        head = "headband",
        face = null,
        tab = tab,
        pantry = Collectibles.FOOD.mapIndexed { i, food ->
            PantryEntry(food, count = PREVIEW_STOCK[i], isNew = food.id == "steak", newestFrom = null)
        },
        slots = GearSlot.entries.map { slot ->
            SlotEntry(
                slot = slot,
                worn = Gear.HEADBAND.takeIf { slot == GearSlot.HEAD },
                owned = PREVIEW_GEAR.filter { it.slot == slot }.map { GearEntry(it, worn = it == Gear.HEADBAND) },
            )
        },
        slot = GearSlot.HEAD,
        places = TitanPlace.STARTING.toList() + TitanPlace.SAUNA,
        habitat = TitanPlace.RACK,
    )

private val PREVIEW_GEAR = listOf(Gear.HEADBAND, Gear.CROWN, Gear.CAPE)

@Preview(name = "Titan tab", widthDp = 390, heightDp = 1200)
@Composable
fun TitanTabPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) {
        TitanTabScreen(previewTitan(), {}, {})
    }
}

@Preview(name = "Titan tab, dark, gear", widthDp = 390, heightDp = 1200)
@Composable
fun TitanTabDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, reducedMotion = true) {
        TitanTabScreen(previewTitan(SuppliesTab.GEAR), {}, {})
    }
}

@Preview(name = "Titan tab, habitat, twice the font", widthDp = 390, heightDp = 2400, fontScale = 2f)
@Composable
fun TitanTabLargeFontPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) {
        TitanTabScreen(previewTitan(SuppliesTab.HABITAT), {}, {})
    }
}

@Preview(name = "Titan tab, no titan, an egg", widthDp = 390, heightDp = 700)
@Composable
fun TitanTabNoTitanPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) {
        TitanTabScreen(
            TitanTabUiState.NoTitan(
                eggs = listOf(CollectedItem(1, "pet_egg", "session_finished", Instant.parse("2026-11-12T18:00:00Z"))),
            ),
            {},
            {},
        )
    }
}

/** The canvas's pantry, food by food. */
private val PREVIEW_STOCK = listOf(1, 0, 3, 6, 4, 0, 2, 2)
