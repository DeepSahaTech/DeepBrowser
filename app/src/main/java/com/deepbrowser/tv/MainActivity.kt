package com.deepbrowser.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.deepbrowser.tv.companion.CompanionAction
import com.deepbrowser.tv.companion.CompanionServer
import androidx.compose.ui.platform.ComposeView
import com.deepbrowser.tv.browser.BrowserController
import com.deepbrowser.tv.ui.BrowserScreen
import com.deepbrowser.tv.ui.DeepBrowserTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: BrowserController
    private val companion by lazy { CompanionServer { a -> runOnUiThread { onCompanion(a) } } }

    private fun onCompanion(a: CompanionAction) = when (a) {
        is CompanionAction.Text -> controller.loadInput(a.s)
        is CompanionAction.Url -> controller.loadInput(a.s)
        is CompanionAction.Paste -> controller.pasteText(a.s)
        is CompanionAction.Key -> { // through the same path as the physical remote
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, a.code)); dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, a.code)); Unit
        }
    }

    private var visible = false
    // Socket + LAN lookup wait until the first frames are drawn (controller.onWarm), so they never delay the home screen.
    private fun startCompanion() { if (visible && controller.warmed) controller.companionUrl = companion.start() }

    override fun onStart() { super.onStart(); visible = true; startCompanion() }
    override fun onStop() { visible = false; companion.stop(); controller.companionUrl = null; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        controller = BrowserController(this, root)
        controller.onWarm = ::startCompanion
        val compose = ComposeView(this).apply {
            setContent { DeepBrowserTheme { BrowserScreen(controller) } }
        }
        root.addView(compose, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.toString()?.let { controller.loadInput(it) }
    }

    // The controller sees every key first so the remote works the same no matter what has focus.
    @SuppressLint("RestrictedApi") // false positive: androidx ComponentActivity.dispatchKeyEvent
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller.handleKey(event) || super.dispatchKeyEvent(event)

    override fun onPause() { super.onPause(); controller.onPause() }
    override fun onResume() { super.onResume(); controller.onResume() }
    override fun onDestroy() { controller.destroy(); super.onDestroy() }
}
