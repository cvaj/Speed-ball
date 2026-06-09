# How To Run Speed-ball (Start To Finish)

A practical, end-to-end guide for building, installing, and operating the app on a
Samsung Galaxy S10+ (or similar Camera2 high-speed device).

> Status note: this guide describes the current reviewed **Setup Mode / Run
> Mode** build. Speed **accuracy** is not yet validated against a known
> reference (a named open gate) — treat reported numbers as unproven.

---

## 0. Prerequisites (one time)

- A debug-capable build machine with the Android SDK at
  `ANDROID_HOME=/home/vangmountain/Android/Sdk` (adb at
  `$ANDROID_HOME/platform-tools/adb`).
- An Android phone with **Developer options + USB debugging** (and **Wireless
  debugging** if connecting over Wi-Fi).
- A visible moving ball and a stable/tripod camera. The recorded-HFR detector is
  fixed-camera median-background motion first; selected ball color is optional
  evidence after motion is found.

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
`Distance calibration required`, `Level required`).

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
4. **Plane depth correction (optional)** — leave **`Cal plane ft`** and
   **`Ball plane ft`** blank when the A/B calibration span and ball travel are
   on the same plane. If they are not, enter camera-to-calibration-plane feet
   and camera-to-ball-travel-plane feet. Both fields must be valid if either is
   filled; the app will not silently apply same-plane scale to partial depth
   setup. Valid depth correction is still estimate-only and caps otherwise
   strong track confidence at medium.
5. **Shape gate** — **`Shape ratio`** is the maximum long-side/short-side ratio
   for one moving ball candidate. Default `2.00` allows normal 120 fps
   motion-blurred ball streaks while leaving path, velocity, color, and
   consistency gates to reject non-ball motion.
6. **Launch height** — **`Launch ft`** is the ball height above ground used only
   for the reported carry/distance projection. Default is `4.0`; set it higher
   for throw tests, such as `5.5`. This does not change blob detection or
   measured speed.
7. **Ball color and size lock (optional discriminator)** — place the ball at
   the expected impact/travel distance, tap **`Ball Box`**, then tap the ball in
   the preview. The app samples HSV color and draws a magenta four-point
   ball-size polygon. Drag any corner independently until it bounds the visible
   ball at that distance; the points are not locked to a rectangle. This gives
   recorded-HFR a detector-scale expected ball size so tiny net/grass fragments
   and oversized moving junk are rejected before candidate caps. Recorded-HFR
   Run Mode can arm without this, but outdoor green/yellow scenes should use it.
8. **Impact zone (recommended outdoors)** — tap **`Impact`** and drag the four
   orange corners around the part of the camera image where the batted ball is
   allowed to travel. Each corner moves independently, so the shape can be any
   quadrilateral you need inside the camera image. Recorded-HFR ignores motion
   outside this polygon before connected-component/candidate counting, which
   keeps nets, trees, hands, and grass motion from exhausting the blob budget.
   After selecting **`Impact`** or **`Ball Box`**, you may press **`Hide`**; the
   drawer disappears but the handles remain on the camera image and stay
   draggable. Corner drags clamp to the image edge, so pulling outward to the
   border should not go dead.
9. **Level** — captured automatically once permission/mode are ready; tap
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
is simply waiting for one of the start words: `shoot`, `record`, `cheese`, or
`smile`. Those idle events should restart the listener while leaving the Run
status green, and should **not** turn into a yellow retry or fatal voice error.
If you see repeated `VOICE_RECORD_LISTEN_IDLE` logs, the app is still cycling
the listener and waiting for the command.

---

## 6. Fire a shot

Two ways (both run the same recorded-HFR estimate path):

- **Voice** — say **"shoot"**, **"record"**, **"cheese"**, or **"smile"**
  while the strip shows `READY - LISTENING`.
  This is the intended hands-free operating path after setup.
- **Manual** — tap **`Shoot`** (enabled whenever setup is valid).
  Use this only as a fallback/proof path if the recognizer is genuinely
  unavailable or in repeated real-error state.

After the command, wait for the armed **Ready/beep** cue. The app arms the
impact mic first, starts HFR recording after the mic is actually listening,
waits for the first valid video timestamp, then emits the Ready cue. Make the
mouth pop / shouted pop / impact sound after that cue finishes while the neon
ball crosses the ROI. The trigger is any loud ambient-relative spike; it does
not classify sound type. The app ignores its own Ready cue, keeps about 1 second
after the accepted marker plus pre-impact context, and decodes only the bounded
high-speed window around the marker. The current decode request starts 1 second
before the accepted sound marker and ends 1 second after it, so a late impact pop
can still include ball frames that crossed just before the sound.

