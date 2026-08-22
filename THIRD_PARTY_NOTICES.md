# Third-party notices — 4Zones (Shizuku Edition)

This file records every third-party component **packaged into the released
artifact**, and the notice obligation each one carries. It is a
distribution-level notice file: it covers what ships, not what is used to build.

**How this inventory was produced (reproducible).** The component list is the
resolved release runtime graph, not a hand-written list:

```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Each module's licence was then read from its own POM in the Gradle module cache
(`~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/*/<artifact>-<version>.pom`,
element `<licenses><license><name>`). Both steps are re-runnable against this
repository at the version pins in `gradle/libs.versions.toml`, which is what
makes the inventory reproducible rather than asserted.

**Inventory size:** 111 modules on `releaseRuntimeClasspath`.

**Vendored third-party source:** none. This repository vendors no third-party
source or native code. `gradle/wrapper/gradle-wrapper.jar` is a build-time
bootstrap and is not packaged into the APK. The only native libraries in the
artifact (`libandroidx.graphics.path.so`) come from the Apache-2.0
`androidx.graphics:graphics-path` dependency listed below, not from this
repository.

---

## MIT — Shizuku API (4 modules)

Shizuku is the privilege backend this edition depends on. Its client libraries
are **MIT**, not Apache-2.0, and MIT requires that the copyright notice and the
permission notice be reproduced in distributions.

- `dev.rikka.shizuku:aidl:13.1.5`
- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`
- `dev.rikka.shizuku:shared:13.1.5`

Project: <https://github.com/RikkaApps/Shizuku-API>
Author: Rikka (<https://github.com/RikkaW>)
Declared licence URL (from the POM):
<https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE>

> **OBLIGATION CLOSED — 2026-08-20, owner-authorised network fetch.**
> The copyright line below is transcribed verbatim from the upstream LICENSE at
> the declared POM URL, retrieved from
> `https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/LICENSE`
> (1062 bytes). The whole MIT block below was then verified byte-for-byte
> against that file. It was NOT derivable offline: none of the four cached
> `dev.rikka.shizuku` AARs embeds a LICENSE or NOTICE entry, and the POMs carry
> only the licence name and URL — so the earlier refusal to invent it was correct.

```
MIT License

Copyright (c) 2021 RikkaW

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
```

*The Shizuku **manager application** is a separate app the user installs
themselves. It is not redistributed here and is not part of this artifact.*

---

## Apache License 2.0 — AndroidX, Jetpack Compose, Kotlin, KotlinX (105 modules)

Each POM below declares "The Apache Software License, Version 2.0" or "The
Apache License, Version 2.0". The full licence text is distributed with this
repository as [`LICENSE`](LICENSE) (this project is itself Apache-2.0, so a
single copy satisfies both obligations). None of these components ships a
NOTICE file in its artifact, so there is no NOTICE text to propagate.

- `androidx.activity:activity:1.13.0`
- `androidx.activity:activity-compose:1.13.0`
- `androidx.activity:activity-ktx:1.13.0`
- `androidx.annotation:annotation:1.10.0`
- `androidx.annotation:annotation-experimental:1.4.1`
- `androidx.annotation:annotation-jvm:1.10.0`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`
- `androidx.autofill:autofill:1.0.0`
- `androidx.collection:collection:1.5.0`
- `androidx.collection:collection-jvm:1.5.0`
- `androidx.collection:collection-ktx:1.5.0`
- `androidx.compose.animation:animation:1.11.4`
- `androidx.compose.animation:animation-android:1.11.4`
- `androidx.compose.animation:animation-core:1.11.4`
- `androidx.compose.animation:animation-core-android:1.11.4`
- `androidx.compose.foundation:foundation:1.11.4`
- `androidx.compose.foundation:foundation-android:1.11.4`
- `androidx.compose.foundation:foundation-layout:1.11.4`
- `androidx.compose.foundation:foundation-layout-android:1.11.4`
- `androidx.compose.material3:material3:1.4.0`
- `androidx.compose.material3:material3-android:1.4.0`
- `androidx.compose.material:material-ripple:1.11.4`
- `androidx.compose.material:material-ripple-android:1.11.4`
- `androidx.compose.runtime:runtime:1.11.4`
- `androidx.compose.runtime:runtime-android:1.11.4`
- `androidx.compose.runtime:runtime-annotation:1.11.4`
- `androidx.compose.runtime:runtime-annotation-android:1.11.4`
- `androidx.compose.runtime:runtime-retain:1.11.4`
- `androidx.compose.runtime:runtime-retain-android:1.11.4`
- `androidx.compose.runtime:runtime-saveable:1.11.4`
- `androidx.compose.runtime:runtime-saveable-android:1.11.4`
- `androidx.compose.ui:ui:1.11.4`
- `androidx.compose.ui:ui-android:1.11.4`
- `androidx.compose.ui:ui-geometry:1.11.4`
- `androidx.compose.ui:ui-geometry-android:1.11.4`
- `androidx.compose.ui:ui-graphics:1.11.4`
- `androidx.compose.ui:ui-graphics-android:1.11.4`
- `androidx.compose.ui:ui-text:1.11.4`
- `androidx.compose.ui:ui-text-android:1.11.4`
- `androidx.compose.ui:ui-tooling-preview:1.11.4`
- `androidx.compose.ui:ui-tooling-preview-android:1.11.4`
- `androidx.compose.ui:ui-unit:1.11.4`
- `androidx.compose.ui:ui-unit-android:1.11.4`
- `androidx.compose.ui:ui-util:1.11.4`
- `androidx.compose.ui:ui-util-android:1.11.4`
- `androidx.compose:compose-bom:2026.06.01`
- `androidx.concurrent:concurrent-futures:1.1.0`
- `androidx.core:core:1.19.0`
- `androidx.core:core-ktx:1.19.0`
- `androidx.core:core-viewtree:1.0.0`
- `androidx.customview:customview-poolingcontainer:1.0.0`
- `androidx.documentfile:documentfile:1.0.0`
- `androidx.dynamicanimation:dynamicanimation:1.0.0`
- `androidx.emoji2:emoji2:1.4.0`
- `androidx.graphics:graphics-path:1.0.1`
- `androidx.interpolator:interpolator:1.0.0`
- `androidx.legacy:legacy-support-core-utils:1.0.0`
- `androidx.lifecycle:lifecycle-common:2.11.0`
- `androidx.lifecycle:lifecycle-common-java8:2.11.0`
- `androidx.lifecycle:lifecycle-common-jvm:2.11.0`
- `androidx.lifecycle:lifecycle-livedata:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-process:2.11.0`
- `androidx.lifecycle:lifecycle-runtime:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-android:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-compose:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-android:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0`
- `androidx.loader:loader:1.0.0`
- `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0`
- `androidx.navigationevent:navigationevent:1.0.0`
- `androidx.navigationevent:navigationevent-android:1.0.0`
- `androidx.navigationevent:navigationevent-compose:1.0.0`
- `androidx.navigationevent:navigationevent-compose-android:1.0.0`
- `androidx.print:print:1.0.0`
- `androidx.profileinstaller:profileinstaller:1.4.0`
- `androidx.savedstate:savedstate:1.4.0`
- `androidx.savedstate:savedstate-android:1.4.0`
- `androidx.savedstate:savedstate-compose:1.4.0`
- `androidx.savedstate:savedstate-compose-android:1.4.0`
- `androidx.savedstate:savedstate-ktx:1.4.0`
- `androidx.startup:startup-runtime:1.1.1`
- `androidx.tracing:tracing:1.2.0`
- `androidx.transition:transition:1.6.0`
- `androidx.versionedparcelable:versionedparcelable:1.1.1`
- `androidx.window:window:1.5.0`
- `androidx.window:window-core:1.5.0`
- `androidx.window:window-core-android:1.5.0`
- `org.jetbrains.kotlin:kotlin-stdlib:2.2.10`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3`
- `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`
- `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3`
- `org.jetbrains:annotations:23.0.0`

---

## Other components (2 modules)

- `com.google.guava:listenablefuture:1.0`
- `org.jspecify:jspecify:1.0.0`

- `com.google.guava:listenablefuture:1.0` — a stub artifact published by the
  Guava project (it exists only to resolve a version conflict and contains no
  compiled classes of its own). **Its POM declares no `<license>` element.**
  Recorded as Apache-2.0 **by project provenance, not by declaration** — Guava
  is Apache-2.0. This inference is flagged rather than silently resolved.
- `org.jspecify:jspecify:1.0.0` — Apache-2.0 (declared "The Apache License,
  Version 2.0"); grouped here rather than above only because it is neither an
  AndroidX nor a JetBrains component.

---

## Components deliberately NOT in this list

`androidx.compose.ui:ui-tooling`, `androidx.compose.ui:ui-tooling-data` and
`androidx.compose.ui:ui-test-manifest` are `debugImplementation`-scoped. They
are absent from `releaseRuntimeClasspath` and from the released artifact, so
they carry no distribution obligation here. `androidx.compose.ui:ui-tooling-preview`
IS a release input (an intentional `implementation` dependency) and is listed
above.

## Scope of this file

This inventory is complete for the resolved release runtime graph at the version
pins in `gradle/libs.versions.toml`. It is **not** a warranty that every
transitive artifact's own embedded notices were individually extracted: the
artifacts were checked for embedded `LICENSE`/`NOTICE` entries and the Shizuku
AARs contain none, but the check was per-group, not exhaustive per-artifact.
Any dependency change requires this file to be regenerated by the commands above.
