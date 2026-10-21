@echo off
rem Usage: set-dock.bat pkg1,pkg2,pkg3,pkg4   (max 4, in the order you want, no spaces)
rem Example: set-dock.bat com.google.android.youtube.tv,com.netflix.ninja,com.android.vending,com.android.tv.settings
if "%~1"=="" (echo Usage: set-dock.bat pkg1,pkg2,pkg3,pkg4 & exit /b 1)
adb shell am start -n com.local.glasshome/.MainActivity --es dock "%~1"
