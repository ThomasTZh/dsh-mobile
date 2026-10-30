# DSH Mobile

A LAN-only Android client for **DeepSeek Harness (DSH)**, with a native shell for the
DSH web UI and a companion PC bridge for background alerts. It is unofficial and is
not affiliated with, endorsed by, or sponsored by DeepSeek.

## Abstract

- **Problem:** DSH is designed around a desktop browser and a local web server. A
  phone can reach it over the LAN, but a plain mobile browser loses the PIN flow,
  clipboard, uploads/downloads, and reliable background notifications.
- **Contribution:** an Android WebView shell that performs the DSH Pocket LAN
  login natively, stores the PIN encrypted with Android Keystore, keeps the real
  DSH UI, adds Android file/clipboard integrations, and runs a foreground service
  fed by a read-only PC-side alert bridge.
- **Approach:** a Kotlin/Compose app loads DSH through the DSH Pocket LAN proxy;
  the bridge subscribes to DSH session events and exposes a read-only SSE feed
  authenticated by the Pocket LAN PIN. No command channel is exposed on the
  phone.
- **Evidence:** the release tree builds, app unit tests and lint pass, and the
  bridge test suite passes. A development build was exercised on an Android 15
  phone with DSH Pocket 2.12.0: LAN login, DSH UI, keyboard/composer behavior,
  background notifications, and a live DSH question notification. Quantitative
  performance was not benchmarked.

## Composition

### Original components

| Component | Path | Role |
| --- | --- | --- |
| Android app | `app/` | Compose shell, DSH Pocket login, WebView host, PIN storage, local-only URL policy, QR/discovery, background alert service |
| PC alert bridge | `bridge/` | DSH plugin; subscribes to session events and serves a read-only SSE feed |
| Mock bridge | `bridge/tools/mock-bridge.mjs` | Test double for the bridge contract; supports `POST /emit` |

### App behavior

- One-tap reconnect to a saved LAN server; the PIN is stored with Android
  Keystore. A saved server can be reopened from the saved-server list.
- QR, gallery QR, manual address, or LAN discovery for first setup.
- The real DSH web UI: sessions, workspaces, plugins, model picker, streaming,
  approvals/questions, file picker and downloads.
- Native clipboard bridge, external-link handoff, system document picker, and
  `DownloadManager` downloads with the server cookie.
- Phone UI adaptations: Enter inserts a newline (the send button submits);
  opening a session does not raise the keyboard; the session header uses two
  rows on narrow screens; workspace menus render above the sidebar. These rules
  target DSH-generated class names and may need attention after a DSH update.
- Background alerts: an ongoing low-importance service notification,
  high-importance approval/question notifications, and finished/error updates.
  The service stops on Disconnect, alerts-off, or a recents swipe.
- Local-only policy: RFC1918 IPv4, IPv6 ULA/link-local, `localhost`, and
  `.local`/single-label names; public hosts, CGNAT, credentials in URLs, and
  non-http schemes are refused before any request.

### External components

Nothing from the following is bundled as source or binary except the icon
attribution described below:

- **DeepSeek Harness (DSH)** [1] is the MIT-licensed host and UI. The app loads
  its web UI over HTTP; the bridge uses its plugin API.
- **dsh-pocket** [2] is a separate GPL-2.0 program installed on the PC that
  provides the LAN proxy and the PIN/cookie login. It is not included here; the
  app implements its local HTTP login protocol and communicates with it at
  arm's length. The bridge reads the Pocket token file for authentication only.
  Installing or redistributing dsh-pocket is outside this repository.
- **AndroidX, Compose, Kotlin, OkHttp, ZXing, CameraX, WebKit,
  kotlinx.coroutines, and kotlinx.serialization** are unmodified dependencies
  installed from their official repositories; the pinned versions are in
  `gradle/libs.versions.toml`.
- **Gradle, the Android SDK, the JDK, and Node.js** are build/test tools, not
  distributed here. The Gradle wrapper is intentionally not vendored.

### Platform and tools

