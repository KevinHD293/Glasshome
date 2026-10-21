@echo off
rem Put the stock Google TV launcher back as default home.
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
adb shell input keyevent KEYCODE_HOME
echo Stock launcher restored. To remove Glass Home completely: adb uninstall com.local.glasshome
