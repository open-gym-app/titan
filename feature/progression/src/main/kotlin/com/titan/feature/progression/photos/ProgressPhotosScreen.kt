package com.titan.feature.progression.photos

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.titan.core.domain.model.ProgressPhoto
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.repository.PhotoSource
import com.titan.core.ui.component.EmptyState
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * The progress photo gallery, wired to its ViewModel and to the two ways a photo arrives: the
 * camera, through [PhotoCapture], and the system photo picker.
 *
 * Neither needs a permission. The capture intent runs in the camera app, which writes into the
 * one URI it is granted; the photo picker hands back the one image the user chose. The app never
 * asks to see the gallery, and never could see anything else in it.
 */
@Composable
fun ProgressPhotosRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProgressPhotosViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var capture by remember { mutableStateOf<File?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = capture
        capture = null
        if (saved && file != null && file.length() > 0) {
            viewModel.onEvent(
                ProgressPhotosEvent.PhotoPicked(PhotoSource { FileInputStream(file) }, cleanup = { file.delete() }),
            )
        } else {
            file?.delete()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onEvent(ProgressPhotosEvent.PhotoPicked(context.sourceOf(uri)))
    }

    ProgressPhotosScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onTakePhoto = {
            val (file, uri) = PhotoCapture.prepare(context)
            capture = file
            runCatching { camera.launch(uri) }.onFailure {
                // No camera app at all — a tablet, a locked-down work profile.
                capture = null
                viewModel.onEvent(ProgressPhotosEvent.PhotoUnavailable)
            }
        },
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = modifier,
    )
}

/** The picked image's bytes, opened afresh on every read, as [PhotoSource] requires. */
private fun Context.sourceOf(uri: android.net.Uri): PhotoSource =
    PhotoSource { contentResolver.openInputStream(uri) ?: throw IOException("the picked photo could not be opened") }

/**
 * Every progress photo, newest first, as a grid — with the viewer, the comparison and the delete
 * confirmation drawn over it.
 *
 * ### Comparing two photos
 *
 * The viewer's Compare puts the grid into picking mode, with a banner saying which photo it
 * started from: the next photo tapped opens the two side by side, older on the left. Picking from
 * the grid the user already knows beats a second, smaller chooser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressPhotosScreen(
    state: ProgressPhotosUiState,
    onEvent: (ProgressPhotosEvent) -> Unit,
    onBack: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val messageText = state.message?.let {
        stringResource(
            when (it) {
                ProgressPhotosMessage.ADD_FAILED -> R.string.photos_add_failed
                ProgressPhotosMessage.DELETE_FAILED -> R.string.photos_delete_failed
            },
        )
    }
    LaunchedEffect(state.message) {
        if (messageText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(messageText)
        onEvent(ProgressPhotosEvent.MessageShown)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.photos_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.progression_detail_back),
                        )
                    }
                },
                actions = {
                    AddPhotoAction(
                        enabled = !state.isAdding,
                        onTakePhoto = onTakePhoto,
                        onPickPhoto = onPickPhoto,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> Unit

            state.isEmpty -> EmptyState(
                title = stringResource(R.string.photos_empty_title),
                description = stringResource(R.string.photos_empty_body),
                modifier = Modifier.padding(innerPadding),
            )

            else -> PhotoGrid(state = state, onEvent = onEvent, contentPadding = innerPadding)
        }
    }

    state.viewing?.let { photo -> PhotoViewer(photo = photo, onEvent = onEvent) }
    state.comparison?.let { (older, newer) ->
        PhotoComparison(older = older, newer = newer, onClose = { onEvent(ProgressPhotosEvent.CompareCancelled) })
    }
    state.confirmingDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { onEvent(ProgressPhotosEvent.DeleteCancelled) },
            title = { Text(text = stringResource(R.string.photos_delete_title)) },
            text = { Text(text = stringResource(R.string.photos_delete_body, formatSessionDate(photo.takenAt))) },
            confirmButton = {
                TextButton(onClick = { onEvent(ProgressPhotosEvent.DeleteConfirmed) }) {
                    Text(text = stringResource(R.string.photos_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ProgressPhotosEvent.DeleteCancelled) }) {
                    Text(text = stringResource(R.string.photos_cancel))
                }
            },
        )
    }
}

/** The bar's add button, and the two ways to add behind it. */
@Composable
private fun AddPhotoAction(
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(imageVector = Icons.Outlined.AddAPhoto, contentDescription = stringResource(R.string.photos_add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.photos_take)) },
                leadingIcon = { Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null) },
                onClick = {
                    open = false
                    onTakePhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.photos_pick)) },
                leadingIcon = { Icon(imageVector = Icons.Outlined.PhotoLibrary, contentDescription = null) },
                onClick = {
                    open = false
                    onPickPhoto()
                },
            )
        }
    }
}