Current provisional impact-audio gates are field-tuned from S10+ proof:
`minimumPeakDelta=100`, `thresholdMultiplier=2.5`, and
`minimumBaselineRms=25.0`. These are intentionally treated as provisional. A
post-Ready pop around delta `107` / ratio `3.0` should trigger, while
near-silent breath/rustle and moderate sub-pop ambient should not. The audio
detector is streaming/chunked, so no-impact should return promptly after the
5-second actionable window instead of lagging far behind the recording.

On S10+ the shot path records a bounded high-speed app-owned clip from the
selected mode, normally `1280x720 @ 120`, with an offscreen companion preview
surface. The visible preview is for setup only. While capturing, the preview may
briefly go **black** because the recorder owns the camera; this is expected and
the preview returns afterward.

---

## 7. Read the result

- **Success** → a full-screen black result overlay with **VELOCITY / ANGLE /
  DISTANCE** and a `CLEAR` button. The baseline distance is drag-only carry;
  the backspin distance is a model-based level-swing undercut/Magnus estimate.
  While this overlay is up, say **"clear"** or tap `CLEAR` to dismiss it and
  immediately return to Run listening. If neither happens, the app clears the
  report automatically after 10 seconds and restarts Run listening.
- **No read / failure** → a non-destructive report (over the still-visible
  preview) showing **REASON / ACTION / MESSAGE**, capture-proof thumbnails, and
  detector counts with **no speed value** (e.g. `INSUFFICIENT_DETECTIONS` —
  "At least four usable detections are required"). This is the app correctly
  refusing to show a wrong number.
- Proof thumbnails are the processed recorded-HFR frames the detector saw,
  downscaled for display. The app first runs a low-resolution luma scout over
  the bounded impact window, selects the high-travel dense motion interval, then
  reopens the decoder and converts only that interval to detector ARGB frames.
  If the scout is unsure, it falls back to the full bounded window. The detector
  builds a luma median background, differences each processed frame against
  that background, masks the user-drawn Impact zone when present, applies the
  locked Ball Box size gate when present, and emits isolated motion-ball
  candidates. Color/ROI may help diagnostics but do not gate Run Mode
  readiness. The app keeps only
  bounded thumbnail proof plus compact blob/index/timestamp records after
  detection. The
  recorded-HFR window decoder uses MediaCodec byte-buffer output, not the
  crashed `ImageReader`/plane route; S10+ proof measured NV12/format 21 with
  `stride=1280` and `sliceHeight=720`. The
  report shows recorded source size, detector working size, decoded metadata
  sample count, scanned decoded-frame count, candidate frame/blob counts,
  selected samples, unique `SENSOR_TIMESTAMP` count, requested fps, and
  drop/cadence gate verdicts.
- `candidateFrames=0` means the median-background motion detector found no
  isolated moving ball candidate in the processed impact-window frames.
  `BALL_NOT_ISOLATED` means foreground moved but was too merged/ambiguous to
  treat as the ball after Impact-zone masking, optional Ball Box size, optional
  color, near-circular shape ratio, centroid velocity, and smooth-path
  discriminators. `selectedSamples<4` means motion candidates
  existed but not enough usable track samples were selected.

After the result, Run Mode returns to ready/listening. **Repeat a start command
as many times as you like — no re-setup needed.** Clearing a report does not clear
setup and a cleared report does not reappear. Voice **"clear"** is equivalent to
tapping `CLEAR` and returning to Run. Entering **Setup** also drops any old
report so returning to **Run** starts clean.

---

## 8. Watch the logs (optional, recommended)

In a second terminal:
```bash
adb logcat -c                                   # clear first
# fire a shot, then:
adb logcat | grep -E "RECORDED_HFR|RECORDED_ESTIMATE|VISUAL_ESTIMATE|VOICE_|DECODE_"
```
- Expected for a `shoot`/`Shoot`: `RECORDED_HFR_AUDIO_ARMED`, then
  `RECORDED_HFR_START`, `RECORDED_HFR_FIRST_FRAME_ANCHOR`,
  `RECORDED_HFR_READY_CUE_EMITTED`,
  `RECORDED_HFR_MARKER_ACCEPTANCE_ENABLED`,
  `RECORDED_HFR_IMPACT_DETECTED`, `RECORDED_ESTIMATE_WINDOW ...`,
  `RECORDED_HFR_MOTION_SCOUT ...`,
  `RECORDED_HFR_CAPTURE_SUCCESS`, followed by
  `RECORDED_HFR_ESTIMATE_COMPLETE` or `RECORDED_HFR_ESTIMATE_NO_READ`.
