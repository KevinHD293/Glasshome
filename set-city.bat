@echo off
rem Usage: set-city.bat "Da Nang"      (type the city name without accents if accents come out garbled)
if "%~1"=="" (echo Usage: set-city.bat "Da Nang" & exit /b 1)
adb shell am start -n com.local.glasshome/.MainActivity --es city "'%~1'"
