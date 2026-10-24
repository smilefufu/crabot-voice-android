package com.crabot.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

// A static state indicator, not an audio-level meter. No animation or audio sampling.
internal class VoiceStatusView(context: Context, private val mode: VoiceController.Mode) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        paint.color = Color.rgb(228, 238, 228)
        canvas.drawCircle(cx, cy, size / 2, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, size * .39f, paint)
        val heights = when (mode) {
            VoiceController.Mode.CLOSED -> floatArrayOf(.06f, .06f, .06f, .06f, .06f)
            VoiceController.Mode.RECORDING, VoiceController.Mode.FOLLOWUP -> floatArrayOf(.16f, .33f, .44f, .28f, .16f)
            VoiceController.Mode.PLAYING -> floatArrayOf(.18f, .4f, .24f, .34f, .14f)
            VoiceController.Mode.PROCESSING, VoiceController.Mode.WAITING -> floatArrayOf(.08f, .08f, .08f, .08f, .08f)
            else -> floatArrayOf(.14f, .25f, .36f, .25f, .14f)
        }
        paint.color = if (mode == VoiceController.Mode.CLOSED) Color.rgb(116, 133, 119) else Color.rgb(36, 94, 60)
        heights.forEachIndexed { i, height ->
            val x = cx + (i - 2) * size * .105f
            canvas.drawRoundRect(x - size * .026f, cy - size * height / 2, x + size * .026f, cy + size * height / 2, size * .026f, size * .026f, paint)
        }
    }
}