- `RECORDED_HFR_MOTION_SCOUT selected=true dense=A-B run=C-D` means the
  low-resolution scout narrowed the heavy motion detector to a padded frame
  interval. `selected=false` means it fell back to the full decoded window.
- If no marker is accepted, `RECORDED_HFR_IMPACT_NO_READ` should arrive promptly
  after the 5-second actionable window and include strongest delta/ratio plus
  the configured gates. A 40-second delay indicates a regression.
- A blocked shot logs `VOICE_SHOOT_NOT_READY reason=<specific>`.
- **Must NOT appear** for the `shoot` path: `VOICE_RECORD_SUCCESS`. That marker
  belongs to the old queued voice-record path and indicates the wrong route.

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Run` button unavailable | Setup incomplete — the status names the missing gate; finish it. |
| Always "No read" | No ball cleanly crossing the decoded impact window, no post-Ready loud delta was accepted, the phone/background moved enough to trip the global-motion/lighting guard, foreground motion was not isolated as a ball, or the path/speed gates rejected it. Check the no-impact log fields first; if the sound marker was accepted, read the detector reason and proof thumbnails before changing setup. |
| `Recorded-HFR window decode exceeded the wall-clock budget` | The sound marker was accepted and a video window was selected, but post-recording decode did not finish in time. The decode budget is 5 minutes; if this still appears, inspect `RECORDED_ESTIMATE_WINDOW decoded=... syncPrefix=... decodeMs=... timedOut=true`. |
| `Recorded-HFR streaming decode exceeded the scanned-frame limit` | The decoder tried to scan beyond its reviewed frame budget. The recorded-window path treats exactly reaching its bounded window frame count as normal completion, so this should only appear for an unbounded/invalid stream or a regression. |
| False trigger before your pop | The provisional gate is too permissive for the ambient setting. Field proof must include realistic outdoor/noisy ambient with no user pop; if ambient transients meet the gate, revisit the gate model instead of only changing constants. |
| `Recorded window source frames were black or invalid` | The bounded impact window decoded as black/near-black or otherwise unusable; this is source proof, not a ball-tracking failure. Reconnect/test camera recording and check lighting/aim before changing detector settings. |
| `motion-blur-risk` or exposure above `12000000ns` | The app starts with AE and leaves normal 120 fps exposure around 8.33 ms alone. It only applies a manual cap if Camera2 reports exposure slower than 12 ms before Ready. If this still appears, the capture was outside the reviewed blur-risk budget, so the app must not show mph from that evidence. |
| You need proof imagery after no-read | In debug builds the app retains up to three app-private failed `speed_ball_...mp4` files, capped at 25 MB total. The UI proof panel should show bounded thumbnails whenever any in-window frame decoded; logs show only sanitized filename and bytes. |
| Repeated `VOICE_RECORD_LISTEN_IDLE code=7` or timeout logs | Normal idle listening cycle; the recognizer did not hear `shoot`, `record`, `cheese`, or `smile` yet and should keep restarting. Speak clearly near the phone after Run Mode is green. |
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
- **Recorded-HFR uses AE first.** Actual exposure min/median/max appears in
  logs/report when Camera2 reports it. Normal 120 fps AE around 8.33 ms is left
  alone; only exposure slower than 12 ms gets manually capped before Ready.
  Blurred ball streaks are expected at 120 fps; median exposure above 12 ms
  still no-reads as motion-blur risk.
- **IMU level-sign** and the **human live-voice start-command** path are still open
  physical validation gates.
- Voice recognition reliability varies by device/firmware, but Run Mode is
  designed to keep cycling through idle no-match/timeouts until it hears
  `shoot`, `record`, `cheese`, or `smile`.
- 240 fps capture has device constraints; this flow uses the supported
  recorded-HFR 120 fps path on S10+ unless another mode is explicitly selected
  and proven.

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
$ADB logcat -c && $ADB logcat | grep -E "RECORDED_HFR|RECORDED_ESTIMATE|VISUAL_ESTIMATE|VOICE_|DECODE_"
```
Then on the phone: **Setup** (permission -> distance A/B + feet -> Ball Box/color
-> Impact zone -> level) -> **Run** -> **Shoot** (or say "shoot", "record",
"cheese", or "smile") with the neon
ball crossing the camera frame. Recorded-HFR can process without ROI; the
Impact zone and Ball Box are optional outdoor noise-reduction controls, not
per-shot blockers.
