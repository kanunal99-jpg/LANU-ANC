#!/usr/bin/env bash
set -euo pipefail
mkdir -p diagnostics
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 120 adb shell am instrument -w -r com.lanu.anc.test/com.lanu.anc.SmokeInstrumentation | tee diagnostics/android-smoke.txt
python3 - <<'PY'
from pathlib import Path
text = Path('diagnostics/android-smoke.txt').read_text()
assert 'OK (1)' in text and 'FAILURES!!!' not in text, text
PY
adb shell am start -W -n com.lanu.anc/.MainActivity
adb exec-out screencap -p > diagnostics/single-ear-ui.png
adb logcat -d -t 500 > diagnostics/logcat.txt
