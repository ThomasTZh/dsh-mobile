# Third-party software, attribution, and modifications

This repository contains original Android app code and a bridge plugin. It does
not vendor unmodified third-party implementations. The whale-girl launcher icon
is the only third-party asset distributed here; it is MIT-licensed and modified
as described below.

| Component | Authors / copyright holder | Upstream source + revision | Licence | Usage / modifications |
| --- | --- | --- | --- | --- |
| DeepSeek Harness (DSH) | DeepSeek | <https://github.com/deepseek-ai/deepseek-harness> (development host) | MIT | Host and web UI; installed separately. No DSH source is included. |
| dsh-pocket | shaobeichen | <https://github.com/shaobeichen/dsh-pocket> 2.12.0 | GPL-2.0 | Separate PC prerequisite installed by the user. The app implements its local HTTP login protocol; the bridge reads its token file. No dsh-pocket code is included or linked. |
| dsh-whale-musume (鲸鱼娘) | Sutera-Diffusus / @SuteraWu | <https://github.com/Sutera-Diffusus/dsh-whale-musume> (icon source) | MIT | Modified launcher icon; see below. Full MIT text is reproduced here. |
| AndroidX, Jetpack Compose, Activity, Lifecycle, WebKit, CameraX | Google / Android Open Source Project | <https://developer.android.com/jetpack/androidx> | Apache-2.0 | Unmodified dependencies, pinned in `gradle/libs.versions.toml`; installed by Gradle. |
| Kotlin and kotlinx.coroutines / serialization | JetBrains | <https://kotlinlang.org/> | Apache-2.0 | Language/runtime and unmodified libraries. |
| OkHttp | Square | <https://square.github.io/okhttp/> 4.12.0 | Apache-2.0 | Unmodified HTTP client dependency. |
| ZXing | ZXing authors | <https://github.com/zxing/zxing> 3.5.4 | Apache-2.0 | QR decoding library dependency. |
| JUnit and AndroidX Test | JUnit / Google | <https://junit.org/>; <https://developer.android.com/training/testing> | EPL-1.0 / Apache-2.0 | Test-only dependencies; not part of the app runtime. |
| Gradle and the Android Gradle Plugin | Gradle Inc. / Google | <https://gradle.org/> 9.6.0; <https://developer.android.com/build> 9.4.0 | Apache-2.0 | Build tools; not distributed here. The Gradle wrapper is intentionally not vendored. |
| Node.js | OpenJS Foundation | <https://nodejs.org/> | MIT | Bridge tests and the mock bridge; not distributed here. |
| Pillow (optional icon regeneration) | Python Imaging Library contributors | <https://python-pillow.org/> | HPND | Not a runtime dependency; only needed to regenerate the launcher icon. |
| JDK 17 and Android SDK / build-tools | Oracle/OpenJDK contributors; Google | <https://adoptium.net/>; <https://developer.android.com/studio> | GPLv2+CE / Android SDK terms | Build tools; not distributed here. |

## Modified upstream component: whale-girl launcher icon

- **Original project and author:** `dsh-whale-musume` by Sutera-Diffusus; the
  upstream README credits the character art to @SuteraWu / the project author.
  <https://github.com/Sutera-Diffusus/dsh-whale-musume>
- **Original licence:** MIT License, Copyright (c) 2026 Sutera-Diffusus.
- **Files distributed:** `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`.
  The source logo and character art remain in the upstream repository; they are
  not copied as separate files here.
- **Changes:** the upstream logo was scaled/cropped to 432×432, made full-bleed,
  and composited with a blurred copy of the same artwork for the adaptive
  launcher icon. The app's Settings screen displays the credit.
- **Rationale:** the upstream logo is already a compact character bust suitable
  for a launcher icon; the blurred background fills the rounded corners of the
  adaptive mask.
- **Reconstruction:** obtain <https://raw.githubusercontent.com/Sutera-Diffusus/dsh-whale-musume/main/docs/images/logo.png>
  and run the following with Pillow 10+ (the upstream licence permits the
  modification; the output is the 432×432 launcher resource):

  ```python
  from PIL import Image, ImageFilter
  logo = Image.open("logo.png").convert("RGBA")
  bg = logo.resize((680, 680), Image.LANCZOS).crop((84, 84, 596, 596))
  bg = bg.filter(ImageFilter.GaussianBlur(14))
  bg = Image.alpha_composite(bg, Image.new("RGBA", (512, 512), (8, 12, 26, 70)))
  out = bg.copy()
  out.alpha_composite(logo.resize((500, 500), Image.LANCZOS), (6, 6))
  out.resize((432, 432), Image.LANCZOS).save("ic_launcher_foreground.png")
  ```

  Pillow is only needed to regenerate the icon; it is not a runtime dependency.
- **Verification:** the APK resource was installed on an Android 15 phone and
  displayed in App info; the MIT notice is packaged in the APK at
  `app/src/main/assets/THIRD_PARTY_NOTICES.txt`.

### MIT License (upstream icon)

MIT License

Copyright (c) 2026 Sutera-Diffusus

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Unmodified dependencies

AndroidX/Compose, Kotlin, OkHttp, ZXing, CameraX, WebKit, coroutines,
serialization, JUnit, and AndroidX Test are installed from their official
repositories at the versions pinned in `gradle/libs.versions.toml`. No copies of
their completed implementations are distributed here. DSH and dsh-pocket are
installed separately by the user; their code is not included in this repository.

## Copyright and licence compliance

- **Original repository code and documentation:** MIT; Copyright (c) 2026
  ThomasTZh. See [LICENSE](LICENSE). The root licence does not claim the icon or
  third-party dependencies.
- **Whale-girl icon:** MIT; Copyright (c) 2026 Sutera-Diffusus; full notice above
  and in `app/src/main/assets/THIRD_PARTY_NOTICES.txt`. The icon's copyright and
  permission notice must remain with redistributed copies.
- **DSH:** MIT; external host; no code included.
- **dsh-pocket:** GPL-2.0; external PC program. It is not distributed or linked
  by this repository. The app communicates with it over its local HTTP protocol;
  the bridge reads its token file. Users who redistribute dsh-pocket must comply
  with its own GPL-2.0 obligations.
- **No unresolved third-party code:** the app and bridge are original work; the
  only modified third-party asset is the icon described above. The unlicensed
  viral fan-art image was not used.
- **Trademark:** the project is unofficial and is not affiliated with,
  endorsed by, or sponsored by DeepSeek. No official DeepSeek logo is used.
