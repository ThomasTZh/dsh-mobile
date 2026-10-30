// Local-only notification bridge for DSH Mobile.
//
// The phone cannot see DSH's event bus, so this plugin subscribes to the host's
// session/agent events and exposes a tiny read-only SSE feed on the LAN. It never
// accepts commands: the worst a leaked token exposes is notification text.
import os from 'node:os';
import path from 'node:path';
import { promises as fs } from 'node:fs';
import { createMonitor } from './monitor.js';
import { startServer } from './server.js';
import { createTokenReader } from './token.js';

export const name = 'dsh-mobile-bridge';
export const inject = ['agents'];

const DEFAULTS = {
  port: 3082,
  bind: '0.0.0.0',
  settleSec: 5,
  minDurationSec: 20,
  doneCooldownSec: 60,
  attentionGraceSec: 4,
  heartbeatSec: 15,
  startedAt: 0,
};

function titleOf(ctx, session) {
  try {
    const service = ctx.get('sessionTitle');
    return service?.get?.(session) ?? undefined;
  } catch {
    return undefined;
  }
}

export function apply(ctx, rawConfig = {}) {
  const cfg = { ...DEFAULTS, ...rawConfig, startedAt: Date.now() };
  const log = ctx.logger?.('mobile-bridge') ?? console;
  const tokenFile = process.env.DSH_MOBILE_TOKEN_FILE
    || path.join(process.env.DSH_HOME || path.join(os.homedir(), '.dsh'), 'dsh-pocket', 'token-lan');
  const readToken = createTokenReader(tokenFile);

  let server = null;
  const safe = (fn) => (...args) => {
    try {
      return fn(...args);
    } catch (err) {
      log.warn?.(`dsh-mobile-bridge: listener failed: ${err?.message ?? err}`);
    }
  };

  const monitor = createMonitor(cfg, (note) => {
    try {
      server?.broadcast(note);
    } catch (err) {
      log.warn?.(`dsh-mobile-bridge: broadcast failed: ${err?.message ?? err}`);
    }
  });

  ctx.on('session/event', safe((session, event) => {
    monitor.event(session, event, Date.now(), (s) => titleOf(ctx, s));
  }), { global: true });
  ctx.on('agent/status', safe(({ agent, status }) => monitor.status(agent.session, status, Date.now(), (session) => titleOf(ctx, session))));
  ctx.on('agent/disposed', safe(({ agent }) => monitor.forget(agent.session, Date.now())));

  for (const agent of ctx.agents.list()) {
    safe(() => monitor.status(agent.session, agent.status, Date.now(), (session) => titleOf(ctx, session)))();
  }

  ctx.effect(() => {
    server = startServer({
      port: cfg.port,
      bind: cfg.bind,
      heartbeatSec: cfg.heartbeatSec,
      tokenPath: tokenFile,
      readToken,
      snapshot: () => monitor.snapshot(Date.now()),
      log,
    });

    const timer = setInterval(safe(() => monitor.tick(Date.now())), 1000);
    timer.unref?.();
    return () => {
      clearInterval(timer);
      server?.stop();
      server = null;
    };
  }, 'dsh-mobile-bridge server');

  log.info?.(`dsh-mobile-bridge: ready on ${cfg.bind}:${cfg.port}`);
}

/** Exported for tests and for the install script's token path. */
export function pocketTokenPath(home = process.env.DSH_HOME || path.join(os.homedir(), '.dsh')) {
  return path.join(home, 'dsh-pocket', 'token-lan');
}

export { createMonitor };
