package com.deepbrowser.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.deepbrowser.tv.browser.Panel
import com.deepbrowser.tv.browser.UrlResolver
import com.deepbrowser.tv.data.Page

data class Tile(val name: String, val url: String, val color: Color, val featured: Boolean = false)

val defaultTiles = listOf(
    Tile("Movy", "https://www.movy.sx/", Palette.BrandTeal, featured = true),
    Tile("YouTube", "https://www.youtube.com/tv", Palette.BrandRed),
)

@Composable
fun HomeScreen(
    last: MutableState<String>, // survives this screen leaving composition: the tile focused last gets focus back
    bookmarks: List<Page>, onOpen: (String) -> Unit, onAddress: () -> Unit, onInfo: () -> Unit, onPanel: (Panel) -> Unit,
) {
    val start = remember { FocusRequester() }
    val firstPanel = remember { FocusRequester() } // Down from the full-width address bar goes to the first chip, not the one under its centre
    val initial = remember { last.value }
    LaunchedEffect(Unit) { start.requestWhenReady() }
    fun Modifier.slot(id: String): Modifier =
        onFocusChanged { if (it.isFocused) last.value = id }.then(if (id == initial) Modifier.focusRequester(start) else Modifier)

    Column(
        Modifier
            .fillMaxSize()
            .background(backdropBrush())
            .padding(overscanPadding())
            .restoringFocus(start),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(Sem.accent, CircleShape))
            Spacer(Modifier.size(Cmp.gap))
            Text("DeepBrowser", fontSize = Cmp.heading, fontWeight = FontWeight.SemiBold, color = Sem.onSurface)
        }
        Spacer(Modifier.height(Cmp.gap))

        // Address pill: opens the command bar
        GlassButton(
            onClick = onAddress,
            modifier = Modifier.fillMaxWidth().focusProperties { down = firstPanel }.slot("address"),
            radius = Cmp.barRadius,
            focusedScale = 1.04f,
            container = Cmp.barContainer,
            focusedContainer = Cmp.tileFocused,
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Search or type an address", fontSize = Cmp.bodyLarge, color = Sem.onSurface)
            }
        }
        Spacer(Modifier.height(Cmp.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap)) {
            listOf(Panel.Bookmarks, Panel.History, Panel.Tabs, Panel.Settings).forEach { p ->
                GlassButton(onClick = { onPanel(p) }, modifier = (if (p == Panel.Bookmarks) Modifier.focusRequester(firstPanel) else Modifier).slot("panel:${p.name}")) { Text(p.title, fontSize = Cmp.body, color = Sem.onSurface, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(Cmp.gap))
        Text("Quick access", fontSize = Cmp.body, color = Sem.onSurfaceMuted)
        Spacer(Modifier.height(Cmp.gap))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = Cmp.tileMinWidth),
            horizontalArrangement = Arrangement.spacedBy(Cmp.gap),
            verticalArrangement = Arrangement.spacedBy(Cmp.gap),
            contentPadding = PaddingValues(bottom = Cmp.gap, top = 12.dp, start = 12.dp, end = 12.dp),
        ) {
            items(defaultTiles, span = { t -> GridItemSpan(if (t.featured) 2 else 1) }) { tile ->
                GlassButton(onClick = { onOpen(tile.url) }, modifier = Modifier.fillMaxWidth().slot("tile:${tile.url}"), radius = Cmp.tileRadius, container = Cmp.tileContainer, focusedContainer = Cmp.tileFocused,
                    contentPadding = PaddingValues(0.dp)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = Cmp.tileMinHeight).padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(72.dp).background(tile.color, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(tile.name.take(1), fontSize = Cmp.heading, fontWeight = FontWeight.Bold, color = Sem.onSurface) }
                        Spacer(Modifier.height(12.dp))
                        Text(tile.name, fontSize = Cmp.body, color = Sem.onSurface)
                    }
                }
            }
            items(bookmarks) { b ->
                GlassButton(onClick = { onOpen(b.url) }, modifier = Modifier.fillMaxWidth().slot("bm:${b.url}"), radius = Cmp.tileRadius, container = Cmp.tileContainer, focusedContainer = Cmp.tileFocused,
                    contentPadding = PaddingValues(0.dp)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = Cmp.tileMinHeight).padding(vertical = 20.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(72.dp).background(Sem.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(b.title.ifBlank { "?" }.take(1).uppercase(), fontSize = Cmp.heading, fontWeight = FontWeight.Bold, color = Sem.background) }
                        Spacer(Modifier.height(12.dp))
                        Text(b.title.ifBlank { UrlResolver.displayHost(b.url) }, fontSize = Cmp.body, color = Sem.onSurface, maxLines = 1)
                    }
                }
            }
            item {
                GlassButton(onClick = onInfo, modifier = Modifier.fillMaxWidth().slot("info"), radius = Cmp.tileRadius, container = Cmp.tileContainer, focusedContainer = Cmp.tileFocused,
                    contentPadding = PaddingValues(0.dp)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = Cmp.tileMinHeight).padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(72.dp).background(Sem.outline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("i", fontSize = Cmp.heading, fontWeight = FontWeight.Bold, color = Sem.onSurface) }
                        Spacer(Modifier.height(12.dp))
                        Text("This device", fontSize = Cmp.body, color = Sem.onSurface)
                    }
                }
            }
        }
    }
}
