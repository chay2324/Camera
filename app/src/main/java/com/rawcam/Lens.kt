package com.rawcam

import android.content.Context
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
        return out
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
