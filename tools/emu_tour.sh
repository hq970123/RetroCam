#!/bin/bash
# UI tour on emulator: install, tap through screens, screenshot each.
set -x
OUT=shots; mkdir -p $OUT
PKG=com.hq.retrocam
n=0
shot(){ n=$((n+1)); sleep ${2:-2}; anr; f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > $OUT/$f.png; adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml $OUT/$f.xml >/dev/null 2>&1; }
center(){ # $1 = attr regex
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1
  python3 - "$1" <<'PY'
import sys,re
x=open('/tmp/u.xml',encoding='utf-8').read()
for node in re.findall(r'<node [^>]*>',x):
    if re.search(sys.argv[1],node):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node)
        if b:
            a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
}
anr(){ for k in 1 2 3; do c=$(center "text=\"Wait\""); [ -n "$c" ] && { adb shell input tap $c; sleep 2; } || break; done; }
tap_id(){ anr;  c=$(center "resource-id=\"$PKG:id/$1\""); [ -n "$c" ] && adb shell input tap $c || echo "MISSING id $1"; }
tap_text(){ anr;  c=$(center "text=\"$1\""); [ -n "$c" ] && adb shell input tap $c || echo "MISSING text $1"; }
back(){ anr; adb shell input keyevent 4; sleep 1; }

adb install -r -g RetroCam.apk
adb shell pm grant $PKG android.permission.CAMERA; adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 20; anr; shot main 4
tap_id btnQuick; shot quick_sheet; back
tap_id btnMode; shot mode_sheet; back
tap_id btnFilter; shot filter_sheet
tap_text "复古"; shot filter_retro
tap_text "黑白"; shot filter_bw
tap_text "LUT"; shot filter_lut; back
tap_id proShutter; shot pro_shutter
tap_id proIso; shot pro_iso
tap_id proEv; shot pro_ev
tap_id proFocus; shot pro_focus
tap_id proWb; shot pro_wb
tap_id proLut; shot pro_lut; back
tap_id btnRatio; shot ratio_tap
tap_id btnFormat; shot format_tap
tap_id btnFlash; shot flash_tap
tap_id btnSettings; shot settings_general
tap_text "视频"; shot settings_video
tap_text "辅助"; shot settings_assist
tap_text "关于"; shot settings_about; back
shot after_settings_back
adb shell input swipe 1000 1300 200 1300 300; shot swipe_left 3
adb shell input swipe 200 1300 1000 1300 300; shot swipe_right 3
tap_id shutter; shot after_shutter 4
tap_id btnFlip; shot front_cam 4
adb shell input keyevent 4; adb shell input keyevent 4
adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt
adb shell dumpsys window | grep -i -E "mCurrentFocus|InsetsSource" > $OUT/window.txt
true
