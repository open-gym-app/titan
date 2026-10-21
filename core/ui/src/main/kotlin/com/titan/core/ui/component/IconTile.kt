package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.TitanTheme

/**
 * An icon on a tinted square — the app's way of putting a picture next to a line of text.
 *
 * A bare [Icon] beside a label reads as a control that can be pressed; the same icon inside a
 * filled tile reads as a *mark* for the row it labels, which is what a feature list, a chosen
 * option and a recap line all want. It is one component rather than a shape drawn at each call
 * site so that the tile, the radius and the icon's share of it cannot drift between the three
 * screens that use it.
 *
 * **It is decorative and says nothing.** The icon carries no content description: every caller
 * draws it beside the words it illustrates, and a screen reader that announced "fitness centre"
 * before "Log sets" would be reading the same fact twice, badly. A caller that needs a tile to
 * *mean* something on its own is drawing a button, not this.
 *
 * @param size the tile's side. [ICON_TILE_SIZE] beside a line of text; [ICON_TILE_HERO_SIZE]
 *   for the single large mark at the top of a screen. The icon takes half of it, so the glyph
 *   keeps the same share of the tile at either end.
 * @param shape defaults to `shapes.small`, the button-and-card radius. Pass `CircleShape` for
 *   a hero mark that is announcing an outcome rather than labelling a row.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = ICON_TILE_SIZE,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier = modifier.size(size).background(color = container, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size / 2),
        )
    }
}

/** The tile that sits beside a line of text — a shade taller than the two lines it labels. */
val ICON_TILE_SIZE: Dp = 40.dp

/**
 * The single large mark at the top of a screen.
 *
 * Only one of these belongs on a screen: it is the thing the eye lands on first, and a second
 * one makes both of them ordinary — the same rule the accent itself is under.
 */
val ICON_TILE_HERO_SIZE: Dp = 64.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Icon tile")
@Composable
fun IconTilePreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        IconTile(icon = Icons.Outlined.FitnessCenter)
    }
}

@Preview(name = "Icon tile, hero, dark")
@Composable
fun IconTileHeroDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        IconTile(icon = Icons.Outlined.FitnessCenter, size = ICON_TILE_HERO_SIZE)
    }
}
