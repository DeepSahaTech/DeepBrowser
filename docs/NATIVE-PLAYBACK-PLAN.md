# Native playback for videos the WebView shows black (T-046)

## Evidence (Fire TV Stick 4K Max, Amazon WebView 138, 2026-11-13)
- Some H.264 streams (Movy "Spider-Man" 1994 S1E1 `/tv/888`, avc1.640028 1280x960; "Spider-Man: Brand New Day" `/movie/969681`,
  avc1.640032 2592x1080) decode at full rate in the WebView (24 fps, ~0 dropped, readyState 4, no EME, bt709) but every frame is
  black (canvas average 0), on every server. The same bytes show a picture in desktop Chrome. Other titles with the same codec
  and size are fine. The original main build is also black (not a regression).
- The first 4 video `appendBuffer` chunks (init `ftyp/moov` + 3 `moof/mdat` fragments, 5.8 MB) captured from the page and
  played in the app's Media3 player show a full picture. So feeding the page's MSE data to the native player fixes it.
- The site's source API is signed (`/seed`, 30 s TTL), so asking the site for a playlist is not an option. The MSE bridge is
  generic and works on any site.

## Design
1. **Page side** (`assets/nav/mse.js`, injected at document start in every frame, before `tweaks.js`):
   - Wrap `MediaSource.prototype.addSourceBuffer`: per SourceBuffer keep `{id, mime, init (Uint8Array), timescale, segs[]}`.
     The init is the first append(s) up to the first `moof`. Each later append is parsed for `moof` start time
     (`tfdt.baseMediaDecodeTime / timescale` from the init's `mdhd`), and stored as `{t, bytes}`. Keep at most 48 MB per page
     (drop oldest segments first). Pure parsing helpers live in `assets/nav/msecore.js` (`boxes`, `timescale`, `fragTime`).
   - **Black detection** (only for a video with `__dbUser`, i.e. started by a real OK/click; see tweaks.js): after it has
     played 3 s with `currentTime` advancing and `requestVideoFrameCallback` firing, sample a 32x18 canvas 3 times, 1.5 s apart.
     If every sample has average RGB < 3, request takeover. Once per video element.
   - **Takeover**: post `{type:'start', sbs:[{id,mime,timescale}], t: video.currentTime}`; then for each SB post its init
     (`{type:'init', sb, data}`) and every stored segment with `t >= currentTime - 12` in order (`{type:'seg', sb, t, data}`);
     from then on forward each new append live. Mute and keep the page video playing (it is the data pump). Show nothing.
   - **Commands from the app** (via the reply proxy): `seek(t)`: set `video.currentTime = t`, increase `gen`, post
     `{type:'reset', gen, t}`, then re-send each SB's init and forward new appends (tagged with `gen`). `pause`/`play`: mirror
     on the page video (keeps the site's buffer near the native position). `stop`: stop forwarding, pause the page video.
   - Transport: `window.dbMse.postMessage(obj)` for JSON and `window.dbMse.postMessage(arrayBuffer)` for bytes. Each binary
     message is preceded by a JSON header `{type:'init'|'seg', sb, gen, t}`; the next binary message is its body.
2. **App side**:
   - `player/StreamStore.kt` (pure, unit-tested): per track (`sb` id) an append-only byte stream for the current generation;
     `append(sb, gen, bytes)`, `reset(gen)`, `close()`. `GrowingInput.read()` blocks (with 15 s timeout) until bytes arrive,
     returns end of stream after `close()`. Old generations are discarded on `reset`.
   - `player/GrowingDataSource.kt`: a Media3 `DataSource` over one track of a `StreamStore` (unknown length, not seekable).
   - `player/BridgeSession.kt`: a process-wide registry `{id -> session(store, sbs, startTime, commandSink)}`; the command
     sink posts `seek/pause/play/stop` back to the page through the `JavaScriptReplyProxy`.
   - `player/BridgePlayerActivity.kt` (not exported): builds one `ProgressiveMediaSource` (DefaultExtractorsFactory, fMP4)
     per track and a `MergingMediaSource` when there are two (video + audio); plays with the same controls and key handling
     as `PlayerActivity` (reuse `PlayerLogic`: OK toggle, Left/Right seek). A seek calls `session.command("seek", t)`; on the
     page's `reset` message the activity rebuilds the media source for the new generation and continues. Back: `stop`,
     finish, return to the page (page video paused at the native position).
   - `browser/WebTab.kt`: `WebViewCompat.addWebMessageListener(view, "dbMse", setOf("*"), listener)` when
     `WebViewFeature.WEB_MESSAGE_LISTENER` is supported; binary bodies need `WEB_MESSAGE_ARRAY_BUFFER` (else skip the feature).
     The listener feeds `BridgeSession`; on `start` the controller launches `BridgePlayerActivity`.
3. **Non-goals (v1)**: EME/DRM streams; MSE in cross-origin iframes is captured too but only the frame that sent `start` is
   bridged; subtitles drawn by the site are not shown in the native player.

## Work packages (each: gate `bash .tmp/gate.sh` green; one writer per file)
- **NP1** `player/StreamStore.kt` + `player/GrowingDataSource.kt` + `StreamStoreTest.kt` (append/read order, blocking read
  wakes on append, generations, close returns end of stream, timeout).
- **NP2** `assets/nav/msecore.js` + `tools/navtest/msecore.test.mjs` (box walk, `mdhd` timescale, `tfdt` v0/v1 time, using
  the real init/segment bytes in `.tmp/sp-capture.mp4` copied to a small fixture `tools/navtest/fixtures/frag.bin` (first
  init + first moof header only, < 64 KB)); add the file to NavEngineJsTest.
- **NP3** `player/BridgeSession.kt` + `player/BridgePlayerActivity.kt` + manifest entry + `BridgeSessionTest.kt`.
- **NP4** `browser/WebTab.kt` listener + `BrowserController` launch + `NavScripts` includes msecore.js + mse.js first.
- **NP5** `assets/nav/mse.js` (capture, detection, takeover, commands) + smoke test.
- **NP6** TV verification: Spider-Man S1E1 and Brand New Day switch to the native player with a picture within ~6 s;
  OK pause/play, Left/Right seek, Back returns to the page; Heart of the Beast (works in WebView) does NOT switch.
