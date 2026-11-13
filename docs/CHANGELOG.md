# Changelog

## 0.1.2
- D-pad at a dead end now does nothing; before, the WebView's own spatial navigation jumped far away (Right from a Movy row heading landed on the slideshow dots).
- A card's caption link (same address, right under the card) is no longer a second stop.
- Up/Down inside a row only goes to items fully past the current one, so Down from Movy's wide TOP 1 card reaches the next row instead of TOP 2.
- YouTube TV: the home-page ad tile is no longer hidden; hiding it left YouTube's focus on an invisible tile and the remote did nothing.

## 0.1.1
- Smooth scrolling when D-pad focus moves off-screen (was an instant jump); fast repeated keys keep the focused item in view.
- Less idle main-thread work on the TV: the focus ring loop checks player mode every 250 ms instead of every frame, and the overlay scanner reads `innerText` only for elements whose text matches a scam phrase.
- First D-pad press on a page skips tiny icons (e.g. 16 px vote buttons) so detail pages start on Play.

## 0.1.0
- First release.
