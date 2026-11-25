package com.rawcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraEngine.Callback {

    private lateinit var engine: CameraEngine
    private lateinit var texView: TextureView
    private lateinit var previewHolder: FrameLayout
    private lateinit var grid: GridView
    private lateinit var focusRing: FocusRing
    private lateinit var shutter: ShutterView
    private lateinit var thumb: ImageView
    private lateinit var msg: TextView
    private lateinit var zoomText: TextView
    private lateinit var recTime: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var proPanel: LinearLayout
    private lateinit var formatChip: TextView
    private lateinit var flashChip: TextView
    private lateinit var lookChip: TextView
    private lateinit var timerChip: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var evLabel: TextView

    private var lenses: List<Lens> = emptyList()
    private var facingBack = true
    private var lens: Lens? = null
    private var mode = Mode.PHOTO
    private var timerSec = 0
    private var gridOn = false
    private var lastUri: Uri? = null
    private var lastMime = "image/*"
    private var recStart = 0L
    private var minZoom = 1f
    private var maxZoom = 1f
    private var started = false
    private val tick = object : Runnable {
        override fun run() {
            if (engine.isRecording()) {
                val s = (SystemClock.elapsedRealtime() - recStart) / 1000
                recTime.text = "%02d:%02d".format(s / 60, s % 60)
                recTime.postDelayed(this, 500)
            }
        }
    }
    private lateinit var orient: OrientationEventListener

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(this, MediaSaver(this), this)
        buildUi()
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) engine.deviceOrientation = ((o + 45) / 90 * 90) % 360
            }
        }
        try { lenses = Lenses.enumerate(this) } catch (e: Exception) { toast("No camera: ${e.message}") }
        selectDefaultLens()
        rebuildLensRow()
    }

    override fun onResume() {
        super.onResume()
        orient.enable()
        if (!hasPerms()) { requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1); return }
        if (texView.isAvailable) startCamera()
    }

    override fun onPause() {
        orient.disable()
        engine.stop()
        started = false
        super.onPause()
    }

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (hasPerms()) { if (texView.isAvailable) startCamera() }
        else toast("Camera and microphone permission are needed")
    }

    private fun hasPerms() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val l = lens ?: return
        val t = texView.surfaceTexture ?: return
        if (started) return
        started = true
        engine.open(l, mode, t)
    }

    // ------------------------------------------------------------------ lens handling

    private fun selectDefaultLens() {
        val list = lenses.filter { it.facingBack == facingBack }
        lens = if (facingBack) list.firstOrNull { it.physicalId != null && it.label == "1x" && it.hasRaw }
            ?: list.firstOrNull { it.hasRaw } ?: list.firstOrNull()
        else list.firstOrNull()
    }

    private fun reopen() { started = false; if (texView.isAvailable) startCamera() }

    private fun rebuildLensRow() {
        lensRow.removeAllViews()
        val list = lenses.filter { it.facingBack == facingBack }
        if (list.size > 1) for (l in list) {
            val c = chip(l.label) { lens = l; rebuildLensRow(); reopen() }
            c.setActive(l == lens)
            lensRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // preview
        previewHolder = FrameLayout(this)
        texView = TextureView(this)
        previewHolder.addView(texView, FrameLayout.LayoutParams(-1, -1))
        grid = GridView(this).apply { visibility = View.GONE }
        previewHolder.addView(grid, FrameLayout.LayoutParams(-1, -1))
        focusRing = FocusRing(this).apply { visibility = View.GONE }
        previewHolder.addView(focusRing, FrameLayout.LayoutParams(dp(70), dp(70)))
        root.addView(previewHolder, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(56) })

        texView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (hasPerms()) startCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        // pinch to zoom + tap to focus
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = (engine.settings.zoom * d.scaleFactor).coerceIn(minZoom, maxZoom)
                engine.setZoom(z); showZoom(z); return true
            }
        })
        texView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && e.pointerCount == 1 && !scale.isInProgress &&
                e.eventTime - e.downTime < 250) {
                engine.tapFocus(e.x / v.width, e.y / v.height)
                focusRing.x = e.x - focusRing.width / 2f; focusRing.y = e.y - focusRing.height / 2f
                focusRing.visibility = View.VISIBLE
                focusRing.postDelayed({ focusRing.visibility = View.GONE }, 1200)
            }
            true
        }

        // top bar
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        flashChip = chip("Flash off") {
            val s = engine.settings
            s.flash = FlashMode.values()[(s.flash.ordinal + 1) % FlashMode.values().size]
            it.text = s.flash.label; engine.updateRepeating()
        }
        formatChip = chip(Format.RAW_JPEG.label) {
            val s = engine.settings
            if (mode == Mode.RAW_VIDEO) {
                val opts = intArrayOf(5, 10, 15, 24)
                s.rawVideoFps = opts[(opts.indexOf(s.rawVideoFps) + 1) % opts.size]
                it.text = "${s.rawVideoFps} fps"
            } else {
                s.format = Format.values()[(s.format.ordinal + 1) % Format.values().size]
                it.text = s.format.label
            }
        }
        lookChip = chip("Natural") {
            val s = engine.settings; s.natural = !s.natural
            it.text = if (s.natural) "Natural" else "Std"; engine.updateRepeating()
        }
        timerChip = chip("Timer off") {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            it.text = if (timerSec == 0) "Timer off" else "${timerSec}s"
        }
        val gridChip = chip("Grid") { gridOn = !gridOn; grid.visibility = if (gridOn) View.VISIBLE else View.GONE; (it).setActive(gridOn) }
        val proChip = chip("Pro") {
            proPanel.visibility = if (proPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            it.setActive(proPanel.visibility == View.VISIBLE)
        }
        for (c in listOf(flashChip, formatChip, lookChip, timerChip, gridChip, proChip))
            top.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        root.addView(top, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))

        // bottom stack
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        msg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        recTime = TextView(this).apply { setTextColor(Color.parseColor("#FF5252")); textSize = 16f; gravity = Gravity.CENTER; visibility = View.GONE }
        bottom.addView(msg); bottom.addView(recTime)

        proPanel = buildProPanel()
        bottom.addView(proPanel)

        zoomText = TextView(this).apply { setTextColor(Color.parseColor("#FFC107")); textSize = 14f; gravity = Gravity.CENTER; text = "1.0x" }
        bottom.addView(zoomText)
        lensRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        bottom.addView(lensRow, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(4), 0, dp(4)) })

        modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val names = listOf("PHOTO" to Mode.PHOTO, "VIDEO" to Mode.VIDEO, "RAW VIDEO" to Mode.RAW_VIDEO)
        for ((n, m) in names) {
            val c = chip(n) { setMode(m) }
            c.tag = m
            modeRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        bottom.addView(modeRow)

        val ctl = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(10), dp(24), dp(22)) }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.DKGRAY) }
            clipToOutline = true
            setOnClickListener { openGallery() }
        }
        ctl.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)))
        ctl.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        shutter = ShutterView(this).apply { setOnClickListener { onShutter() } }
        ctl.addView(shutter, LinearLayout.LayoutParams(dp(84), dp(84)))
        ctl.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val flip = chip("Flip") {
            if (engine.isRecording()) return@chip
            facingBack = !facingBack; selectDefaultLens(); rebuildLensRow(); reopen()
        }
        ctl.addView(flip, LinearLayout.LayoutParams(dp(56), dp(56)))
        bottom.addView(ctl, LinearLayout.LayoutParams(-1, -2))

        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        refreshModeChips()
    }

    private fun buildProPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setBackgroundColor(Color.parseColor("#99000000")); setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 12f }
        fun row(name: String, value: TextView, bar: SeekBar, toggle: TextView?): LinearLayout {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(label(name), LinearLayout.LayoutParams(dp(44), -2))
            r.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(value, LinearLayout.LayoutParams(dp(64), -2))
            toggle?.let { r.addView(it) }
            return r
        }
        fun bar(max: Int, on: (Int) -> Unit) = SeekBar(this).apply {
            this.max = max
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { if (user) on(v) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val readout = { TextView(this).apply { setTextColor(Color.parseColor("#FFC107")); textSize = 12f; gravity = Gravity.END } }

        // ISO + shutter (manual exposure when "M" is on)
        val isoL = readout(); val shL = readout(); val focL = readout(); evLabel = readout()
        val c = { lens?.logicalChars }
        val isoBar = bar(1000) { v ->
            val r = c()?.let { Ranges.iso(it) } ?: return@bar
            val iso = lerpLog(r.lower.toDouble(), minOf(r.upper, 6400).toDouble(), v / 1000.0).roundToInt()
            engine.settings.iso = iso; isoL.text = "$iso"; engine.updateRepeating()
        }
        val shBar = bar(1000) { v ->
            val r = c()?.let { Ranges.shutter(it) } ?: return@bar
            val ns = lerpLog(maxOf(r.lower, 125_000L).toDouble(), minOf(r.upper, 1_000_000_000L).toDouble(), v / 1000.0).toLong()
            engine.settings.shutterNs = ns; shL.text = fmtShutter(ns); engine.updateRepeating()
        }
        val mToggle = chip("M") {
            val s = engine.settings; s.manualExposure = !s.manualExposure; it.setActive(s.manualExposure)
            if (s.manualExposure) { isoL.text = "${s.iso}"; shL.text = fmtShutter(s.shutterNs) }
            engine.updateRepeating()
        }
        val focBar = bar(100) { v ->
            val mf = c()?.let { Ranges.minFocusDiopter(it) } ?: 0f
            engine.settings.focusDiopter = v / 100f * mf
            focL.text = if (v == 0) "inf" else "%.2f m".format(1f / maxOf(engine.settings.focusDiopter, 0.01f))
            engine.updateRepeating()
        }
        val mfToggle = chip("MF") {
            val s = engine.settings; s.manualFocus = !s.manualFocus; it.setActive(s.manualFocus); engine.updateRepeating()
        }
        val evBar = bar(1) { v ->
            val r = c() ?: return@bar
            val rg = Ranges.ev(r); val idx = rg.lower + v
            engine.settings.evIndex = idx
            evLabel.text = "%+.1f".format(idx * Ranges.evStep(r)); engine.updateRepeating()
        }
        evBar.tag = "ev"
        p.addView(row("ISO", isoL, isoBar, mToggle))
        p.addView(row("Shutter", shL, shBar, null))
        p.addView(row("Focus", focL, focBar, mfToggle))
        p.addView(row("EV", evLabel, evBar, null))

        val wbRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val wbChips = ArrayList<TextView>()
        for (w in WbPreset.values()) {
            val ch = chip(w.label) {
                engine.settings.wb = w; wbChips.forEach { x -> x.setActive(x === it) }; engine.updateRepeating()
            }
            ch.setActive(w == WbPreset.AUTO); wbChips.add(ch)
            wbRow.addView(ch, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
        }
        p.addView(wbRow)
        return p
    }

    private fun lerpLog(a: Double, b: Double, t: Double) = exp(ln(a) + (ln(b) - ln(a)) * t)

    private fun fmtShutter(ns: Long): String =
        if (ns >= 500_000_000L) "%.1f s".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"

    private fun showZoom(z: Float) { zoomText.text = "%.1fx".format(z) }

    private fun refreshModeChips() {
        for (i in 0 until modeRow.childCount) {
            val c = modeRow.getChildAt(i) as TextView
            c.setActive(c.tag == mode)
        }
        formatChip.visibility = View.VISIBLE
        formatChip.text = if (mode == Mode.RAW_VIDEO) "${engine.settings.rawVideoFps} fps" else engine.settings.format.label
        flashChip.visibility = if (mode == Mode.RAW_VIDEO) View.INVISIBLE else View.VISIBLE
        timerChip.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.INVISIBLE
        shutter.video = mode != Mode.PHOTO
    }

    private fun setMode(m: Mode) {
        if (engine.isRecording() || m == mode) return
        mode = m
        refreshModeChips()
        engine.setMode(m)
    }

    // ------------------------------------------------------------------ actions

    private fun onShutter() {
        when (mode) {
            Mode.PHOTO -> {
                if (timerSec == 0) engine.takePhoto()
                else object : CountDownTimer(timerSec * 1000L, 1000L) {
                    override fun onTick(ms: Long) { msg.text = "${(ms / 1000) + 1}" }
                    override fun onFinish() { msg.text = ""; engine.takePhoto() }
                }.start()
            }
            else -> if (engine.isRecording()) engine.stopRecording() else engine.startRecording()
        }
    }

    private fun openGallery() {
        val u = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("No gallery app found") }
    }

    private fun toast(t: String) = runOnUiThread { msg.text = t; msg.postDelayed({ if (msg.text == t) msg.text = "" }, 3500) }

    // ------------------------------------------------------------------ engine callbacks (any thread)

    override fun onSaved(uri: Uri, mime: String) = runOnUiThread {
        lastUri = uri; lastMime = mime
        Thread {
            val bmp = try { contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (_: Exception) { null }
            runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }
        }.start()
    }

    override fun onMessage(msg: String) { toast(msg) }

    override fun onPreviewSize(size: Size) = runOnUiThread {
        // sensor buffers are landscape; the screen is locked to portrait
        val w = previewHolder.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = (w.toFloat() * size.width / size.height).roundToInt()
        val lp = texView.layoutParams as FrameLayout.LayoutParams
        lp.width = w; lp.height = h; lp.gravity = Gravity.TOP
        texView.layoutParams = lp
        val gl = grid.layoutParams as FrameLayout.LayoutParams
        gl.width = w; gl.height = h; grid.layoutParams = gl
    }

    override fun onReady() = runOnUiThread {
        val (lo, hi) = engine.zoomRange()
        minZoom = lo; maxZoom = hi
        showZoom(engine.settings.zoom)
        zoomText.visibility = if (hi > lo) View.VISIBLE else View.GONE
        // size the EV slider for this lens
        val c = lens?.logicalChars
        if (c != null) {
            val rg = Ranges.ev(c)
            val ev = findEvBar(proPanel)
            ev?.max = (rg.upper - rg.lower)
            ev?.progress = -rg.lower
            engine.settings.evIndex = 0
            evLabel.text = "+0.0"
        }
    }

    override fun onRecording(active: Boolean) = runOnUiThread {
        shutter.recording = active
        recTime.visibility = if (active) View.VISIBLE else View.GONE
        if (active) { recStart = SystemClock.elapsedRealtime(); recTime.post(tick) }
        for (i in 0 until modeRow.childCount) modeRow.getChildAt(i).isEnabled = !active
    }

    private fun findEvBar(v: View): SeekBar? {
        if (v is SeekBar && v.tag == "ev") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findEvBar(v.getChildAt(i))?.let { return it }
        return null
    }
}
