package com.rawcam

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** Saves everything straight into the system gallery (MediaStore). Nothing else is touched. */
class MediaSaver(ctx: Context) {
    private val cr: ContentResolver = ctx.applicationContext.contentResolver
    @Volatile var lastError = ""

    private fun insertImage(name: String, mime: String, dir: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawCam$dir")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
    }

    private fun publish(uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        cr.update(uri, v, null, null)
    }

    private fun discard(uri: Uri) {
        try { cr.delete(uri, null, null) } catch (_: Exception) {}
    }

    fun saveJpeg(name: String, bytes: ByteArray): Uri? {
        val uri = insertImage("$name.jpg", "image/jpeg", "") ?: return null
        return try {
            cr.openOutputStream(uri)?.use { it.write(bytes) }
            publish(uri); uri
        } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; discard(uri); null }
    }

    /** Writes a DNG. [subDir] groups RAW-video frames, e.g. "/clip_0001". */
    fun saveDng(
        name: String, image: Image, chars: CameraCharacteristics,
        result: CaptureResult, exifOrientation: Int, subDir: String = ""
    ): Uri? {
        val uri = insertImage("$name.dng", "image/x-adobe-dng", subDir) ?: return null
        return try {
            DngCreator(chars, result).use { dng ->
                dng.setOrientation(exifOrientation)
                cr.openOutputStream(uri)?.use { dng.writeImage(it, image) }
            }
            publish(uri); uri
        } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; discard(uri); null }
    }

    class PendingVideo(val uri: Uri, val pfd: ParcelFileDescriptor)

    fun newVideo(name: String): PendingVideo? {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/RawCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        val pfd = cr.openFileDescriptor(uri, "w") ?: run { discard(uri); return null }
        return PendingVideo(uri, pfd)
    }

    fun finishVideo(p: PendingVideo, keep: Boolean): Uri? {
        try { p.pfd.close() } catch (_: Exception) {}
        return if (keep) { publish(p.uri); p.uri } else { discard(p.uri); null }
    }
}
