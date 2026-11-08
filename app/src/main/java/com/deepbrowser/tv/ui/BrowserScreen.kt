package com.deepbrowser.tv.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.deepbrowser.tv.browser.BrowserController
import kotlin.math.roundToInt

@Composable
fun BrowserScreen(c: BrowserController) {
    LaunchedEffect(c) { // two drawn frames, then the heavy start-up (WebView/Chromium) off the first-paint path
        withFrameNanos { }; withFrameNanos { }
        c.warmUp()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .onSizeChanged { c.onSizeChanged(it.width, it.height) },
    ) {
        // Home draws without any WebView. The WebView is built after the first frames (warmUp) or on first navigation.
        if (c.webReady) {
            val wv = c.webView
            key(wv) { // a different tab = a different WebView
                AndroidView(
                    factory = {
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv
                    },
                    update = { it.visibility = if (c.isHome) View.INVISIBLE else View.VISIBLE },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (c.isHome) {
            HomeScreen(
                bookmarks = c.bookmarks,
                onOpen = c::loadInput,
                onAddress = { c.commandBarVisible = true },
                onInfo = { c.infoVisible = true },
                onPanel = c::openPanel,
            )
        }

        val ring = c.focusRect
        if (ring != null && !c.isHome && !c.cursorEnabled && !c.commandBarVisible && !c.infoVisible && c.panel == null) {
            val r by animateRectAsState(ring, spring(dampingRatio = 0.85f, stiffness = 900f), label = "focusRing")
            val d = LocalDensity.current
            val pad = 4.dp
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .offset { IntOffset(r.left.roundToInt() - pad.roundToPx(), r.top.roundToInt() - pad.roundToPx()) }
                    .size(with(d) { r.width.toDp() + pad * 2 }, with(d) { r.height.toDp() + pad * 2 })
                    .background(Palette.Accent.copy(alpha = 0.14f), shape)
                    .border(4.dp, Palette.Accent, shape),
            )
        }

        if (!c.isHome && c.cursorEnabled && !c.commandBarVisible && !c.infoVisible && c.panel == null) {
            val r = 14.dp
            Box(
                Modifier
                    .offset { IntOffset(c.cursorPos.x.roundToInt() - r.roundToPx(), c.cursorPos.y.roundToInt() - r.roundToPx()) }
                    .size(r * 2)
                    .background(Color.White.copy(alpha = 0.88f), CircleShape)
                    .border(3.dp, Color(0xCC0B1020), CircleShape),
            )
        }

        if (c.progress in 0..99 && !c.isHome) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(c.progress / 100f)
                    .height(5.dp)
                    .background(Palette.Accent),
            )
        }

        c.panel?.let { PanelScreen(c, it) }

        AnimatedVisibility(
            visible = c.commandBarVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
        ) { CommandBar(c) }

        AnimatedVisibility(
            visible = c.infoVisible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .padding(48.dp)
                    .glass(radius = 32, strong = true)
                    .padding(40.dp),
            ) {
                androidx.compose.foundation.layout.Column {
                    Text("This device", fontSize = 30.sp, color = Color.White)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    Text(c.capabilities.summary(), fontSize = 22.sp, color = Color.White.copy(alpha = 0.85f))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    Text("Press Back to close", fontSize = 18.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}
