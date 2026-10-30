// Read the pocket LAN PIN lazily so a PIN rotation in the DSH settings page is
// picked up without reloading the bridge.
import { readFileSync, statSync } from 'node:fs';

export function createTokenReader(file) {
  let cached = null;
  let mtime = -1;

  return function read() {
    try {
      const stat = statSync(file);
      if (stat.mtimeMs === mtime) return cached;
      const raw = readFileSync(file, 'utf8').trim();
      cached = raw.length > 0 ? raw : null;
      mtime = stat.mtimeMs;
      return cached;
    } catch {
      cached = null;
      mtime = -1;
      return null;
    }
  };
}
