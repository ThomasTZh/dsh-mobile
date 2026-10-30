import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createTokenReader } from '../lib/token.js';

test('reads and refreshes the pocket PIN file', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'dsh-mobile-token-'));
  const file = path.join(dir, 'token-lan');
  try {
    writeFileSync(file, 'first-pin\n');
    const read = createTokenReader(file);
    assert.equal(read(), 'first-pin');
    writeFileSync(file, 'second-pin\n');
    utimesSync(file, new Date(), new Date(Date.now() + 2000));
    assert.equal(read(), 'second-pin');
    rmSync(file);
    assert.equal(read(), null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('an empty token file is treated as missing', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'dsh-mobile-token-'));
  const file = path.join(dir, 'token-lan');
  try {
    writeFileSync(file, '   \n');
    assert.equal(createTokenReader(file)(), null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
