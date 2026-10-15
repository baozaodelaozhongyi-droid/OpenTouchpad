package io.github.opentouchpad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * 构建饱满气泡曲线路径：
 * - 当 w == h 时，绘制精确圆；
 * - 当高度或宽度被拉长时，长边两侧呈现柔和外凸的连续气泡张力弧线（鼓形微弧），
 *   两端平滑过渡为圆润饱满的穹顶，消除传统胶囊的笔直冷硬长边。
 */
internal fun buildBubblePath(
    path: Path,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    path.reset()
    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) return

    val w2 = w / 2f
    val h2 = h / 2f
    val cx = left + w2
    val cy = top + h2

    val minDim = minOf(w, h)
    val maxDim = maxOf(w, h)
    val rho = maxDim / minDim.coerceAtLeast(1f)
    val (kt, ks) = bubbleControlCoefficients(rho)

    if (h >= w) {
        // 纵向气泡（如侧边按键在高度拉高时）
        val c1x = cx + w2 * kt
        val c1y = top
        val c2x = right
        val c2y = cy - h2 * ks

        val c3x = right
        val c3y = cy + h2 * ks
        val c4x = cx + w2 * kt
        val c4y = bottom

        val c5x = cx - w2 * kt
        val c5y = bottom
        val c6x = left
        val c6y = cy + h2 * ks

        val c7x = left
        val c7y = cy - h2 * ks
        val c8x = cx - w2 * kt
        val c8y = top

        path.moveTo(cx, top)
        path.cubicTo(c1x, c1y, c2x, c2y, right, cy)
        path.cubicTo(c3x, c3y, c4x, c4y, cx, bottom)
        path.cubicTo(c5x, c5y, c6x, c6y, left, cy)
        path.cubicTo(c7x, c7y, c8x, c8y, cx, top)
        path.close()
    } else {
        // 横向气泡（如上下按键在横向拉宽时）
        val c1x = left
        val c1y = cy - h2 * kt
        val c2x = cx - w2 * ks
        val c2y = top

        val c3x = cx + w2 * ks
        val c3y = top
        val c4x = right
        val c4y = cy - h2 * kt

        val c5x = right
        val c5y = cy + h2 * kt
        val c6x = cx + w2 * ks
        val c6y = bottom

        val c7x = cx - w2 * ks
        val c7y = bottom
        val c8x = left
        val c8y = cy + h2 * kt

        path.moveTo(left, cy)
        path.cubicTo(c1x, c1y, c2x, c2y, cx, top)
        path.cubicTo(c3x, c3y, c4x, c4y, right, cy)
        path.cubicTo(c5x, c5y, c6x, c6y, cx, bottom)
        path.cubicTo(c7x, c7y, c8x, c8y, left, cy)
        path.close()
    }
}

/**
 * 饱满气泡型 Drawable：
 * - 纯正圆/连续弧度自适应；
 * - 垂直双色微渐变（顶部 8% 轻微提亮高光感）；
 * - 支持面板透明度与描边；
 * - 作为遮罩时支持 RippleDrawable 精确剪裁水波纹。
 */
class BubbleDrawable(
    private var baseColor: Int,
    private var strokeColor: Int = Color.TRANSPARENT,
    private var strokeWidthPx: Float = 0f,
    private var opacityPercent: Int = 100,
    private val isMask: Boolean = false,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val fillPath = Path()
    private val strokePath = Path()

    init {
        updatePaints()
    }

    private fun updatePaints() {
        if (isMask) {
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
        } else {
            val a = (255 * opacityPercent / 100).coerceIn(0, 255)
            val topC = withAlpha(lightenColor(baseColor, 0.08f), a)
            val botC = withAlpha(baseColor, a)
            if (bounds.height() > 0) {
                fillPaint.shader = LinearGradient(
                    0f, bounds.top.toFloat(),
                    0f, bounds.bottom.toFloat(),
                    topC, botC,
                    Shader.TileMode.CLAMP,
                )
            } else {
                fillPaint.shader = null
                fillPaint.color = botC
            }

            if (strokeWidthPx > 0f) {
                val strokeAlpha = (Color.alpha(strokeColor) * opacityPercent / 100).coerceIn(0, 255)
                strokePaint.color = Color.argb(
                    strokeAlpha,
                    Color.red(strokeColor),
                    Color.green(strokeColor),
                    Color.blue(strokeColor),
                )
                strokePaint.strokeWidth = strokeWidthPx
            }
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            fillPath.reset()
            strokePath.reset()
            return
        }

        if (isMask) {
            buildBubblePath(fillPath, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        } else {
            val halfStroke = if (strokeWidthPx > 0f) strokeWidthPx / 2f else 0f
            buildBubblePath(
                fillPath,
                bounds.left + halfStroke,
                bounds.top + halfStroke,
                bounds.right - halfStroke,
                bounds.bottom - halfStroke,
            )
            if (strokeWidthPx > 0f) {
                strokePath.set(fillPath)
            }
            updatePaints()
        }
    }

    override fun draw(canvas: Canvas) {
        if (fillPath.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            onBoundsChange(bounds)
        }
        canvas.drawPath(fillPath, fillPaint)
        if (!isMask && strokeWidthPx > 0f) {
            canvas.drawPath(strokePath, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if (fillPath.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            onBoundsChange(bounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(fillPath)
        } else {
            @Suppress("DEPRECATION")
            if (fillPath.isConvex) {
                outline.setConvexPath(fillPath)
            } else {
                outline.setRect(bounds)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
