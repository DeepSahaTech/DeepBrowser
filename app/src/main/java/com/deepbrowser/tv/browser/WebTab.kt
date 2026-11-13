package com.deepbrowser.tv.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.deepbrowser.tv.player.BridgeInbox
import com.deepbrowser.tv.player.BridgeSession
import com.deepbrowser.tv.player.BridgeSessions
import com.deepbrowser.tv.player.BridgeTrack
import com.deepbrowser.tv.player.MediaUrl
import org.json.JSONObject
import java.util.UUID

/**
 * Adapter: a [TabView] over a WebView built only when first asked for (WebView startup is slow on Fire TV).
 * All WebView configuration and client callbacks live here; page facts go up through [events].
 */
@SuppressLint("SetJavaScriptEnabled")
class WebTab(
    private val context: Context,
    private val fullscreen: FullscreenSession,
    private val events: PageEvents,
    private val scripts: NavScripts,
    private val config: () -> TabConfig,
) : TabView {
    private var wv: WebView? = null
    private var bridge: PageBridge? = null
    private val main = Handler(Looper.getMainLooper())
    override var ready by mutableStateOf(false); private set

    private var destroyed = false
    private val deadView by lazy { View(context) } // what a destroyed tab hands out instead of rebuilding a WebView
    private val popups = HashSet<WebView>()
    private var inbox: BridgeInbox? = null // binary and text messages of the page's current native-playback session
    private var nativeSession: BridgeSession? = null // at most one session per tab
    private var frameOrigin: String? = null // the frame whose 'start' opened the session: the only sender while it lasts
    private var frameMain = false

    override val hostView: View get() = wv ?: if (destroyed) deadView else build().also { wv = it; ready = true }
    override val userAgent: String? get() = wv?.settings?.userAgentString
    override val canGoBack get() = wv?.canGoBack() == true
    override val canGoForward get() = wv?.canGoForward() == true

    override fun load(url: String) { (hostView as? WebView)?.let { it.loadUrl(url); it.requestFocus() } }
    override fun reload() { wv?.reload() }
    override fun goBack() { wv?.let { if (it.canGoBack()) it.goBack() } }
    override fun goForward() { wv?.let { if (it.canGoForward()) it.goForward() } }
    override fun eval(js: String, onResult: ((String?) -> Unit)?) { wv?.evaluateJavascript(js, onResult?.let { cb -> { r -> cb(r) } }) }
    override fun applyUserAgent(ua: String) { wv?.settings?.userAgentString = ua }
    override fun applyScale(percent: Int) { wv?.setInitialScale(percent) }
    override fun applyZoom(percent: Int) { wv?.settings?.textZoom = percent }
    override fun clearData() { wv?.let { it.clearCache(true); it.clearHistory(); it.clearFormData() } }
    override fun page(down: Boolean) { (hostView as? WebView)?.let { if (down) it.pageDown(false) else it.pageUp(false) } }
    private var canFocus = true
    override fun focus() { wv?.requestFocus() }
    override fun setFocusable(on: Boolean) {
        canFocus = on
        wv?.apply { if (!on) clearFocus(); isFocusable = on; isFocusableInTouchMode = on }
    }
    override fun pause() { wv?.onPause() }
    override fun resume() { wv?.onResume() }
    override fun destroy() {
        destroyed = true
        popups.toList().forEach(::dropPopup)
        val v = wv ?: return
        v.stopLoading(); (v.parent as? ViewGroup)?.removeView(v); v.destroy(); wv = null; ready = false
    }

    private fun dropPopup(v: WebView) { if (popups.remove(v)) v.destroy() }

    override fun attachBridge(bridge: PageBridge) { this.bridge = bridge }

    private inner class JsBridge {
        @JavascriptInterface fun ime(fx: Float, fy: Float) { main.post { bridge?.ime(fx, fy) } }
    }

    private fun build(): WebView {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // debug builds only: lets us inspect pages over adb
        }
        val v = WebView(context)
        val cfg = config()
        configure(v, cfg)
        v.addJavascriptInterface(JsBridge(), "DBBridge")
        // Hide AV1 (no hardware decoder on Fire TV sticks: shows a blue/green frame) so players pick VP9/H.264/HEVC.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(v, PageScripts.CODEC_PATCH, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(v, scripts.all, setOf("*"))
        }
        installMseBridge(v)
        cfg.scalePercent?.let { v.setInitialScale(it) }
        return v
    }

    /** Page MSE data reaches the app as `window.dbMse` messages; without both features the bridge is simply absent. */
    private fun installMseBridge(v: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) return
        WebViewCompat.addWebMessageListener(v, "dbMse", setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
            try {
                onPageMessage(message, sourceOrigin.toString(), isMainFrame, reply)
            } catch (e: Exception) {
                android.util.Log.w("DBMSE", "ignored bad page message", e)
            }
        }
    }

    /** One session per tab: a 'start' is taken only when none is active, and while one is, only its own frame may send. */
    private fun onPageMessage(message: WebMessageCompat, origin: String, mainFrame: Boolean, reply: JavaScriptReplyProxy) {
        val binary = message.type == WebMessageCompat.TYPE_ARRAY_BUFFER
        val bytes = if (binary) message.arrayBuffer else null
        val text = if (binary) null else message.data.orEmpty()
        if ((bytes?.size ?: text!!.length) > MAX_MESSAGE_BYTES) return
        // The session ended (the player removed it): forget the tab's session so a later 'start' is accepted.
        nativeSession?.let { if (BridgeSessions.get(it.id) == null) clearNative() }
        if (nativeSession != null && (origin != frameOrigin || mainFrame != frameMain)) return
        if (bytes != null) { inbox?.onBytes(bytes); return }
        val type = runCatching { JSONObject(text!!).optString("type") }.getOrDefault("")
        when {
            type == "start" -> if (nativeSession == null) startNativePlayback(text!!, reply, origin, mainFrame)
            else -> inbox?.onText(text!!)
        }
    }

    private fun clearNative() {
        nativeSession = null
        inbox = null
        frameOrigin = null
        frameMain = false
    }

    private fun startNativePlayback(json: String, reply: JavaScriptReplyProxy, origin: String, mainFrame: Boolean) {
        val msg = JSONObject(json)
        val sbs = msg.optJSONArray("sbs") ?: return
        val tracks = (0 until sbs.length()).map { i ->
            val o = sbs.getJSONObject(i)
            // mse.js names each SourceBuffer "sb" (a number); the same key tags its init/seg headers.
            BridgeTrack(o.optString("sb"), o.optString("mime"), o.optLong("timescale", 1000L))
        }
        if (tracks.isEmpty()) return
        val session = BridgeSession(UUID.randomUUID().toString(), tracks, msg.optDouble("t", 0.0)) { cmd ->
            runCatching { reply.postMessage(cmd) } // the page may already be gone
        }
        BridgeSessions.put(session)
        nativeSession = session
        frameOrigin = origin
        frameMain = mainFrame
        inbox = BridgeInbox(session)
        events.nativePlayback(session.id)
    }

    private fun configure(wv: WebView, cfg: TabConfig) {
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(wv, true) }
        // WRAP_CONTENT height makes Chromium report 100vh as 0, which collapses menus sized in vh (player quality/server lists)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.apply {
            setBackgroundColor(0xFF0B1020.toInt())
            isFocusable = canFocus
            isFocusableInTouchMode = canFocus
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = cfg.userAgent
                setSupportZoom(false)
                textZoom = cfg.zoomPercent
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && MediaUrl.isDirectMedia(request.url.toString())) {
                        events.openMedia(request.url.toString(), view.url); return true
                    }
                    return !UrlResolver.isLoadableScheme(request.url.scheme)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { events.started(this@WebTab, url) }
                override fun onPageFinished(view: WebView, url: String?) { events.loaded(this@WebTab) }
                override fun onPageCommitVisible(view: WebView, url: String?) { events.loaded(this@WebTab) }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (events.shouldBlock(request.url.toString(), request.url.host)) emptyResponse() else null

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    events.visited(this@WebTab, url, isReload)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) view.loadDataWithBaseURL(
                        request.url.toString(), PageScripts.errorPage(request.url, error.description?.toString().orEmpty()),
                        "text/html", "utf-8", request.url.toString(),
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.message().startsWith("DBADS")) android.util.Log.d("DBADS", m.message())
                    return true
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) { events.progress(this@WebTab, newProgress) }
                override fun onReceivedTitle(view: WebView, t: String?) { events.title(this@WebTab, t.orEmpty()) }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) { fullscreen.show(view, callback) }
                override fun onHideCustomView() = fullscreen.exit()

                override fun onPermissionRequest(request: PermissionRequest) {
                    // Only DRM (Widevine) is granted. Camera / microphone are never granted to pages.
                    val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    main.post { if (drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny() }
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    if (!isUserGesture) return false // block pop-ups/pop-unders not caused by a click
                    // Pop-ups open in the same tab (no hidden windows on a TV).
                    val temp = WebView(view.context)
                    temp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            events.popup(r.url.toString()); dropPopup(v); return true
                        }
                    }
                    temp.webChromeClient = object : WebChromeClient() { override fun onCloseWindow(window: WebView) { dropPopup(window) } }
                    popups += temp
                    main.postDelayed({ dropPopup(temp) }, POPUP_TIMEOUT_MS) // never navigated: do not keep a hidden WebView alive
                    (resultMsg.obj as WebView.WebViewTransport).webView = temp
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
    }
}

private const val POPUP_TIMEOUT_MS = 15_000L
private const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024 // larger page messages are ignored (text counted in chars)

private fun emptyResponse() =
    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
