// Tiny read-only HTTP/SSE server for the phone's foreground alert service.
//
//   GET /health   -> {"ok":true,"version":"0.1.0","clients":N}
//   GET /events   -> text/event-stream: hello, snapshot, then live events
//
// Auth: Bearer <pocket LAN PIN>. Only private/loopback peers are accepted, so
// binding 0.0.0.0 cannot expose the feed on a public interface even by accident.
import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';

const VERSION = '0.1.0';
const FAILURE_WINDOW_MS = 60_000;
const FAILURE_LIMIT = 10;

const PRIVATE_V4 = /^(?:10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|127\.)/;

export function isPrivateAddress(address) {
  const raw = String(address ?? '').replace(/^::ffff:/i, '');
  if (raw === '::1') return true;
  if (PRIVATE_V4.test(raw)) return true;
  const lower = raw.toLowerCase();
  if (!lower.includes(':')) return false;
  // IPv6 ULA fc00::/7 and link-local fe80::/10.
  if (/^f[cd][0-9a-f]{2}:/.test(lower)) return true;
  if (/^fe[89ab][0-9a-f]:/.test(lower)) return true;
  return false;
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a ?? ''));
  const right = Buffer.from(String(b ?? ''));
  if (left.length !== right.length || left.length === 0) return false;
  return timingSafeEqual(left, right);
}

function tokenOf(req, url) {
  const header = req.headers?.authorization;
  if (typeof header === 'string' && header.startsWith('Bearer ')) return header.slice(7).trim();
  return url.searchParams.get('token') ?? '';
}

export function startServer({ port = 3082, bind = '0.0.0.0', heartbeatSec = 15, readToken, snapshot, log = console }) {
  const clients = new Set();
  const failures = new Map();

  const rateLimited = (ip, now) => {
    const record = failures.get(ip);
    if (!record || now - record.start > FAILURE_WINDOW_MS) return false;
    return record.count >= FAILURE_LIMIT;
  };
  const recordFailure = (ip, now) => {
    const record = failures.get(ip);
    if (!record || now - record.start > FAILURE_WINDOW_MS) failures.set(ip, { count: 1, start: now });
    else record.count += 1;
  };

  const json = (res, status, body) => {
    const payload = JSON.stringify(body);
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'content-length': Buffer.byteLength(payload),
    });
    res.end(payload);
  };

  const writeEvent = (res, type, payload) => {
    const json = JSON.stringify({ v: 1, type, ...payload });
    res.write(`event: ${type}\ndata: ${json}\n\n`);
  };

  const broadcast = (note) => {
    if (!note || typeof note !== 'object') return;
    const { type, ...payload } = note;
    if (!type) return;
    for (const client of clients) {
      try {
        writeEvent(client.res, type, payload);
      } catch {
        clients.delete(client);
      }
    }
  };

  const server = createServer((req, res) => {
    const now = Date.now();
    const ip = req.socket?.remoteAddress ?? 'unknown';
    let url;
    try {
      url = new URL(req.url ?? '/', 'http://dsh-mobile-bridge.invalid');
    } catch {
      json(res, 400, { ok: false, error: 'bad-request' });
      return;
    }

    if (!isPrivateAddress(ip)) {
      json(res, 403, { ok: false, error: 'local-network-only' });
      return;
    }
    if (rateLimited(ip, now)) {
      json(res, 429, { ok: false, error: 'too-many-attempts' });
      return;
    }

    const token = readToken?.() ?? null;
    if (!token) {
      json(res, 503, { ok: false, error: 'pin-unavailable' });
      return;
    }
    if (!safeEqual(tokenOf(req, url), token)) {
      recordFailure(ip, now);
      json(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }

    if (req.method !== 'GET') {
      json(res, 405, { ok: false, error: 'method-not-allowed' });
      return;
    }

    if (url.pathname === '/health') {
      json(res, 200, { ok: true, version: VERSION, clients: clients.size, port });
      return;
    }

    if (url.pathname !== '/events') {
      json(res, 404, { ok: false, error: 'not-found' });
      return;
    }

    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      'connection': 'keep-alive',
      'x-accel-buffering': 'no',
    });
    res.write('retry: 3000\n\n');
    writeEvent(res, 'hello', { version: VERSION, heartbeatSec });
    try {
      const state = snapshot?.() ?? { pending: [], recent: [] };
      writeEvent(res, 'snapshot', state);
    } catch (err) {
      log.warn?.(`dsh-mobile-bridge: snapshot failed: ${err?.message ?? err}`);
      writeEvent(res, 'snapshot', { pending: [], recent: [] });
    }

    const client = { res, ip };
    clients.add(client);
    req.on('close', () => clients.delete(client));
    res.on('error', () => clients.delete(client));
  });

  server.requestTimeout = 0;
  server.headersTimeout = 60_000;
  server.keepAliveTimeout = 120_000;

  const heartbeat = setInterval(() => {
    for (const client of clients) {
      try {
        client.res.write(': hb\n\n');
      } catch {
        clients.delete(client);
      }
    }
  }, Math.max(5, heartbeatSec) * 1000);
  heartbeat.unref?.();

  server.on('error', (err) => {
    log.warn?.(`dsh-mobile-bridge: server error: ${err?.message ?? err}`);
  });

  server.listen(port, bind, () => {
    const actual = server.address();
    log.info?.(`dsh-mobile-bridge: listening on ${bind}:${actual && typeof actual === 'object' ? actual.port : port}`);
  });

  return {
    port,
    address() {
      const value = server.address();
      return value && typeof value === 'object' ? value.port : port;
    },
    get clients() {
      return clients.size;
    },
    broadcast,
    stop() {
      clearInterval(heartbeat);
      for (const client of clients) {
        try {
          client.res.end();
        } catch {
          // already closed
        }
      }
      clients.clear();
      try {
        server.closeAllConnections?.();
      } catch {
        // older Node
      }
      server.close();
    },
  };
}
