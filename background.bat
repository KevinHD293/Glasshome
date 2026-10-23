@echo off
rem Usage: background.bat on | off     (animated gradient background; "off" = plain black, lighter on the TV)
if /i "%~1"=="off" (adb shell am start -n com.local.glasshome/.MainActivity --ez bganim false & exit /b)
if /i "%~1"=="on" (adb shell am start -n com.local.glasshome/.MainActivity --ez bganim true & exit /b)
echo Usage: background.bat on ^| off
