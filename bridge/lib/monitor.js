// Pure notification state machine. No timers, no I/O, no clock reads: the host
// feeds it session events, agent status flips and ticks with an explicit `now`.
//
// It answers three questions only:
//   * did a root session finish a meaningful chain of turns?
//   * is a human approval waiting?
//   * is a question (including a plan review) waiting?
// plus turn errors. Stalls and retry storms are deliberately out of scope.

const DEFAULTS = {
  settleSec: 5,
  minDurationSec: 20,
  doneCooldownSec: 60,
  attentionGraceSec: 4,
  startedAt: 0,
  labelMax: 80,
  replyMax: 160,
  recentDoneMax: 8,
  recentWindowSec: 600,
};

const basename = (p) => String(p ?? '').split(/[\\/]/).filter(Boolean).pop() ?? '';
const shortId = (s) => String(s ?? '').slice(0, 8);
const secs = (ms) => Math.max(0, Math.round(ms / 1000));

function parseJson(value) {
  if (value && typeof value === 'object') return value;
  try {
    const parsed = JSON.parse(String(value ?? ''));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function textOf(content) {
  return (content ?? [])
    .filter((block) => block?.type === 'text')
    .map((block) => block.text ?? '')
    .join('')
    .trim();
}

function clip(value, max) {
  const text = String(value ?? '').replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (text.length <= max) return text;
  return `${text.slice(0, Math.max(0, max - 1)).trimEnd()}…`;
}

const outcomeOf = (reason) => {
  switch (reason?.kind) {
    case 'completed':
      return 'done';
    case 'error':
      return 'error';
    default:
      return 'silent';
  }
};

export function createMonitor(options = {}, emit = () => {}) {
  const cfg = { ...DEFAULTS, ...options };
  const roots = new Map(); // root id -> state
  const parentOf = new Map(); // subagent id -> parent id
  const runningKids = new Set();

  const isSub = (session) => session?.header?.origin === 'subagent';

  function register(session) {
    if (!session) return;
    if (isSub(session) && session.header?.parentSession) {
      parentOf.set(session.id, session.header.parentSession);
    }
  }

  function rootIdOf(id) {
    let current = id;
    for (let i = 0; i < 32 && parentOf.has(current); i += 1) current = parentOf.get(current);
    return parentOf.has(current) ? undefined : current;
  }

  function stateOf(session, resolveTitle) {
    let state = roots.get(session.id);
    if (!state) {
      state = {
        id: session.id,
        cwd: session.header?.cwd,
        project: basename(session.header?.cwd),
        title: resolveTitle?.(session),
        firstUserText: undefined,
        lastReply: undefined,
        running: false,
        chainStart: null,
        turnEndedAt: null,
        pendingDoneAt: null,
        lastDoneAt: 0,
        lastErrorAt: 0,
        attention: new Map(),
        recentDone: [],
      };
      roots.set(session.id, state);
    }
    return state;
  }

  function labelOf(state) {
    return state.title || state.firstUserText || state.project || shortId(state.id);
  }

  function busyKids(state) {
    let count = 0;
    for (const kid of runningKids) if (rootIdOf(kid) === state.id) count += 1;
    return count;
  }

  function pushAttention(state, id, kind, facts, now) {
    if (id === undefined || id === null || state.attention.has(String(id))) return;
    state.attention.set(String(id), {
      id: String(id),
      kind,
      facts,
      since: now,
      dueAt: now + cfg.attentionGraceSec * 1000,
      notified: false,
    });
    // An approval or question supersedes the pending "turn finished": the session
    // is not finished, it is waiting for the human.
    state.pendingDoneAt = null;
  }

  function clearAttention(state, id, now) {
    const key = String(id ?? '');
    const item = state.attention.get(key);
    if (!item) return;
    state.attention.delete(key);
    if (item.notified) {
      emit({
        type: 'clear',
        kind: item.kind,
        id: item.id,
        sessionId: state.id,
        at: now,
      });
    }
  }

  function noteDone(state, now) {
    state.lastDoneAt = now;
    state.recentDone.push({
      type: 'turn-done',
      sessionId: state.id,
      label: state.title || state.firstUserText || state.project || shortId(state.id),
      cwd: state.cwd,
      at: now,
      durationSec: state.lastDurationSec,
      reply: state.lastReply,
    });
    while (state.recentDone.length > cfg.recentDoneMax) state.recentDone.shift();
    state.recentDone = state.recentDone.filter((item) => now - item.at <= cfg.recentWindowSec * 1000);
  }

  function emitAttention(state, item, now) {
    const common = {
      sessionId: state.id,
      label: labelOf(state),
      cwd: state.cwd,
      at: now,
      id: item.id,
      waitedSec: secs(now - item.since),
      ...item.facts,
    };
    emit({ type: item.kind, ...common });
    item.notified = true;
  }

  return {
    event(session, event, now, resolveTitle) {
      if (!session || !event) return;
      if (typeof event.time === 'number' && event.time < cfg.startedAt - 2000) return;
      register(session);
      const sub = isSub(session);
      const rootId = sub ? rootIdOf(session.id) : session.id;
      if (!rootId) return;
      const state = sub ? roots.get(rootId) : stateOf(session, resolveTitle);
      if (!state) return;
      const data = event.data ?? {};
      if (!sub && (event.type === 'turn/start' || event.type === 'turn/end')) {
        try {
          state.title = resolveTitle?.(session) ?? state.title;
        } catch {
          // keep the previous label
        }
      }

      switch (event.type) {
        case 'approval/asked':
          pushAttention(state, data.id, 'approval', {
            tool: clip(data.toolName ?? 'tool', 40),
            reason: clip(data.reason ?? '', 160),
          }, now);
          break;
        case 'approval/decided':
          clearAttention(state, data.id, now);
          break;
        case 'tool/call': {
          const args = parseJson(data.arguments);
          if (data.name === 'ask_user_question') {
            const questions = Array.isArray(args.questions) ? args.questions : [];
            const first = questions[0] ?? {};
            pushAttention(state, data.callId, 'question', {
              header: clip(first.header ?? '', 60),
              question: clip(first.question ?? 'Question waiting', 240),
              options: (first.options ?? []).map((option) => clip(option?.label ?? option, 80)).slice(0, 8),
              count: questions.length || 1,
            }, now);
          } else if (data.name === 'exit_plan_mode') {
            pushAttention(state, data.callId, 'question', {
              header: 'Plan review',
              question: clip('Review the plan and approve or reject it.', 240),
              options: [],
              count: 1,
            }, now);
          }
          break;
        }
        case 'tool/result': {
          const callId = data.message?.toolCallId;
          if (callId === undefined) break;
          const item = state.attention.get(String(callId));
          if (!item || item.kind !== 'question') break;
          const text = textOf(data.message?.content);
          // A timed question that returned {pending:true} stays answerable.
          if (text.startsWith('{"pending":true')) break;
          clearAttention(state, callId, now);
          break;
        }
        case 'user/message': {
          if (data.source?.kind === 'user-question-reply' && data.source.callId !== undefined) {
            clearAttention(state, data.source.callId, now);
          }
          if (!sub && !state.firstUserText) {
            const text = textOf(data.message?.content);
            if (text) state.firstUserText = clip(text, cfg.labelMax);
          }
          break;
        }
        case 'assistant/message': {
          if (!sub) {
            const text = textOf(data.message?.content);
            if (text) state.lastReply = clip(text, cfg.replyMax);
          }
          break;
        }
        case 'turn/start': {
          if (sub) break;
          state.pendingDoneAt = null;
          state.turnEndedAt = null;
          state.chainStart ??= now;
          state.running = true;
          break;
        }
        case 'turn/end': {
          if (sub) break;
          const kind = outcomeOf(data.reason);
          state.running = false;
          state.lastDurationSec = secs(now - (state.chainStart ?? now));
          if (kind === 'done') {
            state.turnEndedAt = now;
            state.pendingDoneAt = now + cfg.settleSec * 1000;
          } else if (kind === 'error') {
            state.pendingDoneAt = null;
            state.chainStart = null;
            if (state.attention.size === 0 && now - state.lastErrorAt >= cfg.doneCooldownSec * 1000) {
              state.lastErrorAt = now;
              emit({
                type: 'error',
                sessionId: state.id,
                label: labelOf(state),
                cwd: state.cwd,
                at: now,
                error: clip(data.reason?.error?.message ?? 'The turn failed.', 200),
              });
            }
          } else {
            state.pendingDoneAt = null;
            state.chainStart = null;
          }
          break;
        }
        default:
          break;
      }
    },

    status(session, status, now, resolveTitle) {
      if (!session) return;
      register(session);
      if (isSub(session)) {
        if (status === 'running') runningKids.add(session.id);
        else runningKids.delete(session.id);
        return;
      }
      const state = stateOf(session, resolveTitle);
      state.running = status === 'running';
      if (state.running) state.pendingDoneAt = null;
    },

    tick(now) {
      for (const state of roots.values()) {
        for (const item of state.attention.values()) {
          if (!item.notified && now >= item.dueAt) emitAttention(state, item, now);
        }
        if (state.pendingDoneAt === null || now < state.pendingDoneAt) continue;
        if (state.running || busyKids(state) > 0) continue;
        state.pendingDoneAt = null;
        if (state.attention.size > 0) {
          state.chainStart = null;
          state.turnEndedAt = null;
          continue;
        }
        const duration = secs((state.turnEndedAt ?? now) - (state.chainStart ?? now));
        state.chainStart = null;
        state.turnEndedAt = null;
        if (duration < cfg.minDurationSec) continue;
        if (now - state.lastDoneAt < cfg.doneCooldownSec * 1000) continue;
        emit({
          type: 'turn-done',
          sessionId: state.id,
          label: labelOf(state),
          cwd: state.cwd,
          at: now,
          durationSec: duration,
          reply: state.lastReply,
        });
        state.lastDurationSec = duration;
        state.lastReply = undefined;
        noteDone(state, now);
      }
    },

    snapshot(now) {
      const pending = [];
      const recent = [];
      for (const state of roots.values()) {
        for (const item of state.attention.values()) {
          if (!item.notified && now < item.dueAt) continue;
          pending.push({
            type: item.kind,
            sessionId: state.id,
            label: labelOf(state),
            cwd: state.cwd,
            at: now,
            id: item.id,
            since: item.since,
            ...item.facts,
          });
        }
        for (const done of state.recentDone) {
          if (now - done.at <= cfg.recentWindowSec * 1000) recent.push(done);
        }
      }
      return { pending, recent: recent.slice(-cfg.recentDoneMax) };
    },

    forget(session, now = Date.now()) {
      if (!session) return;
      if (isSub(session)) {
        const root = rootIdOf(session.id);
        const state = root && roots.get(root);
        if (state) {
          for (const item of [...state.attention.values()]) clearAttention(state, item.id, now);
        }
        parentOf.delete(session.id);
        runningKids.delete(session.id);
        return;
      }
      const state = roots.get(session.id);
      if (!state) return;
      for (const item of [...state.attention.values()]) clearAttention(state, item.id, now);
      roots.delete(session.id);
      for (const [child, parent] of parentOf) if (parent === session.id) parentOf.delete(child);
    },

    pendingCount(rootId) {
      return roots.get(rootId)?.attention.size ?? 0;
    },

    rootCount() {
      return roots.size;
    },
  };
}
