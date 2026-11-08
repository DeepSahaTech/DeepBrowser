# DeepBrowser

A remote-first web browser for Android TV, Google TV and Fire TV. Navigate with the D-pad: focus jumps between whole cards and buttons, sliders switch with Left/Right, and the Back button closes menus and fullscreen before leaving a page.

## Features
- Spatial D-pad navigation with an animated focus ring (optional mouse cursor)
- Built-in ad and popup blocking with an updatable filter list (see below)
- Auto-fullscreen video that keeps the site's own controls; native player for direct media links
- Tabs, bookmarks, history, settings, reader mode
- Phone companion page for typing, pasting and sending URLs to the TV

## Ad-block filter list
The app downloads `filters/easylist.txt` from this repository:

`https://raw.githubusercontent.com/DeepSahaTech/DeepBrowser/main/filters/easylist.txt`

Replace that file with any Adblock Plus-syntax list (for example a fresh EasyList or EasyPrivacy) and commit it. The browser picks it up from Settings → Update filter list, and refreshes on its own every few days.

## Build
Requires JDK 21 and the Android SDK (compileSdk 35).

```
./gradlew :app:assembleDebug
```

## Licence
Not yet chosen. The optional FFmpeg audio decoder dependency is GPL-3.0; check its terms before redistributing builds.