@Composable
private fun PhotoGrid(
    state: ProgressPhotosUiState,
    onEvent: (ProgressPhotosEvent) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(TitanTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        state.comparingFrom?.let { from ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                CompareBanner(from = from, onCancel = { onEvent(ProgressPhotosEvent.CompareCancelled) })
            }
        }
        // Under a heading per year, so each tile can carry the short `24 Apr` that fits its width
        // without losing which spring it was.
        state.photos.groupBy { Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).year }
            .forEach { (year, photos) ->
                item(key = "year-$year", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading(text = year.toString())
                }
                items(items = photos, key = ProgressPhoto::id) { photo ->
                    PhotoTile(
                        photo = photo,
                        isComparisonStart = photo.id == state.comparingFrom?.id,
                        onClick = { onEvent(ProgressPhotosEvent.PhotoTapped(photo.id)) },
                    )
                }
            }
    }
}

@Composable
private fun CompareBanner(
    from: ProgressPhoto,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TitanTheme.spacing.card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.photos_compare_hint, formatSessionDate(from.takenAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(text = stringResource(R.string.photos_cancel)) }
        }
    }
}

/** One photo, cropped to a portrait tile, with the day it was taken under it. */
@Composable
private fun PhotoTile(
    photo: ProgressPhoto,
    isComparisonStart: Boolean,
    onClick: () -> Unit,
) {
    val date = formatSessionDate(photo.takenAt)
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
        AsyncImage(
            model = File(photo.path),
            contentDescription = stringResource(R.string.photos_photo_description, date),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PORTRAIT)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                .then(
                    if (isComparisonStart) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onClick),
        )
        Text(
            text = formatDayAndMonth(photo.takenAt),
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // The image already says the full date to a screen reader, which has no year
            // heading in view to lean on; the short label would only repeat half of it.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** One photo, as large as the screen allows, with what can be done to it. */
@Composable
private fun PhotoViewer(
    photo: ProgressPhoto,
    onEvent: (ProgressPhotosEvent) -> Unit,
) {
    val date = formatSessionDate(photo.takenAt)
    Dialog(
        onDismissRequest = { onEvent(ProgressPhotosEvent.ViewerClosed) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(TitanTheme.spacing.screen),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            ) {
                Text(
                    text = date,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AsyncImage(
                    model = File(photo.path),
                    contentDescription = stringResource(R.string.photos_photo_description, date),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                    OutlinedButton(
                        onClick = { onEvent(ProgressPhotosEvent.CompareStarted(photo.id)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.photos_compare))
                    }
                    OutlinedButton(
                        onClick = { onEvent(ProgressPhotosEvent.DeleteRequested(photo.id)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.photos_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = { onEvent(ProgressPhotosEvent.ViewerClosed) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.photos_close))
                }
            }
        }
    }
}

/** Two photos side by side, older on the left, each with its date. */
@Composable
private fun PhotoComparison(
    older: ProgressPhoto,
    newer: ProgressPhoto,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(TitanTheme.spacing.screen),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
                ) {
                    listOf(older, newer).forEach { photo ->
                        val date = formatSessionDate(photo.takenAt)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
                        ) {
                            Text(
                                text = date,
                                style = TitanTheme.typography.numericSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            AsyncImage(
                                model = File(photo.path),
                                contentDescription = stringResource(R.string.photos_photo_description, date),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.photos_close))
                }
            }
        }
    }
}

/** Three across on a 360dp phone, and more on anything wider. */
private val TILE_MIN_WIDTH = 104.dp

/** The shape a progress photo is taken in. */
private const val PORTRAIT = 3f / 4f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Progress photos - empty", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun ProgressPhotosEmptyPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ProgressPhotosScreen(
            state = ProgressPhotosUiState(isLoading = false),
            onEvent = {},
            onBack = {},
            onTakePhoto = {},
            onPickPhoto = {},
        )
    }
}

@Preview(name = "Progress photos - comparing", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun ProgressPhotosComparingPreview() {
    val photos = List(PREVIEW_PHOTOS) { index ->
        ProgressPhoto(
            id = index + 1L,
            takenAt = PREVIEW_INSTANT - index * PREVIEW_WEEK,
            path = "",
            createdAt = PREVIEW_INSTANT,
        )
    }
    TitanTheme(theme = ThemePreference.DARK) {
        ProgressPhotosScreen(
            state = ProgressPhotosUiState(isLoading = false, photos = photos, comparingFrom = photos[2]),
            onEvent = {},
            onBack = {},
            onTakePhoto = {},
            onPickPhoto = {},
        )
    }
}

private const val PREVIEW_PHOTOS = 7
private const val PREVIEW_INSTANT = 1_777_000_000_000L
private const val PREVIEW_WEEK = 7 * 86_400_000L
