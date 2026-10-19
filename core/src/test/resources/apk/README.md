# APK test fixtures

`fixture.apk` is an unsigned APK built on the box with `aapt2` (Android build-tools 35.0.0)
from `fixture-source-AndroidManifest.xml` plus a tiny `res/` and one class compiled to
`classes.dex`. It is used by the AXML/ARSC/APK-mod round-trip tests.

It contains:
- a binary `AndroidManifest.xml` (package `com.example.fixture`, versionCode 7,
  versionName 1.2.3, minSdk 24, targetSdk 34, INTERNET + CAMERA + a custom permission,
  an exported activity, a non-exported activity/service/provider, no `debuggable` flag),
- `resources.arsc` with string resources in `default` and `es`, incl. two equal strings and
  a styled string,
- `res/layout/main.xml`,
- `classes.dex`.

Regenerate with the commands in `docs/APK_DEEP_EDIT.md`. The APK is deliberately tiny and
carries no third-party code.
