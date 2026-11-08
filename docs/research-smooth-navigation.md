# Research: native-feel D-pad navigation in a WebView on Fire TV Stick 4K Max (Fire OS 7, API 28)

Date: 2026-11-07. Tooling note: TinyFish MCP tools were not available in this session; WebSearch/WebFetch were used.
Labels: [DOC] = stated by a primary source; [INFER] = my inference, not stated by any source; [GAP] = not found/verified.

## 1. How TV browsers / TV web runtimes do D-pad navigation

Findings
- [DOC] Native (engine-side) spatial navigation exists in Blink: `spatialNavigationEnabled` is a Blink Setting (default false, "special keyboard navigation mode for platforms with no proper mouse or touch support, such as a TV remote"), also switchable via `--enable-spatial-navigation` / `--blink-settings`. Android WebView got the flag in 2013 (r221946), with unhandled DPAD events bubbling to neighbouring Android views and `TakeFocus()` handing focus to the next view. A blink-dev thread notes it is on by default for Android TV WebViews. Sources: [S1][S2][S3].
- [GAP] I could not confirm from primary sources whether Amazon WebView (AWV) on Fire OS 7 enables native spatnav, or whether `WebSettings` exposes any toggle (public WebSettings has none that I found). Test on device: with no JS injected, does D-pad move focus among links?
- [DOC] The CSS Spatial Navigation spec (css-nav-1) defines `navigate()`, `spatialNavigationSearch()`, `focusableAreas()`, `getSpatialNavigationContainer()` and CSS props `--spatial-navigation-contain/-action/-function`. The WICG page says no browser had implemented it; the WICG repo was archived 2026-03-23 (read-only). `spatialNavigationSearch()` never scrolls and returns null if nothing is in the container. Sources: [S4][S5][S6].
- [DOC] The WICG explainer itself argues JS implementations are slow with many focusables because every arrow press needs DOM access, and that native responds better on animated pages [S5]. (Vendor-authored argument, no benchmark.)
- [DOC] TV Bro (truefedex/tv-bro): README says engine is "Android builtin web rendering engine"; release notes mention "Cursor behavior improvements" and a Gecko variant. [GAP] I did NOT find (in the pages I could read) TV Bro's key-handling source. Search-result snippets and a fork (SimpleBrowser) suggest its primary mode is a virtual mouse cursor with edge scrolling plus an optional direct D-pad mode, but that is secondary evidence, not verified from source [S7][S8]. Read `app/` for `dispatchKeyEvent`/cursor classes before copying anything.
- [GAP] Puffin TV and Silk internals: no primary source found on how they implement D-pad navigation.
- [DOC] Amazon Fire TV web-app guidance: `div`/`span` cannot receive remote focus (only focusable elements, i.e. add `tabindex`); scale focused elements with `transform: scale()` rather than changing width/height (avoids full repaint) [S9].
- [DOC] Held-key handling on TV runtimes: throttling is an "effective fix" for held-key scrolling on Tizen [S10]. A webOS app issue shows focus scrolling tweened in JS keeps the renderer busy for every key press; fix is an immediate (non-tweened) scroll path so focus lands in the same frame, and slow TVs disable smooth scrolling/blur/transitions [S11][S12]. (Community evidence, not vendor docs.)

Takeaways [INFER]
- Two proven architectures: (a) let the engine do spatnav (zero JS round trip); (b) do spatnav in a content script that listens to `keydown` inside the page (no native->JS call per key). Your current design (native key -> evaluateJavascript -> scan DOM -> scroll) pays an IPC round trip plus full DOM scan per key, which is the worst of both.

## 2. Android WebView performance on low-end TV hardware

