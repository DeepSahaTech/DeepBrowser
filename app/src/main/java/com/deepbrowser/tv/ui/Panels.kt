package com.deepbrowser.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.deepbrowser.tv.browser.AUTO_UPDATE_DAYS
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.deepbrowser.tv.browser.BrowserController
import com.deepbrowser.tv.browser.FilterUpdater
import com.deepbrowser.tv.browser.MAX_TABS
import com.deepbrowser.tv.browser.Panel
import com.deepbrowser.tv.browser.UrlResolver
import com.deepbrowser.tv.data.Library
import com.deepbrowser.tv.data.Page

/** Full-screen list screens (bookmarks, history, tabs, settings). Back closes (handled in BrowserController.handleKey). */
@Composable
fun PanelScreen(c: BrowserController, panel: Panel) {
    val first = remember(panel) { FocusRequester() }
    val rows = rememberRowFocus()
    val editing = remember(panel) { mutableStateOf(false) } // the filter-link field is open
    LaunchedEffect(panel) { first.requestWhenReady() }
    Column(Modifier.fillMaxSize().background(backdropBrush()).padding(overscanPadding())) {
        Text(panel.title, fontSize = Cmp.heading, fontWeight = FontWeight.SemiBold, color = Sem.onSurface)
        Spacer(Modifier.height(Cmp.gap))
        // One list per panel: focus stays inside it, lands on the first row, and every focused row is scrolled into view.
        LazyColumn(
            Modifier.fillMaxSize().focusTrap().restoringFocus(first),
            contentPadding = rowPadding,
            verticalArrangement = Arrangement.spacedBy(Cmp.gap / 2),
        ) {
            when (panel) {
                Panel.Bookmarks -> bookmarkRows(c, c.library, first, rows)
                Panel.History -> historyRows(c, c.library, first)
                Panel.Tabs -> tabRows(c, first, rows)
                Panel.Settings -> settingRows(c, c.filters, first, rows, editing)
            }
        }
    }
}

private val rowPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp)
private const val ROW_SCALE = 1.03f // wide rows: keep the focus scale inside the list's clip bounds

@Composable
private fun Label(text: String, muted: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, fontSize = Cmp.body, color = if (muted) Sem.onSurfaceMuted else Sem.onSurface, maxLines = 1, modifier = modifier)

@Composable
private fun PageRow(p: Page, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    GlassButton(onClick = onOpen, modifier = modifier, focusedScale = ROW_SCALE) {
        Column(Modifier.fillMaxWidth()) {
            Label(p.title.ifBlank { UrlResolver.displayHost(p.url) })
            Label(p.url, muted = true)
        }
    }
}

/** After a row disappears, focus goes to the next row, else the previous one, else [fallback]. */
private fun <T> neighbour(list: List<T>, i: Int, key: (T) -> Any, fallback: Any): Any =
    list.getOrNull(i + 1)?.let(key) ?: list.getOrNull(i - 1)?.let(key) ?: fallback

private fun LazyListScope.bookmarkRows(c: BrowserController, lib: Library, first: FocusRequester, rows: RowFocus) {
    item("head") {
        GlassButton(onClick = { c.toggleBookmark() }, modifier = Modifier.fillMaxWidth().focusRequester(first).then(rows.modifier("head"))) {
            Label(if (c.isHome) "Open a page to bookmark it" else if (c.isBookmarked) "Remove this page" else "Bookmark this page")
        }
    }
    if (lib.bookmarks.isEmpty()) item("empty") { Label("No bookmarks yet.", muted = true) }
    itemsIndexed(lib.bookmarks, key = { _, b -> b.url }) { i, b ->
        Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap / 2), verticalAlignment = Alignment.CenterVertically) {
            PageRow(b, { c.loadInput(b.url) }, Modifier.weight(1f))
            GlassButton(onClick = { rows.moveTo(neighbour(lib.bookmarks, i, { it.url }, "head")); lib.toggleBookmark(b.url, b.title) }, modifier = rows.modifier(b.url)) { Label("Remove") }
        }
    }
}

private fun LazyListScope.historyRows(c: BrowserController, lib: Library, first: FocusRequester) {
    item("head") { GlassButton(onClick = { lib.clearHistory() }, modifier = Modifier.fillMaxWidth().focusRequester(first)) { Label("Clear history") } }
    if (lib.history.isEmpty()) item("empty") { Label("No history yet.", muted = true) }
    items(lib.history, key = { it.url }) { h -> PageRow(h, { c.loadInput(h.url) }, Modifier.fillMaxWidth()) }
}

