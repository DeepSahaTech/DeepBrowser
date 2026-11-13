import { test } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import vm from 'node:vm';

vm.runInThisContext(fs.readFileSync(new URL('../../app/src/main/assets/nav/msecore.js', import.meta.url), 'utf8'));
const mse = globalThis.__dbMseCore;

const frag = new Uint8Array(fs.readFileSync(new URL('./fixtures/frag.bin', import.meta.url)));

test('boxes() lists ftyp, moov, moof, mdat in order', () => {
  const types = mse.boxes(frag).map((b) => b.type);
  assert.deepEqual(types, ['ftyp', 'moov', 'moof', 'mdat']);
});

test('timescale() returns a positive integer from the init segment', () => {
  const ts = mse.timescale(frag.subarray(0, mse.splitInit(frag)));
  assert.ok(Number.isInteger(ts) && ts > 0, `got ${ts}`);
});

test('fragTime() of the first fragment is a finite number >= 0', () => {
  const ts = mse.timescale(frag.subarray(0, mse.splitInit(frag)));
  const seg = frag.subarray(mse.splitInit(frag));
  const t = mse.fragTime(seg, ts);
  assert.ok(Number.isFinite(t) && t >= 0, `got ${t}`);
});

test('splitInit() equals the start of the moof box', () => {
  const moof = mse.boxes(frag).find((b) => b.type === 'moof');
  assert.equal(mse.splitInit(frag), moof.start);
});

test('find() returns null for a missing path', () => {
  assert.equal(mse.find(frag, 'moov/trak/mdia/nope'), null);
  assert.equal(mse.find(frag, 'nothere'), null);
});

test('malformed input does not throw and yields empty results', () => {
  const bad = new Uint8Array([0, 0, 0, 4, 1, 2, 3]);
  assert.deepEqual(mse.boxes(bad), []);
  assert.equal(mse.find(bad, 'moov/trak'), null);
  assert.equal(mse.timescale(bad), 0);
  assert.equal(mse.fragTime(bad, 1000), null);
  assert.equal(mse.splitInit(bad), -1);
});
