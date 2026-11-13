# DeepBrowser remote-navigation rebuild: architecture plan (T-043)

Date 2026-11-13. Scope: how remote (D-pad) navigation works on web pages. Evidence: .tmp/repro-2026-11-13.md, .tmp/t3.png, .tmp/t4.png (Movy, CSS viewport 1280x720 shown on a 1920x1080 view, scale 1.5).

## A. Root causes (current code)

**R1. Decisions are made on a stale target list.**
- SmartNav.kt:52: every DOM change only schedules `push(600)`.
- SmartNav.kt:159-163: `push()` returns early while any timer is pending (`if(timer)return`), so a pending 600 ms push swallows the 120 ms push from `__dbSel` (:191) and the scroll push (:178).
- FocusNav.kt:68-79 chooses from `items`, the last snapshot. Nothing asks for a fresh one on a key press.
- After OK, BrowserController.kt:320 taps the ring (PointerInjector.kt:30-35) and `__dbOk` (which would push) is never called.
- So for at least 600 ms after the search accordion opens, Kotlin does not know Play and See more exist.

**R2. Up/Down scoring favours wide rows over the nearest control below.**
- SnapshotProtocol.kt:88-92: `cross = |candidate.cx - anchorX|`, multiplied by 3 unless the anchor column lies inside the candidate.
- With view-pixel coordinates from t3.png (focused row l1140 r1757 b355, anchor 1448): Play (1155..1263, top 466) = 111 + 239x3 = 827; See more (1276..1400) = 110 + 110x3 = 440; Batman Beyond row (top 540, full width) = 185. Down picks Batman Beyond.
- The scoring uses centres, not edges; the spec uses edge-to-edge distance (section B).

**R3. OK is a real touch at the centre of the ring rect, not an activation of the focused element.**
- BrowserController.kt:320 -> PointerInjector.kt:30-35. The ring rect is often a card ancestor chosen by `cardOf` (SmartNav.kt:56-71, used at :138); its centre can sit on "More info", not Play. When a carousel moves, the rect is stale and the touch lands on another slide.

**R4. Two focus systems that disagree.**
- Kotlin's `curId` is the source of truth (FocusNav.kt:89-91) and moves the ring before the page acts.
- `__dbSel` (SmartNav.kt:182-193) calls `focus()` on `prim.get(el)||el`, which does nothing on non-focusable cards/divs.
- When React replaces the focused node (auto-rotating hero), focus drops to BODY; `__dbSel` returns 0 for a disconnected id (:183) but Kotlin has already moved.
- t4.png: the site's own focus outline is on the "M" logo while our ring is on a result row.

**R5. Duplicate nested targets.** SEL (SmartNav.kt:22) matches both A and BUTTON; the containment filter at :148 keeps the outer A because the inner BUTTON counts as a control (`isControl` :90). Spatial's validity test (`ob.cy - cb.cy > 4`, SnapshotProtocol.kt:86-89) can never move between the twins.

**R6. Carousels.** On-screen checks (SmartNav.kt:109-112) run at snapshot time, not key time. `Spatial.carry` (SnapshotProtocol.kt:71-74) runs only on the next snapshot. `hover()` on every selection (:165-171, :191) fires site hover effects that move targets further.

**R7. Header unreachable by Up (NOT verified, hypothesis).** Most likely R1 or R4 (stale/disconnected id, focus lands on BODY), or header items missing from the snapshot. `Spatial.initial` avoids the top 18% (SnapshotProtocol.kt:53-57) but only for first focus. WP0/WP8 check with `__dbNav.debug()`.

**R8. Scrolling.** With no candidate the page scrolls 70% smoothly (SmartNav.kt:239-244); the pending move runs on a snapshot taken mid-animation (:178, FocusNav.kt:57). The target window is +/-30% of the viewport (:109), capped at 250 items (:107). `scrollIntoView` (:188-190) moves the page under a ring drawn from pre-scroll rects, corrected later via `DBBridge.cur` -> `main.post` (WebTab.kt:404-406).

**R9. Ring drawn in Compose from late rects.** BrowserScreen.kt:91-104 springs towards a rect that travelled JS -> binder thread -> main thread -> next Compose frame; it lags during scroll/animation and cannot show above the fullscreen custom view.

