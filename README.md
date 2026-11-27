# VRTune: per-game optimizer for Meta Quest 3

Pick a game from your library, choose render resolution, refresh rate, foveation, CPU/GPU level and recording quality, then **Apply & Launch**. VRTune saves a profile per game and applies it every time you start the game through VRTune.

Runs entirely in the headset. No root needed.

## What it can change

| Setting | Property | Values |
|---|---|---|
| Refresh rate | `debug.oculus.refreshRate` | 72 / 80 / 90 / 120 Hz |
| Render resolution (supersampling) | `debug.oculus.textureWidth` / `textureHeight` | 1216×1272 (0.72x) up to 3072×3216 (1.83x). Quest 3 default is 1680×1760 |
| CPU level | `debug.oculus.cpuLevel` | 0–5 |
| GPU level | `debug.oculus.gpuLevel` | 0–5 |
| Foveation level | `debug.oculus.foveation.level` | Off, Low, Medium, High, High top |
| Dynamic foveation | `debug.oculus.foveation.dynamic` | On / Off |
| Full-rate recording | `debug.oculus.fullRateCapture` | On / Off |
| Recording resolution / bitrate | `debug.oculus.capture.width/height/bitrate` | up to 1920×1080, 40 Mbps |
| Anything else | Custom `key=value` lines | e.g. properties you find in Meta's docs |

These are Meta's documented system properties
([Meta docs](https://developers.meta.com/horizon/documentation/native/android/ts-systemproperties/)).

**Presets:** Battery saver · Balanced · Smooth 120 · Sharp · Ultra sharp · Game default.

**About sharpening and anti-aliasing:** Quest has no documented system-wide sharpen or MSAA switch. The effective way to get a sharper, less jagged image is supersampling: raise *Render resolution* to 1.2x–1.5x and lower foveation. The app warns you when a combination is likely too heavy for the GPU.

## How it works

Android only lets the `shell` user change `debug.oculus.*` properties. VRTune has a small built-in ADB client (`AdbClient.java`) that connects to the headset's **own** ADB service on `127.0.0.1:5555`. It authenticates with its own RSA key and runs `setprop`. When you tap **Apply & Launch** it:

1. force-stops the game (games read these values at start-up),
2. clears all VRTune-managed properties,
3. sets the ones in the game's profile,
4. launches the game.

The properties are global and reset when the headset reboots. **Reset all** clears them at any time.

## Install on the Quest 3

1. **Developer Mode:** in the Meta Horizon phone app, go to *Devices → your headset → Headset settings → Developer mode → On*. Reboot the headset.
2. **Install the APK:** use [SideQuest](https://sidequestvr.com/setup-howto) (drag `VRTune.apk` onto it), or with platform-tools:
   ```
   adb install -r VRTune.apk
   ```
3. **Wake up wireless ADB (after every reboot):** plug the Quest into a PC and run `enable-wireless-adb.bat` (from the release), or:
   ```
   adb tcpip 5555
   ```
   Then unplug.
4. In the headset, open **Library → Unknown Sources → VRTune** and tap **Connect**.
5. The first time, the headset asks *"Allow USB debugging?"* for VRTune. Tick **Always allow** and press **Allow**.

Step 3 is needed after each reboot because the headset turns its ADB network port off on restart. Android doesn't let an app turn it back on by itself.

## Build it yourself

Needs JDK 17 and the Android SDK (API 36).

```
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/VRTune.apk`. Without a signing key it is signed with the debug key.

## Publishing on GitHub

The workflow in `.github/workflows/build.yml` builds the APK on every push. When you push a tag like `v1.0.0`, it creates a GitHub Release with `VRTune.apk` and the helper scripts attached.

To sign releases with your own key, add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | your `.jks` file encoded as base64 |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

If you don't add them, CI still builds, but each build is signed with a throwaway debug key. Then a new version can't install over an old one until you uninstall the old one.

## Disclaimer

Not affiliated with Meta. These are developer settings; aggressive CPU/GPU levels raise heat and battery drain. Everything resets on reboot. Use at your own risk.

License: MIT
