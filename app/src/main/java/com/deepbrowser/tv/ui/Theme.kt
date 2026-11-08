package com.deepbrowser.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Layer 1, primitives: raw colour values only; nothing outside this file may use a hex literal. */
object Palette {
    val Ink = Color(0xFF0B1020)
    val Indigo = Color(0xFF1B2556)
    val Violet = Color(0xFF3B2A7A)
    val Accent = Color(0xFF8FB4FF)
    val Glass = Color.White
    val Navy = Color(0xFF121A3A)
    val NavyLift = Color(0xFF26337A)
    val Snow = Color.White
    // Brand dots, darkened so white initials hold >= 4.5:1.
    val BrandTeal = Color(0xFF00796B)
    val BrandRed = Color(0xFFC62828)
    val BrandOrange = Color(0xFFD84315)
    val BrandSlate = Color(0xFF546E7A)
    val BrandPurple = Color(0xFF7C4DFF)
    val BrandRust = Color(0xFFBF360C)
    val BrandPink = Color(0xFFC2185B)
    val BrandBlue = Color(0xFF0277BD)
    val BrandIndigo = Color(0xFF3D5AFE)
}

/** Layer 2, semantic roles: what a colour means (surface, text, accent, focus, scrim); components reference these. */
object Sem {
    val background = Palette.Ink
    val surface = Palette.Navy
    val surfaceRaised = Palette.NavyLift
    val onSurface = Palette.Snow
    val onSurfaceMuted = Palette.Snow.copy(alpha = 0.80f)
    val accent = Palette.Accent
    val focus = Palette.Accent
    val outline = Palette.Snow.copy(alpha = 0.30f)
    val scrim = Palette.Ink.copy(alpha = 0.72f)
}

/** Layer 3, component tokens: sizes, spacing, focus motion and per-component colours for tile, bar and chip. */
object Cmp {
    // 10-foot type scale (sp): body >= 24, headings >= 40.
    val body = 24.sp
    val bodyLarge = 28.sp
    val heading = 40.sp
    // Focus: scale 1.06-1.1, 4dp accent ring, lift glow; Surface animates scale/glow (~150ms).
    const val focusScale = 1.08f
    val focusBorder = 4.dp
    val restBorder = 1.dp
    val focusLift = 12.dp
    // Layout
    val gap = 24.dp
    val tileMinWidth = 230.dp
    val tileMinHeight = 140.dp
    val tileRadius = 28.dp
    val barRadius = 32.dp
    val chipRadius = 24.dp
    val tileContainer = Sem.surface
    val tileFocused = Sem.surfaceRaised
    val barContainer = Sem.surface
    val chipContainer = Sem.surface
    val chipFocused = Sem.surfaceRaised
    const val OVERSCAN = 0.05f
}

/** 5% overscan safe margin on every edge, derived from the real screen size. */
@Composable
fun overscanPadding(): PaddingValues {
    val c = LocalConfiguration.current
    return PaddingValues(horizontal = (c.screenWidthDp * Cmp.OVERSCAN).dp, vertical = (c.screenHeightDp * Cmp.OVERSCAN).dp)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DeepBrowserTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sem.accent,
            background = Sem.background,
            surface = Sem.surface,
            onSurface = Sem.onSurface,
        ),
        content = content,
    )
}

/** Opaque raised panel (text-safe): solid surface + soft outline. Signature kept; `strong` raises the fill. */
fun Modifier.glass(radius: Int = 28, strong: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .background(if (strong) Sem.surface else Sem.surface, shape)
        .border(BorderStroke(Cmp.restBorder, Sem.outline), shape)
}

fun backdropBrush(): Brush = Brush.linearGradient(listOf(Palette.Ink, Palette.Indigo, Palette.Violet))
