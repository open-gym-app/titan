package com.titan.core.ui.component

import androidx.compose.runtime.Immutable

/**
 * One fact: an eyebrow and the word under it. `EQUIPMENT` / `Barbell`.
 *
 * @param value already resolved to what the user reads. [MetaGrid] renders strings because
 *   the things it lays out have nothing else in common — one cell is an enum's label, the
 *   next is a formatted weight, the next a count.
 */
@Immutable
data class MetaEntry(
    val label: String,
    val value: String,
)
