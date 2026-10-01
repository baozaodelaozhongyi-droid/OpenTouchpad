package io.github.opentouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.view.View
import kotlin.math.min

/** A familiar desktop-style pointer with a dark outline for any background. */
class CursorArrowView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }
    private val pointer = Path()

    var pointerColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    init {
        background = ColorDrawable(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        contentDescription = context.getString(R.string.cursor_content_description)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        pointer.reset()
        pointer.moveTo(0f, 0f)
        pointer.lineTo(0f, size * 0.83f)
        pointer.lineTo(size * 0.23f, size * 0.64f)
        pointer.lineTo(size * 0.43f, size * 0.93f)
        pointer.lineTo(size * 0.60f, size * 0.83f)
        pointer.lineTo(size * 0.40f, size * 0.53f)
        pointer.lineTo(size * 0.70f, size * 0.53f)
        pointer.close()

        outline.strokeWidth = (size * 0.055f).coerceAtLeast(2f)
        outline.color = if (Color.luminance(pointerColor) > 0.55f) 0xEE111827.toInt() else 0xEEFFFFFF.toInt()
        outline.setShadowLayer(size * 0.035f, 0f, size * 0.02f, 0x99000000.toInt())
        canvas.drawPath(pointer, outline)

        fill.color = pointerColor
        fill.alpha = Color.alpha(pointerColor)
        canvas.drawPath(pointer, fill)
    }
}
