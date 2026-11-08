package com.deepbrowser.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface

/** A focusable opaque button: scales up, shows a 4dp accent ring and a lift glow when the remote lands on it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = Cmp.chipRadius,
    focusedScale: Float = Cmp.focusScale,
    container: Color = Cmp.chipContainer,
    focusedContainer: Color = Cmp.chipFocused,
    contentPadding: PaddingValues = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Sem.onSurface,
            focusedContainerColor = focusedContainer,
            focusedContentColor = Sem.onSurface,
            pressedContainerColor = focusedContainer,
            pressedContentColor = Sem.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Sem.accent.copy(alpha = 0.35f), Cmp.focusLift)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(Cmp.restBorder, Sem.outline), shape = shape),
            focusedBorder = Border(BorderStroke(Cmp.focusBorder, Sem.focus), shape = shape),
        ),
    ) {
        Box(Modifier.padding(contentPadding), contentAlignment = Alignment.Center) { content() }
    }
}
