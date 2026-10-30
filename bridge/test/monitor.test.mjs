import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMonitor } from '../lib/monitor.js';

const root = (id = 'root', cwd = '/home/me/project') => ({ id, header: { cwd } });
const child = (id, parent) => ({ id, header: { origin: 'subagent', parentSession: parent, cwd: '/home/me/project' } });
const title = () => 'Session title';

function harness(config = {}) {
  const notes = [];
  const monitor = createMonitor(
    { startedAt: 0, settleSec: 1, minDurationSec: 0, attentionGraceSec: 0, doneCooldownSec: 0, ...config },
    (note) => notes.push(note),
  );
  return { monitor, notes };
}

test('turn completion is delayed by the settle window and carries the label', () => {
  const { monitor, notes } = harness();
  const session = root();
  monitor.event(session, { type: 'turn/start', time: 1000, data: {} }, 1000, title);
  monitor.event(session, { type: 'turn/end', time: 2000, data: { reason: { kind: 'completed' } } }, 2000, title);
  monitor.status(session, 'idle', 2000, title);
  monitor.tick(2500);
  assert.equal(notes.length, 0);
  monitor.tick(3000);
  assert.equal(notes.length, 1);
  assert.equal(notes[0].type, 'turn-done');
  assert.equal(notes[0].label, 'Session title');
  assert.equal(notes[0].sessionId, 'root');
});

test('short chats are suppressed by minDurationSec', () => {
  const { monitor, notes } = harness({ minDurationSec: 20 });
  const session = root();
  monitor.event(session, { type: 'turn/start', time: 1000, data: {} }, 1000, title);
  monitor.event(session, { type: 'turn/end', time: 2000, data: { reason: { kind: 'completed' } } }, 2000, title);
  monitor.tick(5000);
  assert.equal(notes.length, 0);
});

test('a new turn inside the settle window continues the chain', () => {
  const { monitor, notes } = harness();
  const session = root();
  monitor.event(session, { type: 'turn/start', time: 1000, data: {} }, 1000, title);
  monitor.event(session, { type: 'turn/end', time: 2000, data: { reason: { kind: 'completed' } } }, 2000, title);
  monitor.event(session, { type: 'turn/start', time: 2500, data: {} }, 2500, title);
  monitor.tick(3500);
  assert.equal(notes.length, 0);
  monitor.event(session, { type: 'turn/end', time: 4000, data: { reason: { kind: 'completed' } } }, 4000, title);
  monitor.tick(5500);
  assert.equal(notes.filter((n) => n.type === 'turn-done').length, 1);
});

test('approval is announced once and cleared by the decision', () => {
  const { monitor, notes } = harness();
  const session = root();
  monitor.event(session, {
    type: 'approval/asked',
    time: 1000,
    data: { id: 'a1', toolName: 'Bash', reason: 'Run the release script' },
  }, 1000, title);
  monitor.tick(1000);
  assert.equal(notes.length, 1);
  assert.equal(notes[0].type, 'approval');
  assert.equal(notes[0].tool, 'Bash');
  assert.equal(monitor.pendingCount('root'), 1);
  monitor.event(session, { type: 'approval/decided', time: 2000, data: { id: 'a1' } }, 2000, title);
  assert.equal(notes.at(-1).type, 'clear');
  assert.equal(notes.at(-1).kind, 'approval');
  assert.equal(monitor.pendingCount('root'), 0);
});

test('questions survive a timed pending result and clear on a reply', () => {
  const { monitor, notes } = harness();
  const session = root();
  const args = JSON.stringify({
    questions: [{
      header: 'Plan review',
      question: 'Approve the migration?',
      options: [{ label: 'Approve' }, { label: 'Reject' }],
    }],
  });
  monitor.event(session, {
    type: 'tool/call',
    time: 1000,
    data: { callId: 'q1', name: 'ask_user_question', arguments: args },
  }, 1000, title);
  monitor.tick(1000);
  const question = notes.find((n) => n.type === 'question');
  assert.ok(question);
  assert.equal(question.header, 'Plan review');
  assert.deepEqual(question.options, ['Approve', 'Reject']);
  monitor.event(session, {
    type: 'tool/result',
    time: 2000,
    data: { message: { toolCallId: 'q1', content: [{ type: 'text', text: '{"pending":true,"callId":"q1"}' }] } },
  }, 2000, title);
  assert.equal(monitor.pendingCount('root'), 1);
  monitor.event(session, {
    type: 'user/message',
    time: 3000,
    data: { source: { kind: 'user-question-reply', callId: 'q1' } },
  }, 3000, title);
  assert.equal(monitor.pendingCount('root'), 0);
});

test('errors are reported once and do not become a finish', () => {
  const { monitor, notes } = harness();
  const session = root();
  monitor.event(session, { type: 'turn/start', time: 1000, data: {} }, 1000, title);
  monitor.event(session, {
    type: 'turn/end',
    time: 2000,
    data: { reason: { kind: 'error', error: { message: 'model unavailable' } } },
  }, 2000, title);
  assert.equal(notes.filter((n) => n.type === 'error').length, 1);
  assert.equal(notes.filter((n) => n.type === 'turn-done').length, 0);
  monitor.tick(5000);
  assert.equal(notes.filter((n) => n.type === 'turn-done').length, 0);
});

test('subagent attention is attributed to the root and blocks a root finish', () => {
  const { monitor, notes } = harness();
  const parent = root('root');
  const kid = child('kid', 'root');
  monitor.event(parent, { type: 'turn/start', time: 1000, data: {} }, 1000, title);
  monitor.status(kid, 'running', 1000, title);
  monitor.event(kid, { type: 'approval/asked', time: 1500, data: { id: 'a2', toolName: 'Bash', reason: 'child needs it' } }, 1500, title);
  monitor.event(parent, { type: 'turn/end', time: 2000, data: { reason: { kind: 'completed' } } }, 2000, title);
  monitor.tick(1500);
  assert.equal(notes.find((n) => n.type === 'approval').sessionId, 'root');
  monitor.tick(4000);
  assert.equal(notes.filter((n) => n.type === 'turn-done').length, 0);
});

test('snapshot exposes pending attention', () => {
  const { monitor } = harness();
  const session = root();
  monitor.event(session, { type: 'approval/asked', time: 1000, data: { id: 'a1', toolName: 'Bash' } }, 1000, title);
  monitor.tick(1000);
  const snapshot = monitor.snapshot(1500);
  assert.equal(snapshot.pending.length, 1);
  assert.equal(snapshot.pending[0].type, 'approval');
});
