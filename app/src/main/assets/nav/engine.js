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
  var lastKeyAt = 0, lastMoveAt = 0;
  var menuBase = 0, menuOpener = null;
  var seekRun = 0, seekAt = 0, jitter = 0;
  var consumed = {};               // keys we handled on keydown; their keyup is swallowed too
  var ids = new WeakMap(), nextId = 1, warmed = {};
  var ringEl = null, ringShown = false, ringKey = '';

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
  function scopeRoot() { return fsRootEl() || modalEl() || doc.documentElement; }
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
      var c = liveCandidate(els[i], full);
      if (c) items.push(c);
    }
    var kept = core.dedupe(items);
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
    var pref = ok.filter(function (c) { return cy(c.rect) > 0.18 * VH; });
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
  // Bring el inside the inset visible area, instantly (D.7).
  function scrollInto(el) {
    var r = rectOf(el);
    if (!r || typeof el.scrollIntoView !== 'function') return;
    var sc = nearestScroller(el), b = sc ? rectOf(sc) : null;
    var VW = vw(), VH = vh();
    var V = { l: Math.max(0, b ? b.l : 0), t: Math.max(0, b ? b.t : 0),
      r: Math.min(VW, b ? b.r : VW), b: Math.min(VH, b ? b.b : VH) };
    var w = V.r - V.l, h = V.b - V.t;
    var I = { l: V.l + 0.05 * w, r: V.r - 0.05 * w, t: V.t + 0.1 * h, b: V.b - 0.1 * h };
    if (r.t >= I.t && r.b <= I.b && r.l >= I.l && r.r <= I.r) return;
    el.scrollIntoView({ block: (r.b - r.t) > 0.8 * (I.b - I.t) ? 'start' : 'center', inline: 'nearest', behavior: 'instant' });
  }

  // ---- scoring with hit-test retry (D.6) ----
  function hitOk(p) {
    if (typeof doc.elementFromPoint !== 'function') return true;
    var r = p.rect, VW = vw(), VH = vh();
    if (r.b <= 0 || r.t >= VH || r.r <= 0 || r.l >= VW) return true;   // off-screen: nothing to test
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
  function choose(dir, S, list) {
    for (var tries = 0; tries < 10 && list.length; tries++) {
      var p = core.pick(dir, S.rect, list);
      if (!p) return null;
      if (hitOk(p)) return p;
      list = list.filter(function (c) { return c !== p; });
    }
    return null;
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
      if (!p) p = choose(dir, S, inside);
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
      if (prev && prev !== el) hover(prev, ['pointerout', 'mouseout']);
      hover(el, ['pointerover', 'mouseover', 'mouseenter', 'pointermove']);
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
  function frame() {
    var el = last && last.el, now = Date.now();
    if (el) {
      if (el.isConnected) last.rect = rectOf(el) || last.rect;
      if (!el.isConnected || (now - lastKeyAt > 600 && (!last.rect || viewFrac(last.rect) < 0.5))) carry();
    }
    el = last && last.el;
    if (el && el.isConnected && doc.activeElement === el && last.rect) drawRing(last.rect);
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
    var T = doc.activeElement || null, R = scopeRoot(), full = !!fsRootEl();
    var cands = candidates(R, full);
    if (isBody(T)) {
      var p = initialPick(cands);
      if (p) { focusEl(p.el); handled(e); }
      return;
    }
    if (full && cands.every(function (c) { return c.el.tagName === 'VIDEO'; })) {
      var v = largestVideo(R);
      if (v) { togglePlay(v); handled(e); }
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
    menuBase = cands.length;
    menuOpener = T;
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
    if (lr && hidden && (!inside || a.tagName === 'VIDEO' || isSliderEl(a))) {
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
      if (lr && isSliderEl(a) && !fsRootEl()) return;         // native slider keeps Left/Right
      lastMoveAt = now;
      lastKeyAt = now;
      var R = scopeRoot(), full = !!fsRootEl(), cands = candidates(R, full);
      if (full && fullscreenKey(e, dir, a, R, cands)) return;
      var S = startFrom(a);
      if (!S) {
        var p = initialPick(cands);
        if (p) { focusEl(p.el); handled(e); }
        return;
      }
      cands = cands.filter(function (c) { return !(S.el && (S.el.contains(c.el) || c.el.contains(S.el))); });
      if (move(dir, S, cands, R)) handled(e);
    } catch (err) {
      // Fall through: the key keeps its default behaviour.
    }
  }
  function onKeyUp(e) {
    try {
      if (consumed[e.key]) { consumed[e.key] = false; e.preventDefault(); }
    } catch (err) { /* ignore */ }
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
  function back() {
    try {
      var fsEl = fsRootEl(), op = menuOpener;
      if (fsEl && op) menuOpener = null;   // the player-menu memory only applies in fullscreen
      if (fsEl && op && op.isConnected && candidates(fsEl, true).length >= menuBase + 3) {
        op.click();
        focusEl(op);
        return 'closed';
      }
      if (window.__dbUnfake && window.__dbUnfake()) return 'closed';
      if (doc.fullscreenElement || doc.webkitFullscreenElement) {
        (doc.exitFullscreen || doc.webkitExitFullscreen).call(doc);
        return 'closed';
      }
      if (!popupOpen()) return 'none';
      dismissPopup();
      return 'closed';
    } catch (e) {
      return 'none';
    }
  }

  // ---- control surface ----
  function enable(on) {
    enabled = !!on;
    if (enabled) startLoop();
    else hideRing();
  }
  // Debug JSON for CDP probes (D.13).
  function debug(dir) {
    var out = {};
    try {
      var a = doc.activeElement || null, R = scopeRoot(), full = !!fsRootEl();
      var S = startFrom(a);
      out.active = a ? describe(a) : null;
      out.scope = describe(R);
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
  doc.addEventListener('fullscreenchange', moveRing);
  doc.addEventListener('webkitfullscreenchange', moveRing);
  window.__dbNav = { back: back, enable: enable, debug: debug };
  window.__dbBack = back;   // legacy alias used by Kotlin until WP4
  startLoop();
})();
