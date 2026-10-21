@echo off
rem Usage: sound.bat off | on | 0.3     (a number 0.0 - 1.0 sets the volume)
if /i "%~1"=="off" (adb shell am start -n com.local.glasshome/.MainActivity --ez sound false & exit /b)
if /i "%~1"=="on" (adb shell am start -n com.local.glasshome/.MainActivity --ez sound true & exit /b)
if "%~1"=="" (echo Usage: sound.bat off ^| on ^| 0.3 & exit /b 1)
adb shell am start -n com.local.glasshome/.MainActivity --ez sound true --ef sfxvol %~1
