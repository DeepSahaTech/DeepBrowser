# Changelog

## 0.1.1
- Smooth scrolling when D-pad focus moves off-screen (was an instant jump); fast repeated keys keep the focused item in view.
- Less idle main-thread work on the TV: the focus ring loop checks player mode every 250 ms instead of every frame, and the overlay scanner reads `innerText` only for elements whose text matches a scam phrase.
- First D-pad press on a page skips tiny icons (e.g. 16 px vote buttons) so detail pages start on Play.

## 0.1.0
- First release.
