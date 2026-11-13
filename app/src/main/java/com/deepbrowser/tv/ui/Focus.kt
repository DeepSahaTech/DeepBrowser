package com.deepbrowser.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer

/** Requests focus once the target is attached: retries over a few frames instead of failing silently. */
suspend fun FocusRequester.requestWhenReady(frames: Int = 10): Boolean {
    repeat(frames) {
        withFrameNanos { }
        val ok = try { requestFocus() } catch (_: IllegalStateException) { false } // not attached yet
        if (ok) return true
    }
    return false
}

/** Focus stays inside this group: Up/Down/Left/Right at its edge do nothing instead of escaping. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.focusTrap(): Modifier = focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()

/** Entering the group (or coming back to it) lands on the last focused child, else on [fallback]. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.restoringFocus(fallback: FocusRequester): Modifier = focusRestorer(fallback).focusGroup()

/** Focus request for a row identified by [key]; the row that matches [target] carries the requester. */
@Stable
class RowFocus {
    val requester = FocusRequester()
    var target by mutableStateOf<Any?>(null)
    fun moveTo(key: Any) { target = key }
    fun modifier(key: Any): Modifier = if (target == key) Modifier.focusRequester(requester) else Modifier
}

@Composable
fun rememberRowFocus(): RowFocus {
    val rf = remember { RowFocus() }
    LaunchedEffect(rf.target) { if (rf.target != null) { rf.requester.requestWhenReady(); rf.target = null } }
    return rf
}
