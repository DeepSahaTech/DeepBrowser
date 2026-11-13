// ISO-BMFF (fragmented MP4) box helpers for the native playback engine. No DOM access.
// Input is a Uint8Array. Loaded as a plain script; exposes globalThis.__dbMseCore.
(function () {
  'use strict';
  var CONTAINERS = { moov: 1, trak: 1, mdia: 1, minf: 1, stbl: 1, moof: 1, traf: 1, mvex: 1 };

  function u32(u8, p) {
    return ((u8[p] << 24) >>> 0) + (u8[p + 1] << 16) + (u8[p + 2] << 8) + u8[p + 3];
  }

  function u64(u8, p) {
    return u32(u8, p) * 4294967296 + u32(u8, p + 4);
  }

  function typeAt(u8, p) {
    return String.fromCharCode(u8[p + 4], u8[p + 5], u8[p + 6], u8[p + 7]);
  }

  // Top-level boxes within [start, end). A malformed size stops the walk; a box
  // running past `end` (e.g. a truncated mdat) is kept with its end clamped.
  function boxes(u8, start, end) {
    start = start || 0;
    end = end === undefined ? u8.length : Math.min(end, u8.length);
    var out = [], p = start;
    while (p + 8 <= end) {
      var size = u32(u8, p), header = 8;
      if (size === 1) {
        if (p + 16 > end) break;
        size = u64(u8, p + 8);
        header = 16;
      } else if (size === 0) {
        size = end - p;
      }
      if (size < header) break;
      var boxEnd = Math.min(p + size, end);
      out.push({ type: typeAt(u8, p), start: p, end: boxEnd, header: header });
      p = boxEnd;
    }
    return out;
  }

  // First box matching a slash path such as 'moov/trak/mdia/mdhd', or null.
  function find(u8, path) {
    var segs = path.split('/'), start = 0, end = u8.length, hit = null;
    for (var i = 0; i < segs.length; i++) {
      hit = null;
      var list = boxes(u8, start, end);
      for (var j = 0; j < list.length; j++) {
        if (list[j].type === segs[i]) { hit = list[j]; break; }
      }
      if (!hit) return null;
      if (i < segs.length - 1) {
        if (!CONTAINERS[segs[i]]) return null;
        start = hit.start + hit.header;
        end = hit.end;
      }
    }
    return hit;
  }

  // Timescale of the first track's mdhd (v0: 32-bit fields, v1: 64-bit), or 0.
  function timescale(initU8) {
    var mdhd = find(initU8, 'moov/trak/mdia/mdhd');
    if (!mdhd) return 0;
    var p = mdhd.start + mdhd.header;
    var version = initU8[p];
    var ts = version === 1 ? u32(initU8, p + 4 + 16) : u32(initU8, p + 4 + 8);
    return ts > 0 ? ts : 0;
  }

  // Seconds of the first moof/traf/tfdt baseMediaDecodeTime, or null.
  function fragTime(segU8, ts) {
    var tfdt = find(segU8, 'moof/traf/tfdt');
    if (!tfdt || !ts) return null;
    var p = tfdt.start + tfdt.header;
    var version = segU8[p];
    var t = version === 1 ? u64(segU8, p + 4) : u32(segU8, p + 4);
    return t / ts;
  }

  // Byte offset of the first moof (everything before it is the init segment), or -1.
  function splitInit(u8) {
    var list = boxes(u8, 0, u8.length);
    for (var i = 0; i < list.length; i++) {
      if (list[i].type === 'moof') return list[i].start;
    }
    return -1;
  }

  globalThis.__dbMseCore = {
    boxes: boxes,
    find: find,
    timescale: timescale,
    fragTime: fragTime,
    splitInit: splitInit
  };
})();
