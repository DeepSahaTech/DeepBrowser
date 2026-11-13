// Page-side MSE capture and native takeover for videos the WebView shows black (T-046).
// Runs at document start in every frame, before page scripts, so it sees the first MSE calls.
// Needs msecore.js loaded first. Plain ES2017 on purpose: the WebView engine is older.
(function () {
  'use strict';
  if (window.__dbMse) return;
  window.__dbMse = true;

  var core = globalThis.__dbMseCore;
  if (!core) return;

  var CAP = (typeof window.__dbMseCapBytes === "number" && window.__dbMseCapBytes) || 48 * 1024 * 1024;  // stored segment bytes per page (override: tests)
  var INIT_CAP = 8 * 1024 * 1024;  // give up on an init that never reaches a moof
  var recs = new Map();            // SourceBuffer -> rec
  var all = [];                    // recs in creation order
  var blobMs = new Map();          // blob URL -> MediaSource
  var total = 0, seqNo = 0, appendNo = 0, idNo = 0;
  var state = { active: false, gen: 0, video: null, pos: 0 };
  var replyAttached = false;
  var timer = null, lastSeen = 0;  // sampling timer; stopped once no video needs a check for 60 s
  var IDLE_MS = 60 * 1000;

  function toU8(data) {
    if (data instanceof ArrayBuffer) return new Uint8Array(data).slice();
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength).slice();
    return null;
  }

  function concat(a, b) {
    var out = new Uint8Array(a.length + b.length);
    out.set(a, 0);
    out.set(b, a.length);
    return out;
  }

  function dropOldest() {
    // Drop the fragment farthest from the playback position (state.pos), with all its chunks: sites buffer minutes
    // ahead, so dropping the oldest would lose exactly what a backward seek needs. Data ahead was already forwarded
    // live to the app. The init is never in segs, so it is never dropped.
    var pos = state.pos || 0, best = null, bestRec = null, bestD = -1;
    for (var i = 0; i < all.length; i++) {
      var s = all[i].segs;
      for (var j = 0; j < s.length; j++) {
        var d = s[j].t === null ? Infinity : Math.abs(s[j].t - pos);
        if (d > bestD) { bestD = d; best = s[j]; bestRec = all[i]; }
      }
    }
    if (!best) { total = 0; return; }
    var t = best.t;
    bestRec.segs = bestRec.segs.filter(function (x) {
      var same = x === best || (t !== null && x.t === t);
      if (same) { bestRec.stored -= x.bytes.length; total -= x.bytes.length; }
      return !same;
    });
  }

  function addSeg(rec, u8) {
    // A chunk that begins a fragment (moof near its start, after an optional styp/sidx) has its own time; a chunk that
    // only continues the previous fragment's data inherits that time and must travel together with it.
    var at = core.splitInit(u8), start = at >= 0 && at < 256;
    var t = start ? core.fragTime(u8, rec.timescale) : null;
    if (t === null) t = rec.lastT; else rec.lastT = t;
    var seg = { seq: ++seqNo, t: t, start: start, bytes: u8 };
    rec.segs.push(seg);
    rec.stored += u8.length;
    total += u8.length;
    while (total > CAP) dropOldest();
    return seg;
  }

  function post(obj) { window.dbMse.postMessage(JSON.stringify(obj)); }

  function postBytes(u8) {
    window.dbMse.postMessage(u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength));
  }

  function sendInit(r) {
    post({ type: 'init', sb: r.id, gen: state.gen });
    postBytes(r.init);
  }

  // Start time of the stored fragment that contains t (the latest fragment start <= t), so a replay begins right at the
  // target instead of up to a fragment earlier; falls back to t - 12 when nothing at or before t is stored.
  function fragStart(r, t) {
    var best = null;
    r.segs.forEach(function (s) { if (s.start && s.t !== null && s.t <= t + 0.05 && (best === null || s.t > best)) best = s.t; });
    return best === null ? t - 12 : best;
  }

  // True when a stored fragment starts at most 30 s before t (so a replay can start at or just before t).
  function hasNear(r, t) {
    return r.segs.some(function (s) { return s.start && s.t !== null && s.t <= t + 0.05 && s.t >= t - 30; });
  }

  // Removes [t - 1, t + 90] from the site's SourceBuffer so its player fetches that range again.
  function refetch(r, t) {
    var sb = r.sb;
    if (!sb || typeof sb.remove !== 'function') return;
    var go = function () {
      try { sb.remove(Math.max(0, t - 1), t + 90); } catch (e) { /* the site may have closed it */ }
    };
    if (sb.updating) sb.addEventListener('updateend', go, { once: true }); else go();
  }

  function sendSeg(r, seg) {
    // After a seek the site may append a fragment the app already got from the replay: send each fragment once per
    // generation. Only a fragment's first chunk is checked; its continuation chunks follow that decision.
    if (r.sentGen !== state.gen) { r.sentGen = state.gen; r.sent = {}; r.skipping = false; }
    if (seg.start && seg.t !== null && seg.t !== undefined) {
      r.skipping = !!r.sent[seg.t];
      r.sent[seg.t] = true;
    }
    if (r.skipping) return;
    post({ type: 'seg', sb: r.id, gen: state.gen, t: seg.t });
    postBytes(seg.bytes);
  }

  function onSeg(rec, u8) {
    var seg = addSeg(rec, u8);
    if (state.active && rec.live) sendSeg(rec, seg);
  }

  function capture(rec, u8) {
    if (rec.dead) return;
    rec.lastAppend = ++appendNo;
    if (rec.init !== null) { onSeg(rec, u8); return; }
    // Init may arrive in several appends; the first moof ends it.
    rec.acc = rec.acc ? concat(rec.acc, u8) : u8;
    var at = core.splitInit(rec.acc);
    if (at < 0) {
      if (rec.acc.length > INIT_CAP) { rec.acc = null; rec.dead = true; }
      return;
    }
    var acc = rec.acc;
    rec.acc = null;
    rec.init = acc.slice(0, at);
    rec.timescale = core.timescale(rec.init);
    if (state.active && rec.live) sendInit(rec);
    if (at < acc.length) onSeg(rec, acc.slice(at));
  }

  function wrapMediaSource(name) {
    var MS = window[name];
    if (!MS || !MS.prototype || typeof MS.prototype.addSourceBuffer !== 'function') return;
    var orig = MS.prototype.addSourceBuffer;
    if (orig.__dbWrapped) return;
    var wrapped = function (mime) {
      var sb = orig.apply(this, arguments);
      try {
        if (sb && typeof sb === 'object') {
          var rec = {
            id: ++idNo, mime: String(mime), ms: this, sb: sb, init: null, acc: null, timescale: 0,
            segs: [], stored: 0, lastT: null, lastAppend: 0, live: false, dead: false
          };
          recs.set(sb, rec);
          all.push(rec);
          startTimer();  // a page with media again: sampling may need to run
        }
      } catch (e) { /* capture is best effort; the page must never notice */ }
      return sb;
    };
    wrapped.__dbWrapped = true;
    MS.prototype.addSourceBuffer = wrapped;
  }

  function wrapSourceBuffer(name) {
    var SB = window[name];
    if (!SB || !SB.prototype || typeof SB.prototype.appendBuffer !== 'function') return;
    var orig = SB.prototype.appendBuffer;
    if (orig.__dbWrapped) return;
    var wrapped = function (data) {
      var rec = recs.get(this);
      if (rec) {
        try {
          var u8 = toU8(data);
          if (u8) capture(rec, u8);
        } catch (e) { /* ignore: the original append below still runs exactly once */ }
      }
      return orig.apply(this, arguments);
    };
    wrapped.__dbWrapped = true;
    SB.prototype.appendBuffer = wrapped;
  }

  wrapMediaSource('MediaSource');
  wrapMediaSource('ManagedMediaSource');
  wrapSourceBuffer('SourceBuffer');
  wrapSourceBuffer('ManagedSourceBuffer');

  // Remember which MediaSource a blob URL belongs to, so a <video> can be matched to its SBs.
  if (window.URL && typeof URL.createObjectURL === 'function') {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function (obj) {
      var url = origCreate.apply(this, arguments);
      try {
        var isMs = (window.MediaSource && obj instanceof window.MediaSource) ||
                   (window.ManagedMediaSource && obj instanceof window.ManagedMediaSource);
        if (isMs) blobMs.set(url, obj);
      } catch (e) { /* ignore */ }
      return url;
    };
  }

  function sbsForVideo(v) {
    var ms = blobMs.get(v.currentSrc || v.src || '') || null;
    if (!ms) {
      // Fallback: the MediaSource whose SourceBuffers received data most recently.
      var best = null;
      for (var i = 0; i < all.length; i++) {
        if (all[i].lastAppend && (!best || all[i].lastAppend > best.lastAppend)) best = all[i];
      }
      ms = best ? best.ms : null;
    }
    if (!ms) return [];
    return all.filter(function (r) { return r.ms === ms && r.init !== null; });
  }

  function takeover(v) {
    // Only the app can play the stream; without its bridge there is nothing to hand over to.
    if (!window.dbMse || state.active || v.mediaKeys) return;  // EME/DRM video is never taken over
    var sbs = sbsForVideo(v);
    if (!sbs.length) return;
    state.active = true;
    state.video = v;
    sbs.forEach(function (r) { r.live = true; });
    var now = v.currentTime;
    state.pos = now;
    post({
      type: 'start', t: now,
      sbs: sbs.map(function (r) { return { sb: r.id, mime: r.mime, timescale: r.timescale }; })
    });
    sbs.forEach(sendInit);
    // Replay recent segments in global append order so audio and video stay interleaved.
    var queue = [];
    sbs.forEach(function (r) {
      var from = fragStart(r, now);
      r.segs.forEach(function (s) {
        if (s.t === null || s.t >= from) queue.push({ r: r, s: s });
      });
    });
    queue.sort(function (a, b) { return a.s.seq - b.s.seq; });
    queue.forEach(function (q) { sendSeg(q.r, q.s); });
    // Muted page video keeps playing: it is the data pump, nothing is shown.
    // Silent but not muted: Chromium pauses muted videos of a hidden page, which would stop the site loading data.
    state.volume = v.volume;
    v.volume = 0;
    keepPumping(v);
  }

  // While the native player shows the video, this page is hidden behind it but must keep loading: report the page as
  // visible and resume the page video when the site pauses it on its own (sites pause players on "page hidden").
  function keepPumping(v) {
    try {
      Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return !state.active ? realHidden() : false; } });
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return state.active ? 'visible' : (realHidden() ? 'hidden' : 'visible'); } });
    } catch (e) { /* non-configurable on this engine: the resume below still keeps loading */ }
    document.addEventListener('visibilitychange', function (e) { if (state.active) e.stopImmediatePropagation(); }, true);
    v.addEventListener('pause', function () {
      if (!state.active || state.wantPaused) return;
      setTimeout(function () { if (state.active && !state.wantPaused && v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } }, 200);
    });
  }
  var hiddenDesc = typeof Document !== 'undefined' ? Object.getOwnPropertyDescriptor(Document.prototype, 'hidden') : null;
  function realHidden() { try { return hiddenDesc ? hiddenDesc.get.call(document) : false; } catch (e) { return false; } }

  function onCommand(e) {
    var m;
    try { m = JSON.parse(e.data); } catch (err) { return; }
    var v = state.video;
    if (!v || !m) return;
    if (m.cmd === 'seek') {
      if (!state.active) return;
      state.gen++;
      state.pos = m.t;
      post({ type: 'reset', gen: state.gen, t: m.t });
      // Re-send inits so the app can rebuild its streams for the new generation.
      var live = all.filter(function (r) { return r.live && r.init; });
      live.forEach(sendInit);
      // A target the site already buffered gets no new appends, so replay what we hold from just before it, in time order.
      var q = [], missing = false;
      live.forEach(function (r) {
        if (!hasNear(r, m.t)) missing = true;
        var from = fragStart(r, m.t);
        r.segs.forEach(function (s) { if (s.t !== null && s.t >= from) q.push({ r: r, s: s }); });
      });
      if (missing) {
        // We no longer hold the target: drop it from the site's buffer so the site downloads it again; those appends
        // are forwarded live in the new generation.
        live.forEach(function (r) { refetch(r, m.t); });
      } else {
        q.sort(function (a, b) { return (a.s.t - b.s.t) || (a.s.seq - b.s.seq); });
        q.forEach(function (x) { sendSeg(x.r, x.s); });
      }
      v.currentTime = m.t;
      var pp = v.play();   // the page video is the data pump; it must keep loading after the seek
      if (pp && pp.catch) pp.catch(function () {});
    } else if (m.cmd === 'sync') {
      // Keep the page clock near the native position so the site keeps loading ahead (no reset: same generation).
      if (typeof m.t === 'number') state.pos = m.t;
      if (state.active && typeof m.t === 'number' && Math.abs(v.currentTime - m.t) > 5) v.currentTime = m.t;
    } else if (m.cmd === 'pause') {
      state.wantPaused = true;
      v.pause();
    } else if (m.cmd === 'play') {
      state.wantPaused = false;
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    } else if (m.cmd === 'stop') {
      if (typeof state.volume === 'number') v.volume = state.volume;   // back on the page the video has its sound again
      state.active = false;
      v.pause();
    }
  }

  // Shared 32x18 canvas; a tainted or failed read is "unknown" (null), never "black".
  var cvs = null, ctx = null;
  function sampleBlack(v) {
    try {
      if (!cvs) {
        cvs = document.createElement('canvas');
        cvs.width = 32;
        cvs.height = 18;
        ctx = cvs.getContext('2d', { willReadFrequently: true });
      }
      ctx.drawImage(v, 0, 0, 32, 18);
      var d = ctx.getImageData(0, 0, 32, 18).data, sum = 0;
      for (var i = 0; i < d.length; i += 4) sum += d[i] + d[i + 1] + d[i + 2];
      return sum / (d.length / 4) < 9; // average R+G+B per pixel < 9, i.e. under 3 per channel
    } catch (e) {
      return null;
    }
  }

  function checkVideo(v) {
    var st = v.__dbMseSt || (v.__dbMseSt = { played: 0, last: -1, frames: 0, frameMark: 0, run: 0, samples: 0, rvfc: false });
    if (!st.rvfc && typeof v.requestVideoFrameCallback === 'function') {
      st.rvfc = true;
      var onFrame = function () {
        st.frames++;
        if (!v.__dbMseChecked) v.requestVideoFrameCallback(onFrame);
      };
      v.requestVideoFrameCallback(onFrame);
    }
    var now = v.currentTime;
    var advancing = st.last >= 0 && now > st.last;
    if (!v.paused && v.readyState >= 2 && advancing) st.played += now - st.last;
    st.last = now;
    if (st.played < 3 || v.paused || v.readyState < 2 || !advancing) return;

    // Without frame callbacks the "frames presented" requirement is skipped.
    var framesOk = typeof v.requestVideoFrameCallback !== 'function' || st.frames > st.frameMark;
    st.frameMark = st.frames;
    st.samples++;
    var black = framesOk ? sampleBlack(v) : null;
    st.run = black === true ? st.run + 1 : 0;
    if (st.run >= 6) {  // 6 samples 1.5 s apart: about 9 s of black, not a dark scene
      v.__dbMseChecked = true;
      takeover(v);
    } else if (st.samples >= 12) {
      // shortcut: stop sampling after ~18 s of playback so a normal video costs nothing more;
      // upgrade if a real video turns black later than that.
      v.__dbMseChecked = true;
    }
  }

  // A video that still needs a black check: a main player (user-started, or unmuted and large) that is visible,
  // at least half inside the viewport and not EME-protected. Sites often start muted and unmute a moment later;
  // banner trailers stay muted and are never checked.
  function needsCheck(v) {
    if (v.mediaKeys || v.__dbMseChecked) return false;
    var vr = v.getBoundingClientRect(), area = vr.width * vr.height;
    if (!(area > 0)) return false;
    var cs = getComputedStyle(v);
    if (cs.visibility !== 'visible' || !(parseFloat(cs.opacity) > 0.1)) return false;
    var ix = Math.max(0, Math.min(vr.right, innerWidth) - Math.max(vr.left, 0));
    var iy = Math.max(0, Math.min(vr.bottom, innerHeight) - Math.max(vr.top, 0));
    if (ix * iy < 0.5 * area) return false;
    return v.__dbUser === true || (!v.muted && area >= 0.3 * innerWidth * innerHeight);
  }

  function tick() {
    if (window.dbMse && !replyAttached) {
      try { window.dbMse.onmessage = onCommand; replyAttached = true; } catch (e) { /* retry next tick */ }
    }
    if (typeof document === 'undefined' || !document.querySelectorAll) return;
    var list = document.querySelectorAll('video'), any = false;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!needsCheck(v)) continue;
      any = true;
      if (!state.active) state.pos = v.currentTime;   // eviction keeps what is near the playing position
      try { checkVideo(v); } catch (e) { /* ignore */ }
    }
    if (any) lastSeen = Date.now();
    else if (Date.now() - lastSeen > IDLE_MS) stopTimer();
  }

  function startTimer() {
    if (timer !== null) return;
    lastSeen = Date.now();
    timer = setInterval(tick, 1500);
    window.__dbMseTimer = timer;
  }

  function stopTimer() {
    clearInterval(timer);
    timer = null;
  }

  window.__dbMseDebug = function () {
    return JSON.stringify({
      sbCount: all.length,
      gen: state.gen,
      takeover: state.active,
      sbs: all.map(function (r) {
        return {
          id: r.id, mime: r.mime, timescale: r.timescale,
          initBytes: r.init ? r.init.length : 0, segs: r.segs.length, storedBytes: r.stored, times: r.segs.map(function (s) { return s.t; })
        };
      })
    });
  };

  startTimer();
})();
