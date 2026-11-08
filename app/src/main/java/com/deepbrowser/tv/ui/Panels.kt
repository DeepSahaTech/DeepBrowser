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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.deepbrowser.tv.browser.MAX_TABS
import com.deepbrowser.tv.browser.Panel
import com.deepbrowser.tv.browser.UrlResolver
import com.deepbrowser.tv.data.Page

/** Full-screen list screens (bookmarks, history, tabs, settings). Back closes (handled in BrowserController.handleKey). */
@Composable
fun PanelScreen(c: BrowserController, panel: Panel) {
    val first = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(backdropBrush()).padding(overscanPadding())) {
        Text(panel.title, fontSize = Cmp.heading, fontWeight = FontWeight.SemiBold, color = Sem.onSurface)
        Spacer(Modifier.height(Cmp.gap))
        when (panel) {
            Panel.Bookmarks -> BookmarksList(c, first)
            Panel.History -> HistoryList(c, first)
            Panel.Tabs -> TabsList(c, first)
            Panel.Settings -> SettingsList(c, c.adsBlocked(), c.filterRules, first)
        }
    }
}

private val rowPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
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

@Composable
private fun Empty(text: String) = Label(text, muted = true, modifier = Modifier.padding(top = Cmp.gap))

@Composable
private fun BookmarksList(c: BrowserController, first: FocusRequester) {
    GlassButton(onClick = { c.toggleBookmark() }, modifier = Modifier.focusRequester(first)) {
        Label(if (c.isHome) "Open a page to bookmark it" else if (c.isBookmarked) "Remove this page" else "Bookmark this page")
    }
    if (c.bookmarks.isEmpty()) Empty("No bookmarks yet.")
    LazyColumn(contentPadding = rowPadding, verticalArrangement = Arrangement.spacedBy(Cmp.gap / 2)) {
        items(c.bookmarks, key = { it.url }) { b ->
            Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap / 2), verticalAlignment = Alignment.CenterVertically) {
                PageRow(b, { c.loadInput(b.url) }, Modifier.weight(1f))
                GlassButton(onClick = { c.removeBookmark(b); runCatching { first.requestFocus() } }) { Label("Remove") }
            }
        }
    }
}

@Composable
private fun HistoryList(c: BrowserController, first: FocusRequester) {
    GlassButton(onClick = { c.clearHistory() }, modifier = Modifier.focusRequester(first)) { Label("Clear history") }
    if (c.historyItems.isEmpty()) Empty("No history yet.")
    LazyColumn(contentPadding = rowPadding, verticalArrangement = Arrangement.spacedBy(Cmp.gap / 2)) {
        items(c.historyItems, key = { it.url }) { h -> PageRow(h, { c.loadInput(h.url) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun TabsList(c: BrowserController, first: FocusRequester) {
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(Cmp.gap / 2)) {
        GlassButton(onClick = { c.newTab() }, modifier = Modifier.focusRequester(first)) {
            Label(if (c.tabs.size < MAX_TABS) "New tab" else "Tab limit ($MAX_TABS) reached")
        }
        c.tabs.forEachIndexed { i, t ->
            val live = i == c.activeTab // the active tab's live state is on the controller
            val url = if (live) c.currentUrl else t.url
            val title = (if (live) c.title else t.title).ifBlank { if (url == UrlResolver.HOME) "Home" else UrlResolver.displayHost(url) }
            Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap / 2), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(onClick = { c.switchTo(i) }, modifier = Modifier.weight(1f), focusedScale = ROW_SCALE) {
                    Row(Modifier.fillMaxWidth()) { Label("${i + 1}. $title", modifier = Modifier.weight(1f)); if (live) Label("current", muted = true) }
                }
                GlassButton(onClick = { c.closeTab(i); runCatching { first.requestFocus() } }) { Label("Close") }
            }
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
private fun SettingsList(c: BrowserController, adsBlocked: Long, filterRules: Int, first: FocusRequester) {
    var clearStep by remember { mutableIntStateOf(0) } // 0 idle, 1 asked to confirm, 2 done
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(Cmp.gap / 2)) {
        SettingRow("Ad blocking", if (c.adBlockOn) "On" else "Off", Modifier.focusRequester(first)) { c.setAdBlock(!c.adBlockOn) }
        SettingRow("Ads blocked", "$adsBlocked  ($filterRules filter rules)") {}
        FilterListSection(c, first)
        SettingRow("Site mode (user agent)", c.uaMode.label) { c.cycleUa() }
        SettingRow(
            "Clear browsing data",
            when (clearStep) { 1 -> "Press OK again: cookies, cache, history"; 2 -> "Cleared"; else -> "Cookies, cache, history" },
        ) { if (clearStep == 1) { c.clearBrowsingData(); clearStep = 2 } else clearStep = 1 }
    }
}

@Composable
private fun FilterListSection(c: BrowserController, first: FocusRequester) {
    var editing by remember { mutableStateOf(false) }
    val st = c.filterStatus
    val date = st.updatedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "never"
    SettingRow("Update filter list", if (c.filterUpdating) "Updating..." else st.result?.label ?: "Press OK") { c.updateFilterList() }
    Label("Source: ${c.filterSource}  -  auto-update every $AUTO_UPDATE_DAYS days", muted = true)
    Label("Last updated: $date  -  ${st.rules} rules", muted = true)
    SettingRow("Set filter list link", "https only") { editing = true }
    if (editing) LinkField(c) { editing = false; runCatching { first.requestFocus() } }
    SettingRow("Reset to default", "GitHub list") { editing = false; c.resetFilterLink() }
}

@Composable
private fun LinkField(c: BrowserController, onDone: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("https://", TextRange(8))) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value, onValueChange = { value = it }, singleLine = true,
        textStyle = TextStyle(color = Sem.onSurface, fontSize = Cmp.bodyLarge),
        cursorBrush = SolidColor(Sem.focus),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (c.setFilterLink(value.text)) onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .border(if (focused) Cmp.focusBorder else Cmp.restBorder, if (focused) Sem.focus else Sem.outline, RoundedCornerShape(22.dp))
            .background(Sem.background, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp, vertical = 18.dp),
    )
    c.filterLinkError?.let { Label(it, muted = true) }
}