- [DOC] Never call `setLayerType(LAYER_TYPE_SOFTWARE)` on WebView or any parent; software draw is slow and uses more memory; hardware acceleration is on by default [S13]. Keep `android:hardwareAccelerated="true"`. One blog warns HW layers can exhaust GPU memory on heavy WebGL/SVG pages [S14] (low-authority; [INFER] leave default, don't add layers).
- [DOC] JS bridge options [S15]: `addWebMessageListener` (recommended; async; listener on UI thread; needs WebView >= 82 + androidx.webkit 1.3.0; page->app, and app->page via `replyProxy.postMessage`); `postWebMessage` (v45+, main frame only); `addJavascriptInterface` (legacy, synchronous, runs on a background thread, no origin checks, all frames). `addDocumentStartJavaScript()` injects before page scripts run; `evaluateJavascript` is the post-load path.
- [DOC] Chromium list: a Cordova dev reported small evaluateJavascript snippets are slow versus event/message triggers (old, anecdotal) [S16]. [GAP] No benchmark for low-end TV found.
- [INFER] Avoid per-key `evaluateJavascript`. Inject the nav script ONCE (document-start or onPageFinished) and handle `keydown` inside the page. Native then only needs to forward keys the page did not consume (e.g. BACK, MENU) and receive events like "focus moved"/"edge reached" via the bridge.
- [INFER] Prefer `addWebMessageListener` if AWV >= 82 (see section 3: recent Fire OS 7 builds report Chrome 108-138), with `addJavascriptInterface` as fallback. Check `WebViewFeature.isFeatureSupported`.
- [INFER] rAF-throttled reporting: batch scroll/focus reports in `requestAnimationFrame`, passive listeners, one message per frame max.
- [GAP] `setInitialScale`/viewport/offscreen-rendering advice: no primary source found; treat as experiment (lower effective resolution = fewer pixels to raster on a weak GPU; measure).

## 3. Amazon WebView on Fire OS 7: facts and pitfalls

- [DOC] AWV is a custom Chromium build, "hardware-optimized and GPU-accelerated" for Fire TV, a transparent replacement for `WebView`; Amazon says it is not identical to Silk [S17]. Fire OS 7 = Android 9 / API 28 [S18].
- [DOC] Amazon's docs give no Chromium version per Fire OS. Version-gated notes: AWV v84 auto-upgrades http audio/video (no fallback); v87 auto-upgrades image mixed content [S9]. Amazon says do not rely on UA version numbers [S19].
- [DOC, third-party UA lists] AFTKA (4K Max) WebView UA shows Chrome/108.0.5359.128 (older) and Chrome/138.0.7204.244 (recent), i.e. the engine is updated over time [S20]. Read `navigator.userAgent` on the real unit; do not assume.
- [DOC] Known AWV limitations [S9]: no MOV; `location.assign()` unreliable; `location.replace()`/href redirects to third-party auth can break history; no HLS seek/duration; no ad-ID.
- [DOC] Fire TV: `div`/`span` not focusable; virtual keyboard opens on Select on text inputs and cannot be launched programmatically; `change` doesn't fire on Back [S9].
- Debugging/CDP: [DOC] `WebView.setWebContentsDebuggingEnabled(true)` + `chrome://inspect` works for WebViews on Android 4.4+; manifest `debuggable` is irrelevant [S21]. Amazon's page describes adb over USB or `adb connect <ip>` then chrome://inspect [S22]. [Third-party, TVLabs] on Fire OS the USB device list shows "locked"; use `adb forward` + "Discover network targets" at 127.0.0.1:9222 [S23]. [GAP] I did not verify raw CDP (e.g. Tracing/Performance domains) specifically on AWV; the DevTools Performance panel over chrome://inspect is the practical profiler.

## 4. Recommended architecture (ranked by impact / effort)

1. [Highest impact, low effort] First, measure: on-device check of whether native D-pad focus already works with zero JS (tests whether AWV has native spatnav), and a DevTools Performance recording of one key press in the current design.
2. Move key handling INTO the page: inject one script at document start (`addDocumentStartJavaScript` if supported, else onPageStarted/Finished) with a single `keydown` listener. Eliminates the evaluateJavascript round trip per key.
3. Native `dispatchKeyEvent` forwards to JS only when needed; consume nothing natively on the hot path. Keep BACK/MENU/long-press logic native.
4. Cache a candidate snapshot (focusable elements + rects) per "layout epoch"; invalidate via `MutationObserver` (coalesced), `ResizeObserver`, scroll end, and transitions. Compute the geometry once, not per key. Use `IntersectionObserver` to track viewport candidates. [INFER]
5. Read-then-write per frame: batch all `getBoundingClientRect` reads, then one write; handle the key in the same frame (no forced layout after writes).
6. Limit candidate set: viewport +/- one screen, in the current scroll container first, then fall back to global (matches the css-nav container model [S4]).
7. Key repeat: process at most one move per rAF (or fixed ~60-80 ms) while `event.repeat`; coalesce the rest; no smooth-scroll tween during repeat [S10][S11].
8. Scrolling: use `scrollBy`/`scrollTo` with `behavior:'instant'` (no JS-tweened scroll) while repeating; optional short CSS-driven ease only on single presses on fast devices. Scroll only when the target is out of a safe margin.
9. Focus ring: one absolutely positioned `position:fixed` div moved with `transform: translate3d` (compositor-only), or use `:focus-visible` outline; avoid animating width/height/box-shadow/blur [S9]. Disable focus animations if frame time > budget.
10. Bridge: `addWebMessageListener` (fallback `addJavascriptInterface`) for page->native events, batched once per frame; avoid synchronous JS interface calls on the key path [S15].
11. Honor the engine: do not set software layer type anywhere in the view tree; keep hardware acceleration on [S13].
12. Pass-through for pages with their own handlers: if the page calls `preventDefault` on keydown, do not navigate (YouTube-style TV UIs, sites with own focus).
13. Adaptive "slow mode": measure rAF frame time; when slow, drop effects and widen the repeat interval [S11][S12].
14. Make unfocusable clickables focusable (`tabindex=-1` stamped lazily by the snapshot pass), since Fire TV does not focus bare div/span [S9].
15. Longer-term/experimental: if AWV exposes native spatnav, use it as the base and layer scrolling/ring only; if `document.body.spatialNavigationSearch` exists, call it instead of your own geometry [S5].

## Sources
- [S1] https://groups.google.com/a/chromium.org/d/topic/blink-dev/x6Kr0gHcvT8 (spatial navigation support, blink-dev)
- [S2] https://codereview.chromium.org/23619024 ([Android WebView] Enable spatial navigation / DPAD)
- [S3] https://chromium.googlesource.com/chromium/src/third_party/+/master/blink/renderer/core/frame/settings.json5 (spatialNavigationEnabled setting)
- [S4] https://drafts.csswg.org/css-nav-1/explainer and https://wicg.github.io/spatial-navigation/
- [S5] https://github.com/WICG/spatial-navigation/blob/main/docs/spatialNavigationSearch_explainer.md
- [S6] https://github.com/WICG/spatial-navigation (archived 2026-03-23)
- [S7] https://github.com/truefedex/tv-bro and https://github.com/truefedex/tv-bro/blob/master/RELEASE_NOTES.md
- [S8] https://github.com/SimpleSoftwareLTDA/SimpleBrowser (fork; secondary)
- [S9] https://developer.amazon.com/docs/fire-tv/web-app-faq.html
- [S10] https://www.oxagile.com/article/tizen-tv-app-performance-optimization/ (secondary)
- [S11] https://github.com/NuvioMedia/NuvioTVSmart/issues/1153 (community)
- [S12] https://github.com/NuvioMedia/NuvioTVSmart/issues/1139 (community)
- [S13] https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/software_draw_deprecated.md
- [S14] https://nativine.com/blog/webview-app-speed-performance-memory-management (low authority)
- [S15] https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge
- [S16] https://groups.google.com/a/chromium.org/g/chromium-dev/c/Ey1xgbn_lE0 (old, anecdotal)
- [S17] https://developer.amazon.com/docs/fire-tv/web-hybrid-app-faq.html and https://developer.amazon.com/docs/fire-tv/hybrid-apps-overview.html
- [S18] https://developer.amazon.com/docs/fire-tv/fire-os-overview.html
- [S19] https://developer.amazon.com/docs/fire-tv/user-agent-strings.html
- [S20] https://whatmyuseragent.com/device/amazon/fire-tv-stick-4k-max (third-party UA aggregator)
- [S21] https://developer.chrome.com/docs/devtools/remote-debugging/webviews
- [S22] https://developer.amazon.com/docs/fire-tv/webapp-debug-app.html
- [S23] https://docs.tvlabs.ai/platform/platforms/android (third-party)

## Not verified
TV Bro and Puffin TV key-handling internals; whether AWV on Fire OS 7 enables native spatnav; exact AWV version on your unit; raw CDP domains on AWV; any low-end-TV benchmark of bridge options; setInitialScale benefit.
