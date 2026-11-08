# DeepBrowser: improvement and growth research (2026-11-08)

Mode: standard, 6 web searches. Findings labelled SOURCED (from a search result), or OPINION (my inference). Many roundup sites are low-quality SEO lists; treat competitor claims as indicative only. Not opened/verified: Google's TV quality page, Play TV policy, Amazon's browser requirements.

## 1. Competitive picture (SOURCED)
- **TV Bro** (open source, ~1.4k stars): D-pad focus plus automatic pointer mode on sites, voice search, ad blocker, tabs, bookmarks, history, incognito, UA switch, download manager, custom shortcuts. It is the closest competitor. [TV Bro review](https://www.makeuseof.com/browse-on-smart-tv-with-tvbro/), [repo](https://github.com/truefedex/tv-bro)
- **Silk**: preinstalled on Fire TV, usually named best for streaming sites; some reviewers still call its remote navigation awkward. [roundup](https://rigorousthemes.com/blog/best-browsers-for-fire-tv/), [dissent](https://www.ofzenandcomputing.com/best-browser-for-smart-tv/)
- **Puffin TV**: cloud-rendered, so pages and data pass through its servers; subscription since 2020. [Puffin](https://en.wikipedia.org/wiki/Puffin_Browser)
- **Firefox**: legacy on Fire TV, not TV-optimised.
- Where DeepBrowser can win: card-level smart focus (Netflix-like), fake-ad/overlay killing, auto-fullscreen with the site's own controls, phone typing page, local rendering (no cloud), and TV-native design. TV Bro's pointer-first model is the weaker feel.

## 2. Platform limits (SOURCED)
- Codecs and DRM depend on device hardware decoders and the Widevine level, not on the browser engine. Fire TV lists Widevine with H.264, HEVC, VP8 and VP9 (L1 or L3 by device). [DoveRunner](https://docs.doverunner.com/content-security/multi-drm/getting-started/supported-env/)
- System WebView lacks Widevine VMP, so Netflix-class apps will not play in any WebView browser. GeckoView needs Widevine enabled explicitly. [Vuplex](https://support.vuplex.com/articles/how-to-enable-widevine/)
- A WebView DRM sample on Fire TV failed until `PROTECTED_MEDIA` permission was added. [Shaka issue](https://github.com/google/shaka-player/issues/1722). OPINION: check our manifest for it.
- **Amazon restricts third-party browser apps on Fire TV and may allow them only if they meet privacy and security requirements.** Expect sideload-only distribution on Fire TV unless Amazon approves. Google TV/Play TV rules were not verified. [Amazon](https://developer.amazon.com/docs/app-submission/device-filtering-and-compatibility.html)
- Fire TV apps must pause audio on the Voice Search button, and need touchscreen=false in the manifest. [Amazon FAQ](https://developer.amazon.com/docs/app-submission/faq-submission.html)
- Don't mix `androidx.compose.material3` with `androidx.tv.material3`; mobile focus indicators are too faint at 10 ft. [guide](https://leanware.co/insights/android-tv-application-development-complete-guide). Keep ~5% overscan safe margin. [Android Developers](https://developer.android.com/develop/adaptive-apps/guides/tv/build-adaptive-apps-for-tv)

## 3. Ad blocking (SOURCED)
- Current host list is small. A real filter engine is feasible: [AdblockAndroid](https://github.com/Edsuns/AdblockAndroid) (Brave C++ engine, EasyList/AdGuard compatible, hooks `shouldInterceptRequest` plus script injection).
- Brave's Rust engine matches in microseconds; the 2026 rewrite cut memory 75%. [Brave](https://brave.com/blog/improved-ad-blocker-performance/), [Register](https://www.theregister.com/2026/01/06/brave_refurbishes_rust_adblocking_engine/). I found no ready WebView binding for adblock-rust (would need JNI/UniFFI). On a Stick, load time and memory matter more than match speed.

## 4. Prioritised roadmap (OPINION, from sources plus what we have built)
**P0: fix what users feel now**
1. Hero banner Left/Right and Play/More-info grouping; first-key focus landing. Verify website search typing.
2. Smoothness on heavy pages: freeze hero autoplay/animations, lighter focus-sync, measure with gfxinfo (Movy p90 ~28 ms).
3. Back-stack correctness for SPA history; confirm `PROTECTED_MEDIA` permission and DRM sites behaviour.
4. Cross-origin iframe players: inject our script in all frames so player menus (quality/server) are navigable.

**P1: table stakes versus TV Bro**
5. Tabs, bookmarks, history, incognito, UA switch (desktop/mobile/TV), download manager. Settings screen.
6. Real filter engine (AdblockAndroid-style, EasyList + EasyPrivacy, auto-updated), with a per-site toggle and an "ads blocked" counter.
7. Remote shortcuts (long-press OK = context menu, Menu = command bar, number keys = tiles).
8. Voice: Fire keyboard mic or phone page works; add a clear on-screen hint.

**P2: differentiators**
9. Reader/"TV mode" that restyles article pages for 10 ft (big text, single column).
10. Native DeepPlayer (Media3 + FFmpeg extension) for direct video URLs: all codecs, subtitles, speed, PiP.
11. Phone companion v2: send URL, remote touchpad, paste text, clipboard.
12. Profiles with per-profile bookmarks; screensaver-safe idle handling; casting-friendly "continue on TV" links.
13. Privacy: tracker blocking dashboard, cookie-banner auto-decline, HTTPS-only, clear-on-exit.

**P3: distribution**
14. Decide channels: Play TV (verify policy) and sideload for Fire TV; ask Amazon about the browser exception before investing.
15. Accessibility: large-text mode, high-contrast focus ring option, TalkBack labels on native UI.

## 5. UI design-system recommendations (OPINION; design-system skill gave a generic token scheme)
- Three token layers: primitive colours → semantic (`surface`, `onSurface`, `accent`, `focus`) → component (`tile`, `bar`, `chip`). Replace raw hex in Compose with semantic tokens in `Palette`.
- Use only `androidx.tv.material3`; mobile Material3 focus is too weak at 10 ft.
- Focus state = scale 1.06–1.1 plus 3–4 dp accent border plus lift; never colour alone. Motion 120–200 ms spring, honour reduced-motion.
- Text floor ~24 sp body (unverified against official docs), 48 sp+ headings; 5% overscan margin; tiles ≥ 200×112 dp with ≥ 24 dp gaps.
- Opaque surfaces for text-carrying panels (matches your earlier "too transparent" feedback); keep glass only for decorative layers. Contrast 4.5:1.
- Dark-first theme; one accent hue; max two type weights.

## Not covered
Google Play TV policy text, Amazon's actual browser privacy criteria, real-device benchmarks of engine choices, Kodi-style/launcher integration, monetisation (deliberately skipped; ads conflict with an ad-blocking product, so consider donations or a paid pro tier later).