private fun LazyListScope.tabRows(c: BrowserController, first: FocusRequester, rows: RowFocus) {
    item("head") {
        GlassButton(onClick = { c.newTab() }, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
            Label(if (c.tabs.size < MAX_TABS) "New tab" else "Tab limit ($MAX_TABS) reached")
        }
    }
    itemsIndexed(c.tabs, key = { i, _ -> i }) { i, t ->
        val live = i == c.activeTab // the active tab's live state is on the controller
        val url = if (live) c.currentUrl else t.url
        val title = (if (live) c.title else t.title).ifBlank { if (url == UrlResolver.HOME) "Home" else UrlResolver.displayHost(url) }
        Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap / 2), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(onClick = { c.switchTo(i) }, modifier = Modifier.weight(1f), focusedScale = ROW_SCALE) {
                Row(Modifier.fillMaxWidth()) { Label("${i + 1}. $title", modifier = Modifier.weight(1f)); if (live) Label("current", muted = true) }
            }
            GlassButton(onClick = { rows.moveTo(minOf(i, c.tabs.size - 2).coerceAtLeast(0)); c.closeTab(i) }, modifier = rows.modifier(i)) { Label("Close") }
        }
    }
}

@Composable
private fun SettingRow(name: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = ROW_SCALE) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(name, modifier = Modifier.weight(1f)); Label(value, muted = true)
        }
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(name, muted = true, modifier = Modifier.weight(1f)); Label(value, muted = true)
    }
}

private fun LazyListScope.settingRows(c: BrowserController, f: FilterUpdater, first: FocusRequester, rows: RowFocus, editing: MutableState<Boolean>) {
    val st = f.status
    val date = st.updatedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "never"
    item("ads") { SettingRow("Ad blocking", if (c.adBlockOn) "On" else "Off", Modifier.focusRequester(first)) { c.setAdBlock(!c.adBlockOn) } }
    item("blocked") { InfoRow("Ads blocked", "${c.adsBlocked()}  (${st.rules} filter rules)") }
    item("update") { SettingRow("Update filter list", if (f.updating) "Updating..." else st.result?.label ?: "Press OK") { f.update() } }
    item("src") { Label("Source: ${f.source}  -  auto-update every $AUTO_UPDATE_DAYS days", muted = true) }
    item("upd") { Label("Last updated: $date  -  ${st.rules} rules", muted = true) }
    item("link") { SettingRow("Set filter list link", "https only", rows.modifier("link")) { editing.value = true } }
    if (editing.value) item("field") { LinkField(f) { editing.value = false; rows.moveTo("link") } }
    item("reset") { SettingRow("Reset to default", "GitHub list") { editing.value = false; f.resetLink() } }
    item("ua") { SettingRow("Site mode (user agent)", c.uaMode.label) { c.cycleUa() } }
    item("nav") { SettingRow("Navigation", c.navMode.label) { c.toggleNavMode() } }
    item("ptr") {
        val steps = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 2.5f)
        SettingRow("Pointer speed", "%.2gx".format(c.pointerSpeed)) {
            c.pointerSpeed = steps.firstOrNull { it > c.pointerSpeed + 0.01f } ?: steps.first()
        }
    }
    item("clear") { ClearRow(c) }
}

@Composable
private fun ClearRow(c: BrowserController) {
    var step by remember { mutableIntStateOf(0) } // 0 idle, 1 asked to confirm, 2 done
    SettingRow(
        "Clear browsing data",
        when (step) { 1 -> "Press OK again: cookies, cache, history"; 2 -> "Cleared"; else -> "Cookies, cache, history" },
    ) { if (step == 1) { c.clearBrowsingData(); step = 2 } else step = 1 }
}

@Composable
private fun LinkField(f: FilterUpdater, onDone: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("https://", TextRange(8))) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestWhenReady() }
    Column {
        BasicTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            textStyle = TextStyle(color = Sem.onSurface, fontSize = Cmp.bodyLarge),
            cursorBrush = SolidColor(Sem.focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (f.setLink(value.text)) onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .border(if (focused) Cmp.focusBorder else Cmp.restBorder, if (focused) Sem.focus else Sem.outline, RoundedCornerShape(22.dp))
                .background(Sem.background, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp, vertical = 18.dp),
        )
        f.linkError?.let { Label(it, muted = true) }
    }
}
