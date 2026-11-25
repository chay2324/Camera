package com.rawcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 engine. All camera work happens on one background thread (`handler`);
 * public functions are safe to call from the UI thread.
 */
class CameraEngine(
    private val ctx: Context,
    private val saver: MediaSaver,
    private val cb: Callback
) {
    interface Callback {
        fun onSaved(uri: Uri, mime: String)
        fun onMessage(msg: String)
        fun onPreviewSize(size: Size)
        fun onReady()
        fun onRecording(active: Boolean)
    }

    /** Tag riding along with a still/RAW-video request so images and results can be paired. */
    private class Tag(val name: String, val jpeg: Boolean, val raw: Boolean, val orient: Int, val subDir: String)

    private class Job {
        var tag: Tag? = null
        var result: TotalCaptureResult? = null
        var jpeg: Image? = null
        var raw: Image? = null
    }

    val settings = CaptureSettings()
    @Volatile var deviceOrientation = 0
    @Volatile var prefer4k = true

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cam-engine").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }
    private val io = Executors.newFixedThreadPool(2)
    private val inFlight = AtomicInteger(0)

    private var lens: Lens? = null
    private var mode = Mode.PHOTO
    private var texture: SurfaceTexture? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var pendingVideo: MediaSaver.PendingVideo? = null
    private var rawDisabled = false
    private var epoch = 0
    private var sessionGen = 0

    @Volatile private var recording = false
    private var afRegion: MeteringRectangle? = null

    // RAW video bookkeeping
    private var clipDir = ""
    private var frameNo = 0
    private var dropped = 0
    private var lastUri: Uri? = null

    private val jobs = LinkedHashMap<Long, Job>()

    // ------------------------------------------------------------------ public API

    fun isRecording() = recording
    fun currentLens(): Lens? = lens

    fun open(l: Lens, m: Mode, tex: SurfaceTexture) = handler.post {
        closeInternal()
        lens = l; mode = m; texture = tex; rawDisabled = false
        settings.zoom = 1f
        openInternal()
    }

    fun setMode(m: Mode) = handler.post {
        if (recording) return@post
        mode = m
        if (device != null) createSession()
    }

    /** Stops everything (used on pause). Waits briefly for pending saves to finish. */
    fun stop() = handler.post {
        closeInternal()
    }

    fun shutdown() = handler.post {
        closeInternal()
        io.shutdown()
        thread.quitSafely()
    }

    fun zoomRange(): Pair<Float, Float> {
        val l = lens ?: return 1f to 1f
        if (l.physicalId != null) return 1f to 1f
        val r = Ranges.zoom(l.logicalChars)
        if (r != null) return r.lower to min(r.upper, 30f)
        val d = l.logicalChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to d
    }

    fun setZoom(z: Float) {
        settings.zoom = z
        updateRepeating()
    }

    fun updateRepeating() = handler.post { applyRepeating() }

    fun takePhoto() = handler.post { takePhotoInternal() }

    fun startRecording() = handler.post {
        if (recording) return@post
        when (mode) {
            Mode.VIDEO -> startVideoInternal()
            Mode.RAW_VIDEO -> startRawVideoInternal()
            else -> {}
        }
    }

    fun stopRecording() = handler.post {
        if (!recording) return@post
        when (mode) {
            Mode.VIDEO -> stopVideoInternal()
            Mode.RAW_VIDEO -> stopRawVideoInternal()
            else -> {}
        }
    }

    /** nx, ny: touch position inside the preview, 0..1, in upright display orientation. */
    fun tapFocus(nx: Float, ny: Float) = handler.post {
        val l = lens ?: return@post
        val s = session ?: return@post
        if (settings.manualFocus || recording && mode == Mode.RAW_VIDEO) return@post
        val a = l.logicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        val so = l.logicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val (sx, sy) = when (so) {
            90 -> ny to (1f - nx)
            270 -> (1f - ny) to nx
            180 -> (1f - nx) to (1f - ny)
            else -> nx to ny
        }
        val cx = a.left + (sx * a.width()).toInt()
        val cy = a.top + (sy * a.height()).toInt()
        val half = (min(a.width(), a.height()) * 0.08f).toInt()
        val r = Rect(max(a.left, cx - half), max(a.top, cy - half), min(a.right - 1, cx + half), min(a.bottom - 1, cy + half))
        afRegion = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        try {
            val trig = repeatingBuilder() ?: return@post
            trig.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(trig.build(), null, handler)
            applyRepeating()
            handler.postDelayed({
                afRegion = null
                try {
                    val cancel = repeatingBuilder() ?: return@postDelayed
                    cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                    session?.capture(cancel.build(), null, handler)
                    applyRepeating()
                } catch (_: Exception) {}
            }, 4000)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ open / session

    @SuppressLint("MissingPermission")
    private fun openInternal() {
        val l = lens ?: return
        val my = ++epoch
        try {
            mgr.openCamera(l.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (my != epoch) { d.close(); return }
                    device = d
                    createSession()
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); if (device === d) device = null
                    cb.onMessage("Camera disconnected")
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device === d) device = null
                    cb.onMessage("Camera error ($error)")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            cb.onMessage("Can't open camera: ${e.message}")
        } catch (e: SecurityException) {
            cb.onMessage("Camera permission missing")
        }
    }

    private fun releaseSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { jpegReader?.close() } catch (_: Exception) {}
        try { rawReader?.close() } catch (_: Exception) {}
        jpegReader = null; rawReader = null
        previewSurface?.release(); previewSurface = null
        for (j in jobs.values) { j.jpeg?.close(); j.raw?.close() }
        jobs.clear()
    }

    private fun pickPreview(map: StreamConfigurationMap, wide: Boolean): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: listOf(Size(1280, 720))
        val ratioOk = all.filter {
            if (wide) it.width * 9 == it.height * 16 else it.width * 3 == it.height * 4
        }.filter { it.width <= 1920 }
        return ratioOk.maxByOrNull { it.width * it.height }
            ?: all.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: all.first()
    }

    private fun createSession() {
        val d = device ?: return
        val l = lens ?: return
        val tex = texture ?: return
        releaseSession()
        val gen = ++sessionGen
        val map = l.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) { cb.onMessage("No stream map for this lens"); return }

        val ps = pickPreview(map, wide = mode != Mode.PHOTO)
        tex.setDefaultBufferSize(ps.width, ps.height)
        cb.onPreviewSize(ps)
        val surface = Surface(tex)
        previewSurface = surface

        val outs = ArrayList<OutputConfiguration>()
        fun add(s: Surface) {
            val oc = OutputConfiguration(s)
            l.physicalId?.let { oc.setPhysicalCameraId(it) }
            outs.add(oc)
        }
        add(surface)

        val wantRaw = (mode == Mode.RAW_VIDEO || mode == Mode.PHOTO) && l.hasRaw && !rawDisabled
        if (mode == Mode.PHOTO) {
            val js = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            if (js != null) {
                jpegReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2).also {
                    it.setOnImageAvailableListener({ r -> onJpegAvailable(r) }, handler)
                    add(it.surface)
                }
            }
        }
        if (wantRaw) {
            val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            if (rs != null) {
                val buffers = if (mode == Mode.RAW_VIDEO) 6 else 3
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, buffers).also {
                    it.setOnImageAvailableListener({ r -> onRawAvailable(r) }, handler)
                    add(it.surface)
                }
            }
        }
        if (mode == Mode.RAW_VIDEO && rawReader == null) {
            cb.onMessage("This lens has no RAW output. Try another lens.")
        }

        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (gen != sessionGen || device !== d) { cs.close(); return }
                    session = cs
                    applyRepeating()
                    cb.onReady()
                }
                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    if (gen != sessionGen) return
                    if (mode == Mode.PHOTO && rawReader != null && !rawDisabled) {
                        rawDisabled = true
                        cb.onMessage("RAW + JPEG not possible on this lens together; JPEG only")
                        createSession()
                    } else cb.onMessage("Could not start this lens")
                }
            })
        try { d.createCaptureSession(sc) } catch (e: Exception) { cb.onMessage("Session error: ${e.message}") }
    }

    // ------------------------------------------------------------------ requests

    private fun applyZoom(b: CaptureRequest.Builder) {
        val l = lens ?: return
        if (l.physicalId != null) return              // pinned lens = its native field of view
        val z = settings.zoom
        val r = Ranges.zoom(l.logicalChars)
        if (r != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z.coerceIn(r.lower, r.upper))
        } else {
            val a = l.logicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val mz = l.logicalChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val zz = z.coerceIn(1f, mz)
            val w = (a.width() / zz).toInt(); val h = (a.height() / zz).toInt()
            val x = a.left + (a.width() - w) / 2; val y = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + w, y + h))
        }
    }

    private fun applyRegions(b: CaptureRequest.Builder) {
        val r = afRegion ?: return
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
    }

    private fun repeatingBuilder(): CaptureRequest.Builder? {
        val d = device ?: return null
        val l = lens ?: return null
        val ps = previewSurface ?: return null
        val vid = (mode == Mode.VIDEO && recording)
        val b = d.createCaptureRequest(if (vid) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(ps)
        if (vid) recorder?.surface?.let { b.addTarget(it) }
        if (mode == Mode.RAW_VIDEO && recording) {
            rawReader?.surface?.let { b.addTarget(it) }
            val fps = settings.rawVideoFps
            val ranges = l.logicalChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ranges?.firstOrNull { it.lower == fps && it.upper == fps }
                ?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        b.applyUserSettings(settings, l, still = false, video = mode != Mode.PHOTO)
        applyZoom(b)
        applyRegions(b)
        return b
    }

    private val repeatCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
            if (mode == Mode.RAW_VIDEO && recording) {
                val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                val name = "frame_%05d".format(++frameNo)
                val job = jobs.getOrPut(ts) { Job() }
                job.tag = Tag(name, false, true, exifOrientation(), clipDir)
                job.result = res
                tryComplete(ts, job)
                prune()
            }
        }
    }

    private fun applyRepeating() {
        val s = session ?: return
        val b = repeatingBuilder() ?: return
        try { s.setRepeatingRequest(b.build(), repeatCallback, handler) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ photo

    private fun takePhotoInternal() {
        val d = device ?: return
        val s = session ?: return
        val l = lens ?: return
        if (mode != Mode.PHOTO) return
        val fmt = settings.format
        val raw = rawReader
        val jpg = jpegReader
        val wantRaw = fmt != Format.JPEG && raw != null
        val wantJpeg = (fmt != Format.RAW || raw == null) && jpg != null
        if (fmt != Format.JPEG && raw == null) cb.onMessage("No RAW on this lens, saving JPEG")
        if (!wantRaw && !wantJpeg) return
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val orient = exifOrientation()

        fun fire() {
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                if (wantJpeg) b.addTarget(jpg!!.surface)
                if (wantRaw) b.addTarget(raw!!.surface)
                b.applyUserSettings(settings, l, still = true, video = false)
                applyZoom(b); applyRegions(b)
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                b.setTag(Tag(name, wantJpeg, wantRaw, orient, ""))
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                        val tag = req.tag as? Tag ?: return
                        val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        val job = jobs.getOrPut(ts) { Job() }
                        job.tag = tag; job.result = res
                        tryComplete(ts, job)
                    }
                    override fun onCaptureFailed(sess: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                        cb.onMessage("Capture failed")
                    }
                }, handler)
            } catch (e: Exception) { cb.onMessage("Capture error: ${e.message}") }
        }

        val flashSeq = !settings.manualExposure && Ranges.hasFlash(l.logicalChars) &&
            (settings.flash == FlashMode.ON || settings.flash == FlashMode.AUTO)
        if (flashSeq) {
            try {
                val pb = repeatingBuilder()!!
                pb.set(CaptureRequest.CONTROL_AE_MODE,
                    if (settings.flash == FlashMode.ON) CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    else CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                pb.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(pb.build(), null, handler)
                handler.postDelayed({ fire() }, 700)
            } catch (_: Exception) { fire() }
        } else fire()
    }

    // ------------------------------------------------------------------ image pairing + saving

    private fun onJpegAvailable(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        val job = jobs.getOrPut(img.timestamp) { Job() }
        job.jpeg = img
        tryComplete(img.timestamp, job)
        prune()
    }

    private fun onRawAvailable(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (mode == Mode.RAW_VIDEO) {
            if (!recording || inFlight.get() >= 4) {
                if (recording) dropped++
                img.close(); return
            }
        }
        val job = jobs.getOrPut(img.timestamp) { Job() }
        job.raw = img
        tryComplete(img.timestamp, job)
        prune()
    }

    private fun prune() {
        while (jobs.size > 10) {
            val k = jobs.keys.first()
            val j = jobs.remove(k)
            j?.jpeg?.close(); j?.raw?.close()
        }
    }

    private fun tryComplete(ts: Long, job: Job) {
        val tag = job.tag ?: return
        val res = job.result ?: return
        if (tag.jpeg && job.jpeg == null) return
        if (tag.raw && job.raw == null) return
        jobs.remove(ts)
        val l = lens ?: run { job.jpeg?.close(); job.raw?.close(); return }
        val jpegImg = job.jpeg
        val rawImg = job.raw
        inFlight.incrementAndGet()
        io.execute {
            try {
                if (tag.jpeg && jpegImg != null) {
                    val buf = jpegImg.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    jpegImg.close()
                    saver.saveJpeg(tag.name, bytes)?.let { lastUri = it; cb.onSaved(it, "image/jpeg") }
                }
                if (tag.raw && rawImg != null) {
                    val phys: CaptureResult = l.physicalId?.let { res.physicalCameraResults[it] } ?: res
                    val uri = saver.saveDng(tag.name, rawImg, l.chars, phys, tag.orient, tag.subDir)
                    rawImg.close()
                    if (uri != null) { lastUri = uri; if (tag.subDir.isEmpty()) cb.onSaved(uri, "image/x-adobe-dng") }
                    else cb.onMessage("Could not write RAW file")
                }
            } catch (e: Exception) {
                cb.onMessage("Save failed: ${e.message}")
                try { jpegImg?.close() } catch (_: Exception) {}
                try { rawImg?.close() } catch (_: Exception) {}
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun jpegOrientation(): Int {
        val l = lens ?: return 0
        val so = l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val dev = ((deviceOrientation + 45) / 90 * 90) % 360
        val d = if (l.facingBack) dev else -dev
        return (so + d + 360) % 360
    }

    private fun exifOrientation(): Int = when (jpegOrientation()) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    // ------------------------------------------------------------------ video

    private fun pickVideoSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: listOf(Size(1920, 1080))
        if (prefer4k) sizes.firstOrNull { it.width == 3840 && it.height == 2160 }?.let { return it }
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        return sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .maxByOrNull { it.width * it.height } ?: sizes.first()
    }

    private fun hasHevc(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .any { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } }

    private fun startVideoInternal() {
        val d = device ?: return
        val l = lens ?: return
        val map = l.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val vs = pickVideoSize(map)
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pv = saver.newVideo(name) ?: run { cb.onMessage("Can't create video file"); return }
        pendingVideo = pv
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(pv.pfd.fileDescriptor)
            mr.setVideoEncodingBitRate(if (vs.width >= 3840) 60_000_000 else 28_000_000)
            mr.setVideoFrameRate(30)
            mr.setVideoSize(vs.width, vs.height)
            mr.setVideoEncoder(if (hasHevc()) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(256_000)
            mr.setAudioSamplingRate(48_000)
            mr.setAudioChannels(2)
            mr.setOrientationHint(jpegOrientation())
            mr.prepare()
        } catch (e: Exception) {
            cb.onMessage("Recorder error: ${e.message}")
            try { mr.release() } catch (_: Exception) {}
            saver.finishVideo(pv, false); pendingVideo = null
            return
        }
        recorder = mr

        // new session: preview + recorder
        try { session?.close() } catch (_: Exception) {}
        session = null
        val gen = ++sessionGen
        val outs = ArrayList<OutputConfiguration>()
        for (s in listOfNotNull(previewSurface, mr.surface)) {
            val oc = OutputConfiguration(s)
            l.physicalId?.let { oc.setPhysicalCameraId(it) }
            outs.add(oc)
        }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (gen != sessionGen) { cs.close(); return }
                    session = cs
                    recording = true
                    applyRepeating()
                    try { mr.start() } catch (e: Exception) {
                        cb.onMessage("Could not start recording"); recording = false
                        abortVideo(); return
                    }
                    cb.onRecording(true)
                }
                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    cb.onMessage("This lens can't record at this resolution")
                    abortVideo()
                }
            })
        try { d.createCaptureSession(sc) } catch (e: Exception) { cb.onMessage("Session error"); abortVideo() }
    }

    private fun abortVideo() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        pendingVideo?.let { saver.finishVideo(it, false) }
        pendingVideo = null
        recording = false
        cb.onRecording(false)
        createSession()
    }

    private fun stopVideoInternal() {
        val mr = recorder
        val pv = pendingVideo
        var ok = true
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { mr?.stop() } catch (_: RuntimeException) { ok = false }
        try { mr?.reset(); mr?.release() } catch (_: Exception) {}
        recorder = null; pendingVideo = null
        recording = false
        cb.onRecording(false)
        val uri = if (pv != null) saver.finishVideo(pv, ok) else null
        if (uri != null) cb.onSaved(uri, "video/mp4") else cb.onMessage("Recording too short, discarded")
        createSession()
    }

    // ------------------------------------------------------------------ RAW video (DNG sequence)

    private fun startRawVideoInternal() {
        if (rawReader == null) { cb.onMessage("No RAW on this lens"); return }
        clipDir = "/CLIP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        frameNo = 0; dropped = 0
        recording = true
        applyRepeating()
        cb.onRecording(true)
    }

    private fun stopRawVideoInternal() {
        recording = false
        val n = frameNo
        applyRepeating()
        cb.onRecording(false)
        cb.onMessage("RAW clip: $n frames, $dropped dropped (DNG sequence in DCIM/RawCam$clipDir)")
        lastUri?.let { cb.onSaved(it, "image/x-adobe-dng") }
    }

    // ------------------------------------------------------------------ teardown

    private fun closeInternal() {
        if (recording) {
            when (mode) {
                Mode.VIDEO -> stopVideoInternalNoRestart()
                Mode.RAW_VIDEO -> { recording = false; cb.onRecording(false) }
                else -> {}
            }
        }
        // let pending saves finish (max ~3 s) before the image buffers are torn down
        var waited = 0
        while (inFlight.get() > 0 && waited < 3000) { try { Thread.sleep(50) } catch (_: Exception) {}; waited += 50 }
        epoch++; sessionGen++
        releaseSession()
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    private fun stopVideoInternalNoRestart() {
        val mr = recorder; val pv = pendingVideo
        var ok = true
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { mr?.stop() } catch (_: RuntimeException) { ok = false }
        try { mr?.reset(); mr?.release() } catch (_: Exception) {}
        recorder = null; pendingVideo = null; recording = false
        cb.onRecording(false)
        if (pv != null) saver.finishVideo(pv, ok)?.let { cb.onSaved(it, "video/mp4") }
    }
}
