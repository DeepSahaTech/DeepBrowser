import { test } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import vm from 'node:vm';

vm.runInThisContext(fs.readFileSync(new URL('../../app/src/main/assets/nav/core.js', import.meta.url), 'utf8'));
const core = globalThis.__dbNavCore;

const rect = (l, t, r, b) => ({ l, t, r, b });
const ROW = rect(760, 175, 1171, 237);
const PLAY = rect(770, 311, 842, 342);
const SEE_MORE = rect(851, 311, 933, 342);
const BEYOND = rect(760, 360, 1171, 409);

test('(1) accordion Down from row picks Play (tie under a wide row falls to reading order)', () => {
  const hit = core.pick('down', ROW, [
    { id: 'play', rect: PLAY }, { id: 'more', rect: SEE_MORE }, { id: 'beyond', rect: BEYOND }
  ]);
  assert.equal(hit.id, 'play');
});

test('(2) Up from Beyond picks Play', () => {
  const hit = core.pick('up', BEYOND, [
    { id: 'row', rect: ROW }, { id: 'play', rect: PLAY }, { id: 'more', rect: SEE_MORE }
  ]);
  assert.equal(hit.id, 'play');
});

test('(2b) narrow start keeps the nearer-centre tie-break', () => {
  const hit = core.pick('down', rect(850, 100, 930, 140), [
    { id: 'play', rect: PLAY }, { id: 'more', rect: SEE_MORE }
  ]);
  assert.equal(hit.id, 'more');
});

test('(3) Right Play goes to See more; Right from See more has no target', () => {
  const r1 = core.pick('right', PLAY, [
    { id: 'more', rect: SEE_MORE }, { id: 'row', rect: ROW }, { id: 'beyond', rect: BEYOND }
  ]);
  assert.equal(r1.id, 'more');
  const r2 = core.pick('right', SEE_MORE, [
    { id: 'play', rect: PLAY }, { id: 'row', rect: ROW }, { id: 'beyond', rect: BEYOND }
  ]);
  assert.equal(r2, null);
});

test('(4) Left/Right with no vertical overlap gives null', () => {
  const S = rect(0, 0, 100, 40);
  const C = rect(200, 100, 300, 140);
  assert.equal(core.score('right', S, C), Infinity);
  assert.equal(core.pick('right', S, [{ id: 'x', rect: C }]), null);
  assert.equal(core.pick('left', rect(200, 0, 300, 40), [{ id: 'x', rect: rect(0, 100, 100, 140) }]), null);
});

test('(5) Up from hero Play reaches header link, not header search', () => {
  const HERO_PLAY = rect(96, 467, 205, 511);
  const hit = core.pick('up', HERO_PLAY, [
    { id: 'link', rect: rect(170, 20, 215, 45) },
    { id: 'search', rect: rect(1090, 20, 1180, 60) }
  ]);
  assert.equal(hit.id, 'link');
});

test('(6) Down from More info picks the first TOP 10 card, not far-right pause', () => {
  const MORE_INFO = rect(217, 467, 362, 511);
  const hit = core.pick('down', MORE_INFO, [
    { id: 'pause', rect: rect(1148, 555, 1184, 591) },
    { id: 'card', rect: rect(96, 697, 710, 900) }
  ]);
  assert.equal(hit.id, 'card');
});

test('(7) dedupe drops an ancestor covered by a descendant, keeps small inner controls', () => {
  const kept = core.dedupe([
    { id: 'A', rect: rect(0, 0, 100, 40), anc: [] },
    { id: 'BUTTON', rect: rect(0, 0, 100, 40), anc: ['A'] }
  ]);
  assert.deepEqual(kept.map((i) => i.id), ['BUTTON']);
  const both = core.dedupe([
    { id: 'card', rect: rect(0, 0, 300, 400), anc: [] },
    { id: 'button', rect: rect(10, 10, 50, 50), anc: ['card'] }
  ]);
  assert.deepEqual(both.map((i) => i.id), ['card', 'button']);
});

test('(8) stage 2 is used only when stage 1 finds nothing', () => {
  const S = rect(0, 0, 100, 40);
  // Stage-2-only candidate: starts above the start element's bottom edge but its centre and far edge are below.
  const stage2Only = rect(0, 30, 100, 80);
  const onlyStage2 = core.pick('down', S, [{ id: 's2', rect: stage2Only }]);
  assert.equal(onlyStage2.id, 's2');
  const stage1Far = rect(0, 200, 100, 240);
  const both = core.pick('down', S, [{ id: 's2', rect: stage2Only }, { id: 's1', rect: stage1Far }]);
  assert.equal(both.id, 's1');
});

