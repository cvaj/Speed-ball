# How To Run Speed-ball (Start To Finish)

A practical, end-to-end guide for building, installing, and operating the app on a
Samsung Galaxy S10+ (or similar Camera2 high-speed device).

> Status note: this guide describes the current in-progress **Setup Mode / Run
> Mode** build. That two-mode UI is implemented in the working tree but is still
> pending implementation review and is not committed, so exact labels may shift
> slightly. Speed **accuracy** is not yet validated against a known reference
> (a named open gate) — treat reported numbers as unproven.

---

## 0. Prerequisites (one time)

- A debug-capable build machine with the Android SDK at
  `ANDROID_HOME=/home/vangmountain/Android/Sdk` (adb at
  `$ANDROID_HOME/platform-tools/adb`).
- An Android phone with **Developer options + USB debugging** (and **Wireless
  debugging** if connecting over Wi-Fi).
- A **neon / high-visibility ball** and a contrasting, well-lit background. The
  detector is neon-color centroid tracking — dull or low-contrast balls will not
  be detected.

---

## 1. Connect the device

### Option A — USB
Plug in, accept the "Allow USB debugging" prompt, then:
```bash
adb devices -l        # should list your device as "device"
```

### Option B — Wireless debugging
On the phone: **Settings → Developer options → Wireless debugging → Pair device
with pairing code**. It shows an IP:port and a 6-digit code. Then on the machine
(replace the placeholders with the values shown on YOUR phone — do not commit
them anywhere):
```bash
adb pair <PHONE_IP>:<PAIR_PORT>      # enter the 6-digit code when prompted
adb connect <PHONE_IP>:<CONNECT_PORT>
adb devices -l                        # confirm "device"
```
> Pairing codes, ports, and endpoints are sensitive and must never be written into
> tracked files, logs, or commits.

---

## 2. Build and install the app

```bash
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:installDebug
```
This builds the debug APK and installs `com.speedball.app`. (To build without
installing: `:app:assembleDebug`; the APK lands in
`app/build/outputs/apk/debug/app-debug.apk`.)

---

## 3. Launch

```bash
adb shell am start -n com.speedball.app/.MainActivity
```
The app runs **landscape**. On first launch with incomplete setup it opens in
**Setup Mode**.

---

## 4. Setup Mode — configure once

Setup Mode keeps the **live camera preview** visible so you can aim and calibrate.
Open the tools via the translucent handle if controls are hidden. Complete every
gate below; the status text always names the **specific** thing still missing
(e.g. `Permission required`, `Mode required`, `Live feed required`,
`Distance calibration required`, `Ball color required`,
`Set the ball-flight ROI before measuring.`, `Level required`).

1. **Permission** — tap **`Permission`** and grant **Camera** and **Microphone**.
2. **Camera mode** — tap **`Modes`** if needed so a fixed high-speed mode is
   selected.
