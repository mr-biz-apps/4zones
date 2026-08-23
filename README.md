# 4Zones

**Shizuku-backed build.** Restore reliable four-zone window tiling to Samsung DeX and Android desktop mode.

4Zones snaps your active window cleanly into one of four screen quadrants. Tap a zone in the app and the window you are using moves into it. If you also want a keyboard route, an optional accessibility service adds fixed global shortcuts (`Alt` + `Win` + `1`–`4`) that do the same thing without opening the app. There are no half-screen layouts and the shortcuts are not customisable. Privileged window operations are performed through [Shizuku](https://shizuku.rikka.app/), which 4Zones uses as its privilege backend — no root required.

---

## Release Repository Scope

This repository (`mr-biz-apps/4zones`) is the **public source and sideload-release
repository** for the Shizuku-backed edition of 4Zones. The app is not on Google
Play; releases are published here as directly installable APKs.

> [!NOTE]
> This repository contains the **full source of the Shizuku edition** — `app/` product code, its
> tests, release configuration, store compliance assets and distribution
> documentation. It is a Shizuku-only edition: the embedded-ADB privilege stack is deliberately
> **not** present here, which is why this edition declares no network permissions. Development
> happens in a separate, private tree, and each release is copied here from it; see
> [`CONTRIBUTING.md`](CONTRIBUTING.md) for how to contribute.

---

## Requirements

- **Environment**: Samsung DeX or compatible Android desktop mode
- **Privilege Backend**: [Shizuku](https://shizuku.rikka.app/) installed and running
- **Permissions**: **optional** — the `AccessibilityService` (`flagRequestFilterKeyEvents`) is enabled only if you want the global keyboard shortcuts. Tapping a zone needs no accessibility service.

---

## Build Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Build release APK / Android App Bundle (AAB) — REQUIRES the release signing key
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease

# Release-shaped artifact for inspection only, no key required.
# NOT distributable, and named so it cannot be confused with the real thing.
./gradlew :app:assembleUnsignedProof
```

> The release tasks **fail loudly** if the signing inputs are absent, incomplete,
> point at a missing file, or point at bytes that are not a keystore. They will
> not quietly emit an unsigned, release-shaped APK.

---

## Should you install this?

4Zones needs one thing most apps don't: Shizuku.

An accessibility service is the second thing you may have heard it needs. It is
optional, and only buys you the keyboard shortcuts — with it switched off, 4Zones
still tiles windows when you tap a zone. If you do enable it, it only watches for
Alt+Win+1–4. It cannot read screen content — the app doesn't request that
permission, and you can check that in the source. There is a concrete reason you
might leave it off: see [Banking and anti-fraud apps](#banking-and-anti-fraud-apps)
below.

Shizuku is the part worth thinking about. It's a separate app by a different
author holding shell-level access, and 4Zones borrows three narrow operations
from it. That power is real and it isn't ours — you're trusting its author as
well as us. Verify its download the way you verify ours.

Starting Shizuku without a computer means enabling wireless debugging, which
opens a service other devices on your network can reach. In May 2026 a flaw in
that service ([CVE-2026-0073](https://nvd.nist.gov/vuln/detail/CVE-2026-0073))
let an attacker on the same network get shell access with no prompt at all. It
is fixed — check Settings → About phone → Android security update shows
2026-05-01 or later — but turn wireless debugging off when you aren't using
it, and think twice on networks you don't control.

We cannot prove the published APK was built from the published source.
Development happens in a private tree and releases are copied here. The two
hashes prove the bytes came from our signing key; they don't prove the bytes
came from this code.

Don't install this if you don't want a shell-level helper on your phone, can't
verify the download, or aren't comfortable with the above.

### Banking and anti-fraud apps

Some banking apps check for apps installed outside the official app stores.
4Zones is one, and it can also run an accessibility service to receive the
`Alt` + `Win` + `1`–`4` shortcuts.

**Confirmed: HSBC UK Mobile Banking.** With 4Zones installed and its
accessibility service enabled, HSBC named 4Zones and suspended banking on that
device. This is HSBC's anti-fraud policy, not a 4Zones fault, and other banks run
similar checks.

**On the device we tested, both conditions had to be true.** Switching the 4Zones
accessibility service off removed 4Zones from HSBC's list within about two
minutes, with 4Zones still installed. Shizuku was never listed, because it runs no
accessibility service. Developer options and wireless debugging were switched on
throughout and were not named.

**To use both:** switch off **Settings → Accessibility → "4Zones keyboard
shortcuts"** before banking, and back on afterwards. The keyboard shortcuts stop
working while it is off — tapping a zone in the app does not, so 4Zones keeps
tiling windows either way. If you never enable the service, the question does not
arise.

This is one device and one bank. Banks change these checks without notice, so
treat it as what we saw, not as a guarantee.

---

## Installing the beta

This is a **pre-1.0 sideload beta**
([`0.9.1-beta`](https://github.com/mr-biz-apps/4zones/releases/tag/v0.9.1-beta)).
It is not on Google Play
yet. Everything below is written to be followed with nothing but this page and
the APK file.

### Before you install: verify the file

Do this first, every time, before you install anything. Two numbers are
published alongside each release: the **APK SHA-256** and the **signer
certificate SHA-256**. Both are on the release page you downloaded the APK from.

1. **Check the file is the file we published.** On the machine you downloaded to:

   ```bash
   sha256sum 4zones-0.9.1-beta.apk        # Linux
   shasum -a 256 4zones-0.9.1-beta.apk    # macOS
   certutil -hashfile 4zones-0.9.1-beta.apk SHA256   # Windows
   ```

   Compare the result to the published **APK SHA-256**. If it differs by even one
   character, stop — do not install it.

2. **Check it was signed by us.** The file hash tells you the bytes are intact;
   it does not tell you who made them. If you have Android's `apksigner`:

   ```bash
   apksigner verify --verbose --print-certs 4zones-0.9.1-beta.apk
   ```

   Compare the printed certificate SHA-256 to the published **signer certificate
   SHA-256**. This is the number that matters most: every genuine future update
   will carry the same one, and a build signed by anyone else will not.

> Both values are listed under "Artifact identity" on the release page.
> If the release page does not show both, treat that as a reason not to install.

### Prerequisites

| # | Requirement | Notes |
|---|---|---|
| 1 | Samsung DeX, or another Android desktop mode | Window tiling only makes sense in a desktop environment. |
| 2 | A physical keyboard — **only if you want the shortcuts** | Tapping a zone in the app is the whole interface. `Alt` + `Win` + `1`–`4` is an optional accelerator, and that is what needs a keyboard. |
| 3 | Android 12 (API 31) or newer | The app will not install below this. |
| 4 | [Shizuku](https://shizuku.rikka.app/) installed **and running** | 4Zones performs its privileged window operations through Shizuku. Without it, the app installs and opens but cannot move windows. |

**Optional, and not a prerequisite: the "4Zones keyboard shortcuts" accessibility
service.** Android delivers global keyboard shortcuts only to an accessibility
service, so enable it if you want `Alt` + `Win` + `1`–`4`; 4Zones explains this
and asks first. Everything the app does is available by tapping a zone without it,
and [Banking and anti-fraud apps](#banking-and-anti-fraud-apps) is one reason you
might choose to leave it off.

### Install

1. Verify the file (above).
2. Allow installing from your browser or file manager: **Settings → Apps →
   [the app you download with] → Install unknown apps → Allow**.
3. Open the APK and confirm the install.

### Set up Shizuku (prerequisite 4)

Shizuku is a separate app by a different author. 4Zones does not bundle it and
does not install it for you.

1. Install Shizuku from <https://shizuku.rikka.app/> or its GitHub releases at
   <https://github.com/RikkaApps/Shizuku/releases>. The Play Store listing may
   not install on recent Android versions, so the direct download is the
   reliable route.
2. Start the Shizuku service using **whichever method Shizuku offers on your
   device** — its own app walks you through wireless debugging (no PC needed on
   Android 11+) or an ADB command from a computer. Follow Shizuku's instructions,
   not ours; they are the authority on their own app.
3. **Shizuku stops when the device reboots.** After every reboot you must start
   it again before 4Zones can move windows. This is Shizuku's design, not a
   4Zones bug.
4. Open 4Zones and grant it Shizuku permission when Shizuku asks. If you miss
   the prompt, it can be granted from Shizuku's own app.

### Optional: keyboard shortcuts

Skip this if you are happy tapping a zone — nothing below is required for 4Zones
to tile windows. Do it if you want `Alt` + `Win` + `1`–`4` to work from any app,
without opening 4Zones first.

1. Open 4Zones.
2. It explains what the accessibility service is used for and asks you to turn it
   on. Accept, and Android's Accessibility settings will open.
3. Turn on **"4Zones keyboard shortcuts"**.
4. If that settings screen fails to open, 4Zones tells you so — open
   **Settings → Accessibility** yourself and enable it there.

You can turn it off again at any time in **Settings → Accessibility**.

### Check it works

Open 4Zones in DeX and tap **Zone 1**. Bring the window you want to move to the
front if it isn't already — a few seconds later it should move into the top-left
zone. Try zones 2, 3 and 4 for the others.

If you enabled the keyboard shortcuts, `Alt` + `Win` + `1` does the same thing
immediately, from any app.

### Which version am I running?

**Settings → Apps → 4Zones → App info** shows the version name, e.g.
`0.9.1-beta`. From a computer with ADB:

```bash
adb shell dumpsys package uk.mr_biz.fourzones | grep versionName
```

*(4Zones does not currently display its own version inside the app. That is a
known gap, not a defect in your install.)*

### Updating to a later beta

1. Verify the new APK's two hashes, exactly as for a first install.
2. Install it **over** the existing app — do not uninstall first. Android
   replaces the app in place and **your settings are preserved**.
3. Re-check prerequisite 4: Shizuku needs to be running. If you use the keyboard
   shortcuts, check those too — on some devices Android turns an accessibility
   service off across an update.

Android will only replace the app in place if the new APK is signed by the same
key and has a **higher** version number than the one installed. That is exactly
why the certificate check above matters: an APK signed by a different key cannot
update your install, and Android will refuse it.

### Going back to an earlier beta

**Android does not allow this in place.** It refuses to install an APK whose
version number is lower than the installed one, and it refuses any APK signed by
a different key. There is no "downgrade" button and no flag that changes this.

The only route back is to **uninstall 4Zones and install the older APK fresh** —
which **erases your 4Zones settings**, because uninstalling removes the app's
data. Nothing is recoverable afterwards: this app's data is deliberately
excluded from Android backup and from device-to-device transfer, so no backup of
it exists to restore.

### Uninstall

**Settings → Apps → 4Zones → Uninstall**, or long-press the icon and choose
Uninstall. This removes the app and all of its data.

Shizuku and its permissions are separate. If you no longer want Shizuku, uninstall
it separately.

---

## Known issues

The device-validation gate for this release ran on 2026-08-23 on a Samsung
Galaxy S25 and **passed**. With the accessibility service switched **off**, all
four zones snapped the intended window on real hardware. With Shizuku's
permission revoked the zones were disabled and said why; granting it re-enabled
them without restarting the app. The artefact validated is the release APK,
version `0.9.1-beta` (versionCode 2), APK SHA-256
`23fe91aac33102330972ad012f631bee2d0481de69c6463a4b01383de5540111`, signer
certificate SHA-256
`98d58b42c8f6a02c26eb34e6c42981cc2b92ea67d4680b26e884593ab471f19d`,
23,047,826 bytes — the digests the release page must publish. The signing
certificate is unchanged from `0.9.0-beta1`, so this installs as an in-place
upgrade.

**The release is published:** download it from
[v0.9.1-beta](https://github.com/mr-biz-apps/4zones/releases/tag/v0.9.1-beta).

The rows below are the known issues that stood at that gate; "Affects"
describes behaviour expected by design, not a per-row device reproduction.

| # | Symptom | Consequence | Workaround | Affects |
|---|---|---|---|---|
| 1 | After a reboot, shortcuts do nothing and 4Zones reports the privileged backend as unavailable. | No window tiling until fixed. | Start the Shizuku service again. | All versions; every device — this is how Shizuku works. |
| 2 | The install is around 23 MB, large for an app this simple. | Cosmetic: download and storage size only. | None. Code shrinking (R8) is deliberately switched off for this beta because it has never been proven safe against this app's Shizuku and binder code paths; enabling it unproven risks failures that only appear on your device. | `0.9.1-beta`; all devices. |
| 3 | 4Zones does not show its own version number anywhere in the app. | You must use Android Settings to tell builds apart. | Settings → Apps → 4Zones → App info. | `0.9.1-beta`; all devices. |
| 4 | After an update, the "4Zones keyboard shortcuts" accessibility service is off. | The keyboard shortcuts stop working until it is re-enabled. Tapping a zone in the app is unaffected. | Settings → Accessibility → turn it back on. | **UNVERIFIED** — reported behaviour of Android accessibility services across updates on some builds. We have not reproduced it on a 4Zones update. |
| 5 | Samsung DeX can present several desktops/workspaces on one display; a window may snap on a workspace other than the one you are looking at. | The window moves, but not where you expected. | Bring the intended window into focus on the workspace you are using before tapping a zone or pressing the shortcut. | All versions; multi-desktop DeX only. |
| 6 | 4Zones never tells you an update exists. | You have to check the release page yourself. | Watch the release page. This is deliberate — the app requests **no network permission at all**, so it cannot check for updates, and cannot send anything anywhere. | All versions; all devices. |

If you hit something not listed here, it is genuinely unknown to us — please
report it.

---

## Contributing

**Issues are open, read, and triaged** — they are the best way to contribute
here, and a precise bug report is genuinely valuable.

**Pull requests cannot be merged into this repository.** That is a property of
the mirror, not a judgement about your change: this repository receives each
release from a separate development tree, so a merge here would not reach the
app and anything under `app/` would be overwritten by the next release. If you
send one anyway, it is read and replied to, ported by hand with credit to you if
it is accepted, and then closed with an explanation either way — your work is
neither discarded nor taken without credit.

Please open an issue before writing code. [`CONTRIBUTING.md`](CONTRIBUTING.md)
has the details, including what makes a bug report actionable and how to report
a security or privacy problem privately.

## Privacy posture, in one paragraph

4Zones requests **no network permission of any kind**. It cannot reach the
internet, so nothing it observes can leave your device. Check that against the
file you downloaded rather than taking our word for it: `aapt2 dump permissions`
on the signed release APK lists exactly three permissions —
`android.permission.FOREGROUND_SERVICE`,
`uk.mr_biz.fourzones.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (contributed by
AndroidX and scoped to this app alone) and
`moe.shizuku.manager.permission.API_V23` — and no `android.permission.INTERNET`. Its app data is excluded
from Android cloud backup and from device-to-device transfer. The accessibility
service is used only to detect its own `Alt` + `Win` + `1`–`4` shortcuts; it
does not read screen content. The diagnostic logging *calls* that could name
your windows, apps or input devices are compiled out of released builds: the
release APK emits none of them. We verify this at the dex level against a debug
build as a positive control. Note the narrower true claim — the call sites are
gone, but because release optimisation is deliberately disabled for this beta,
some unused formatter text still sits in the APK as dead code that nothing can
reach. The single authoritative privacy policy text lives in this repository at
[`play/site/index.html`](play/site/index.html); that file is what is served as
the hosted policy page.

---

## Credits

4Zones depends on [Shizuku](https://shizuku.rikka.app/) by [RikkaW](https://github.com/RikkaW) ([Shizuku-API](https://github.com/RikkaApps/Shizuku-API), MIT). Shizuku provides the elevated, ADB-backed API that lets 4Zones move windows without root — the tiling work here would not be possible without it, and the project is worth having in its own right.

Licence terms are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md); this section is thanks, not a licence condition.

---

## Not affiliated

4Zones is an independent project. It is not affiliated with, endorsed by, or sponsored by Samsung Electronics or by the Shizuku project. "Samsung" and "DeX" are trademarks of Samsung Electronics Co., Ltd. Shizuku is a separate application by RikkaW. Both are named here only to describe what 4Zones is compatible with and what it depends on.

---

## License

Copyright 2026 mr-biz apps

Licensed under the [Apache License 2.0](LICENSE).

Third-party components packaged in the released app, and their notices, are
listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
