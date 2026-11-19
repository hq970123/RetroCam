#!/bin/bash
# UI tour on emulator (pixel_6, 1080x2400). Taps by coordinates measured from screenshots.
OUT=shots; mkdir -p $OUT
PKG=com.hq.retrocam
n=0
center(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1
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
anr(){ for k in 1 2 3; do c=$(center 'text="Wait"'); [ -n "$c" ] && { adb shell input tap $c; sleep 2; } || break; done; }
fg(){ adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG" || { echo "app not in front, relaunching"; adb shell am start -n $PKG/.MainActivity; sleep 5; }; }
shot(){ n=$((n+1)); sleep ${2:-2}; anr; f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > $OUT/$f.png; }
t(){ anr; adb shell input tap $1 $2; }
tap_text(){ anr; c=$(center "text=\"$1\""); [ -n "$c" ] && adb shell input tap $c || echo "MISSING text $1"; }
back(){ anr; adb shell input keyevent 4; sleep 1; fg; }
Y_TOP=211; Y_PRO=1949; Y_BOT=2176
adb install -r -g RetroCam.apk
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 20; anr; shot main 4
tap_text "1.0x"; sleep 1
c=$(center "resource-id=\"$PKG:id/btnQuick\""); [ -n "$c" ] && adb shell input tap $c; shot quick_sheet; back
t 321 $Y_BOT; shot mode_sheet; back
t 788 $Y_BOT; shot filter_sheet
tap_text "黑白"; shot filter_bw
tap_text "LUT"; shot filter_lut; back
t 119 $Y_PRO; shot pro_shutter
t 292 $Y_PRO; shot pro_iso
t 467 $Y_PRO; shot pro_ev
t 640 $Y_PRO; shot pro_focus
t 815 $Y_PRO; shot pro_wb; t 815 $Y_PRO; sleep 1
t 991 $Y_PRO; shot pro_lut; back
t 424 $Y_TOP; shot ratio_tap
t 165 $Y_TOP; shot format_tap
t 683 $Y_TOP; shot flash_tap
t 942 $Y_TOP; shot settings_general
tap_text "视频"; shot settings_video
tap_text "辅助"; shot settings_assist
tap_text "关于"; shot settings_about; back
shot after_settings_back
adb shell input swipe 900 1000 150 1000 250; shot swipe_left 3
t 554 $Y_BOT; shot video_recording 4
t 554 $Y_BOT; sleep 2
adb shell input swipe 150 1000 900 1000 250; shot swipe_right 3
t 554 $Y_BOT; shot after_shutter 4
t 127 $Y_BOT; shot gallery_or_editor 3; back
t 980 $Y_BOT; shot front_cam 4
adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt
true
