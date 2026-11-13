// Pure navigation maths for the remote-control engine. No DOM access.
// Rects are {l, t, r, b}. Loaded as a plain script; exposes globalThis.__dbNavCore.
(function () {
  'use strict';
  var INF = Infinity;
  var SEEK = [10, 10, 10, 20, 20, 30, 30, 30, 60];
  var TEXT_TYPES = { '': 1, text: 1, search: 1, url: 1, email: 1, password: 1, number: 1, tel: 1 };
  var NATIVE_INPUT = { checkbox: 1, radio: 1, button: 1, submit: 1, reset: 1 };
  var META = /^(?:\d+(?:\.\d+)?|\d{4}|[248]K|HD|UHD|PG(?:-13)?|NR|R|G|TV-?[A-Z0-9]+|\d+h(?:\s?\d+m)?|\d+m|S\d+\s?E\d+)$/i;

  function cx(r) { return (r.l + r.r) / 2; }
  function cy(r) { return (r.t + r.b) / 2; }
  function area(r) { return Math.max(0, r.r - r.l) * Math.max(0, r.b - r.t); }
  function isVert(dir) { return dir === 'up' || dir === 'down'; }

  // Gap between two 1-D intervals (0 when they overlap or touch).
  function gap(a0, a1, b0, b1) {
    var ov = Math.min(a1, b1) - Math.max(a0, b0);
    return ov < 0 ? -ov : 0;
  }

  // D.6 stage 1: candidate lies beyond the start edge in direction dir.
  function stage1(dir, S, C) {
    switch (dir) {
      case 'down': return C.t >= S.b - 1;
      case 'up': return C.b <= S.t + 1;
      case 'right': return C.l >= S.r - 1;
      case 'left': return C.r <= S.l + 1;
    }
    return false;
  }

  // D.6 stage 2: centre beyond the start centre and far edge beyond the start far edge.
  function stage2(dir, S, C) {
    switch (dir) {
      case 'down': return cy(C) > cy(S) + 2 && C.b > S.b + 1;
      case 'up': return cy(C) < cy(S) - 2 && C.t < S.t - 1;
      case 'right': return cx(C) > cx(S) + 2 && C.r > S.r + 1;
      case 'left': return cx(C) < cx(S) - 2 && C.l < S.l - 1;
    }
    return false;
  }

  // Formula without the stage-1 gate. Infinity for left/right without vertical overlap.
  function raw(dir, S, C) {
    var vert = isVert(dir), main, ortho, ov, side, W;
    if (vert) {
      main = dir === 'down' ? C.t - S.b : S.t - C.b;
      ortho = gap(S.l, S.r, C.l, C.r);
      ov = Math.min(S.r, C.r) - Math.max(S.l, C.l);
      side = Math.min(S.r - S.l, C.r - C.l);
      W = 2;
    } else {
      main = dir === 'right' ? C.l - S.r : S.l - C.r;
      ortho = gap(S.t, S.b, C.t, C.b);
      ov = Math.min(S.b, C.b) - Math.max(S.t, C.t);
      if (ov < 1) return INF;
      side = Math.min(S.b - S.t, C.b - C.t);
      W = 30;
    }
    main = Math.max(0, main);
    var align = side > 0 ? Math.min(1, Math.max(0, ov / side)) : 0;
    return Math.sqrt(main * main + ortho * ortho) + W * ortho - 5 * align;
  }

  // Tie-break: distance between centres on the axis perpendicular to dir. A candidate whose span lies
  // inside the start's span (e.g. buttons under a wide row) counts as 0, so ties fall to document order.
  function orthoDist(dir, S, C) {
    if (isVert(dir)) return (C.l >= S.l && C.r <= S.r) ? 0 : Math.abs(cx(C) - cx(S));
    return (C.t >= S.t && C.b <= S.b) ? 0 : Math.abs(cy(C) - cy(S));
  }

  function score(dir, S, C) {
    return stage1(dir, S, C) ? raw(dir, S, C) : INF;
  }

  // cands: [{id, rect}]. Returns the winning candidate object or null. strict: stage 1 only.
  function pick(dir, S, cands, strict) {
    var best = null, bestS = INF, bestD = INF;
    for (var pass = 1; pass <= (strict ? 1 : 2) && best === null; pass++) {
      for (var i = 0; i < cands.length; i++) {
        var C = cands[i].rect;
        if (pass === 1 ? !stage1(dir, S, C) : !stage2(dir, S, C)) continue;
        var s = raw(dir, S, C);
        if (!isFinite(s)) continue;
        var d = orthoDist(dir, S, C);
        if (best === null || s < bestS - 0.5 || (Math.abs(s - bestS) < 0.5 && d < bestD)) {
          best = cands[i];
          bestS = s;
          bestD = d;
        }
      }
    }
    return best;
  }

  // items: [{id, rect, anc: [ids]}]. Drops an ancestor when a descendant covers >= 70% of its area.
  function dedupe(items) {
    var drop = [];
    for (var i = 0; i < items.length; i++) {
      for (var j = 0; j < items.length; j++) {
        if (i === j) continue;
        var A = items[i], B = items[j];
        if (B.anc && B.anc.indexOf(A.id) !== -1 && area(B.rect) >= 0.7 * area(A.rect)) drop[i] = true;
      }
    }
    return items.filter(function (_, k) { return !drop[k]; });
  }

  // items: [{rect, href}]. Drops a link stacked right above/below a bigger link to the same href (card + caption),
  // so D-pad stops once per item. Far-apart twins (logo and Home button) both stay.
  function twins(items) {
    return items.filter(function (A) {
      if (!A.href) return true;
      return !items.some(function (B) {
        if (B === A || B.href !== A.href || area(B.rect) <= area(A.rect)) return false;
        var ov = Math.min(A.rect.r, B.rect.r) - Math.max(A.rect.l, B.rect.l);
        return ov >= 0.5 * Math.min(A.rect.r - A.rect.l, B.rect.r - B.rect.l) && gap(A.rect.t, A.rect.b, B.rect.t, B.rect.b) < 48;
      });
    });
  }

  // First-tier target: a text link/button of any size, or a text-less control whose shorter side is >= 28 CSS px.
  // Text-less slide dots and indicator pills (24 px tall) lose the first pick tier.
  function roomy(r, hasText) {
    if (hasText) return area(r) >= 100;
    return Math.min(r.r - r.l, r.b - r.t) >= 28;
  }

  // Fraction of the rect's width inside the horizontal viewport [0, VW].
  function visibleFraction(r, VW) {
    var w = r.r - r.l;
    if (w <= 0) return 0;
    var v = Math.min(r.r, VW) - Math.max(r.l, 0);
    return v <= 0 ? 0 : v / w;
  }

  // D.8 OK action for the focused element.
  function okAction(tag, type, role, editable, fsHidden) {
    var t = String(tag || '').toLowerCase();
    var ty = String(type || '').toLowerCase();
    if (t === 'video') return 'video';
    if (t === 'textarea' || editable) return 'text';
    if (t === 'input' && TEXT_TYPES.hasOwnProperty(ty)) return 'text';
    if (t === 'select') return 'select';
    if (t === 'a' || t === 'button' || t === 'summary') return 'native';
    if (t === 'input' && NATIVE_INPUT.hasOwnProperty(ty)) return 'native';
    return 'click';
  }

  // Seconds for the run-th consecutive seek (run starts at 0).
  function seekStep(run) {
    var i = Math.max(0, Math.floor(run) || 0);
    return SEEK[Math.min(i, SEEK.length - 1)];
  }

  // Short metadata (rating, year, badge, duration, episode) in controls up to 44 px high.
  function isMetaText(text, h) {
    if (!(h <= 44)) return false;
    return META.test(String(text || '').trim());
  }

  // True blocks a repeated key that arrives within 80 ms of the last move.
  function throttle(now, lastMoveAt, repeat) {
    return !!repeat && now - lastMoveAt < 80;
  }

  globalThis.__dbNavCore = {
    score: score,
    pick: pick,
    dedupe: dedupe,
    twins: twins,
    visibleFraction: visibleFraction,
    roomy: roomy,
    okAction: okAction,
    seekStep: seekStep,
    isMetaText: isMetaText,
    throttle: throttle
  };
})();