test('(9) visibleFraction is 0 for a rect fully off the left edge', () => {
  assert.equal(core.visibleFraction(rect(-1184, 0, -100, 50), 1280), 0);
  assert.equal(core.visibleFraction(rect(-50, 0, 50, 10), 1280), 0.5);
});

test('(10) throttle blocks only repeats within 80 ms', () => {
  assert.equal(core.throttle(100, 50, true), true);
  assert.equal(core.throttle(200, 50, true), false);
  assert.equal(core.throttle(100, 50, false), false);
});

test('(11) okAction covers every row of D.8', () => {
  const cases = [
    [['textarea', '', '', false, false], 'text'],
    [['TEXTAREA', '', '', false, false], 'text'],
    [['div', '', '', true, false], 'text'],
    [['input', '', '', false, false], 'text'],
    [['INPUT', 'TEXT', '', false, false], 'text'],
    [['input', 'search', '', false, false], 'text'],
    [['input', 'url', '', false, false], 'text'],
    [['input', 'email', '', false, false], 'text'],
    [['input', 'password', '', false, false], 'text'],
    [['input', 'number', '', false, false], 'text'],
    [['input', 'tel', '', false, false], 'text'],
    [['select', '', '', false, false], 'select'],
    [['video', '', '', false, false], 'video'],
    [['video', '', '', false, true], 'video'],
    [['a', '', '', false, false], 'native'],
    [['button', '', '', false, false], 'native'],
    [['summary', '', '', false, false], 'native'],
    [['input', 'checkbox', '', false, false], 'native'],
    [['input', 'radio', '', false, false], 'native'],
    [['input', 'button', '', false, false], 'native'],
    [['input', 'submit', '', false, false], 'native'],
    [['input', 'reset', '', false, false], 'native'],
    [['div', '', 'button', false, false], 'click'],
    [['span', '', '', false, false], 'click'],
    [['input', 'range', '', false, false], 'click']
  ];
  for (const [args, want] of cases) {
    assert.equal(core.okAction(...args), want, JSON.stringify(args));
  }
});

test('(12) seekStep ramps 10,10,10,20,20,30,30,30,60 then stays at 60', () => {
  const got = [];
  for (let run = 0; run <= 9; run++) got.push(core.seekStep(run));
  assert.deepEqual(got, [10, 10, 10, 20, 20, 30, 30, 30, 60, 60]);
});

test('(13) isMetaText accepts short metadata and rejects action labels', () => {
  for (const text of ['7.7', '2022', '4K', 'PG-13', 'HD', '1h 45m', 'S1 E2']) {
    assert.equal(core.isMetaText(text, 30), true, text);
  }
  assert.equal(core.isMetaText('Play', 30), false);
  assert.equal(core.isMetaText('More info', 30), false);
  assert.equal(core.isMetaText('7.7', 60), false);
});

test('(14) roomy keeps text links and real icon buttons, rejects text-less slide dots and pills', () => {
  assert.equal(core.roomy(rect(0, 0, 30, 30), false), true);     // icon button
  assert.equal(core.roomy(rect(0, 0, 24, 24), false), false);    // slide dot
  assert.equal(core.roomy(rect(0, 0, 46, 24), false), false);    // active slide pill
  assert.equal(core.roomy(rect(0, 0, 90, 20), true), true);      // inline text link
  assert.equal(core.roomy(rect(10, 10, 10, 10), true), false);
});

test('(twins) caption link under its card is dropped; far-apart same-href links stay', () => {
  const kept = core.twins([
    { id: 'card', href: '/t/1', rect: rect(0, 0, 600, 300) },
    { id: 'caption', href: '/t/1', rect: rect(0, 310, 300, 340) },
    { id: 'logo', href: '/', rect: rect(0, 0, 40, 40) },
    { id: 'home', href: '/', rect: rect(700, 0, 800, 40) },
    { id: 'btn', rect: rect(0, 350, 100, 380) }
  ]);
  assert.deepEqual(kept.map((i) => i.id), ['card', 'logo', 'home', 'btn']);
});

test('(strict) pick with strict ignores stage-2-only candidates', () => {
  const S = rect(0, 0, 600, 300);
  const taller = [{ id: 'side', rect: rect(610, 0, 810, 340) }];
  assert.equal(core.pick('down', S, taller).id, 'side');
  assert.equal(core.pick('down', S, taller, true), null);
});
