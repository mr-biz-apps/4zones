# Contributing to 4Zones

Thank you for wanting to help. There is one structural thing to know about this
repository before you spend any time, because it changes how a contribution
actually reaches the app.

## This repository is a release mirror

`mr-biz-apps/4zones` is the **public release repository** for 4Zones. It holds
the complete source of the published app, so you can read it, build it, and
check for yourself what it does and does not do.

It is not, however, where the app is developed. Each release is **copied here**
from the development tree. Changes flow *into* this repository, not out of it,
which has one practical consequence for pull requests — see below.

## Issues are the best way to contribute

**Issues are open, read, and triaged.** They are the primary way to contribute
here, and they are genuinely useful: a precise bug report is often worth more
than a patch, because the fix has to be made in the development tree anyway.

Bug reports, reproducible misbehaviour, device-compatibility reports, and
feature requests are all welcome.

### What makes a bug report actionable

- Your device model, Android version, and One UI (or vendor UI) version
- Whether you were in Samsung DeX or another Android desktop mode
- Whether Shizuku was installed and running, and which version
- The version of 4Zones you are running
- Exactly what you did — including which shortcut you pressed — what you
  expected, and what happened instead
- Whether it reproduces, and if so how reliably

Please strip anything personal out of logs and screenshots before attaching
them. Window titles, notification text, and app names can say more about you
than you intend.

### Security and privacy reports

If you think you have found a security or privacy problem, please **do not open
a public issue**. Use GitHub's private vulnerability reporting on this
repository if it is available to you, so the problem can be fixed before it is
described in public.

## Pull requests cannot be merged here

This is a property of the mirror, not a judgement about your change. A merge
into this repository would not reach the app, and anything under `app/` would be
overwritten the next time a release is copied across — so the merge would
quietly disappear. Pull requests are therefore disabled where the host allows
it; if you do not see a pull request tab, that is why.

**If pull requests are open and you send one, here is exactly what happens to
your work**, so that nothing about it is ambiguous:

1. It is read and replied to, the same as an issue.
2. If the change is accepted, a maintainer **ports it by hand** into the
   development tree, **crediting you by name and linking your pull request** in
   the commit message.
3. The pull request is then **closed with a comment** saying where the change
   landed and which release it will appear in.
4. If the change is not accepted, it is closed with the reason.

Your work is neither discarded nor taken without credit. It simply cannot travel
through a merge button on this repository.

Because of that, **please open an issue before writing code**. It costs you
nothing, and it is the fastest way to find out whether a change will be taken.

## Building and running

Build instructions, requirements, and the shortcut reference are in
[`README.md`](README.md). The unsigned, release-shaped build used for inspection
needs no signing key:

```bash
./gradlew :app:assembleUnsignedProof
```

## Licence

4Zones is licensed under the Apache License 2.0 — see [`LICENSE`](LICENSE). By
opening an issue or a pull request, you agree that any code you contribute may
be released under that licence.

Third-party components packaged in the released app are listed in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
