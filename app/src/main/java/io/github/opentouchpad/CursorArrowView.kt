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
 * 箭头光标：实心圆角三角箭头（尖端朝左上，底部有一个向内的缺口），
 * 外面一圈对比色描边 + 柔和投影。尖端固定在视图左上角，保证点击位置和尖端对齐。
 */
class CursorArrowView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointer = Path()

    var pointerColor: Int = 0xFF141413.toInt()
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
        val ring = (size * 0.09f).coerceAtLeast(2f)
        val o = ring
        val u = (size - ring * 2.6f) / 24f

        // 24 网格：尖端 (0,0)；右侧 (20,8.6)；缺口 (10.6,12.4)；下方 (5.2,21)
        pointer.reset()
        pointer.moveTo(o, o)
        pointer.lineTo(o + 20f * u, o + 8.6f * u)
        pointer.lineTo(o + 10.6f * u, o + 12.4f * u)
        pointer.lineTo(o + 5.2f * u, o + 21f * u)
        pointer.close()

        val corner = CornerPathEffect(u * 2.6f)
        val lightFill = Color.luminance(pointerColor) > 0.5f
        // 描边：用同一路径加粗画一层，圆角更饱满
        outline.pathEffect = corner
        outline.strokeWidth = ring * 2f
        outline.color = if (lightFill) 0xF2141413.toInt() else 0xFFFAF9F5.toInt()
        outline.setShadowLayer(size * 0.06f, 0f, size * 0.03f, 0x59141413)
        canvas.drawPath(pointer, outline)

        fill.pathEffect = corner
        fill.color = pointerColor
        canvas.drawPath(pointer, fill)
    }
}
