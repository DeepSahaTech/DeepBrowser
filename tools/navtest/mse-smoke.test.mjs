import { test, after } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import vm from 'node:vm';

// Fake browser globals: window is globalThis, as in a page.
globalThis.window = globalThis;
globalThis.document = { querySelectorAll: () => [] };
let urlSeq = 0;
globalThis.URL.createObjectURL = () => 'blob:fake/' + (++urlSeq);

class FakeSourceBuffer {
  constructor() { this.calls = []; }
  appendBuffer(data) { this.calls.push(data); }
}
class FakeMediaSource {
  addSourceBuffer() { return new FakeSourceBuffer(); }
}
globalThis.SourceBuffer = FakeSourceBuffer;
globalThis.MediaSource = FakeMediaSource;

// Small page cap (a few fixture fragments) so eviction is exercised without megabytes of data.
globalThis.__dbMseCapBytes = 4 * (6908 - 731) + 100;   // the cap is per page: one earlier test fragment + 3

const read = (rel) => fs.readFileSync(new URL(rel, import.meta.url), 'utf8');
vm.runInThisContext(read('../../app/src/main/assets/nav/msecore.js'));
vm.runInThisContext(read('../../app/src/main/assets/nav/mse.js'));

// The page timer keeps node alive; clear it so the test process exits.
after(() => clearInterval(globalThis.__dbMseTimer));

const frag = new Uint8Array(fs.readFileSync(new URL('./fixtures/frag.bin', import.meta.url)));

test('appendBuffer calls the original once with the same bytes and records init + one segment', () => {
  const sb = new MediaSource().addSourceBuffer('video/mp4; codecs="avc1.42E01E"');
  sb.appendBuffer(frag);
  assert.equal(sb.calls.length, 1);
  assert.strictEqual(sb.calls[0], frag);
  const d = JSON.parse(window.__dbMseDebug());
  const info = d.sbs[d.sbs.length - 1];
  assert.equal(info.initBytes, 731);
  assert.equal(info.timescale, 90000);
  assert.equal(info.segs, 1);
});

test('garbage appends do not throw and still reach the original appendBuffer', () => {
  const sb = new MediaSource().addSourceBuffer('video/mp4');
  const junk = new Uint8Array([0, 0, 0, 4, 1, 2, 3]);
  assert.doesNotThrow(() => sb.appendBuffer(junk));
  assert.equal(sb.calls.length, 1);
  assert.strictEqual(sb.calls[0], junk);
});

// A copy of the fixture's fragment (moof + mdat header) with its tfdt set to t seconds at timescale 90000.
function fragAt(t) {
  const seg = frag.slice(731);
  const box = globalThis.__dbMseCore.find(seg, 'moof/traf/tfdt');
  const dv = new DataView(seg.buffer, seg.byteOffset, seg.byteLength);
  const v = seg[box.start + 8];
  const ticks = Math.round(t * 90000);
  if (v === 1) dv.setBigUint64(box.start + 12, BigInt(ticks)); else dv.setUint32(box.start + 12, ticks);
  return seg;
}

test('eviction keeps the fragments nearest the playback position, not the newest ones', () => {
  const sb = new MediaSource().addSourceBuffer('video/mp4');
  sb.appendBuffer(frag);                                    // init + fragment at 0 s
  for (let t = 10; t <= 100; t += 10) sb.appendBuffer(fragAt(t));
  const d = JSON.parse(window.__dbMseDebug());
  const info = d.sbs[d.sbs.length - 1];
  // The position is 0 (nothing playing yet): the site buffered ahead to 100 s, the store keeps what is near 0.
  assert.deepEqual(info.times, [0, 10, 20]);
  assert.equal(sb.calls.length, 11);                        // every append still reached the site's SourceBuffer
});
