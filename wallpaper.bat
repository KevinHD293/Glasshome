@echo off
rem Usage: wallpaper.bat off | on     (hide or show the wallpaper image; "off" = plain black background)
if /i "%~1"=="off" (adb shell am start -n com.local.glasshome/.MainActivity --ez wallpaper false & exit /b)
if /i "%~1"=="on" (adb shell am start -n com.local.glasshome/.MainActivity --ez wallpaper true & exit /b)
echo Usage: wallpaper.bat on ^| off
