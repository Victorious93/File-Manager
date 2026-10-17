#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner once the emulator has booted.
# 1) instrumented Compose smoke tests, 2) monkey with a fixed seed, 3) logcat scan for
# crashes / ANRs / StrictMode. Fails on test failures, crashes or ANRs; StrictMode is reported.
set -uo pipefail
PKG=ai.victorious.filemanager.debug
OUT=build/emulator-smoke
mkdir -p "$OUT"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
fail=0

adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

echo "::group::connectedDebugAndroidTest"
if ./gradlew --no-daemon :app:connectedDebugAndroidTest; then
  echo "instrumented smoke tests: PASS"
else
  echo "instrumented smoke tests: FAIL"; fail=1
fi
echo "::endgroup::"

echo "::group::monkey"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p /sdcard/FMMonkey && adb shell 'for i in 1 2 3; do echo monkey$i > /sdcard/FMMonkey/m$i.txt; done'
adb shell am start -W -n "$PKG/ai.victorious.filemanager.MainActivity"
# Fixed seed so a failure is reproducible: adb shell monkey -p $PKG -s 4242 ...
adb shell monkey -p "$PKG" -s 4242 --throttle 75 --pct-syskeys 0 --pct-appswitch 5 \
  --ignore-security-exceptions -v 3000 > "$OUT/monkey.txt" 2>&1
MONKEY_RC=$?
tail -n 5 "$OUT/monkey.txt"
echo "::endgroup::"

sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true
adb logcat -d -b crash > "$OUT/crash-buffer.txt" 2>&1 || true

CRASHES=$(grep -c "Process: $PKG" "$OUT/crash-buffer.txt" || true)
ANRS=$(grep -c "ANR in $PKG" "$OUT/logcat.txt" || true)
MONKEY_CRASH=$(grep -c "// CRASH" "$OUT/monkey.txt" || true)
MONKEY_DONE=$(grep -c "Monkey finished" "$OUT/monkey.txt" || true)
STRICT=$(grep -c "StrictMode" "$OUT/logcat.txt" || true)

{
  echo "## Emulator smoke (API ${API_LEVEL:-?})"
  echo ""
  echo "| check | result |"
  echo "|---|---|"
  echo "| instrumented tests | $([ $fail = 0 ] && echo pass || echo FAIL) |"
  echo "| monkey 3000 events seed 4242 | rc=$MONKEY_RC, finished=$MONKEY_DONE, crashes=$MONKEY_CRASH |"
  echo "| app crashes (crash buffer) | $CRASHES |"
  echo "| ANRs | $ANRS |"
  echo "| StrictMode log lines (debug policy, penaltyLog) | $STRICT |"
  echo ""
  echo "### StrictMode violation types"
  echo '```'
  grep -A1 "StrictMode policy violation" "$OUT/logcat.txt" | grep -oE "android\.os\.strictmode\.[A-Za-z]+|policy=[0-9]+ violation=[0-9]+" | sort | uniq -c | sort -rn | head -20 || true
  echo '```'
  echo "### Top app frames in StrictMode stacks"
  echo '```'
  grep -A30 "StrictMode policy violation" "$OUT/logcat.txt" | grep -oE "at ai\.victorious\.filemanager\.[A-Za-z0-9_.$]+" | sort | uniq -c | sort -rn | head -25 || true
  echo '```'
} >> "$SUMMARY"

if [ "$CRASHES" != "0" ] || [ "$MONKEY_CRASH" != "0" ]; then
  echo "::error::App crashed during smoke/monkey run"; grep -A40 "Process: $PKG" "$OUT/crash-buffer.txt" | head -120; fail=1
fi
if [ "$ANRS" != "0" ]; then
  echo "::error::ANR detected"; grep -A20 "ANR in $PKG" "$OUT/logcat.txt" | head -60; fail=1
fi
if [ "$MONKEY_DONE" = "0" ]; then
  echo "::error::monkey did not finish"; tail -n 40 "$OUT/monkey.txt"; fail=1
fi
exit $fail
