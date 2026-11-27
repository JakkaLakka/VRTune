#!/bin/sh
# VRTune helper (macOS/Linux): wakes up the Quest's ADB service on port 5555. Run after each reboot.
set -e
command -v adb >/dev/null 2>&1 || { echo "Install Android platform-tools (adb) first."; exit 1; }
echo "Plug the Quest in via USB-C and allow USB debugging in the headset if asked..."
adb wait-for-usb-device
adb -d tcpip 5555
echo "Done! Unplug, open VRTune in the headset and tap Connect."
