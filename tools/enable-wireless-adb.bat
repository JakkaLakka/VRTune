@echo off
REM VRTune helper: wakes up the Quest's ADB service on port 5555.
REM Needed once after every headset reboot. Requires Android platform-tools (adb) on PATH
REM or in this folder: https://developer.android.com/tools/releases/platform-tools
setlocal
where adb >nul 2>nul
if %errorlevel% neq 0 (
  if exist "%~dp0adb.exe" ( set "ADB=%~dp0adb.exe" ) else (
    echo adb was not found. Download Android platform-tools, unzip, and put this file next to adb.exe.
    pause
    exit /b 1
  )
) else ( set "ADB=adb" )

echo Plug the Quest into this PC with a USB-C cable and put the headset on.
echo If asked "Allow USB debugging?" in the headset, tick Always allow and press Allow.
"%ADB%" wait-for-usb-device
"%ADB%" -d tcpip 5555
if %errorlevel% neq 0 (
  echo Failed. Is Developer Mode enabled and USB debugging allowed?
  pause
  exit /b 1
)
echo.
echo Done! Unplug the cable, open VRTune in the headset and tap Connect.
pause
