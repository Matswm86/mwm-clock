#!/usr/bin/env bash
# Opens the app on the CI emulator and drives all three tabs: saves an alarm
# and checks AlarmManager holds it, runs a 5-second timer until the ring
# screen comes up and stops it, and laps a stopwatch. The alarm-tab screenshot
# it takes is the one the README shows.
#
# A file rather than inline workflow script: the emulator action feeds inline
# scripts to `sh -c` one line at a time, which breaks every multi-line `if`.
set -x

PKG=no.mwmai.clock.debug
ACTIVITY=no.mwmai.clock.MainActivity
OUT=smoke
mkdir -p "$OUT"

chmod +x ./gradlew
./gradlew installDebug --no-daemon || { echo "::error::install failed"; exit 1; }

adb logcat -c || true
# No -W: it waits for a launch to settle, which never happens if the app dies.
adb shell am start -n "$PKG/$ACTIVITY" || echo "am start returned $?"
sleep 12

dump() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb pull /sdcard/ui.xml "$OUT/$1" >/dev/null 2>&1 || true
}
crashed() {
    adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
    if grep -qE "FATAL EXCEPTION|AndroidRuntime: .*(Exception|Error)" "$OUT/logcat.txt"; then
        echo "::error::App crashed"
        grep -B 2 -A 45 -m 1 -E "FATAL EXCEPTION|AndroidRuntime: .*(Exception|Error)" "$OUT/logcat.txt"
        return 0
    fi
    return 1
}
expect() {
    # expect <dump> <grep -E pattern> <message>
    if ! grep -qE "$2" "$OUT/$1"; then
        echo "::error::$3"
        head -c 6000 "$OUT/$1"
        exit 1
    fi
}
tap() {
    python3 tools/ui_center.py "$OUT/$1" "$2" > "$OUT/tap.txt" || { echo "::error::nothing to tap for: $2"; exit 1; }
    # shellcheck disable=SC2046
    adb shell input tap $(cat "$OUT/tap.txt")
}

# Launch: the alarm tab, with the tab bar.
dump ui_launch.xml
crashed && exit 1
for TEXT in "MWM CLOCK" "Alarm tab" "Timer tab" "Stopwatch tab" "NO ALARM SET"; do
    expect ui_launch.xml "$TEXT" "The launch screen is missing: $TEXT"
done

# Alarm: open the editor, save the default 07:00, and check it is armed.
tap ui_launch.xml "desc=Add alarm"
sleep 3
dump ui_editor.xml
crashed && exit 1
expect ui_editor.xml "NEW ALARM" "The alarm editor did not open"
tap ui_editor.xml "desc=Save alarm"
sleep 4
dump ui_alarm.xml
crashed && exit 1
# The emulator may use a 12-hour clock, which shows 7:00 rather than 07:00.
expect ui_alarm.xml 'text="0?7:00"' "The saved alarm is not in the list"
expect ui_alarm.xml 'NEXT [A-Z]+ 0?7:00' "The display does not show the next alarm"
adb shell dumpsys alarm > "$OUT/dumpsys_alarm.txt" 2>&1 || true
if ! grep -q "$PKG" "$OUT/dumpsys_alarm.txt"; then
    echo "::error::AlarmManager holds no alarm for $PKG"
    exit 1
fi
adb exec-out screencap -p > "$OUT/clock.png" 2>/dev/null || true

# Timer: 5 seconds, then the ring screen must come up on its own.
tap ui_alarm.xml "desc=Timer tab"
sleep 2
dump ui_timers.xml
tap ui_timers.xml "desc=Add timer"
sleep 2
dump ui_keypad.xml
crashed && exit 1
tap ui_keypad.xml "desc=5"
sleep 1
dump ui_keypad5.xml
expect ui_keypad5.xml "00h 00m 05s" "The keypad did not take the digit"
tap ui_keypad5.xml "desc=Start timer"
sleep 2
dump ui_timer_running.xml
crashed && exit 1
expect ui_timer_running.xml "1 RUNNING" "The timer is not running"
sleep 10
dump ui_ring.xml
adb exec-out screencap -p > "$OUT/ring.png" 2>/dev/null || true
crashed && exit 1
# The apostrophe may come out of the dump as an XML entity.
expect ui_ring.xml "TIME.{1,6}S UP" "The timer finished but no ring screen came up"
tap ui_ring.xml "desc=Stop"
sleep 3
dump ui_after_ring.xml
crashed && exit 1
expect ui_after_ring.xml "NO TIMER RUNNING" "Stopping the ring did not return to the timer tab"

# Stopwatch: start, two laps, stop.
tap ui_after_ring.xml "desc=Stopwatch tab"
sleep 2
dump ui_sw.xml
tap ui_sw.xml "desc=Start Stopwatch 1"
sleep 2
dump ui_sw_running.xml
tap ui_sw_running.xml "desc=Lap Stopwatch 1"
sleep 1
tap ui_sw_running.xml "desc=Lap Stopwatch 1"
sleep 1
tap ui_sw_running.xml "desc=Stop Stopwatch 1"
sleep 2
dump ui_sw_laps.xml
adb exec-out screencap -p > "$OUT/stopwatch.png" 2>/dev/null || true
crashed && exit 1
expect ui_sw_laps.xml "LAP TIME" "No lap table after two laps"
expect ui_sw_laps.xml 'text="02"' "The second lap is missing"
expect ui_sw_laps.xml 'text="0[0-9]:[0-9]{2}\.[0-9]{2}"' "No stopwatch time on screen"
echo "Alarm armed, timer rang and stopped, stopwatch lapped."
