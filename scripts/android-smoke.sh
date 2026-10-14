#!/usr/bin/env bash
set -euo pipefail
mkdir -p diagnostics
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm revoke com.lanu.anc android.permission.RECORD_AUDIO || true
timeout 120 adb shell am instrument -w -r com.lanu.anc.test/com.lanu.anc.SmokeInstrumentation | tee diagnostics/permission-denial.txt
python3 - <<'PY'
from pathlib import Path
text = Path('diagnostics/permission-denial.txt').read_text()
assert 'real microphone permission denial' in text and 'FAILURES!!!' not in text, text
PY
adb shell pm grant com.lanu.anc android.permission.RECORD_AUDIO
adb shell pm grant com.lanu.anc android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.lanu.anc android.permission.POST_NOTIFICATIONS
timeout 120 adb shell am instrument -w -r com.lanu.anc.test/com.lanu.anc.SmokeInstrumentation | tee diagnostics/android-smoke.txt
python3 - <<'PY'
from pathlib import Path
text = Path('diagnostics/android-smoke.txt').read_text()
assert 'OK (1)' in text and 'FAILURES!!!' not in text, text
PY
adb shell am start -W -n com.lanu.anc/.MainActivity
adb exec-out screencap -p > diagnostics/single-ear-ui.png
adb logcat -d -t 500 > diagnostics/logcat.txt