| Component | Requirement |
| --- | --- |
| Phone | Android 10+ (minSdk 29); tested on Android 15 with WebView 155 |
| PC | Linux/macOS/Windows with `dsh web` and DSH Pocket 2.12.0; same LAN |
| Build | JDK 17, Android SDK 37, build-tools 36.0.0, Gradle 9.6.0 (or the Android Studio equivalent) |
| App dependencies | pinned in `gradle/libs.versions.toml`; AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2025.08.00 |
| Bridge tests | Node.js 18+ |
| Network | Phone and PC on the same private LAN; Pocket proxy port 3081 and bridge port 3082 reachable |

## Build

The repository does not vendor the Gradle wrapper. Use Gradle 9.6.0 directly or
let Android Studio use its bundled Gradle. To generate a local wrapper:

```sh
gradle wrapper --gradle-version 9.6.0   # optional; generated files are ignored
```

Set the SDK/JDK paths; `local.properties` is optional when `ANDROID_HOME` is set:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk

gradle :app:assembleDebug
gradle :app:testDebugUnitTest :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and is
signed with the standard Android debug keystore. No keystore, APK, or build
output is committed.

The app targets SDK 36. Android 17's `ACCESS_LOCAL_NETWORK` runtime permission is
declared but not requested; moving to target 37 requires implementing that
request.

## Connect

1. Install the APK.
2. Scan the Pocket LAN QR, or choose **Enter address** and use
   `http://<PC-IP>:3081`.
3. Enter the Pocket LAN PIN; **Remember PIN** is enabled by default.
4. Reopen the saved server to reconnect. If the PIN changed, enter the new one.

The app never sends a request to a public address. If the PC is unreachable it
shows a setup/retry message rather than hanging.

## Background alerts

DSH's event bus lives inside the PC process. The companion bridge is required for
reliable alerts; without it the app works normally and shows "Bridge not
detected".

1. From the repository root, install the bridge into the DSH `web` profile:

   ```sh
   bridge/scripts/install-into-profile.sh
   ```

   The script links the plugin into `$DSH_HOME/local-plugins`, adds it to the
   profile with pnpm, and appends an insert to `cordis.patch.yml`. It does not
   restart `dsh web`. The profile's live patch reload may load it immediately; if
   not, restart DSH when convenient (that ends the current DSH session).

2. Verify the bridge from the PC:

   ```sh
   PIN=$(tr -d '\r\n' < ~/.dsh/dsh-pocket/token-lan)
   curl -s -H "Authorization: Bearer $PIN" http://127.0.0.1:3082/health
   ```

3. On the phone: **Menu -> Settings -> Background alerts**, grant notification
   permission, and allow unrestricted battery use if the phone is aggressive.

The bridge exposes no command channel. It is read-only and local-only; see
[`bridge/README.md`](bridge/README.md) for the event contract, install/uninstall,
security notes, and tests.

## Verification

All commands below were run from a clean release tree unless marked otherwise.

| Check | Command / conditions | Result |
| --- | --- | --- |
| Debug build | Gradle 9.6.0, JDK 17, SDK 37; `gradle :app:assembleDebug` | pass; APK produced |
| App unit tests | `gradle :app:testDebugUnitTest` | 31 tests pass |
| Lint | `gradle :app:lintDebug` | pass |
| Bridge tests | `cd bridge && node --test test/*.test.mjs` | 14 tests pass |
| Device smoke | development phone; `connectedDebugAndroidTest` | pass in development; not repeated in the clean tree because the runner uninstalls the app and its data |
| Live LAN E2E | Android 15 phone + DSH Pocket 2.12.0 + DSH web, development network | connect, DSH UI, drawer, composer, keyboard, PIN storage, notification permission/tap, recents-swipe stop, task-removal cleanup |
| Live alert | real bridge on the PC; one real DSH `ask_user_question` | high-priority question notification delivered and cleared after answering; the matching turn-done was intentionally suppressed by the question alert |
| Mock alert paths | mock bridge `POST /emit` | done, approval, question, error and clear notifications observed; recents swipe cancels the service notification |
| Icon licence | source/licence inspection | MIT; attribution and full notice included |

