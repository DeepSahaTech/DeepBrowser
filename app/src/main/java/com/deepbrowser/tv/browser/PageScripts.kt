package com.deepbrowser.tv.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import com.deepbrowser.tv.companion.CompanionProtocol
import com.deepbrowser.tv.data.UaMode

/** Small JS snippets and strings the controller sends to pages. */
object PageScripts {
    fun pasteJs(text: String): String {
        val t = CompanionProtocol.jsString(text)
        return "(function(t){var e=document.activeElement;if(!e)return 'none';" +
            "if(e.tagName==='INPUT'||e.tagName==='TEXTAREA'){var p=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;" +
            "Object.getOwnPropertyDescriptor(p,'value').set.call(e,(e.value||'')+t);e.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'}" +
            "if(e.isContentEditable){document.execCommand('insertText',false,t);return 'ok'}return 'none'})($t)"
    }

    fun videoJs(action: String): String =
        "(function(){var v=[].slice.call(document.querySelectorAll('video'))" +
            ".sort(function(a,b){return b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight})[0];" +
            "if(!v)return;$action})()"

    fun errorPage(url: Uri, reason: String): String {
        val safeReason = android.text.Html.escapeHtml(reason)
        val safeUrl = android.text.Html.escapeHtml(url.toString())
        return """<html><body style="margin:0;background:#0B1020;color:#fff;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh;text-align:center">
            <div><h1 style="font-size:48px">Can't open this page</h1>
            <p style="font-size:28px;opacity:.7">$safeReason</p>
            <p style="font-size:22px;opacity:.5">$safeUrl</p>
            <p style="font-size:24px;opacity:.8">Press Menu to enter another address or Back to go home.</p></div></body></html>"""
    }

    const val CODEC_PATCH = """(function(){
// AV1 has no hardware decoder here; 10-bit HEVC / Dolby Vision decode to a black picture in this WebView. Players then pick H.264/VP9/8-bit HEVC.
var bad=/av01|av1|hvc1\.2|hev1\.2|dvh1|dvhe/i;
window.__dbMime=[];
try{var a=MediaSource.isTypeSupported;MediaSource.isTypeSupported=function(t){return bad.test(t)?false:a.call(MediaSource,t);};}catch(e){}
try{var ab=MediaSource.prototype.addSourceBuffer;MediaSource.prototype.addSourceBuffer=function(t){window.__dbMime.push(t);return ab.call(this,t);};}catch(e){}
try{var c=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(t){return bad.test(t)?'':c.call(this,t);};}catch(e){}
try{var d=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);navigator.mediaCapabilities.decodingInfo=function(cfg){var t=cfg&&cfg.video&&cfg.video.contentType;return bad.test(t||'')?Promise.resolve({supported:false,smooth:false,powerEfficient:false}):d(cfg);};}catch(e){}
})();"""
}

/** User-agent strings. Nothing touches WebView until the first call (getDefaultUserAgent starts Chromium). */
class UserAgents(private val context: Context) {
    private val default by lazy { WebSettings.getDefaultUserAgent(context) }
    private val major by lazy { Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1) ?: "120" }

    fun of(m: UaMode): String = when (m) {
        // Look like Chrome, not an embedded WebView, so sites serve their normal (and DRM-capable) players.
        UaMode.TV -> default.replace("; wv", "").replace(Regex(" Version/\\d+\\.\\d+"), "")
        UaMode.MOBILE -> "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
        UaMode.DESKTOP -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}
