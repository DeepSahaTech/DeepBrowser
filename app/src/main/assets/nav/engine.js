// Remote-control navigation engine: DOM glue around core.js. One instance per frame.
// Everything is read at key time from live rects, so the only state kept is `last`.
(function () {
  'use strict';
  if (window.__dbNav) return;

  var core = globalThis.__dbNavCore;
  var doc = document;
  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,' +
    '[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=tab],[role=menuitem],' +
    '[role=option],[role=checkbox],[role=switch],[role=slider],[onclick],video,' +
    '[contenteditable=""],[contenteditable=true]';
  var MODAL = 'dialog[open],[aria-modal="true"],[role=dialog],[role=alertdialog]';
  var CONTAINER_ROLE = /^(listbox|menu|menubar|tablist|tree|grid|dialog)$/;
  var YT_TV = /^https?:\/\/([a-z0-9-]+\.)*youtube\.com\/tv/i;
  var NATIVE_TAG = /^(A|BUTTON|INPUT|SELECT|TEXTAREA|SUMMARY|VIDEO|IFRAME)$/;
  var RING_CSS = 'position:fixed;left:0;top:0;pointer-events:none;z-index:2147483647;' +
    'border:4px solid #8FB4FF;border-radius:10px;box-sizing:border-box;display:none;' +
    'transition:transform 90ms,width 90ms,height 90ms';

  var enabled = true, looping = false;
  var last = null;                 // {el, rect}: focused element and its latest rect
  var exitTriedAt = 0;             // last Back that tried the player's own exit arrow
  var lastKeyAt = 0, lastMoveAt = 0;
  var menuBase = 0, menuOpener = null;
  var seekRun = 0, seekAt = 0, jitter = 0;
  var consumed = {};               // keys we handled on keydown; their keyup is swallowed too
  var playerEvt = null;            // keydown already offered to the engine by the capture pass
  var playerUp = {};               // keys consumed in the capture pass; their keyup is swallowed there
  var ids = new WeakMap(), nextId = 1, warmed = {};
  var ringEl = null, ringShown = false, ringKey = '';
  var returnTo = null;             // {el, rect} of the last OK: where focus goes back when what it opened closes
  var wasMode = false;             // videoMode() on the previous frame
  var smoothUntil = 0;             // a smooth scroll may still be moving the page until then
  var modeAt = 0;                  // when the frame loop last asked videoMode() (it queries the DOM: not every frame)
  var inFrame = window !== window.top;   // iframes draw the ring only while they hold document focus

  // ---- small DOM helpers (every call tolerates missing APIs) ----
  function vw() { return window.innerWidth || doc.documentElement.clientWidth || 0; }
  function vh() { return window.innerHeight || doc.documentElement.clientHeight || 0; }
  function cx(r) { return (r.l + r.r) / 2; }
  function cy(r) { return (r.t + r.b) / 2; }
  function rectOf(el) {
    if (!el || typeof el.getBoundingClientRect !== 'function') return null;
    var b = el.getBoundingClientRect();
    return { l: b.left, t: b.top, r: b.right, b: b.bottom };
  }
  function styleOf(el) { return getComputedStyle(el) || {}; }
  function scrolls(v) { return v === 'auto' || v === 'scroll'; }
  function isBody(el) { return !el || el === doc.body || el === doc.documentElement; }
  function isVisible(el) {
    return typeof el.checkVisibility !== 'function' ||
      el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
  }
  function textOf(el) { return String(el.innerText || el.textContent || '').trim(); }
  function labelOf(el) { return [el.getAttribute('aria-label'), el.title, textOf(el)].join(' '); }
  function idOf(el) {
    var v = ids.get(el);
    if (!v) { v = nextId++; ids.set(el, v); }
    return v;
  }
  function ancestorIds(el) {
    var out = [];
    for (var p = el.parentElement; p && out.length < 25; p = p.parentElement) out.push(idOf(p));
    return out;
  }
  function hitAt(x, y) {
    return typeof doc.elementFromPoint === 'function' ? doc.elementFromPoint(x, y) : null;
  }
  // Share of the rect's area inside the viewport.
  function viewFrac(r) {
    var a = (r.r - r.l) * (r.b - r.t);
    if (!(a > 0)) return 0;
    var w = Math.min(r.r, vw()) - Math.max(r.l, 0), h = Math.min(r.b, vh()) - Math.max(r.t, 0);
    return w <= 0 || h <= 0 ? 0 : (w * h) / a;
  }
  function describe(el) {
    return { tag: el.tagName, text: textOf(el).slice(0, 60), rect: rectOf(el) };
  }
  function isTextEl(a) {
    return !!a && core.okAction(a.tagName, a.type, '', a.isContentEditable) === 'text';
  }
  function isSliderEl(a) {
    return !!a && (a.getAttribute('role') === 'slider' || (a.tagName === 'INPUT' && a.type === 'range'));
  }
  // The player timeline: the findSlider bar, or any slider at least half as wide as the mode root.
  function isTimelineEl(a, R) {
    if (!a) return false;
    if (a === findSlider(R)) return true;
    if (!isSliderEl(a)) return false;
    var r = rectOf(a), m = rectOf(R);
    return !!r && !!m && (r.r - r.l) >= 0.5 * (m.r - m.l);
  }
  // A native slider that is not the timeline (e.g. volume) keeps Left/Right in player mode.
  function sliderNotTimeline(a, mode) {
    return !!mode && isSliderEl(a) && !isTimelineEl(a, mode.root);
  }
  // Caret owns Left/Right while it is inside the text (selection rules of D.9).
  function caretBusy(a, dir) {
    if (typeof a.selectionStart !== 'number') return false;
    return dir === 'left' ? a.selectionStart !== 0 : a.selectionEnd !== String(a.value || '').length;
  }

  // ---- scope and fullscreen (D.3, D.11) ----
  function fsRootEl() {
    var f = doc.fullscreenElement || doc.webkitFullscreenElement;
    if (f) return f;
    try { return (typeof window.__dbFakeFs === 'function' && window.__dbFakeFs()) || null; } catch (e) { return null; }
  }
  // Topmost open modal whose centre is hit inside itself.
  function modalEl() {
    var list = Array.prototype.slice.call(doc.querySelectorAll(MODAL));
    for (var i = list.length - 1; i >= 0; i--) {
      var m = list[i], r = rectOf(m);
      if (!r || r.r - r.l <= 0 || r.b - r.t <= 0 || !isVisible(m)) continue;
      var hit = hitAt(cx(r), cy(r));
      if (hit && (hit === m || m.contains(hit))) return m;
    }
    return null;
  }
  // Plain-DIV overlay (no ARIA): a fixed ancestor of the centre hit that covers >= 40% of the viewport.
  function overlayRoot() {
    var el = hitAt(vw() / 2, vh() / 2);
    if (!el) return null;
    var top = null;
    for (var p = el; !isBody(p); p = p.parentElement) {
      if (styleOf(p).position === 'fixed') top = p;
    }
    if (!top) return null;
    var r = rectOf(top);
    if (!r || (r.r - r.l) * (r.b - r.t) < 0.4 * vw() * vh()) return null;
    var list = Array.prototype.slice.call(top.querySelectorAll(SEL));
    return list.some(isVisible) ? top : null;
  }
  function scopeRoot() {
    var fs = fsRootEl();
    if (fs) return fs;
    var m = modalEl();
    if (m) return m;
    var v = playerVideo();
    if (v) return playerRoot(v);
    return overlayRoot() || doc.documentElement;
  }
  // Share of the viewport covered by the visible part of a rect.
  function clipFrac(r) {
    var w = Math.min(r.r, vw()) - Math.max(r.l, 0), h = Math.min(r.b, vh()) - Math.max(r.t, 0);
    return w <= 0 || h <= 0 ? 0 : (w * h) / ((vw() * vh()) || 1);
  }
  // Page-level player (no browser fullscreen): the largest visible video covering >= 80% of the viewport.
  // A muted autoplay video is an idle banner, not a player.
  function playerVideo() {
    var best = null, bestF = 0.8;
    Array.prototype.forEach.call(doc.querySelectorAll('video'), function (v) {
      // Only a video the user started (flagged by tweaks.js on a real OK/click) is a player; a full-width
      // banner trailer the site autoplays is not, even when unmuted.
      if (!v.__dbUser) return;
      var r = rectOf(v), f = r ? clipFrac(r) : 0;
      if (f >= bestF && isVisible(v)) { bestF = f; best = v; }
    });
    return best;
  }
  // Nearest ancestor covering >= 80% of the viewport that holds a non-VIDEO candidate (controls shown).
  // Controls hidden: the nearest covering ancestor. Nothing covering: the document.
  function playerRoot(v) {
    var covering = null;
    for (var p = v.parentElement; p && !isBody(p); p = p.parentElement) {
      var r = rectOf(p);
      if (!r || clipFrac(r) < 0.8) continue;
      if (!covering) covering = p;
      if (candidates(p, true).some(function (c) { return c.el.tagName !== 'VIDEO'; })) return p;
    }
    return covering || doc.documentElement;
  }
  // Fullscreen or player mode: the root and video that get the fullscreen rules. Null on a plain page.
  function videoMode() {
    var fs = fsRootEl();
    if (fs) return { root: fs, video: largestVideo(fs), player: false };
    if (modalEl()) return null;
    var v = playerVideo();
    return v ? { root: playerRoot(v), video: v, player: true } : null;
  }
  function largestVideo(R) {
    var list = Array.prototype.slice.call(R.querySelectorAll('video'));
    if (R.tagName === 'VIDEO') list.unshift(R);
    var best = null, bestA = -1;
    list.forEach(function (v) {
      var r = rectOf(v), a = r ? (r.r - r.l) * (r.b - r.t) : 0;
      if (a > bestA) { bestA = a; best = v; }
    });
    return best;
  }

  // ---- candidates (D.4) ----
  // Timeline bar heuristic, ported from SmartNav findSlider: wide, thin, text-free box near the bottom.
  function findSlider(R) {
    var W = vw(), H = vh();
    var ds = Array.prototype.slice.call(R.querySelectorAll('[role=slider],div'), 0, 1500);
    for (var i = 0; i < ds.length; i++) {
      var e = ds[i], r = rectOf(e);
      if (!r) continue;
      var h = r.b - r.t, w = r.r - r.l;
      if (e.getAttribute('role') !== 'slider' && (w < W * 0.6 || h < 16 || h > 64 || r.t < H * 0.6 ||
          textOf(e) || e.querySelector('button,a,input,video,svg'))) continue;
      if (w >= W * 0.4 && isVisible(e)) return e;
    }
    return null;
  }
  function hScroller(el) {
    for (var p = el.parentElement; p && p !== doc.body; p = p.parentElement) {
      if (scrolls(styleOf(p).overflowX) && p.scrollWidth > p.clientWidth) return true;
    }
    return false;
  }
  function liveCandidate(e, full) {
    if (e.disabled || e.getAttribute('aria-disabled') === 'true') return null;
    if (e.closest && e.closest('[inert],[aria-hidden="true"]')) return null;
    if (!isVisible(e)) return null;
    var r = rectOf(e), VH = vh();
    if (!r || r.r - r.l < 8 || r.b - r.t < 8) return null;
    if (core.isMetaText(textOf(e), r.b - r.t)) return null;
    if (full ? (r.b <= 0 || r.t >= VH) : (r.b <= -VH || r.t >= 2 * VH)) return null;
    // Horizontal rule drops carousel slides moved off-screen, unless a real horizontal scroller holds them.
    if (core.visibleFraction(r, vw()) < 0.5 && !hScroller(e)) return null;
    return { id: idOf(e), rect: r, anc: ancestorIds(e), el: e };
  }
  function candidates(R, full) {
    var els = Array.prototype.slice.call(R.querySelectorAll(SEL));
    if (full) {
      var sl = findSlider(R);
      if (sl && els.indexOf(sl) < 0) els.push(sl);
    }
    var items = [];
    for (var i = 0; i < els.length; i++) {
      // Outside fullscreen a video is a target only if it shows its own controls (banner trailers are not).
      if (!full && els[i].tagName === 'VIDEO' && !els[i].controls) continue;
      var c = liveCandidate(els[i], full);
      if (c) { if (c.el.tagName === 'A') c.href = c.el.href; items.push(c); }
    }
    var kept = core.twins(core.dedupe(items));
    // Non-native wrappers (e.g. a focusable scroller around card rows) would take focus as one big box.
    return kept.filter(function (c) {
      if (NATIVE_TAG.test(c.el.tagName)) return true;
      return !kept.some(function (o) { return o.el !== c.el && c.el.contains(o.el); });
    });
  }

  // ---- start point and initial pick (D.2) ----
  function startFrom(a) {
    if (a && !isBody(a) && a.isConnected) {
      var ra = rectOf(a);
      if (ra) return { el: a, rect: ra };
    }
    if (!last) return null;
    var connected = !!last.el && last.el.isConnected;
    return { el: connected ? last.el : null, rect: (connected && rectOf(last.el)) || last.rect };
  }
  function initialPick(cands) {
    var VH = vh(), best = null, bestV = Infinity;
    var ok = cands.filter(function (c) { return viewFrac(c.rect) >= 0.5; });
    // Skip fixed/sticky bars (site menus); a fixed screen band would also skip detail-page Play buttons near the top.
    var pref = ok.filter(function (c) {
      var r = c.rect, small = (r.b - r.t) < 0.08 * VH && r.t < 0.1 * VH; // top-bar icons (back, mute, logo)
      var tiny = r.r - r.l < 24 || r.b - r.t < 24;   // 16 px vote/like icons above a detail page's Play button
      return !inFixedOrSticky(c.el) && c.el.tagName !== 'VIDEO' && !small && !tiny;
    });
    if (pref.length) ok = pref;
    ok.forEach(function (c) {
      var v = c.rect.t * 2 + c.rect.l * 0.5;
      if (v < bestV) { bestV = v; best = c; }
    });
    return best;
  }

  // ---- containers and scrolling (D.5) ----
  function isContainer(p) {
    if (CONTAINER_ROLE.test(p.getAttribute('role') || '')) return true;
    var cs = styleOf(p);
    if (cs.position === 'fixed' || cs.position === 'sticky') return true;
    return (scrolls(cs.overflowY) && p.scrollHeight > p.clientHeight) ||
      (scrolls(cs.overflowX) && p.scrollWidth > p.clientWidth);
  }
  function containers(el, R) {
    var out = [];
    for (var p = el ? el.parentElement : null; p && p !== R && p !== doc.documentElement && out.length < 30;
      p = p.parentElement) {
      if (isContainer(p)) out.push(p);
    }
    return out;
  }
  function scroller(C) {
    return isBody(C) ? (doc.scrollingElement || doc.documentElement) : C;
  }
  function canScroll(C, dir) {
    var s = scroller(C), vert = dir === 'up' || dir === 'down';
    // Only real scroll containers scroll: a fixed header with visible overflow looks taller than it is.
    if (s !== doc.documentElement && s !== doc.scrollingElement) {
      var cs = styleOf(s);
      if (!scrolls(vert ? cs.overflowY : cs.overflowX)) return false;
    }
    if (dir === 'up') return s.scrollTop > 1;
    if (dir === 'down') return s.scrollTop < s.scrollHeight - s.clientHeight - 1;
    if (dir === 'left') return s.scrollLeft > 1;
    return s.scrollLeft < s.scrollWidth - s.clientWidth - 1;
  }
  function scrollBy(C, dir, frac) {
    var s = scroller(C), vert = dir === 'up' || dir === 'down';
    var sign = dir === 'down' || dir === 'right' ? 1 : -1;
    var amt = sign * frac * (vert ? s.clientHeight : s.clientWidth);
    var o = vert ? { top: amt, behavior: 'instant' } : { left: amt, behavior: 'instant' };
    if (s === doc.documentElement || s === doc.scrollingElement) window.scrollBy(o);
    else s.scrollBy(o);
  }
  function nearestScroller(el) {
    for (var p = el.parentElement; p && p !== doc.documentElement; p = p.parentElement) {
      if (scrolls(styleOf(p).overflowY) && p.scrollHeight > p.clientHeight) return p;
    }
    return null;
  }
  // Bring el inside the inset visible area (D.7), with a smooth scroll.
  function scrollInto(el) {
    var r = rectOf(el);
    if (!r || typeof el.scrollIntoView !== 'function') return;
    var sc = nearestScroller(el), b = sc ? rectOf(sc) : null;
    var VW = vw(), VH = vh();
    var V = { l: Math.max(0, b ? b.l : 0), t: Math.max(0, b ? b.t : 0),
      r: Math.min(VW, b ? b.r : VW), b: Math.min(VH, b ? b.b : VH) };
    var w = V.r - V.l, h = V.b - V.t;
    var I = { l: V.l + 0.05 * w, r: V.r - 0.05 * w, t: V.t + 0.1 * h, b: V.b - 0.1 * h };
    // In view mid-scroll proves nothing: the running scroll can carry it out, so re-aim at this element.
    if (r.t >= I.t && r.b <= I.b && r.l >= I.l && r.r <= I.r && Date.now() > smoothUntil) return;
    smoothUntil = Date.now() + 800;
    // Smooth: an instant jump loses the eye's place on a TV; the ring follows the element every frame.
    el.scrollIntoView({ block: (r.b - r.t) > 0.8 * (I.b - I.t) ? 'start' : 'center', inline: 'nearest', behavior: 'smooth' });
  }

  // ---- scoring with hit-test retry (D.6) ----
  function hitOk(p) {
    if (typeof doc.elementFromPoint !== 'function') return true;
    var r = p.rect, VW = vw(), VH = vh();
    if (r.b <= 0 || r.t >= VH || r.r <= 0 || r.l >= VW) return true;   // off-screen: nothing to test
    // Partly off-screen: it gets scrolled into view, and its visible sliver may sit under a fixed header.
    if (r.t < 0 || r.b > VH) return true;
    // Three points down the centre line: a fixed header can cover the top of a partly scrolled row.
    var x = (Math.max(r.l, 0) + Math.min(r.r, VW)) / 2;
    var t = Math.max(r.t, 0), b = Math.min(r.b, VH);
    for (var i = 0; i < 3; i++) {
      var hit = hitAt(x, t + (b - t) * [0.25, 0.5, 0.75][i]);
      if (hit && (hit === p.el || p.el.contains(hit) || hit.contains(p.el))) return true;
    }
    return false;
  }
  function inFixedOrSticky(el) {
    for (var p = el; p && p !== doc.documentElement; p = p.parentElement) {
      var pos = styleOf(p).position;
      if (pos === 'fixed' || pos === 'sticky') return true;
    }
    return false;
  }
  function pickOnce(dir, S, list, strict) {
    // Player controls overlay the video and toggle pointer-events while fading, so a hit test would reject them.
    if (videoMode()) return core.pick(dir, S.rect, list, strict);
    for (var tries = 0; tries < 10 && list.length; tries++) {
      var p = core.pick(dir, S.rect, list, strict);
      if (!p) return null;
      if (hitOk(p)) return p;
      list = list.filter(function (c) { return c !== p; });
    }
    return null;
  }
  // Two tiers: tiny targets (slide dots, 24 px icons) only win when no normal-sized target lies that way.
  function choose(dir, S, list, strict) {
    // Cards scrolled out sideways in a row/carousel are reachable only by Left/Right, never by Up/Down.
    if (dir === 'up' || dir === 'down') {
      var VW = vw();
      list = list.filter(function (c) { return core.visibleFraction(c.rect, VW) >= 0.5; });
    }
    var big = list.filter(function (c) { return isTextEl(c.el) || core.roomy(c.rect, !!textOf(c.el)); });
    var p = pickOnce(dir, S, big, strict);
    if (!p && big.length < list.length) p = pickOnce(dir, S, list, strict);
    return p;
  }
  function fresh(list) {
    var out = [];
    list.forEach(function (c) {
      var r = rectOf(c.el);
      if (r) out.push({ id: c.id, el: c.el, rect: r, anc: c.anc });
    });
    return out;
  }
  // Container chain first (innermost to R), then scroll one step and retry once (D.5).
  function move(dir, S, cands, R) {
    var chain = containers(S.el, R).concat([R]);
    var fixedStart = inFixedOrSticky(S.el);
    for (var k = 0; k < chain.length; k++) {
      var C = chain[k];
      var inside = C === R ? cands : cands.filter(function (c) { return C.contains(c.el); });
      // Up from a fixed header must not jump to content scrolled out of view above it.
      if (C === R && fixedStart) {
        inside = inside.filter(function (c) {
          var r = c.rect;
          return r.b > 0 && r.t < vh() && r.r > 0 && r.l < vw();
        });
      }
      // From content, prefer non-fixed targets (e.g. hero buttons) over an always-visible header.
      var p = null;
      if (C === R && !fixedStart) {
        p = choose(dir, S, inside.filter(function (c) { return !inFixedOrSticky(c.el); }));
      }
      // Up/Down inside a row: only targets fully past the start, else a taller card beside it would win
      // over the next row (Down from Movy's wide TOP 1 card went to TOP 2).
      if (!p) p = choose(dir, S, inside, C !== R && (dir === 'up' || dir === 'down'));
      if (p) { focusEl(p.el); return true; }
      if (canScroll(C, dir)) {
        scrollBy(C, dir, 0.6);
        var q = choose(dir, { el: S.el, rect: rectOf(S.el) || S.rect }, fresh(inside));
        if (q) focusEl(q.el);
        return true;
      }
    }
    return false;
  }

  // ---- focus, hover, warm, ring (D.7) ----
  function fire(el, types, x, y) {
    types.forEach(function (t) {
      try {
        var Ctor = /^pointer/.test(t) && window.PointerEvent ? window.PointerEvent : window.MouseEvent;
        el.dispatchEvent(new Ctor(t, { bubbles: true, cancelable: true, view: window,
          clientX: x, clientY: y, button: 0, pointerType: 'mouse' }));
      } catch (e) { /* synthetic hover is best-effort */ }
    });
  }
  function hover(el, types) {
    var r = rectOf(el);
    if (!r) return;
    jitter = (jitter + 7) % 40;   // moving the pointer a little keeps hover menus refreshing
    fire(el, types, cx(r) + jitter, cy(r) + jitter);
  }
  function warm(el) {
    try {
      var a = el.closest && el.closest('a[href]');
      if (!a) return;
      var o = new URL(a.href, location.href).origin;
      if (warmed[o] || o === 'null') return;
      warmed[o] = 1;
      var l = doc.createElement('link');
      l.rel = 'preconnect';
      l.href = o;
      (doc.head || doc.documentElement).appendChild(l);
    } catch (e) { /* preconnect is an optimisation only */ }
  }
  // quiet = carry: focus without hover, scroll or warm-up.
  function focusEl(el, quiet) {
    var prev = last && last.el;
    if (el.tabIndex < 0 && !el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
    try { el.focus({ preventScroll: true }); } catch (e) { /* not focusable */ }
    if (!quiet) {
      // Fullscreen or player mode: synthetic hover opens site menus by itself and mouse-out closes them
      // (an open subtitle menu faded out as focus moved into it), so no hover events there at all.
      if (!videoMode()) {
        if (prev && prev !== el) hover(prev, ['pointerout', 'mouseout']);
        hover(el, ['pointerover', 'mouseover', 'mouseenter', 'pointermove']);
      }
      warm(el);
      scrollInto(el);
    }
    last = { el: el, rect: rectOf(el) };
  }
  function moveRing() {
    var host = fsRootEl() || doc.body;
    if (ringEl && host && ringEl.parentNode !== host) host.appendChild(ringEl);
  }
  function drawRing(r) {
    if (!ringEl && doc.body) {
      ringEl = doc.createElement('div');
      ringEl.style.cssText = RING_CSS;
    }
    if (!ringEl) return;
    moveRing();
    if (!ringEl.parentNode) return;
    var w = r.r - r.l, h = r.b - r.t, key = [r.l, r.t, w, h].join(',');
    if (key !== ringKey) {
      ringKey = key;
      ringEl.style.transform = 'translate(' + r.l + 'px,' + r.t + 'px)';
      ringEl.style.width = w + 'px';
      ringEl.style.height = h + 'px';
    }
    if (!ringShown) { ringEl.style.display = ''; ringShown = true; }
  }
  function hideRing() {
    if (ringEl && ringShown) { ringEl.style.display = 'none'; ringShown = false; }
  }
  // Focus left the element or it scrolled away unseen: take the control under its last spot.
  function carry() {
    var r = last && last.rect;
    if (!r || typeof doc.elementFromPoint !== 'function') return;
    var hit = hitAt(cx(r), cy(r));
    var t = hit && hit.closest ? hit.closest(SEL) : null;
    if (!t && hit && hit.querySelector) t = hit.querySelector(SEL);
    if (t && t !== last.el) focusEl(t, true);
  }
  function docFocused() {
    try { return typeof doc.hasFocus !== 'function' || doc.hasFocus(); } catch (e) { return false; }
  }
  function frame() {
    var el = last && last.el, now = Date.now();
    var inMode = wasMode;
    if (now - modeAt >= 250) { modeAt = now; inMode = !!videoMode(); }
    var closedPlayer = wasMode && !inMode;
    wasMode = inMode;
    if (el) {
      if (el.isConnected) last.rect = rectOf(el) || last.rect;
      // The player just closed and took focus with it: return to the control OK pressed, not a neighbour.
      if (closedPlayer && (!el.isConnected || !isVisible(el)) && returnTo &&
          returnTo.el.isConnected && isVisible(returnTo.el)) {
        var back = returnTo.el;
        returnTo = null;
        focusEl(back, true);
      } else if (!el.isConnected || (now - lastKeyAt > 1000 && (!last.rect || viewFrac(last.rect) < 0.5))) {
        carry();
      }
    }
    el = last && last.el;
    if (el && el.isConnected && doc.activeElement === el && last.rect && (!inFrame || docFocused())) drawRing(last.rect);
    else hideRing();
  }
  // Requests a frame only while enabled; the loop stops itself when disabled.
  function loop() {
    if (!enabled) { looping = false; return; }
    try { frame(); } catch (e) { /* the ring is cosmetic */ }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(loop);
  }
  function startLoop() {
    if (looping) return;
    looping = true;
    loop();
  }

  // ---- OK (D.8) and text fields (D.9) ----
  function ime(el) {
    try {
      var r = rectOf(el), b = window.DBBridge;
      if (r && b && typeof b.ime === 'function') b.ime(cx(r) / (vw() || 1), cy(r) / (vh() || 1));
    } catch (e) { /* keyboard tap is best-effort */ }
  }
  function togglePlay(v) {
    try {
      if (v.paused) {
        var p = v.play();
        if (p && p.catch) p.catch(function () {});
      } else {
        v.pause();
      }
    } catch (e) { /* media not ready */ }
  }
  function handled(e) {
    consumed[e.key] = true;
    e.preventDefault();
  }
  function onOk(e) {
    var T = doc.activeElement || null, R = scopeRoot(), mode = videoMode(), full = !!mode;
    if (!isBody(T)) returnTo = { el: T, rect: rectOf(T) };
    var cands = candidates(R, full);
    // Player or fullscreen: OK on BODY or the video toggles play whatever the controls' visibility.
    if (full && mode.video && (isBody(T) || T === mode.video)) {
      togglePlay(mode.video);
      handled(e);
      return;
    }
    // Controls hidden: OK toggles play even with focus on BODY (checked before the BODY pick).
    if (full && cands.every(function (c) { return c.el.tagName === 'VIDEO'; })) {
      var v = largestVideo(R);
      if (v) { togglePlay(v); handled(e); }
      return;
    }
    if (isBody(T)) {
      var p = initialPick(cands);
      if (p) { focusEl(p.el); handled(e); }
      return;
    }
    var kind = core.okAction(T.tagName, T.type, T.getAttribute('role'), T.isContentEditable, false);
    if (kind !== 'native') {
      handled(e);   // 'native' keeps the browser's own Enter activation
      if (kind === 'text' || kind === 'select') {
        ime(T);
      } else if (kind === 'video') {
        togglePlay(T);
      } else {
        var r = rectOf(T) || { l: 0, t: 0, r: 0, b: 0 };
        fire(T, ['pointerdown', 'mousedown', 'pointerup', 'mouseup'], cx(r), cy(r));
        T.click();
      }
    }
    if (mode) { menuBase = cands.length; menuOpener = T; }   // the player-menu memory only applies inside player/fullscreen
    setTimeout(function () {
      var n = doc.activeElement;
      if (n !== T && isTextEl(n)) ime(n);   // a field that OK just focused gets the keyboard
    }, 350);
  }

  // ---- fullscreen video (D.11) ----
  function wake(R) {
    var r = rectOf(R), v = largestVideo(R), vr = v && rectOf(v);
    if (r) fire(R, ['mousemove', 'pointermove'], cx(r), cy(r));
    if (vr) fire(v, ['mousemove', 'pointermove'], cx(vr), cy(vr));
  }
  function seek(R, sign) {
    var v = largestVideo(R), now = Date.now();
    if (!v) return;
    if (now - seekAt > 500) seekRun = 0;   // a new run starts after a 500 ms pause
    seekAt = now;
    var t = (v.currentTime || 0) + sign * core.seekStep(seekRun++);
    if (isFinite(v.duration) && v.duration > 0) t = Math.min(t, v.duration);
    v.currentTime = Math.max(0, t);
  }
  // Controls hidden: wake them with a pointer move and focus the play control once they show.
  function focusPlay(R) {
    var list = candidates(R, true).filter(function (c) { return c.el.tagName !== 'VIDEO'; });
    var target = list.filter(function (c) { return /play|pause/i.test(labelOf(c.el)); })[0];
    if (!target && list.length) {
      var bottom = Math.max.apply(null, list.map(function (c) { return c.rect.b; }));
      target = list.filter(function (c) { return c.rect.b >= bottom - 20; })
        .sort(function (x, y) { return x.rect.l - y.rect.l; })[0];
    }
    if (target) focusEl(target.el);
  }
  // Returns true when the key was consumed by the fullscreen rules.
  function fullscreenKey(e, dir, a, R, cands) {
    var hidden = cands.every(function (c) { return c.el.tagName === 'VIDEO'; });
    var inside = !!a && a !== R && R.contains(a);
    var lr = dir === 'left' || dir === 'right';
    wake(R);
    // Left/Right seek from BODY, the video or the timeline whatever the controls' visibility.
    var seekFocus = isBody(a) || (!!a && a.tagName === 'VIDEO') || isTimelineEl(a, R);
    if (lr && (seekFocus || (hidden && !inside))) {
      seek(R, dir === 'left' ? -1 : 1);
      handled(e);
      return true;
    }
    if (!lr && (hidden || !inside)) {
      setTimeout(function () { focusPlay(R); }, 220);
      handled(e);
      return true;
    }
    return false;
  }

  // ---- key intake (D.1) ----
  function onKeyDown(e) {
    try {
      if (e === playerEvt) return;   // already offered by the capture pass and not consumed there
      if (!enabled || e.defaultPrevented || e.ctrlKey || e.altKey || e.metaKey) return;
      if (YT_TV.test(String(location.href || ''))) return;
      if (e.key === 'Enter') return onOk(e);
      var dir = DIRS[e.key];
      if (!dir) return;
      var now = Date.now();
      if (core.throttle(now, lastMoveAt, e.repeat)) return handled(e);
      var a = doc.activeElement || null;
      var lr = dir === 'left' || dir === 'right';
      if (lr && isTextEl(a) && caretBusy(a, dir)) return;     // caret keeps the key
      var modeNow = videoMode();
      if (lr && isSliderEl(a) && (!modeNow || sliderNotTimeline(a, modeNow))) return;   // native slider keeps Left/Right
      lastMoveAt = now;
      lastKeyAt = now;
      var R = scopeRoot(), full = !!modeNow, cands = candidates(R, full);
      if (full && fullscreenKey(e, dir, a, R, cands)) return;
      var S = startFrom(a);
      // A start outside the scope root (e.g. page behind an overlay) means no start: pick inside R.
      if (S && S.el && R !== doc.documentElement && !R.contains(S.el)) S = null;
      if (!S) {
        var p = initialPick(cands);
        if (p) { focusEl(p.el); handled(e); }
        return;
      }
      cands = cands.filter(function (c) { return !(S.el && (S.el.contains(c.el) || c.el.contains(S.el))); });
      // Top frame: a dead end does nothing. Unhandled, WebView's own spatial navigation jumps far away
      // (Right from a Movy row heading landed on the slideshow dots). Frames keep it so focus can leave them.
      if (move(dir, S, cands, R) || window === window.top) handled(e);
    } catch (err) {
      // Fall through: the key keeps its default behaviour.
    }
  }
  function onKeyUp(e) {
    try {
      if (consumed[e.key]) { consumed[e.key] = false; e.preventDefault(); }
    } catch (err) { /* ignore */ }
  }
  // ---- player/fullscreen capture: runs before the site's own key listeners (window, capture phase) ----
  var PLAYER_KEYS = { ArrowUp: 1, ArrowDown: 1, ArrowLeft: 1, ArrowRight: 1, Enter: 1 };
  function onPlayerKey(e) {
    try {
      if (!enabled || !PLAYER_KEYS[e.key] || e.ctrlKey || e.altKey || e.metaKey) return;
      var a = doc.activeElement || null, fe = doc.fullscreenElement || doc.webkitFullscreenElement || null;
      if (isTextEl(a) || (a && a.isContentEditable) || (fe && fe.tagName === 'IFRAME')) return;
      var modeP = videoMode();
      if (!modeP) return;
      if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && sliderNotTimeline(a, modeP)) return;   // site's own slider handles it
      onKeyDown(e);
      // Not consumed by the engine: let the site have it, and keep the bubble pass from retrying it.
      if (!e.defaultPrevented) { playerEvt = e; return; }
      playerUp[e.key] = true;
      e.preventDefault();
      e.stopImmediatePropagation();
    } catch (err) { /* the key keeps its default path */ }
  }
  function onPlayerKeyUp(e) {
    if (!playerUp[e.key]) return;
    playerUp[e.key] = false;
    consumed[e.key] = false;
    e.preventDefault();
    e.stopImmediatePropagation();
  }

  // ---- Back (D.10) ----
  function popupOpen() {
    if (modalEl()) return true;
    if (doc.querySelector('[aria-expanded="true"]')) return true;
    var VW = vw();
    for (var p = doc.activeElement || null; p && p !== doc.body; p = p.parentElement) {
      var cs = styleOf(p), z = parseInt(cs.zIndex, 10) || 0, r = rectOf(p);
      if ((cs.position === 'absolute' || cs.position === 'fixed') && z >= 10 &&
          p.tagName !== 'HEADER' && p.tagName !== 'NAV' && r && r.r - r.l < 0.8 * VW) return true;
    }
    return false;
  }
  function dismissPopup() {
    var a = doc.activeElement || null;
    ['keydown', 'keyup'].forEach(function (t) {
      [a, doc].forEach(function (target) {
        if (!target) return;
        try { target.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', bubbles: true, cancelable: true })); } catch (e) {}
      });
    });
    var opener = doc.querySelector('[aria-expanded="true"]');
    if (opener) {
      try { opener.click(); } catch (e) {}
      focusEl(opener);
    } else if (last && last.el && last.el.isConnected) {
      focusEl(last.el);
    }
  }
  // Focus goes back to returnTo once (the site re-renders first, hence the delay); ms = 150 popups, 400 player exit.
  function restoreReturn(ms) {
    var rt = returnTo;
    returnTo = null;
    if (!rt) return;
    var tries = 0;
    (function attempt() {
      setTimeout(function () {
        var el = rt.el, R = scopeRoot();
        // Sites often re-render the opener when a popup or player closes: use the control now at its old spot.
        if ((!el || !el.isConnected) && rt.rect) {
          var hit = hitAt(cx(rt.rect), cy(rt.rect));
          el = hit && (hit.closest ? hit.closest(SEL) : null);
        }
        if (el && el.isConnected && liveCandidate(el, true) && R && R.contains(el)) { focusEl(el, true); return; }
        // The popup may still be fading out (scope is still the popup): retry for about a second.
        if (++tries < 7) attempt();
      }, tries ? 150 : ms);
    })();
  }
  // Page player: Escape the open site menu; if it stays visible, click its opener, then refocus the opener.
  function closePlayerMenu(op) {
    var a = doc.activeElement || null;
    ['keydown', 'keyup'].forEach(function (t) {
      [a, doc].forEach(function (target) {
        if (!target) return;
        try { target.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true, cancelable: true })); } catch (e) {}
      });
    });
    if (a && a.isConnected && typeof a.checkVisibility === 'function' && a.checkVisibility()) op.click();
    focusEl(op);
    restoreReturn(150);
    return 'closed';
  }
  // Focus sits inside the player but outside its bottom control row, so a site menu is open.
  // shortcut: bottom row = centre in the bottom 20% of the player root; upgrade if menus sit lower.
  function focusInPlayerMenu(root, op) {
    var a = doc.activeElement || null, rr = rectOf(root), ar = a && rectOf(a);
    if (!a || a === op || isBody(a) || !root.contains(a) || !rr || !ar) return false;
    return cy(ar) < rr.b - 0.2 * (rr.b - rr.t);
  }
  // Site's own back/close control: visible button/link by label, else an svg-only button in the top-left 20%.
  // SVG elements have no click(); a bubbling click event reaches the site's handler for both kinds.
  function pressEl(el) {
    var r = rectOf(el), x = r ? cx(r) : 0, y = r ? cy(r) : 0;
    fire(el, ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'], x, y);
  }
  function playerExitEl(root) {
    var rr = rectOf(root), EXIT = /^(back|go back|close|exit|exit player)$/i;
    if (!rr) return null;
    var els = Array.prototype.slice.call(root.querySelectorAll('button,[role=button],a'));
    var byLabel = els.filter(function (el) {
      if (!isVisible(el)) return false;
      return [el.getAttribute('aria-label'), el.title, el.innerText].some(function (s) {
        return EXIT.test(String(s || '').trim());
      });
    })[0];
    if (byLabel) return byLabel;
    // Else a text-free clickable (button, or a bare svg with cursor:pointer as on Movy) in the top-left 20%.
    return Array.prototype.slice.call(root.querySelectorAll('button,svg,[role=button]')).filter(function (el) {
      var r = rectOf(el);
      if (!r || !isVisible(el) || textOf(el)) return false;
      if (el.tagName.toLowerCase() === 'svg' && styleOf(el).cursor !== 'pointer') return false;
      return r.l < rr.l + 0.2 * (rr.r - rr.l) && r.t < rr.t + 0.2 * (rr.b - rr.t);
    })[0] || null;
  }
  function back() {
    try {
      var mode = videoMode(), op = menuOpener;
      if (mode && op) menuOpener = null;   // the player-menu memory only applies in fullscreen or player mode
      if (mode && op && op.isConnected && candidates(mode.root, true).length >= menuBase + 3) {
        if (mode.player) return closePlayerMenu(op);
        op.click();
        focusEl(op);
        return 'closed';
      }
      if (mode && mode.player) {
        if (op && op.isConnected && focusInPlayerMenu(mode.root, op)) return closePlayerMenu(op);
        var exit = playerExitEl(mode.root);
        if (exit) { pressEl(exit); restoreReturn(400); return 'closed'; }
        // Sites remove the idle top bar from the DOM: wake the controls and click the arrow once it is back.
        // A second Back within 2 s with nothing found falls through, so Back can never get stuck here.
        var now = Date.now();
        if (now - exitTriedAt > 2000) {
          exitTriedAt = now;
          wake(mode.root);
          setTimeout(function () {
            var m = videoMode(), e = m && m.player ? playerExitEl(m.root) : null;
            if (e) { pressEl(e); restoreReturn(400); }
          }, 300);
          return 'closed';
        }
        return 'none';   // page player with no site back control: the site handles Back
      }
      if (window.__dbUnfake && window.__dbUnfake()) return 'closed';
      if (doc.fullscreenElement || doc.webkitFullscreenElement) {
        (doc.exitFullscreen || doc.webkitExitFullscreen).call(doc);
        return 'closed';
      }
      if (!popupOpen()) {
        var ov = overlayRoot();
        if (!ov) return 'none';
        return closeOverlay(ov);
      }
      dismissPopup();
      restoreReturn(150);
      return 'closed';
    } catch (e) {
      return 'none';
    }
  }
  // Escape, then a close control in the overlay's top-right quarter; focus returns to the opener.
  function closeOverlay(ov) {
    var a = doc.activeElement || null;
    ['keydown', 'keyup'].forEach(function (t) {
      [a, doc].forEach(function (target) {
        if (!target) return;
        try {
          target.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27,
            bubbles: true, cancelable: true }));
        } catch (e) {}
      });
    });
    if (ov.isConnected) {
      var o = rectOf(ov), ow = o.r - o.l, oh = o.b - o.t, close = null;
      var named = Array.prototype.slice.call(ov.querySelectorAll(
        'button[aria-label*="close" i],[aria-label*="Close"],[class*="close" i]'));
      close = named.filter(isVisible)[0] || null;
      if (!close) {
        var icons = Array.prototype.slice.call(ov.querySelectorAll('button,[role=button],a'));
        close = icons.filter(function (e) {
          var t = textOf(e), r = rectOf(e);
          var glyph = t === '×' || t === '✕' ||
            (!t && !!e.querySelector('svg'));
          return glyph && r && isVisible(e) && cx(r) >= o.l + 0.75 * ow && cy(r) <= o.t + 0.25 * oh;
        })[0] || null;
      }
      if (close) close.click();
    }
    if (last && last.el && last.el.isConnected && !ov.contains(last.el)) focusEl(last.el);
    restoreReturn(150);
    return 'closed';
  }

  // ---- control surface ----
  function enable(on) {
    enabled = !!on;
    if (enabled) startLoop();
    else hideRing();
    // Iframes do not hear from the app, so the top frame relays the switch down the tree.
    var fr = doc.querySelectorAll('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage({ __dbnav: 'enable', on: enabled }, '*'); } catch (e) { /* cross-origin or detached */ }
    }
  }
  // Debug JSON for CDP probes (D.13).
  function debug(dir) {
    var out = {};
    try {
      var a = doc.activeElement || null, R = scopeRoot(), vm = videoMode(), full = !!vm;
      var S = startFrom(a);
      out.active = a ? describe(a) : null;
      out.scope = describe(R);
      out.mode = vm ? (vm.player ? 'player' : 'fullscreen') : 'page';
      out.chain = containers(S && S.el, R).map(function (c) { return c.tagName; });
      var cands = candidates(R, full);
      out.count = cands.length;
      out.top = [];
      if (S && ['up', 'down', 'left', 'right'].indexOf(dir) >= 0) {
        out.top = cands.map(function (c) { return { c: c, s: core.score(dir, S.rect, c.rect) }; })
          .filter(function (x) { return isFinite(x.s); })
          .sort(function (x, y) { return x.s - y.s; })
          .slice(0, 5)
          .map(function (x) {
            return { tag: x.c.el.tagName, text: textOf(x.c.el).slice(0, 60), rect: x.c.rect, score: Math.round(x.s * 10) / 10 };
          });
      }
    } catch (e) {
      out.error = String(e);
    }
    return JSON.stringify(out);
  }

  window.addEventListener('keydown', onKeyDown);
  window.addEventListener('keyup', onKeyUp);
  window.addEventListener('keydown', onPlayerKey, true);
  window.addEventListener('keyup', onPlayerKeyUp, true);
  doc.addEventListener('fullscreenchange', moveRing);
  doc.addEventListener('webkitfullscreenchange', moveRing);
  window.addEventListener('message', function (e) {
    try {
      if (e.data && e.data.__dbnav === 'enable' && e.source === window.parent) enable(e.data.on);
    } catch (err) { /* foreign messages are ignored */ }
  });
  window.__dbNav = { back: back, enable: enable, debug: debug };
  window.__dbBack = back;   // legacy alias used by Kotlin until WP4
  startLoop();
})();
