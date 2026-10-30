# DSH Mobile Bridge

A tiny, local-only DSH plugin that lets the DSH Mobile Android app show
notifications when a session finishes, needs an approval, or asks a question.

The phone cannot see DSH's internal event bus, so this plugin subscribes to it
on the PC and exposes a read-only SSE feed on the LAN. It accepts no commands:
the worst a leaked token exposes is notification text.

## Contract

- `GET /health` -> `{"ok":true,"version":"0.1.0","clients":N}`
- `GET /events` -> `text/event-stream`
  - `hello`, `snapshot` on connect
  - `turn-done`, `approval`, `question`, `error`, `clear` events
  - `: hb` comments every 15s
- Auth: `Authorization: Bearer <pocket LAN PIN>` (or `?token=` for curl).
- Listen: `0.0.0.0:3082` (configurable in `cordis.patch.yml`).
- Source peers must be loopback/private LAN addresses.

The token is read from `$DSH_HOME/dsh-pocket/token-lan` on every check, so a PIN
rotation in DSH Pocket is picked up without reloading the plugin. The API never
prints the token.

## What counts as a notification

- **turn-done**: the root session's completed turn settles, no subagent is
  running, no approval/question is open, and the chain lasted at least
  `minDurationSec` (default 20s). Consecutive turns inside the settle window
  become one notification; one per `doneCooldownSec` (default 60s).
- **approval**: `approval/asked`; cleared by `approval/decided`.
- **question**: `ask_user_question` / `exit_plan_mode`; cleared by a result or a
  `user-question-reply`.
- **error**: a turn that ends with `reason.kind === "error"`, coalesced.

Subagents never notify on their own; their approvals/questions are attributed to
the root session. Stalls, retry storms and loops are out of scope.

## Install

From the repository root:

```sh
bridge/scripts/install-into-profile.sh
```

The script links the package into `$DSH_HOME/local-plugins`, adds it to the
`web` profile with pnpm, and appends an insert to the profile's
`cordis.patch.yml`. It deliberately does **not** restart `dsh web`; a restart
ends the current DSH session. The web profile uses `patchReload: live`, so the
running host may pick it up immediately.

Verify (from the PC):

```sh
PIN=$(tr -d '\r\n' < ~/.dsh/dsh-pocket/token-lan)
curl -s -H "Authorization: Bearer $PIN" http://127.0.0.1:3082/health
```

If that fails with connection refused/404, restart `dsh web` when convenient,
then verify again. From the phone, the same URL must use the PC's LAN IP.

## Uninstall

Remove the `mobile-bridge` insert from `~/.dsh/profiles/web/cordis.patch.yml`,
run `pnpm remove -w @local/dsh-mobile-bridge` in that profile, and remove
`~/.dsh/local-plugins/dsh-mobile-bridge`.

## Development

```sh
node --test test/*.test.mjs
```

The pure monitor tests cover finish/coalescing, approvals, questions, errors and
subagent attribution. The server tests cover token auth, 503 without a PIN,
private-address classification, and the SSE snapshot/live flow.

`tools/mock-bridge.mjs` implements the same HTTP contract plus `POST /emit`,
so the Android notification stack can be tested without installing this plugin.
It is a development tool, not part of the shipped bridge. Run it from this
directory with a local test PIN:

```sh
DSH_MOBILE_PIN=test-only node tools/mock-bridge.mjs --port 3082
```

## Licence

The bridge plugin is MIT licensed; see the repository `LICENSE` and
[`THIRD_PARTY.md`](../THIRD_PARTY.md). The DSH host is MIT. `dsh-pocket` is a
separate GPL-2.0 program installed on the PC; this plugin reads its token file
for authentication but includes no dsh-pocket code and does not link against it.

## Security

- The feed is read-only and LAN-only. There is no method to approve, answer, or
  execute anything.
- The bearer token is the pocket LAN PIN. That PIN already grants full DSH
  access on the LAN; the bridge does not widen the trust boundary.
- No prompt text, answers, file contents, or workspace paths are written to
  logs. Only the event kind, session id, label and short reason/question text
  travel to the phone.
- Notifications are optional: the Android app works without this plugin and
  shows "Bridge not detected" instead of failing.
