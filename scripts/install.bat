@echo off
REM Put app-debug.apk next to this file (or edit the path), then run it.
adb connect 192.168.1.39:5555
adb install -r app-debug.apk
adb shell cmd package set-home-activity com.example.swifthome/.MainActivity
echo If the TV still shows the old home, press Home and choose SwiftHome - Always.
