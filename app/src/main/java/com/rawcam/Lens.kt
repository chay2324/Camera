package com.rawcam

import android.content.Context
import android.graphics.ImageFormat
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One selectable lens.
 * physicalId == null  -> the logical camera itself (the system blends lenses as you zoom).
 * physicalId != null  -> that one physical lens, pinned (true optical 0.5x / 2x / 5x ...).
 */
data class Lens(
    val logicalId: String,
    val physicalId: String?,
    val label: String,
    val facingBack: Boolean,
    val equivFocalMm: Float,
    val hasRaw: Boolean,
    /** Characteristics of the sensor actually used (physical if pinned, else logical). */
    val chars: CameraCharacteristics,
    /** Characteristics of the logical camera (zoom range, etc.). */
    val logicalChars: CameraCharacteristics,
)

object Lenses {

    fun enumerate(ctx: Context): List<Lens> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = ArrayList<Lens>()
        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            if (!back && !front) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

            if (logical && back) {
                val phys = c.physicalCameraIds.toList()
                out.add(Lens(id, null, "Auto", true, equiv(c), hasRaw(c), c, c))
                val items = phys.mapNotNull { pid ->
                    try { pid to mgr.getCameraCharacteristics(pid) } catch (e: Exception) { null }
                }
                if (items.size > 1) {
                    // Reference "1x" = the lens whose 35mm-equivalent is closest to 26 mm.
                    val ref = items.minByOrNull { abs(equiv(it.second) - 26f) }!!
                    val refMm = equiv(ref.second)
                    for ((pid, pc) in items.sortedBy { equiv(it.second) }) {
                        val ratio = equiv(pc) / refMm
                        out.add(Lens(id, pid, fmt(ratio), true, equiv(pc), hasRaw(pc), pc, c))
                    }
                }
            } else {
                out.add(Lens(id, null, if (back) "1x" else "Front", back, equiv(c), hasRaw(c), c, c))
            }
        }
        return relabelStandalone(out)
    }

    /** Phones that list each lens as its own camera id: give them real 0.5x / 1x / 2x labels. */
    private fun relabelStandalone(list: List<Lens>): List<Lens> {
        val solo = list.filter { it.facingBack && it.physicalId == null && it.label == "1x" }
        if (solo.size < 2) return list
        val ref = solo.minByOrNull { abs(it.equivFocalMm - 26f) }!!.equivFocalMm
        return list.map { if (it in solo) it.copy(label = fmt(it.equivFocalMm / ref)) else it }
    }

    /** Plain-text report of what this phone lets apps use. Shown by the Info button. */
    fun report(ctx: Context): String {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        for (id in mgr.cameraIdList) {
            val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            sb.append("\n").append(describe("Camera $id", c))
            for (pid in c.physicalCameraIds) {
                val pc = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) { null }
                if (pc != null) sb.append(describe("   lens $pid (inside $id)", pc))
            }
        }
        return sb.toString()
    }

    private fun describe(title: String, c: CameraCharacteristics): String {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level3"
            else -> "?"
        }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun maxSize(fmt: Int) = map?.getOutputSizes(fmt)?.maxByOrNull { it.width * it.height }?.let { "${it.width}x${it.height}" } ?: "none"
        return "$title: $facing, ${"%.0f".format(equiv(c))}mm eq, $level\n" +
            "   RAW: ${if (hasRaw(c)) "YES (${maxSize(ImageFormat.RAW_SENSOR)})" else "no"}, " +
            "manual: ${if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) "yes" else "no"}, " +
            "JPEG max ${maxSize(ImageFormat.JPEG)}\n"
    }

    private fun hasRaw(c: CameraCharacteristics): Boolean =
        (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
            .contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)

    /** 35mm-equivalent focal length. */
    private fun equiv(c: CameraCharacteristics): Float {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 26f
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 26f
        val diag = hypot(s.width, s.height)
        return if (diag > 0f) f * 43.27f / diag else 26f
    }

    private fun fmt(r: Float): String {
        val rounded = Math.round(r * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}x" else "${rounded}x"
    }
}
