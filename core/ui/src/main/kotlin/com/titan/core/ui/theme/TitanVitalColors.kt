package com.titan.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.titan.core.domain.model.TitanVital

/**
 * A vital's two colours: the container its icon and chip sit on, and the content drawn on it.
 *
 * The vitals **reuse the quest families** rather than adding hues of their own: fuel is the
 * records' amber, spirit the volume's violet. The accent stays
 * reserved for the titan's feet, its sparks and a low vital.
 */
data class TitanVitalColors(
    val container: Color,
    val content: Color,
)

/** [vital]'s colours in the current theme. */
@Composable
@ReadOnlyComposable
fun TitanTheme.vital(vital: TitanVital): TitanVitalColors {
    val quest = quest
    return when (vital) {
        TitanVital.FUEL -> TitanVitalColors(quest.records, quest.onRecords)
        TitanVital.SPIRIT -> TitanVitalColors(quest.volume, quest.onVolume)
    }
}
