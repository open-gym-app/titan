package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.titan.core.domain.model.Exercise
import com.titan.core.ui.theme.TitanTheme

/**
 * The photo bundled with an exercise, over the letter it falls back to.
 *
 * ## Why the letter is always drawn
 *
 * The initial is not a placeholder that the image replaces — it is *under* the image, drawn
 * unconditionally, and the photo covers it when it arrives. That is one composable and one
 * layout for four states (no image, loading, loaded, failed) instead of a `SubcomposeAsyncImage`
 * with a branch per state, and it means the row never changes height or reflows as images
 * settle in a scrolling list.
 *
 * ## Where the image comes from
 *
 * [Exercise.icon] holds an *asset-relative* path — `seed/images/Bench_Press.webp` — written by
 * the seed generator (PERSISTENCE.md § Seeded catalogue). Coil needs a URI, and
 * `file:///android_asset/` is the scheme that reaches an APK's assets, so the join happens
 * here rather than in every screen that shows an exercise. `:core:ui` cannot see `:core:data`
 * and does not need to: the path is a string on a domain model by the time it arrives.
 *
 * A `null` icon is the ordinary case for a user-authored exercise, and it simply leaves the
 * letter showing.
 */
@Composable
fun ExerciseImage(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    size: Dp = ExerciseImageDefaults.LIST_SIZE,
) {
    ExerciseImageBox(
        exercise = exercise,
        letterStyle = TitanTheme.typography.numeric,
        modifier = modifier.size(size),
    )
}

/**
 * The same photo as the banner an exercise's own screen opens with.
 *
 * Full width at the source images' own 3:2, so the seeded photo is drawn at its aspect ratio
 * rather than cropped to a shape chosen here — the subject of a form demo is a person, and a
 * 16:9 band across one of these takes their head off.
 *
 * The letter fallback underneath is the same one the thumbnail draws, at display size. A
 * caller with no photo to show should usually **draw nothing at all** rather than a
 * full-width box with an initial in it: see the exercise detail screen, which omits the
 * banner for a user-authored exercise instead of opening on 200dp of empty. This composable
 * still falls back rather than failing, because "the file is missing" and "there is no file"
 * are not distinguishable until Coil has tried.
 */
@Composable
fun ExerciseHeroImage(
    exercise: Exercise,
    modifier: Modifier = Modifier,
) {
    ExerciseImageBox(
        exercise = exercise,
        letterStyle = TitanTheme.typography.numericLarge,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ExerciseImageDefaults.HERO_ASPECT_RATIO),
    )
}

/**
 * The letter, and the photo over it — the whole of what both shapes above are.
 *
 * Everything that differs between a 44dp thumbnail and a full-width banner is in the
 * modifier the caller has already applied, which is why this takes no size of its own.
 */
@Composable
private fun ExerciseImageBox(
    exercise: Exercise,
    letterStyle: TextStyle,
    modifier: Modifier,
) {
    val shape = MaterialTheme.shapes.extraSmall
    // Bound to a local: `icon` is a `val` on a class from another module, which Kotlin will
    // not smart-cast, and the alternative is `!!` on a value already checked.
    val icon = exercise.icon

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = exercise.initial(),
            style = letterStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The letter is a drawing of an absent photo, not a word. Left in the tree it
            // merges into the row that wraps this — "B, Barbell bench press, Barbell" — and
            // it stays there after the photo has covered it, since it is never removed.
            modifier = Modifier.clearAndSetSemantics { },
        )
        if (icon != null) {
            AsyncImage(
                model = assetUri(icon),
                // Null, not the exercise name: the name is drawn beside this in every caller,
                // and a screen reader that reads it twice is reading noise the second time.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Sizes the shared exercise components draw the image at. */
object ExerciseImageDefaults {

    /** In a list row: big enough to tell a bench from a squat rack at a glance. */
    val LIST_SIZE: Dp = 44.dp

    /** On a card, where the image sits beside a name, a meta line and a muscle line. */
    val CARD_SIZE: Dp = 60.dp

    /**
     * The banner's shape: 3:2, which is what the seeded photos are (400×267), so
     * `ContentScale.Crop` fits them exactly and crops nothing.
     */
    const val HERO_ASPECT_RATIO = 3f / 2f
}

/** The letter shown when there is no photo. See [initialGrapheme]. */
internal fun Exercise.initial(): String = name.initialGrapheme()

private fun assetUri(assetPath: String): String = "$ANDROID_ASSET_SCHEME$assetPath"

private const val ANDROID_ASSET_SCHEME = "file:///android_asset/"
