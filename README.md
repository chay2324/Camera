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
- **Natural look** (default): sharpening, denoise-boost, extra contrast curve, effects and scene
  modes are turned off, so JPEG/video come out close to the plain sensor image. Switch to "Std" for
  the phone's normal processing.

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
