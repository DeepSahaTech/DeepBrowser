import { test } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import vm from 'node:vm';

// Minimal browser stand-ins: enough for the engine to load and answer back/enable/debug with no page.
const noop = () => {};
globalThis.window = globalThis;
Object.assign(globalThis, {
  addEventListener: noop,
  requestAnimationFrame: noop,
  getComputedStyle: () => ({}),
  location: { href: 'https://example.com/' },
  document: {
    addEventListener: noop,
    querySelectorAll: () => [],
    querySelector: () => null,
    documentElement: { querySelectorAll: () => [] },
    body: null,
    activeElement: null,
  },
});

const assets = new URL('../../app/src/main/assets/nav/', import.meta.url);
vm.runInThisContext(fs.readFileSync(new URL('core.js', assets), 'utf8'));
vm.runInThisContext(fs.readFileSync(new URL('engine.js', assets), 'utf8'));

test('engine exposes back, enable and debug', () => {
  assert.equal(typeof window.__dbNav.back, 'function');
  assert.equal(typeof window.__dbNav.enable, 'function');
  assert.equal(typeof window.__dbNav.debug, 'function');
});

test('back() with no page state reports none, and the legacy alias exists', () => {
  assert.equal(window.__dbNav.back(), 'none');
  assert.equal(typeof window.__dbBack, 'function');
});

test('enable() and debug() run without a page body', () => {
  window.__dbNav.enable(false);
  window.__dbNav.enable(true);
  const info = JSON.parse(window.__dbNav.debug('down'));
  assert.equal(info.error, undefined);
  assert.equal(info.count, 0);
});
