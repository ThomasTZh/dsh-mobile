#!/usr/bin/env node
// Development double for the PC-side DSH Mobile Bridge. It speaks the same
// /health + /events contract, plus POST /emit so the Android notification stack
// can be exercised without installing anything into the user's DSH profile.
//
//   DSH_MOBILE_PIN=... node tools/mock-bridge.mjs [--port 3082]
//
// Never run this on an untrusted network: it is a test tool, not the real bridge.
import { createServer } from 'node:http';

const args = process.argv.slice(2);
const portArg = args.indexOf('--port');
const port = Number(portArg >= 0 ? args[portArg + 1] : process.env.PORT || 3082);
const token = process.env.DSH_MOBILE_PIN || '';
if (!token) {
  console.error('DSH_MOBILE_PIN is required');
  process.exit(2);
}

const clients = new Set();
const startedAt = Date.now();

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

function authorized(req, url) {
  const header = req.headers.authorization;
  if (typeof header === 'string' && header.startsWith('Bearer ')) {
    return header.slice(7).trim() === token;
  }
  return url.searchParams.get('token') === token;
}

function writeEvent(res, type, payload) {
  res.write(`event: ${type}\ndata: ${JSON.stringify({ v: 1, type, ...payload })}\n\n`);
}

function broadcast(note) {
  for (const res of clients) {
    try {
      writeEvent(res, note.type, note);
    } catch {
      clients.delete(res);
    }
  }
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://mock.invalid');
  if (!authorized(req, url)) return json(res, 401, { ok: false, error: 'unauthorized' });

  if (url.pathname === '/health') {
    return json(res, 200, { ok: true, version: 'mock-1', clients: clients.size, uptimeSec: Math.round((Date.now() - startedAt) / 1000) });
  }

  if (url.pathname === '/events') {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      connection: 'keep-alive',
    });
    res.write('retry: 3000\n\n');
    writeEvent(res, 'hello', { version: 'mock-1', heartbeatSec: 15 });
    writeEvent(res, 'snapshot', { pending: [], recent: [] });
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }

  if (url.pathname === '/emit' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 64 * 1024) req.destroy();
    });
    req.on('end', () => {
      let parsed;
      try {
        parsed = JSON.parse(body);
      } catch {
        return json(res, 400, { ok: false, error: 'bad-json' });
      }
      const events = Array.isArray(parsed) ? parsed : [parsed];
      for (const event of events) {
        if (!event || typeof event.type !== 'string') continue;
        broadcast({ ...event, at: event.at ?? Date.now() });
      }
      json(res, 200, { ok: true, sent: events.length, clients: clients.size });
    });
    return;
  }

  json(res, 404, { ok: false, error: 'not-found' });
});

const heartbeat = setInterval(() => {
  for (const res of clients) {
    try {
      res.write(': hb\n\n');
    } catch {
      clients.delete(res);
    }
  }
}, 15_000);
heartbeat.unref();

server.listen(port, '0.0.0.0', () => {
  console.log(`mock-bridge listening on 0.0.0.0:${port}`);
});
