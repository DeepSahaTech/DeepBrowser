package com.deepbrowser.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.deepbrowser.tv.browser.BrowserController
import com.deepbrowser.tv.browser.Panel
import com.deepbrowser.tv.browser.UrlResolver

@Composable
fun CommandBar(c: BrowserController) {
    val initial = if (c.isHome) "" else c.currentUrl
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxWidth().padding(overscanPadding())) {
        Column(
            Modifier.fillMaxWidth()
                .glass(radius = 32, strong = true).padding(32.dp),
        ) {
            Text(
                if (c.isHome) "Home" else c.title.ifBlank { UrlResolver.displayHost(c.currentUrl) },
                fontSize = Cmp.body, color = Sem.onSurfaceMuted, maxLines = 1,
            )
            Spacer(Modifier.height(Cmp.gap))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = Sem.onSurface, fontSize = Cmp.bodyLarge),
                cursorBrush = SolidColor(Sem.focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); c.loadInput(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .border(
                        if (fieldFocused) Cmp.focusBorder else Cmp.restBorder,
                        if (fieldFocused) Sem.focus else Sem.outline,
                        RoundedCornerShape(22.dp),
                    )
                    .background(Sem.background, RoundedCornerShape(22.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) Text("Search or type an address", fontSize = Cmp.bodyLarge, color = Sem.onSurfaceMuted)
                    inner()
                },
            )
            Spacer(Modifier.height(Cmp.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap), verticalAlignment = Alignment.CenterVertically) {
                Chip("Back") { c.commandBarVisible = false; c.goBack() }
                Chip("Forward") { c.commandBarVisible = false; c.goForward() }
                Chip("Reload") { c.reload() }
                Chip("Home") { c.goHome() }
            }
            Spacer(Modifier.height(Cmp.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap), verticalAlignment = Alignment.CenterVertically) {
                Chip("A−") { c.changeZoom(-10) }
                Chip("${c.zoomPercent}%") { c.changeZoom(+10) }
                Chip("Site: ${c.uaMode.label}") { c.cycleUa() }
                Chip(if (c.cursorEnabled) "Cursor on" else "Cursor off") { c.cursorEnabled = !c.cursorEnabled }
            }
            Spacer(Modifier.height(Cmp.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap), verticalAlignment = Alignment.CenterVertically) {
                if (!c.isHome) Chip(if (c.isBookmarked) "Remove bookmark" else "Bookmark page") { c.toggleBookmark() }
                Chip("Bookmarks") { c.openPanel(Panel.Bookmarks) }
                Chip("History") { c.openPanel(Panel.History) }
                Chip("Tabs ${c.tabs.size}") { c.openPanel(Panel.Tabs) }
                Chip("Settings") { c.openPanel(Panel.Settings) }
            }
            Spacer(Modifier.height(Cmp.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(Cmp.gap), verticalAlignment = Alignment.CenterVertically) {
                Chip("Voice / Phone") { c.companionHelp = !c.companionHelp }
                Chip("Device") { c.commandBarVisible = false; c.infoVisible = true }
                if (!c.isHome) Chip(if (c.readerOn) "Reader: on" else "Reader") { c.toggleReader() }
            }
            if (c.companionHelp) {
                Spacer(Modifier.height(Cmp.gap))
                Text(
                    if (c.companionUrl == null) "Phone input is unavailable: connect the TV to Wi-Fi."
                    else "Voice and typing: open ${c.companionUrl} on a phone on the same Wi-Fi, then use the mic on the phone keyboard.",
                    fontSize = Cmp.body, color = Sem.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    GlassButton(onClick = onClick) {
        Text(label, fontSize = Cmp.body, color = Sem.onSurface, maxLines = 1)
    }
}
