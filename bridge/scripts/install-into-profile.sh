#!/usr/bin/env bash
# Link DSH Mobile's local alert bridge into the running DSH web profile.
#
# This writes outside the workspace ($DSH_HOME) and intentionally does NOT
# restart `dsh web`: a restart ends the current DSH session. The profile uses
# `patchReload: live`, so the running host may pick the plugin up immediately.
# If it does not, restart DSH web when convenient.
set -euo pipefail

SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
PROFILE="${DSH_PROFILE:-web}"
PROFILE_DIR="$DSH_HOME/profiles/$PROFILE"
LINK="$DSH_HOME/local-plugins/dsh-mobile-bridge"

if [[ ! -d "$PROFILE_DIR" ]]; then
  echo "profile directory not found: $PROFILE_DIR" >&2
  exit 1
fi

mkdir -p "$DSH_HOME/local-plugins"
ln -sfn "$SOURCE" "$LINK"
echo "linked $LINK -> $SOURCE"

cd "$PROFILE_DIR"
pnpm add -w "link:$LINK"

PROFILE_DIR="$PROFILE_DIR" node --input-type=module <<'NODE'
import { readFileSync, writeFileSync } from 'node:fs';
const file = `${process.env.PROFILE_DIR}/cordis.patch.yml`;
let text = readFileSync(file, 'utf8');
if (text.includes('dsh-mobile-bridge')) {
  console.log('mobile-bridge insert already present');
} else {
  text += `\n# DSH Mobile: local-only SSE alert bridge (reads session events, never commands)\n- insert:\n    - id: mobile-bridge\n      name: '@local/dsh-mobile-bridge'\n      config:\n        port: 3082\n`;
  writeFileSync(file, text);
  console.log('added mobile-bridge insert to cordis.patch.yml');
}
NODE

TOKEN_FILE="$DSH_HOME/dsh-pocket/token-lan"
if [[ -f "$TOKEN_FILE" ]]; then
  echo
  echo "Verify from this PC (the PIN is read from the pocket file, not printed):"
  echo "  curl -s -H \"Authorization: Bearer \$(tr -d '\\r\\n' < $TOKEN_FILE)\" http://127.0.0.1:3082/health"
  echo
  echo "If that fails, the running host did not hot-load the plugin. Restarting"
  echo "\`dsh web\` will activate it, but that ends the current DSH session."
else
  echo "warning: $TOKEN_FILE does not exist; the bridge needs the pocket LAN PIN" >&2
fi
