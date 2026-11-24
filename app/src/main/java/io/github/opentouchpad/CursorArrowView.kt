package io.github.opentouchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 箭头光标：实心圆角三角箭头（尖端朝左上，底部有一个向内的缺口），
 * 外面一圈对比色描边 + 柔和投影。尖端固定在视图左上角，保证点击位置和尖端对齐。
 *
 * 采用按尺寸与颜色按需预渲染的 2× 超采样位图缓存：
 * - 去除 View 级 LAYER_TYPE_SOFTWARE，消除软件图层合成开销；
 * - 仅在光标尺寸或颜色变更时以 2× 超采样渲染一次（含圆角路径与柔和阴影，API 26–36 视觉完全一致）；
 * - 显式指定 DENSITY_NONE 与目标矩形绘制，杜绝密度变更时的二次缩放模糊；
 * - 日常绘制零对象分配，仅执行单次硬件 drawBitmap 下采样。
 */
class CursorArrowView(context: Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointer = Path()
    private val dstRect = Rect()

    private var cachedBitmap: Bitmap? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedColor = 0

    var pointerColor: Int = 0xFF141413.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        background = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = context.getString(R.string.cursor_content_description)
    }

    private fun ensureCachedBitmap(w: Int, h: Int): Bitmap? {
        val existing = cachedBitmap
        if (existing != null && !existing.isRecycled &&
            cachedWidth == w && cachedHeight == h && cachedColor == pointerColor
        ) {
            return existing
        }
        // View 仍附着窗口时不调用 recycle()，避免硬件加速下 RenderThread 回放上一帧 DisplayList 产生竞态
        cachedBitmap = null

        val targetScale = cursorSupersampleScale(w, h)
        val bw = (w * targetScale).roundToInt().coerceAtLeast(w)
        val bh = (h * targetScale).roundToInt().coerceAtLeast(h)
        val bmp = runCatching { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: (if (bw != w || bh != h) runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() else null)
            ?: return null
        bmp.density = Bitmap.DENSITY_NONE

        val scale = min(bmp.width.toFloat() / w, bmp.height.toFloat() / h)
        val c = Canvas(bmp)
        val viewSize = min(w, h).toFloat()
        val size = viewSize * scale
        val ring = cursorHotspotOffset(viewSize) * scale
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
        c.drawPath(pointer, outline)

        fill.pathEffect = corner
        fill.color = pointerColor
        c.drawPath(pointer, fill)

        cachedBitmap = bmp
        cachedWidth = w
        cachedHeight = h
        cachedColor = pointerColor
        return bmp
    }

    /**
     * 安全释放光标预渲染缓存引用（供隐藏/最小化/内存紧张时调用）：
     * 不在 View 仍附着窗口时同步调用 recycle()，避免 RenderThread 仍在回放上一帧 DisplayList 产生竞态崩溃；
     * API 26+ Bitmap 像素由 NativeAllocationRegistry 自动回收，彻底脱离窗口后再在 onDetachedFromWindow 立即 recycle。
     */
    fun releaseCachedBitmap() {
        cachedBitmap = null
        cachedWidth = 0
        cachedHeight = 0
    }

    override fun onDetachedFromWindow() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedWidth = 0
        cachedHeight = 0
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val bmp = ensureCachedBitmap(w, h) ?: return
        dstRect.set(0, 0, w, h)
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
    }
}
