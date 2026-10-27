@echo off
rem Usage: install-glasshome.bat [path\to\app-debug.apk]
rem Installs (or updates) Glass Home and makes it the default home screen. The TV must be connected with adb.
set APK=%~1
if "%APK%"=="" set APK=app-debug.apk
if exist "%APK%" (
  adb install -r "%APK%"
) else (
  echo APK not found: %APK% - skipping the install and only setting the default home
)
adb shell cmd package set-home-activity com.local.glasshome/.MainActivity
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.local.glasshome
adb shell cmd role get-role-holders --user 0 android.app.role.HOME
adb shell input keyevent KEYCODE_HOME
echo.
echo Done. If the remote's Home button still opens the stock launcher, run:
echo   adb shell pm disable-user --user 0 com.google.android.tvlauncher