3. **Distance calibration (scale)** — the app needs to know real-world scale:
   - Two vertical **A/B caliper lines** appear over the preview.
   - Select **`A`**, drag/tap it onto one end of a known real-world span in the
     scene; select **`B`**, place it on the other end.
   - Type the real distance between A and B (in feet) into the distance field.
   - Tap **`Apply Distance`**. Editing the number later overwrites the active
     distance while keeping your A/B lines; clearing or invalid text blocks
     readiness (it will not silently reuse the old value).
   - (Alternative: **`Ball`** diameter fallback if you can't set A/B distance.)
4. **Ball color** — tap **`Color`** to select the color target, then tap the
   neon ball in the preview (or **`Sample`**) to sample its HSV color.
5. **ROI (ball-flight region)** — tap **`ROI`**, then **tap on the preview** along
   the path the ball will travel to center the region box there; fine-tune with
   the **nudge** controls. (The box is a fixed-size rectangle you position; there
   is no drag-resize.)
6. **Level** — captured automatically once permission/mode are ready; tap
   **`Level`** to recapture while the phone is held still.

When all gates pass, the **`Run`** (green) button becomes available.

> `Recal` clears setup (A/B, distance, ball fallback, level) — use it only to
> start setup over.

---

## 5. Enter Run Mode

Tap **`Run`**. Run Mode does **no** setup — it just listens and fires. A status
strip shows the truthful command state, color-coded:

| Status strip | Meaning |
|---|---|
| `READY - LISTENING` (green) | Setup valid and the voice recognizer is actively listening. |
| `READY - MANUAL` | Setup valid; use the manual `Shoot` button. |
| `VOICE UNAVAILABLE - MANUAL READY` | Speech recognition not available; manual `Shoot` works. |
| `VOICE ERROR <code> - MANUAL READY` | Recognizer hit a real repeated failure; manual `Shoot` still works. |
| (Capturing / Reporting) | A shot is in progress / a result is on screen. |
| `SetupInvalid: <reason>` | Setup became invalid — go back to **`Setup`** and fix the named reason. |

The status is **honest**: it only shows green "listening" when the recognizer is
truly running. Voice is the primary operating path once setup is complete. The
manual button is a fallback/proof path, not the expected way to use the app from
the hitting position.

Android speech recognition often reports "no match" or "speech timeout" while it
is simply waiting for the word `shoot`. Those idle events should restart the
listener and should **not** turn into a fatal voice error. If you see repeated
`VOICE_RECORD_LISTEN_IDLE` logs, the app is still cycling the listener and
waiting for the command.

---

## 6. Fire a shot

Two ways (both run the same direct high-speed visual estimate):

- **Voice** — say **"shoot"** while the strip shows `READY - LISTENING`.
  This is the intended hands-free operating path after setup.
- **Manual** — tap **`Shoot`** (enabled whenever setup is valid).
  Use this only as a fallback/proof path if the recognizer is genuinely
  unavailable or in repeated real-error state.

Then **roll/throw the neon ball across the ROI** — the capture window is short
(about 0.8 s), so time the throw to the trigger.

While capturing, the preview may briefly go **black** (the capture path owns the
camera). This is expected; the preview returns afterward.

---

## 7. Read the result

- **Success** → a full-screen black result overlay with **VELOCITY / ANGLE /
  DISTANCE** and a `CLEAR` button. (While this overlay is up the field is covered,
  so listening pauses until you `CLEAR`.)
- **No read / failure** → a non-destructive report (over the still-visible
  preview) showing **REASON / ACTION / MESSAGE**, capture-proof thumbnails, and
  detector counts with **no speed value** (e.g. `INSUFFICIENT_DETECTIONS` —
  "At least four usable detections are required"). This is the app correctly
  refusing to show a wrong number.
- Proof thumbnails are the processed direct-readback frames the detector saw.
  On the S10+ direct route they are low-resolution `160x90` diagnostic frames,
  not full preview photos.
- `candidateFrames=0` means the selected color/ROI matched no ball-like blobs
  in the processed frames. `selectedSamples<4` means blobs existed but not
  enough usable track samples were selected.

After the result, Run Mode returns to ready/listening. **Repeat `shoot` as many
times as you like — no re-setup needed.** Clearing a report does not clear setup
and a cleared report does not reappear.

---

## 8. Watch the logs (optional, recommended)

In a second terminal:
```bash
adb logcat -c                                   # clear first
# fire a shot, then:
adb logcat | grep -E "VISUAL_ESTIMATE|VOICE_|RECORDED_ESTIMATE|DECODE_"
```
- Expected for a `shoot`/`Shoot`: `VISUAL_ESTIMATE_COMPLETE frames=… uniqueSensorTs=…
  candidateFrames=… candidateBlobs=… selectedSamples=…` then
  `VISUAL_ESTIMATE_RESULT …`.
- A blocked shot logs `VOICE_SHOOT_NOT_READY reason=<specific>`.
- **Must NOT appear** for the direct shoot path: `VOICE_RECORD_SUCCESS`,
  `RECORDED_ESTIMATE_STAGE`, `DECODE_` (those indicate the wrong
  record-then-decode route).

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Run` button unavailable | Setup incomplete — the status names the missing gate; finish it. |
| Always "No read" | No neon ball cleanly crossing the ROI in the ~0.8 s window; improve lighting/contrast, re-check Color + ROI + distance, re-time the throw. |
| Repeated `VOICE_RECORD_LISTEN_IDLE code=7` or timeout logs | Normal idle listening cycle; the recognizer did not hear `shoot` yet and should keep restarting. Speak clearly near the phone after Run Mode is green. |
| `VOICE ERROR <code> - MANUAL READY` | Repeated non-idle recognizer failure; leave/re-enter Run Mode or relaunch, and use manual **`Shoot`** only as a fallback. |
| Black screen persists after a shot | Should self-recover; if not, leave/re-enter Run Mode or relaunch. |
| Wrong-looking number | Accuracy is unproven (open gate) — do not trust the magnitude yet. |

---

## 10. Known limitations (current)

- **Speed accuracy is not validated** against a known reference
  (`S10_REAL_BALL_GROUND_TRUTH_ESTIMATE_VALIDATION_PENDING`). Treat readings as
  uncalibrated.
- **Capture-proof imagery is diagnostic, not accuracy proof.** It shows what
  the current attempt captured and what the detector selected, but the
  ground-truth speed validation gate remains open.
- **IMU level-sign** and the **human live-voice "shoot"** path are still open
  physical validation gates.
- Voice recognition reliability varies by device/firmware, but Run Mode is
  designed to keep cycling through idle no-match/timeouts until it hears
  `shoot`.
- 240 fps capture has device constraints; this flow uses the supported high-speed
  direct path.

---

## Quick reference (copy/paste)

```bash
ADB=/home/vangmountain/Android/Sdk/platform-tools/adb
# 1) connect (USB) and verify
$ADB devices -l
# 2) build + install
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:installDebug
# 3) launch
$ADB shell am start -n com.speedball.app/.MainActivity
# 4) (optional) watch logs while you shoot
$ADB logcat -c && $ADB logcat | grep -E "VISUAL_ESTIMATE|VOICE_|RECORDED_ESTIMATE|DECODE_"
```
Then on the phone: **Setup** (permission → distance A/B + feet → color → ROI →
level) → **Run** → **Shoot** (or say "shoot") with the neon ball crossing the ROI.
