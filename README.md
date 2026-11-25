# RawCam

A minimal, dependency-free Android camera app built on Camera2.

## Build the APK with GitHub Actions
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab, pick **Build APK**, and run it (it also runs on every push).
3. When it finishes, download the **RawCam-apk** artifact, unzip it and install the `.apk` on your phone.
   (Allow "install unknown apps" for your browser/files app.)

If the build fails, open the failed step in Actions and send me the error text; I'll fix it.

## What it does
- **Photo**: JPG, RAW (DNG) or RAW+JPG. RAW is written straight from the sensor data.
- **Video**: 4K (or 1080p) HEVC/H.264 with 256 kbps audio, no stabilization crop unless enabled.
- **RAW video**: records a DNG sequence (one DNG per frame) into its own folder. Frame rate is
  selectable (5/10/15/24); frames the phone can't write fast enough are dropped and reported.
- **Lenses**: "Auto" (the phone blends lenses as you pinch-zoom) plus each physical lens pinned
  (0.5x, 1x, 2x, 5x, ...) when your phone exposes them to apps.
- **Controls**: tap to focus, pinch zoom, flash/torch, timer, grid, front/back, and a Pro panel
  with manual ISO, shutter, focus, exposure compensation and white-balance presets.
- **Natural look** (default): plain sRGB tone curve, no effects or scene modes, so colour isn't
  stylised. Sharpening and noise reduction stay at the phone's own quality setting so shots stay
  clear. Switch to "Std" for the phone's normal tone curve. Optical stabilization is on whenever the
  lens has it, and video uses electronic stabilization (slight crop).
- **Info button**: shows which cameras/lenses on your phone give RAW to apps. Send me that text if
  RAW doesn't work.

## Voice assistant (offline, push-to-talk)
Tap **Talk**, then speak. Understood by simple built-in rules; nothing is sent anywhere.
- Shoot: "take a photo", "start recording", "stop", "photo mode", "video mode", "raw video"
- Lens/zoom: "zoom in", "zoom to 3", "ultra wide", "telephoto", "front camera"
- Settings: "flash on/off/auto", "torch on", "RAW", "JPEG", "raw plus jpeg", "natural look",
  "timer 10", "grid on", "ISO 400", "shutter 1/250", "exposure plus one", "auto exposure",
  "manual focus", "focus infinity", "daylight", "tungsten"
- Ask: "how's the light?", "is it in focus?", "what ISO?", "which lens?", "what are the settings?"
- "help" lists ideas; "mute" turns spoken replies off (also the Replies button).
- Volume buttons work as the shutter.

Limits: it needs the phone's **offline** speech recognizer (Android 12+ with an offline language
pack installed). If it's missing, the app says so; it will never fall back to cloud recognition.
The mic is busy while recording video, so use the shutter button then. Spoken replies use the
phone's text-to-speech engine. The assistant can read exposure and focus, but it cannot see or
describe the picture; that would need a large on-device vision model, which isn't included.

## Privacy
- The manifest has **no INTERNET permission**, so the app cannot send anything anywhere.
- Backups are disabled. There are no third-party libraries.
- Files are saved only into the system gallery (`DCIM/RawCam`). The thumbnail button opens the
  last file with your gallery app.

## Honest limits
- Android doesn't let third-party apps use the phone maker's private image pipeline (their HDR,
  night mode, AI processing, some lens/zoom tricks). So in JPEG mode this app will not match
  those; in RAW, manual control and unprocessed output it can be better.
- Some phones hide certain lenses or RAW from third-party apps. Whatever your phone allows is what
  you'll see in the lens row.
- This code was written without being compiled or tested on a device. Expect to iterate once.
