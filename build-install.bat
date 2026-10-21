@echo off
setlocal
cd /d "%~dp0"
echo === Build ===
if exist gradlew.bat (call gradlew.bat assembleDebug) else (call gradle assembleDebug)
if errorlevel 1 (echo Build failed & exit /b 1)
echo === Install ===
adb install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 (echo Install failed & exit /b 1)
echo === Set as default home ===
adb shell cmd package set-home-activity com.local.glasshome/.MainActivity
adb shell input keyevent KEYCODE_HOME
echo Done. Stock launcher is NOT disabled; run restore-launcher.bat to go back.