**R10. The page never sees the D-pad.** MainActivity.kt:64-65 consumes all navigation keys (KeyRouter.kt:65-83), so the site's own keyboard handling never runs; Kotlin re-implements seek, play toggle and reveal with guessed state (KeyRouter.kt:86-106, ChromeState.fsControls, the 6 s timer in FullscreenSession), the source of the T-032/T-036 regressions. OK is a touch, not a key, so sites that act on Enter don't get it.

**Why this design is fragile:** it copies a live DOM into Kotlin asynchronously and acts on the copy; every DOM change, scroll or animation opens a window where the copy is wrong; two focus owners drift apart; ring, touch target and DOM focus are three different things.

## B. Research summary

- **WICG CSS Spatial Navigation polyfill** (LG Electronics, MIT), https://raw.githubusercontent.com/WICG/spatial-navigation/main/polyfill/spatial-navigation-polyfill.js : distance = A + B - C - D (A Euclidean distance between closest edge points; B orthogonal offset x weight, 30 left/right, 2 up/down; C = 5 x alignment; D = intersection area). Candidates must be visible (style + three-point `elementFromPoint` hit test). Scroll containers are searched first; a container with nothing to move to scrolls (40 px step) if it can. One `keydown` listener on `window` navigates only `if (!e.defaultPrevented)`, then `preventDefault()`. Editable fields keep arrows while the caret can move.
- **Spec/explainer:** https://drafts.csswg.org/css-nav-1/ , https://wicg.github.io/spatial-navigation/ , https://github.com/WICG/spatial-navigation (archived 2026-03-23). No browser ships the JS API.
- **Chromium/WebView built-in navigation:** Blink `spatialNavigationEnabled` (https://chromium.googlesource.com/chromium/src/third_party/+/master/blink/renderer/core/frame/settings.json5). AwSettings sets `mSpatialNavigationEnabled = !hasSystemFeature(FEATURE_TOUCHSCREEN)` (on by default on TVs); `setSpatialNavigationEnabled` is `@VisibleForTesting`, not public (https://chromium.googlesource.com/chromium/src.git/+/lkgr/android_webview/java/src/org/chromium/android_webview/AwSettings.java). The app cannot tune it; it runs only as the default action of an arrow keydown, which our `preventDefault()` should suppress (verify on device). Not used as the primary engine.
- **Key mapping:** `AKEYCODE_DPAD_CENTER`/`ENTER` -> VKEY_RETURN / DOM "Enter": https://chromium.googlesource.com/chromium/src/+/HEAD/ui/events/keycodes/keyboard_code_conversion_android.cc
- **User activation:** a trusted `keydown` (except Esc) is activation-triggering: https://html.spec.whatwg.org/multipage/interaction.html#activation-triggering-input-event . Code inside our handler for a real Enter may call `click()`, `requestFullscreen()`, `play()`; this removes the reason for the ring touch (R3).
- **Script injection:** `addDocumentStartJavaScript` runs in any frame whose origin matches the rules, before page scripts; `addWebMessageListener` injects into each matching frame: https://developer.android.com/reference/androidx/webkit/WebViewCompat
- **js-spatial-navigation** (MPL 2.0), https://github.com/luke-chang/js-spatial-navigation : sections with `restrict: 'self-first'` / `'self-only'`, `straightOnly` with `straightOverlapThreshold: 0.5`, `enterTo: 'last-focused'`. Model for the container-first and Left/Right overlap rules.
- **Norigin Spatial Navigation**, https://github.com/NoriginMedia/Norigin-Spatial-Navigation : automatic spatial algorithm, focus boundaries (internals not read).
- **TV Bro**, https://github.com/truefedex/tv-bro : virtual cursor by default, optional link-to-link mode (key-handling source not read).
- **Amazon Fire TV web apps**, https://developer.amazon.com/docs/fire-tv/web-app-faq.html : div/span not focusable without tabindex; keyboard opens on Select on text inputs; prefer `transform` for focus effects.
- Puffin TV, Silk, Samsung/LG engine internals: no primary sources found.
- Earlier project research: docs/research-smooth-navigation.md.

## C. Target module map

**Rule: the page owns navigation.** Each frame runs one engine that handles keys at key time with real DOM focus and draws its own ring. Kotlin routes keys, owns Back, Menu, long-press OK and pointer mode, and taps text fields to raise the keyboard.

### New
- `app/src/main/assets/nav/core.js`: pure functions, no DOM. `globalThis.__dbNavCore = {score, pick, dedupe, visibleFraction, okAction, seekStep, isMetaText, throttle}`. Inputs are plain rects `{l,t,r,b}` and plain objects.
- `app/src/main/assets/nav/engine.js`: DOM glue. `window.__dbNav = {back(), enable(on), debug(dir?)}` plus legacy alias `window.__dbBack = __dbNav.back`. keydown/keyup listeners, candidates, scope, focus, scroll, ring, carry, OK, fullscreen, frame handoff (WP7).
- `app/src/main/assets/nav/tweaks.js`: non-navigation page tweaks moved out of SmartNav unchanged (viewport meta, eager images + `sweep`, `freezeBg`, `fixVideo`, auto-fullscreen on play `goFull`, fake fullscreen). Exposes `window.__dbUnfake()` and `window.__dbFakeFs()` (fake-fullscreen box or null).
- `browser/NavScripts.kt`: `class NavScripts(context)`, `val tweaks`, `val all` (lazy) = tweaks.js + core.js + engine.js from assets.
- `tools/navtest/core.test.mjs`, `tools/navtest/engine-smoke.test.mjs` (node:test, no dependencies).
- `app/src/test/java/com/deepbrowser/tv/browser/NavEngineJsTest.kt`: runs `node --test ../tools/navtest` (Assume-skipped if node missing) so the gate covers the JS.

### Rewritten (smaller)
- **KeyRouter.kt:** page focus mode and fullscreen: D-pad -> `Forward`; OK release -> `OkUpSmart` = real Enter to the page. Removed: `Nav`, `FsReveal`, `FsNav`, `FsOk`, `Seek`, `VideoToggle`, `KeyState.fsControls`.
- **KeySequencer.kt:** KeyEffects = `keyTouched, exitFullscreen, forward, enter, exitReader, readerScroll, back, movePointer, releasePointer, clickPointer, page`. `seekHeld` and the seek run removed.
- **ChromeState.kt:** remove `fsControls`, `keepsControls`, `hideControls`, `seekStep`, `FS_CONTROLS_MS`.
- **FullscreenSession.kt:** `show`, `exit`, `requestExit` only (no timer, no Scheduler).
- **BrowserController.kt:** remove `nav`, `focusRect`, snapshot bridge, `smartOk`. Add `forward()` (send current KeyEvent to `fullscreen.shown ?: tab.view.hostView`) and `enter()` (ENTER down+up to the same target). Call `PageApi.enable(chrome.ringVisible)` when owner or nav mode changes.
- **WebTab.kt / Tab.kt:** `PageBridge` = `ime(fx, fy)` only (viewport fractions). `JsBridge` loses `snap`/`cur`. Document-start script = NavScripts.all (when DOCUMENT_START_SCRIPT is supported).
- **PageApi.kt:** keep `back()`, reader functions, `wasClosed`. Add `enable(on)`. Remove `snap`, `select`, `carousel`, `scroll`, `reveal`, `ok`, `menuBack`, `seekBy`, `togglePlay`.
- **PointerInjector.kt:** remove `tapRing`; `tapForKeyboard(fx, fy)` taps `fx*width, fy*height`.
- **ui/BrowserScreen.kt:** remove the ring block.

### Deleted
FocusNav.kt, SnapshotProtocol.kt (`Bounds` moves into Viewport.kt), SmartNav.kt, SnapshotProtocolTest.kt. PageScripts.videoJs if unused.

### Kept as is
Ad filter (AdFilter, AdScript, FilterEngine, FilterListSource, FilterUpdater, AdBlockState, OverlayRules, BuiltInHosts); data/*, companion/*, player/*, device/*, ReaderMode, CursorState, KeyMap, FocusOwner, TabSession, PageLifecycle (minus one call), UrlResolver, SitePolicy, Viewport; Compose HomeScreen, Panels, CommandBar, Focus.kt.

### Interfaces
- Kotlin -> page: real key events (D-pad down/up/repeat, Enter for OK); `__dbNav.back()` returns "closed" or "none"; `__dbNav.enable(bool)` shows/hides the ring and turns navigation on/off.
- Page -> Kotlin: `DBBridge.ime(fx, fy)` = tap this point (fraction of the viewport) so the TV keyboard opens. Nothing else.

## D. Navigation algorithm (engine.js + core.js)

Units: CSS px of the frame viewport (VW x VH). Rects from `getBoundingClientRect()` at key time. Nothing cached between keys except `last = {el, rect}` and `lastKeyAt`.

### 1. Key intake
- `window.addEventListener('keydown', onKey)` and `keyup`, bubble phase, registered at document start.
- Ignore if `e.defaultPrevented`, `e.ctrlKey`, `e.altKey` or `e.metaKey`. Handle `ArrowUp/Down/Left/Right` and `Enter` only.
- Return at once when disabled, or when the URL matches `^https?://([a-z0-9-]+\.)*youtube\.com/tv`.
- Repeat throttle: if `e.repeat` and < 80 ms since the last handled move, `preventDefault()` and stop (`core.throttle(now, last, repeat)`).

### 2. Start point
S = `document.activeElement` if not body/html, connected and a candidate; else `last.rect`; else **initial pick**: among in-scope candidates at least 50% in the viewport, prefer centre y > 0.18*VH, take min of `t*2 + l*0.5`; focus it and stop (first press only shows focus).

### 3. Scope root
R = fullscreen element (`document.fullscreenElement || webkitFullscreenElement`), else `__dbFakeFs()`, else the topmost open modal (last in document order matching `dialog[open],[aria-modal="true"],[role=dialog],[role=alertdialog]` with non-zero rect, passing `checkVisibility`, centre hit-testing inside itself), else `document.documentElement`.

### 4. Candidates (live) for root R
- `R.querySelectorAll(SEL)`, SEL = `a[href],button,input:not([type=hidden]),select,textarea,summary,[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch],[role=slider],[onclick],video,[contenteditable=""],[contenteditable=true]`. In fullscreen also add the heuristic timeline bar (port `findSlider`, SmartNav.kt:92-101).
- Drop if: disabled or `aria-disabled=true`; inside `[inert]` or `[aria-hidden=true]`; `checkVisibility({checkOpacity:true, checkVisibilityCSS:true})` false; width or height < 8; `isMetaText(text, h)` (port of META regex SmartNav.kt:79, h <= 44); outside vertical window `b > -VH && t < 2*VH` (fullscreen: `[0, VH]`).
- Horizontal rule (removes moved carousel slides): `visibleFraction` along x >= 0.5, unless an ancestor scroll container has overflow-x `auto`/`scroll` (real horizontal scroller).
- **Dedupe:** for every pair where A contains B and area(B) >= 0.7*area(A), drop A (keep the inner one). Smaller inner controls stay separate. `core.dedupe(items)` with `items = {id, rect, anc:[ancestorIds]}`.
- Drop the start element and any candidate containing it or contained by it.

### 5. Containers (search the same container first)
- Chain = S's ancestors up to R that are containers: computed overflow-x/y `auto`/`scroll` with scroll size > client size; or `position` `fixed`/`sticky`; or role in `listbox, menu, menubar, tablist, tree, grid, dialog`.
- For each container C from innermost, then R: (1) `pick(dir, S.rect, candidates inside C)`; if found, focus. (2) If none and C can scroll in dir: scroll C by 0.6 x client size instantly, pick again within C, focus if found, stop either way. (3) Else next container.
- If nothing at R and R is the document: scroll the page 0.6*VH (or 0.6*VW for left/right if it scrolls horizontally) instantly, pick again, focus if found.

### 6. Scoring: core.pick(dir, S, cands), core.score(dir, S, C)
- Stage 1 validity: down `C.t >= S.b - 1`; up `C.b <= S.t + 1`; right `C.l >= S.r - 1`; left `C.r <= S.l + 1`.
- Stage 2 (only when stage 1 is empty in that container): candidate centre beyond S centre by > 2 px in dir, and far edge beyond S far edge by > 1 px.
- Left/Right only: vertical overlap length >= 1 px, else not a candidate.
- `main` = gap along dir (>= 0); `ortho` = gap on the other axis (0 when projections overlap); `ov` = overlap length on the other axis; `align` = ov / min(S side, C side) on that axis, clamped 0..1; `W` = 2 up/down, 30 left/right.
- **score = sqrt(main^2 + ortho^2) + W*ortho - 5*align.** Lowest wins. Ties (|delta| < 0.5): smaller distance between orthogonal centres, then document order.
- Hit test on the winner only: if within the viewport, `elementFromPoint` at the centre of its visible part must return it, a descendant or an ancestor; otherwise drop and re-pick (max 10 times).
- Fixtures: accordion values (CSS px = t3 / 1.5) must pick Play; Up from Batman Beyond must pick Play; Up from hero Play must reach a header link; Down from hero More info must pick the first TOP 10 card, not the far-right pause button.

### 7. Focus, ring, scroll
- Focus T: give `tabindex="-1"` if `T.tabIndex < 0` and no tabindex attribute; `T.focus({preventScroll:true})`; `mouseout`/`pointerout` to previous; `pointerover`/`mouseover`/`mouseenter`/`pointermove` to T (port `hover`, SmartNav.kt:165-171); port `warm` (preconnect, SmartNav.kt:313-320); set `last = {el:T, rect}`, `lastKeyAt = now`.
- Scroll: V = nearest scroll container client rect intersected with viewport, inset 10% top/bottom and 5% left/right. If T not fully inside V: `T.scrollIntoView({block: T.h > 0.8*V.h ? 'start' : 'center', inline:'nearest', behavior:'instant'})`. Always instant.
- Ring: one `div`, `position:fixed`, `pointer-events:none`, `z-index:2147483647`, `border:4px solid #8FB4FF`, `border-radius:10px`, `box-sizing:border-box`, `transition: transform 90ms, width 90ms, height 90ms`. Each animation frame read `last.el.getBoundingClientRect()`, write only on change. Move it into the fullscreen element on `fullscreenchange` and back on exit. Hidden when disabled, no focus, or element gone.
- Carry: in the same frame loop, if `last.el` is disconnected or < 50% in the viewport and no key in the last 600 ms: `elementFromPoint(centre of last.rect)`, nearest candidate (`closest(SEL)` or descendant matching SEL), focus with `preventScroll` and no hover.

### 8. OK (trusted Enter keydown from Kotlin)
Skip if `e.defaultPrevented`. T = active element (body -> initial pick). `core.okAction(tag, type, role, editable, inFullscreenWithHiddenControls)`:
- **'text'** (textarea; input type text/search/url/email/password/number/tel/empty; contenteditable): preventDefault, focus, `DBBridge.ime(cx/VW, cy/VH)`.
- **'select'**: preventDefault, `DBBridge.ime(...)` (real tap opens the native picker).
- **'video'**: preventDefault, toggle `paused`.
- **'native'** (a[href], button, summary, input checkbox/radio/button/submit/reset): do nothing; the browser's Enter activation runs with user activation.
- **'click'** (anything else): preventDefault; dispatch pointerdown/mousedown, pointerup/mouseup (`pointerType 'mouse'`, `button 0`, centre clientX/Y), then `T.click()`.
- Afterwards (as SmartNav.kt:260-265): `okAt = now`, `menuBase = candidate count`, `menuOpener = T`. After 350 ms, if the active element is a text field, `DBBridge.ime` for it.

### 9. Text fields
Keyboard closed: Up/Down navigate; Left navigates only if `selectionStart === 0`, Right only if `selectionEnd === value.length` (else default, caret moves). `input[type=range]` and `[role=slider]`: Left/Right fall through. Keyboard visible: Kotlin passes everything to the system (unchanged).

### 10. Back (`__dbNav.back()`; anything but "closed" means Kotlin does history back / exits fullscreen)
1. Player menu: fullscreen, `menuOpener` connected, candidates >= `menuBase + 3` -> click `menuOpener`, focus it, "closed" (port SmartNav.kt:210-215).
2. Fake fullscreen: `__dbUnfake()` succeeds -> "closed".
3. Real fullscreen exists -> `exitFullscreen`, "closed".
4. Open popup (open modal per step 3, an `[aria-expanded="true"]` element, or active element inside a fixed/absolute popup with z-index >= 10 narrower than 0.8*VW; port SmartNav.kt:220-228): Escape keydown/keyup to the active element and document; click the expanded opener if any; focus that opener (or `last.el`); "closed".
5. Otherwise "none".

### 11. Fullscreen video (R = fullscreen or fake-fullscreen box)
- Every key: `mousemove`/`pointermove` to R's centre and the largest video (keeps site controls visible; replaces the Kotlin 6 s timer). "Controls hidden" = no candidates in R except VIDEO.
- Up/Down while hidden or focus outside R: preventDefault, wake; after 220 ms focus the control whose aria-label/title/text matches `/play|pause/i`, else the leftmost candidate in the bottom row.
- Left/Right while hidden, on the video or on the timeline bar, page not having handled the key: seek the largest video by +/-`core.seekStep(run)` s (10,10,10,20,30,60 growing with repeats < 500 ms apart; port ChromeState.seekStep); preventDefault.
- OK while hidden: toggle play. Otherwise normal spatial navigation inside R.

### 12. Frames (WP7)
Engine runs in every frame (document-start, origin rule "*"). An iframe >= 100x60 is a candidate. Focusing it sends the next keys into its document; the frame engine runs the initial pick on its first key. A frame with no candidate that cannot scroll sends `parent.postMessage({__dbnav:'edge', dir}, '*')`; the parent finds the iframe with `contentWindow === e.source` and navigates from its rect. IME taps from a subframe go up as `{__dbnav:'ime', fx, fy}` (frame-relative), converted by each parent until the top frame calls `DBBridge`.

### 13. Debug
`__dbNav.debug(dir)` returns JSON: active element tag/text/rect, scope root tag, container chain, candidate count, top 5 scored candidates for dir. Used over CDP (.tools/cdp.mjs) on the TV.

## E. Work packages

Gate after every package: `bash .tmp/gate.sh` (unit tests + assembleDebug + lint). JS is tested by node through NavEngineJsTest. One writer per file; packages run in order.

### WP0: on-device probe (orchestrator, no code)
CDP on the current build: `document.querySelectorAll('header a, nav a, header button')` on the Movy home lists Home/Movies/Shows/Anime/Search with visible rects. Record in TASKS.md; numbers become WP1 fixtures.

### WP1: core.js + node tests + JVM runner
- Create `app/src/main/assets/nav/core.js`: one IIFE assigning `globalThis.__dbNavCore = {score, pick, dedupe, visibleFraction, okAction, seekStep, isMetaText, throttle}` exactly as D.4, D.6, D.8, D.11. No DOM.
- Create `tools/navtest/core.test.mjs`: load core.js with `vm.runInThisContext(fs.readFileSync(new URL('../../app/src/main/assets/nav/core.js', import.meta.url), 'utf8'))`; `node:test` + `node:assert`.
- Create `app/src/test/java/com/deepbrowser/tv/browser/NavEngineJsTest.kt`: find `node` on PATH or /usr/local/bin/node or /opt/homebrew/bin/node, else `Assume.assumeTrue(false)`; run `ProcessBuilder(node, "--test", "../tools/navtest").redirectErrorStream(true)`; assert exit 0 with output as failure message.
- Tests: (1) accordion Down -> Play: row {760,175,1171,237}, Play {770,311,842,342}, See more {851,311,933,342}, Beyond {760,360,1171,409}; (2) Up from Beyond -> Play; (3) Right Play -> See more, Right See more -> null; (4) Left/Right with no vertical overlap -> null; (5) Up from hero Play {96,467,205,511} -> header link {170,20,215,45} rather than header search {1090,20,1180,60}; (6) Down from More info {217,467,362,511} -> card {96,697,710,900}, not pause {1148,555,1184,591}; (7) dedupe A{0,0,100,40} containing BUTTON{0,0,100,40} keeps BUTTON, card {0,0,300,400} with button {10,10,40,40} keeps both; (8) stage 2 only when stage 1 empty; (9) visibleFraction of {-1184,0,-100,..} = 0; (10) throttle blocks repeat < 80 ms, passes non-repeats; (11) okAction covers every row of D.8; (12) seekStep run sequence 10,10,10,20,20,30,30,30,60; (13) isMetaText true for "7.7", "2022", "4K", "PG-13", false for "Play".
- Acceptance: `node --test tools/navtest` passes; gate green.

### WP2: engine.js
- Create `app/src/main/assets/nav/engine.js` implementing D.1-D.11 and D.13 with `__dbNavCore`. Guard `if (window.__dbNav) return;`. Register listeners at once; create the ring lazily once `document.body` exists.
- Create `tools/navtest/engine-smoke.test.mjs`: minimal fake `window`/`document` on `globalThis` (addEventListener no-op, querySelectorAll -> [], documentElement {}, location.href 'https://example.com/', requestAnimationFrame no-op); load core.js and engine.js; assert `__dbNav.back/enable/debug` are functions and `__dbNav.back()` === 'none'.
- Acceptance: gate green. Not wired up yet.

### WP3: tweaks.js + NavScripts
- Create `app/src/main/assets/nav/tweaks.js`: move unchanged from SmartNav.kt the viewport-meta block (:12-17, 1280/1920 literal), the play auto-fullscreen listener and goFull/fake (:268-293, exposing `window.__dbFakeFs=function(){return fake&&fake.el}`), and lazy/sweep/freezeBg/fixVideo with listeners and intervals (:294-346). Guard `if (window.__dbTweaks) return;`.
- Create `browser/NavScripts.kt`: `class NavScripts(private val context: Context) { val tweaks by lazy { asset("nav/tweaks.js") }; val all by lazy { tweaks + "\n" + asset("nav/core.js") + "\n" + asset("nav/engine.js") } }`.
- Edit SmartNav.kt (delete the moved lines), BrowserController.inject (also `v.eval(scripts.tweaks)`), ViewportTest.smartNavScriptUsesTheSameWidths (read `File("src/main/assets/nav/tweaks.js")`, assert "1280" and "1920").
- Acceptance: gate green; on TV Movy banner, eager images, auto-fullscreen as before.

### WP4: key cutover (one package)
- KeyRouter.kt: page focus mode D-pad down/up -> `Forward`; nativeKeys D-pad and OK -> `Forward`; OK down (repeat 0) -> `OkDown`, repeat -> `Consume`, up -> `OkUpLong`/`OkUpClick`/`OkUpSmart`; fullscreen BACK up -> `ExitFullscreen`, BACK down -> `Consume`, D-pad -> `Forward`, OK down -> `Consume`, OK up -> `OkUpSmart`, other -> `Pass`; remove `Nav`, `FsReveal`, `FsNav`, `FsOk`, `Seek`, `VideoToggle`, `KeyState.fsControls`.
- KeySequencer.kt: KeyEffects as section C; `Forward` -> `effects.forward()`, `OkUpSmart` -> `effects.enter()`; remove `seekHeld`, `lastSeekAt`, `seekRun`.
- ChromeState.kt: remove `fsControls`, `keepsControls`, `hideControls`, `seekStep`, `FS_CONTROLS_MS`, the FsReveal branch.
- FullscreenSession.kt: remove timer, `armHide`, `onKey`, Scheduler parameter.
- BrowserController.kt: `private var current: KeyEvent?` set in handleKey before `keys.onKey`, cleared after; `forward()` = `(fullscreen.shown ?: tab.view.hostView).dispatchKeyEvent(current)`; `enter()` = KEYCODE_ENTER ACTION_DOWN then ACTION_UP to the same target; `back(false)` and exitFullscreen use `PageApi.back()` (`fullscreen.requestExit { r -> tab.view.eval(PageApi.back(), r) }`); remove `nav`, `focusRect`, `smartOk`, `keyTouched -> fullscreen.onKey`; bridge `snap`/`cur` empty, `ime(fx, fy)` -> `pointer.tapForKeyboard(fx, fy)`; in onOwnerChanged and setNavMode eval `PageApi.enable(chrome.ringVisible)`; inject evaluates `scripts.all` instead of `SmartNav.SCRIPT`.
- WebTab.kt: `addDocumentStartJavaScript(v, scripts.all, setOf("*"))` after CODEC_PATCH (NavScripts parameter); `JsBridge.ime(fx: Float, fy: Float)`. Tab.kt: `PageBridge.ime(fx, fy)`.
- PageApi.kt: `fun enable(on: Boolean) = "window.__dbNav&&window.__dbNav.enable($on)"`; `back()` = `"(window.__dbNav?window.__dbNav.back():'none')"`.
- PointerInjector.kt: `tapForKeyboard(fx, fy)` taps `fx*w.width, fy*w.height`; remove `tapRing`.
- ui/BrowserScreen.kt: remove the ring block (lines 91-104). PageLifecycle.kt:69: drop `tab.view.eval(PageApi.snap())`.
- Tests: update KeyRouterTest, KeySequencerTest, ChromeStateTest, FullscreenSessionTest, PageLifecycleTest to the new actions/effects (e.g. dpadIsForwardedOnPagesAndInFullscreen, okReleaseSendsEnter, fullscreenBackExitsAndOkSendsEnter, nativeSitesGetDpadAndOkForwarded); delete the seek-run and fsControls tests.
- Acceptance: gate green; on TV the D-pad moves the in-page ring on Movy and OK opens the focused item.

### WP5: deletions
- Delete FocusNav.kt, SnapshotProtocol.kt (first move `data class Bounds` into Viewport.kt unchanged), SmartNav.kt, SnapshotProtocolTest.kt.
- PageApi.kt: remove snap, select, carousel, scroll, reveal, ok, menuBack, seekBy, togglePlay, video. PageScripts.kt: remove videoJs if unused. PageApiTest: enable/back/reader only. PageBridge/JsBridge: remove snap/cur. BrowserController: remove the empty overrides.
- Acceptance: gate green; `grep -rn "__dbSel\|FocusNav\|SmartNav\|focusRect" app/src` finds nothing.

### WP6: fullscreen polish
engine.js only; verify and tune D.11 on the TV (wake delay, play/pause preference, timeline heuristic, Back closing a player menu); extend core.test.mjs for any new pure helper. Acceptance: fullscreen checklist items pass.

### WP7: frames
engine.js (D.12) and core.test.mjs (edge routing test if a pure helper is added). Acceptance: a cross-origin player iframe's controls are reachable on a Movy movie page, if it uses one.

### WP8: TV checklist (orchestrator)
Run section F; screenshot + `__dbNav.debug()` output for each failure.

## F. On-device acceptance checklist (Fire TV, debug build)

1. **Movy home**: first key shows focus on hero Play (not the logo); Right -> More info, Right again stays; Up -> header item, Left/Right walk the header, Down -> hero; Down from hero -> TOP 10 row (page scrolls), Left/Right along the row, Down to next row; focus left on the hero 15 s while it rotates: ring stays (carry), `document.activeElement` never BODY.
2. **Search**: header Search, OK on input -> Fire keyboard; type "batman", close keyboard; Down -> first result; OK on "The Batman (2022)" expands; Down -> Play; Right -> See more; Down -> next result; Up -> Play; OK on Play starts playback; Back closes the accordion or dropdown and focus returns to its opener.
3. **Movie detail page**: Play, Add, Download, episode/server lists all reachable; OK on Play starts the player, not "More info".
4. **Fullscreen**: Up shows controls with focus on play/pause; Left/Right move among controls; on the timeline Left/Right seek; with controls hidden Left/Right seek +/-10 s (growing while held) and OK toggles play; quality/audio/subtitle menus open and items are selectable; Back closes a menu first, then leaves fullscreen; no key does two things.
5. **YouTube TV**: D-pad, OK, Back behave as the site expects; no DeepBrowser ring.
6. **Plain article site** (https://en.wikipedia.org/wiki/Television): Down walks links in reading order, scrolls instantly; Up at the top reaches search, OK opens the keyboard; reader mode still toggles.
7. **App**: long-press OK opens the command bar without clicking the page; pointer mode works and hides the ring; panels and Home unchanged.

## G. Risks and what to verify first

Verify first (WP4 on the TV, with CDP):
1. A forwarded D-pad KeyEvent arrives in the page as a trusted `keydown` with correct `key` and `repeat`; same through `FullscreenHost.shown`.
2. A synthesized KEYCODE_ENTER is trusted and grants activation (`navigator.userActivation.isActive` in the handler; Movy Play enters fullscreen).
3. With the engine disabled, do arrows move focus by themselves (Blink built-in navigation on by default on TVs)? With the engine on, `preventDefault` must suppress it, or the page moves twice per key.
4. `addDocumentStartJavaScript` runs in Movy's iframes (WP7).
5. Header reachability (R7) via `__dbNav.debug('up')`.

Other risks: sites handling arrows on `window` after us without `preventDefault` act twice (add SitePolicy exceptions only when seen); clickable divs without role/tabindex are not candidates (later: `cursor:pointer` near the start point); column memory on Up/Down dropped (watch zig-zag on uneven grids); synthesized hover may trigger previews that move targets; dots >= 8 px become targets; per-key candidate collection cost (throttled to ~12 moves/s; profile if > 16 ms); the fullscreen custom view may not pass keys into the page (fallback: forward to `tab.view.hostView`).
