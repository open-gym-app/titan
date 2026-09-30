package com.titan.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The grab bar at the top of every one of the app's bottom sheets.
 *
 * Material's own, with the room around it made real. `BottomSheetDefaults.DragHandle` is a
 * 32dp pill that the sheet makes tappable — a tap expands or collapses — and 32dp is what
 * Accessibility Scanner then measures it at, an error on every sheet. Sat in a 48dp box, the
 * pill is drawn exactly as before and the sheet's tap target is the box.
 *
 * Every `ModalBottomSheet` passes this as its `dragHandle`, so the fix is in one place; a
 * sheet that used the default again would fail its preview's accessibility check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDragHandle(modifier: Modifier = Modifier) {
    Box(modifier = modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        BottomSheetDefaults.DragHandle()
    }
}
