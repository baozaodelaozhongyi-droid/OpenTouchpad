package io.github.opentouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.view.View
import kotlin.math.min

/**
 * 箭头光标：圆角箭头 + 对比色描边 + 柔和投影，风格和面板的线条图标一致。
 * 箭头尖端固定在视图左上角（只留描边宽度的内边距），保证点击位置和看到的尖端对齐。
 */
class CursorArrowView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointer = Path()

    var pointerColor: Int = 0xFFFAF9F5.toInt()
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
        val stroke = (size * 0.07f).coerceAtLeast(1.5f)
        val o = stroke / 2f
        val u = (size - stroke * 1.5f) / 24f

        // 24 网格里的箭头：尖端 (0,0)，左边竖直，尾巴向右下
        pointer.reset()
        pointer.moveTo(o, o)
        pointer.lineTo(o, o + 19.5f * u)
        pointer.lineTo(o + 5.2f * u, o + 15.2f * u)
        pointer.lineTo(o + 8.6f * u, o + 22.4f * u)
        pointer.lineTo(o + 12.2f * u, o + 20.8f * u)
        pointer.lineTo(o + 8.9f * u, o + 13.8f * u)
        pointer.lineTo(o + 15.4f * u, o + 13.8f * u)
        pointer.close()

        val corner = CornerPathEffect(u * 1.6f)
        val lightFill = Color.luminance(pointerColor) > 0.5f
        outline.pathEffect = corner
        outline.strokeWidth = stroke
        outline.color = if (lightFill) 0xF2141413.toInt() else 0xF2FAF9F5.toInt()
        outline.setShadowLayer(size * 0.05f, 0f, size * 0.025f, 0x66141413)
        canvas.drawPath(pointer, outline)

        fill.pathEffect = corner
        fill.color = pointerColor
        canvas.drawPath(pointer, fill)
    }
}