The live tests used a private development network and a phone; the exact
hardware/network prerequisites are listed above and are not needed for the
software-only checks. No performance or battery benchmark was run.

## Conclusion

The app provides a small, local-only Android access path to DSH with native
authentication and background alerts. It deliberately adds no cloud relay, no
account system, and no command endpoint. The main replication boundaries are the
separate GPL-2.0 DSH Pocket proxy, the PC-side bridge install, and phone/network
conditions that cannot be reproduced by a software-only checkout.

## Limitations

- LAN only; it does not work over mobile data or a public tunnel.
- DSH Pocket 2.12.0 is a separate GPL-2.0 prerequisite; it is not distributed or
  linked by this repository.
- Alerts require the PC bridge, a saved PIN, notification permission, and
  Android battery allowances. There is no FCM or public push service.
- The service stops when the app is swiped from recents; no notification is
  delivered after that.
- A finish alert is suppressed when an approval/question already alerted for the
  turn; the question is the human alert.
- The UI adaptations target DSH's generated class names; a DSH update may return
  the stock layout until the selectors are updated.
- Downloading `blob:`/`data:` links is not supported by WebView's download
  callback.
- A notification opens the app but does not deep-link to a specific session.
- Android 17 target-SDK migration and the `ACCESS_LOCAL_NETWORK` runtime request
  remain future work.
- The debug APK is not committed; build it locally or attach it to a GitHub
  Release.

## Acknowledgements

- **DeepSeek Harness** [1]: the MIT-licensed host, UI, and plugin API.
- **dsh-pocket** [2]: the separate GPL-2.0 LAN proxy used as a prerequisite.
- **dsh-whale-musume / 鲸鱼娘** [3]: the MIT-licensed community whale-girl art
  used for the launcher icon, modified by cropping, resizing, and adding a blurred
  background. The unlicensed viral fan-art image was not used.
- **AndroidX / Jetpack Compose** [4], **Kotlin** [5], **OkHttp** [6],
  **ZXing** [7], and the other pinned dependencies: unmodified libraries
  installed from their official repositories.
- **Gradle** [8] and **Node.js** [9]: build and test tools.
- See [`THIRD_PARTY.md`](THIRD_PARTY.md) for complete attribution, licences,
  modification details, and the full MIT text for the icon.

## References

1. DeepSeek, *DeepSeek Harness*, MIT License.
   <https://github.com/deepseek-ai/deepseek-harness>
2. shaobeichen, *dsh-pocket* 2.12.0, GPL-2.0.
   <https://github.com/shaobeichen/dsh-pocket>
3. Sutera-Diffusus, *dsh-whale-musume / 鲸鱼娘*, MIT License.
   <https://github.com/Sutera-Diffusus/dsh-whale-musume>
4. Android Open Source Project, *AndroidX / Jetpack Compose*, Apache-2.0.
   <https://developer.android.com/jetpack/androidx>
5. JetBrains, *Kotlin*, Apache-2.0. <https://kotlinlang.org/>
6. Square, *OkHttp* 4.12.0, Apache-2.0. <https://square.github.io/okhttp/>
7. ZXing authors, *ZXing* 3.5.4, Apache-2.0. <https://github.com/zxing/zxing>
8. Gradle Inc., *Gradle* 9.6.0, Apache-2.0. <https://gradle.org/>
9. OpenJS Foundation, *Node.js*, MIT. <https://nodejs.org/>

## Licence

The original source code, tests, build configuration, and documentation in this
repository are released under the MIT License; see [`LICENSE`](LICENSE). The
launcher icon is a modified MIT-licensed work by Sutera-Diffusus and is excluded
from that copyright claim; its notice is preserved in
[`THIRD_PARTY.md`](THIRD_PARTY.md) and `app/src/main/assets/THIRD_PARTY_NOTICES.txt`.
Third-party dependencies remain under their own licences.
