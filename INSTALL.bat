@echo off
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" (
  echo APK nahi mili. Pehle build karo.
  pause
  exit /b 1
)
echo Phone USB + File Transfer + USB debugging ON karein...
adb install -r "%APK%"
echo.
echo Install ho gaya to phone pe PhoneClean kholo.
echo Settings se All files access Allow karo, phir Scan.
pause
