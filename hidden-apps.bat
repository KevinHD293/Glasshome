@echo off
rem Hide or show apps on the Glass Home screen (the apps stay installed and enabled).
rem   hidden-apps.bat hide com.android.tv     hide Live TV from the home screen
rem   hidden-apps.bat show com.android.tv     show it again
rem   hidden-apps.bat show all                show every hidden app again
set A=com.local.glasshome/.MainActivity
if /i "%~1"=="hide" (adb shell am start -n %A% --es hide %~2 & exit /b)
if /i "%~1"=="show" (adb shell am start -n %A% --es unhide %~2 & exit /b)
echo Usage: hidden-apps.bat hide ^<package^> ^| show ^<package^> ^| show all
