package com.rawcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

/** Small pill-shaped text button. */
fun Context.chip(text: String, onClick: (TextView) -> Unit): TextView = TextView(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    textSize = 13f
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(7), dp(12), dp(7))
    background = chipBg(false)
    setOnClickListener { onClick(this) }
}

fun Context.chipBg(active: Boolean) = GradientDrawable().apply {
    cornerRadius = dp(16).toFloat()
    setColor(if (active) Color.parseColor("#FFC107") else Color.parseColor("#66000000"))
}

fun TextView.setActive(active: Boolean) {
    background = context.chipBg(active)
    setTextColor(if (active) Color.BLACK else Color.WHITE)
}

/** Rule-of-thirds grid. */
class GridView(ctx: Context) : View(ctx) {
    private val p = Paint().apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 2f }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3, 0f, w * i / 3, h, p)
            c.drawLine(0f, h * i / 3, w, h * i / 3, p)
        }
    }
}

/** Focus ring shown where the user tapped. */
class FocusRing(ctx: Context) : View(ctx) {
    private val p = Paint().apply {
        color = Color.parseColor("#FFC107"); style = Paint.Style.STROKE
        strokeWidth = 4f; isAntiAlias = true
    }
    override fun onDraw(c: Canvas) {
        c.drawCircle(width / 2f, height / 2f, width / 2f - 4f, p)
    }
}

/** Round shutter button; turns into a red square-ish record button while recording. */
class ShutterView(ctx: Context) : View(ctx) {
    var video = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    private val ring = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
    private val fill = Paint().apply { isAntiAlias = true }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 8f
        c.drawCircle(cx, cy, r, ring)
        fill.color = if (video) Color.parseColor("#E53935") else Color.WHITE
        val inner = if (recording) r * 0.45f else r * 0.78f
        c.drawCircle(cx, cy, inner, fill)
    }
}
