@echo off
rem Wallpaper album control (the TV must be connected with adb).
rem   wallpaper-album.bat time 10           change the picture every 10 minutes (0 = never change)
rem   wallpaper-album.bat next              show the next picture now
rem   wallpaper-album.bat source all        built-in pictures + your photos (default)
rem   wallpaper-album.bat source builtin    only the built-in pictures
rem   wallpaper-album.bat source mine       only your own photos
rem   wallpaper-album.bat add "C:\Photos"   copy a folder of photos (jpg, png, webp) to the TV
set A=com.local.glasshome/.MainActivity
if /i "%~1"=="time" (adb shell am start -n %A% --ei wpinterval %~2 & exit /b)
if /i "%~1"=="next" (adb shell am start -n %A% --ez wpnext true & exit /b)
if /i "%~1"=="source" (
  if /i "%~2"=="all" (adb shell am start -n %A% --ei wpsource 0 & exit /b)
  if /i "%~2"=="builtin" (adb shell am start -n %A% --ei wpsource 1 & exit /b)
  if /i "%~2"=="mine" (adb shell am start -n %A% --ei wpsource 2 & exit /b)
)
if /i "%~1"=="add" (
  if "%~2"=="" (echo Usage: wallpaper-album.bat add "C:\Photos" & exit /b 1)
  adb shell mkdir -p /sdcard/Android/data/com.local.glasshome/files/wallpapers
  adb push "%~2\." /sdcard/Android/data/com.local.glasshome/files/wallpapers/
  adb shell am start -n %A% --ei wpsource 0
  exit /b
)
echo Usage: wallpaper-album.bat time ^<minutes^> ^| next ^| source all^|builtin^|mine ^| add "C:\Photos"
