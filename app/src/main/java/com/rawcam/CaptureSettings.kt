package com.rawcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Range
import kotlin.math.max
import kotlin.math.min

enum class Mode { PHOTO, VIDEO, RAW_VIDEO }
enum class Format(val label: String) { JPEG("JPG"), RAW("RAW"), RAW_JPEG("RAW+JPG") }
enum class FlashMode(val label: String) { OFF("Flash off"), AUTO("Flash auto"), ON("Flash on"), TORCH("Torch") }
enum class WbPreset(val label: String, val awb: Int) {
    AUTO("AWB", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Day", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloud", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TUNGSTEN("Tung", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluo", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
}

/** Everything the user can change. Read from the camera thread, written from the UI thread. */
class CaptureSettings {
    @Volatile var format = Format.RAW_JPEG
    /** true = plain sRGB tone curve, no effects or scene modes (no stylised colour). */
    @Volatile var natural = true
    @Volatile var manualExposure = false
    @Volatile var iso = 100
    @Volatile var shutterNs = 8_333_333L            // 1/120 s
    @Volatile var manualFocus = false
    @Volatile var focusDiopter = 0f
    @Volatile var evIndex = 0
    @Volatile var flash = FlashMode.OFF
    @Volatile var wb = WbPreset.AUTO
    @Volatile var zoom = 1f
    @Volatile var stabilize = true                  // electronic video stabilization (slight crop)
    @Volatile var rawVideoFps = 10
}

private fun IntArray?.has(v: Int) = this != null && this.contains(v)

object Ranges {
    fun iso(c: CameraCharacteristics): Range<Int> =
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600)
    fun shutter(c: CameraCharacteristics): Range<Long> =
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
    fun ev(c: CameraCharacteristics): Range<Int> =
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    fun evStep(c: CameraCharacteristics): Float =
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    fun minFocusDiopter(c: CameraCharacteristics): Float =
        c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    fun hasFlash(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    fun zoom(c: CameraCharacteristics): Range<Float>? =
        if (android.os.Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
}

/**
 * Applies the user's settings to a request. [still] = a final capture (not the live preview).
 * [video] = a video/RAW-video repeating request.
 */
fun CaptureRequest.Builder.applyUserSettings(s: CaptureSettings, lens: Lens, still: Boolean, video: Boolean) {
    val c = lens.logicalChars
    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
    set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
    set(CaptureRequest.CONTROL_AWB_MODE, s.wb.awb)

    // ---- exposure ----
    val flashOk = Ranges.hasFlash(c)
    if (s.manualExposure) {
        val iso = s.iso.coerceIn(Ranges.iso(c).lower, Ranges.iso(c).upper)
        var t = s.shutterNs.coerceIn(Ranges.shutter(c).lower, Ranges.shutter(c).upper)
        if (!still) t = min(t, 100_000_000L)       // keep the live view fluid
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
        set(CaptureRequest.SENSOR_FRAME_DURATION, max(t, 33_333_333L))
        set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
    } else {
        var ae = CameraMetadata.CONTROL_AE_MODE_ON
        var fm = CameraMetadata.FLASH_MODE_OFF
        if (flashOk) {
            if (s.flash == FlashMode.TORCH) fm = CameraMetadata.FLASH_MODE_TORCH
            else if (still && !video) {
                if (s.flash == FlashMode.ON) ae = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                if (s.flash == FlashMode.AUTO) ae = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            }
        }
        set(CaptureRequest.CONTROL_AE_MODE, ae)
        set(CaptureRequest.FLASH_MODE, fm)
        val ev = Ranges.ev(c)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex.coerceIn(ev.lower, ev.upper))
    }

    // ---- focus ----
    val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    val minFocus = Ranges.minFocusDiopter(c)
    if (s.manualFocus && minFocus > 0f) {
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopter.coerceIn(0f, minFocus))
    } else {
        val want = if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                   else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        when {
            afModes.has(want) -> set(CaptureRequest.CONTROL_AF_MODE, want)
            afModes.has(CameraMetadata.CONTROL_AF_MODE_AUTO) -> set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        }
    }

    // ---- stabilization: optical always (when the lens has it), electronic for video ----
    val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
    if (ois.has(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON))
        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
    val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
        if (s.stabilize && video && eis.has(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON))
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

    // ---- sharpness / noise: always the phone's own quality processing (this is what keeps shots clear) ----
    if (still && !video) {
        val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (nr.has(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY))
            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        val edge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        if (edge.has(CameraMetadata.EDGE_MODE_HIGH_QUALITY))
            set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
    }

    // ---- "Natural": plain sRGB tone curve, no effects/scene modes. Color is not stylised. ----
    if (s.natural) {
        val tm = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        if (tm.has(CameraMetadata.TONEMAP_MODE_PRESET_CURVE)) {
            set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
            set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
        }
    }
}
