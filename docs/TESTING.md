# Testing

## Checks on every PR

| Check | What it runs |
|---|---|
| **Unit tests + debug APK** (required) | `:core:test :app:testDebugUnitTest`, `assembleDebug`, signing fingerprint check |
| **Emulator smoke tests (API 34)** | Builds debug + androidTest APKs, boots an API 34 `google_apis` x86_64 emulator (KVM on `ubuntu-latest`) via `reactivecircus/android-emulator-runner`, runs `.github/scripts/emulator-smoke.sh` |
| **Android lint** | `:app:lintDebug`. Fails on lint errors |

## Emulator smoke job

`.github/scripts/emulator-smoke.sh` runs:

1. `connectedDebugAndroidTest`: the Compose UI smoke tests in `app/src/androidTest/`.
   - `SmokeTest`: app launch (checks `MainActivity` is a `FragmentActivity`), browsing folders,
     dual pane + tabs, create (file/folder) / rename / copy / move / delete with undo, text editor
     edit + save, hex view, zip list + extract, a corrupt-zip regression, APK info on a real APK,
     settings, terminal (`echo hi; echo sum=$((40+2))`, waiting for `sum=42`), app manager list,
     Wi-Fi transfer start → HTTP GET with PIN → 401 without PIN → stop → connection refused,
     secure folder screen (locked; biometric is not driven), plugins screen + sample install,
     scripts screen.
   - `ViewModelThreadingTest`: wraps `FileIo` and fails if any listing/stat call runs on the
     main thread (regression for remote paths throwing `NetworkOnMainThreadException`).
   - Tests use real storage under `/sdcard/FMSmoke/` and grant all-files access with
     `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`.
2. `monkey` with a fixed seed: `adb shell monkey -p ai.victorious.filemanager.debug -s 4242 --throttle 75 -v 3000`.
3. A logcat scan. The job fails on any app crash (crash buffer / `// CRASH`), any ANR, or a
   monkey run that didn't finish. StrictMode lines are counted and summarised in the job
   summary (debug builds install a log-only StrictMode policy in `FileManagerApplication`).

Logs (`logcat.txt`, `monkey.txt`, test reports) upload as the `emulator-smoke-logs`
artifact on a best-effort basis, since the account's artifact quota can block uploads.

## Running locally

To run on any device or emulator with adb:

```bash
./gradlew :app:connectedDebugAndroidTest
bash .github/scripts/emulator-smoke.sh   # tests + monkey + logcat scan
```

The Grok build box cannot run the emulator. `/dev/kvm` exists, but creating a vCPU triggers
a host kernel bug (`kernel BUG at arch/x86/kvm/x86.c:702`, in `vmx_vcpu_create`), so qemu
hangs before the guest kernel starts. x86_64 images can't fall back to software emulation, so
the emulator only runs in GitHub Actions.
