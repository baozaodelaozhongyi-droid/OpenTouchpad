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
 * - 两端严格保持 100% 圆润平滑的半球形穹顶（半圆钝头，严禁收缩变尖）；
 * - 长边两侧呈现柔和外凸的连续气泡张力微弧，消除几何胶囊笔直硬边的同时保证视觉饱满圆润。
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

    if (kotlin.math.abs(w - h) < 0.5f) {
        path.addCircle(cx, cy, w2, Path.Direction.CW)
        return
    }

    if (h > w) {
        // 纵向气泡（两端为圆润半球穹顶，左右侧边微鼓）
        val rho = h / w
        val b = bubbleBulgeAmount(rho, w2)
        val r = w2 - b
        val yt = top + r
        val yb = bottom - r
        val dyTop = cy - yt
        val dyBot = yb - cy

        path.moveTo(cx - r, yt)
        // 顶部半圆穹顶（圆润钝头）
        path.arcTo(cx - r, top, cx + r, top + 2f * r, 180f, 180f, false)
        // 右侧微凸气泡弧
        path.cubicTo(cx + r, yt + dyTop * 0.5f, cx + w2, cy - dyTop * 0.5f, cx + w2, cy)
        path.cubicTo(cx + w2, cy + dyBot * 0.5f, cx + r, yb - dyBot * 0.5f, cx + r, yb)
        // 底部半圆穹顶（圆润钝头）
        path.arcTo(cx - r, bottom - 2f * r, cx + r, bottom, 0f, 180f, false)
        // 左侧微凸气泡弧
        path.cubicTo(cx - r, yb - dyBot * 0.5f, cx - w2, cy + dyBot * 0.5f, cx - w2, cy)
        path.cubicTo(cx - w2, cy - dyTop * 0.5f, cx - r, yt + dyTop * 0.5f, cx - r, yt)
        path.close()
    } else {
        // 横向气泡（左右两端为圆润半球穹顶，上下侧边微鼓）
        val rho = w / h
        val b = bubbleBulgeAmount(rho, h2)
        val r = h2 - b
        val xl = left + r
        val xr = right - r
        val dxL = cx - xl
        val dxR = xr - cx

        path.moveTo(xl, cy + r)
        // 左侧半圆穹顶（圆润钝头）
        path.arcTo(left, cy - r, left + 2f * r, cy + r, 90f, 180f, false)
        // 上边微凸气泡弧
        path.cubicTo(xl + dxL * 0.5f, cy - r, cx - dxL * 0.5f, cy - h2, cx, cy - h2)
        path.cubicTo(cx + dxR * 0.5f, cy - h2, xr - dxR * 0.5f, cy - r, xr, cy - r)
        // 右侧半圆穹顶（圆润钝头）
        path.arcTo(right - 2f * r, cy - r, right, cy + r, 270f, 180f, false)
        // 下边微凸气泡弧
        path.cubicTo(xr - dxR * 0.5f, cy + r, cx + dxR * 0.5f, cy + h2, cx, cy + h2)
        path.cubicTo(cx - dxL * 0.5f, cy + h2, xl + dxL * 0.5f, cy + r, xl, cy + r)
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
