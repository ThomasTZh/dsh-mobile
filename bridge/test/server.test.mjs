import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, isPrivateAddress } from '../lib/server.js';

const silent = { info() {}, warn() {} };

async function start(overrides = {}) {
  const server = startServer({
    port: 0,
    bind: '127.0.0.1',
    heartbeatSec: 15,
    readToken: () => 'secret',
    snapshot: () => ({ pending: [], recent: [] }),
    log: silent,
    ...overrides,
  });
  await new Promise((resolve) => setTimeout(resolve, 30));
  return { server, port: server.address() };
}

test('health requires the bearer token', async () => {
  const { server, port } = await start();
  try {
    const unauthorized = await fetch(`http://127.0.0.1:${port}/health`);
    assert.equal(unauthorized.status, 401);
    const ok = await fetch(`http://127.0.0.1:${port}/health`, {
      headers: { Authorization: 'Bearer secret' },
    });
    assert.equal(ok.status, 200);
    const body = await ok.json();
    assert.equal(body.ok, true);
    assert.equal(body.version, '0.1.0');
  } finally {
    server.stop();
  }
});

test('missing pocket PIN is reported as unavailable', async () => {
  const { server, port } = await start({ readToken: () => null });
  try {
    const response = await fetch(`http://127.0.0.1:${port}/health`, {
      headers: { Authorization: 'Bearer anything' },
    });
    assert.equal(response.status, 503);
  } finally {
    server.stop();
  }
});

test('SSE sends hello and snapshot, then live events', async () => {
  const { server, port } = await start();
  const controller = new AbortController();
  try {
    const response = await fetch(`http://127.0.0.1:${port}/events`, {
      headers: { Authorization: 'Bearer secret' },
      signal: controller.signal,
    });
    assert.equal(response.status, 200);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let text = '';
    const readUntil = async (needle) => {
      while (!text.includes(needle)) {
        const { value, done } = await reader.read();
        if (done) throw new Error(`stream ended before ${needle}`);
        text += decoder.decode(value, { stream: true });
      }
    };
    await readUntil('event: snapshot');
    server.broadcast({ type: 'turn-done', sessionId: 's1', label: 'Test' });
    await readUntil('event: turn-done');
    const match = text.match(/data: (\{.*"type":"turn-done".*\})/);
    assert.ok(match);
    const event = JSON.parse(match[1]);
    assert.equal(event.v, 1);
    assert.equal(event.label, 'Test');
    controller.abort();
  } finally {
    server.stop();
  }
});

test('private address classification', () => {
  assert.equal(isPrivateAddress('127.0.0.1'), true);
  assert.equal(isPrivateAddress('::1'), true);
  assert.equal(isPrivateAddress('192.168.1.37'), true);
  assert.equal(isPrivateAddress('::ffff:10.0.0.2'), true);
  assert.equal(isPrivateAddress('fd00::1'), true);
  assert.equal(isPrivateAddress('fe80::1'), true);
  assert.equal(isPrivateAddress('8.8.8.8'), false);
  assert.equal(isPrivateAddress('2001:db8::1'), false);
});
