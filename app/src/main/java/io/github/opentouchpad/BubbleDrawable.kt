package io.github.opentouchpad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * 极简气泡遮罩 Drawable（专供 RippleDrawable mask 层使用）：
 * 仅持有 1 个 Path 并共享不可变白色 Paint，相比完整 BubbleDrawable 省去 4 个 Native Paint、
 * 1 个 Native Path 与 6 个渐变数组。
 */
internal class BubbleMaskDrawable : Drawable() {
    private val fillPath = Path()
    private var pathsDirty = true

    private fun rebuildPath(bounds: Rect) {
        pathsDirty = false
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            fillPath.reset()
            return
        }
        buildBubblePath(
            fillPath,
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
        )
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        if (pathsDirty || fillPath.isEmpty) {
            rebuildPath(bounds)
        }
        canvas.drawPath(fillPath, MASK_PAINT)
    }

    // Mask 永远只需纯白不透明剪裁区域，空操作确保静态 MASK_PAINT 绝对不可变
    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if ((pathsDirty || fillPath.isEmpty) && bounds.width() > 0 && bounds.height() > 0) {
            rebuildPath(bounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(fillPath)
        } else {
            @Suppress("DEPRECATION")
            if (fillPath.isConvex) {
                outline.setConvexPath(fillPath)
            } else {
                val radius = minOf(bounds.width(), bounds.height()) / 2f
                outline.setRoundRect(bounds, radius)
            }
        }
    }

    private companion object {
        val MASK_PAINT: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
    }
}

/**
 * 饱满气泡型 Drawable（支持双模态材质）：
 * - 纯正圆/连续弧度自适应；
 * - 高不透明度（80%–100%）：呈现干净柔和的 Material You 容器表面（零分配液态玻璃画笔与路径）；
 * - 中低不透明度（20%–60%）：呈现 Apple 液态玻璃（焦散透光基底、内部聚光、张力内高光弧、135° 菲涅尔折光描边）；
 * - 60%–80% 之间平滑演进，并支持 [setInteractiveTilt] 实时按压流光；
 * - 作为遮罩时支持 RippleDrawable 精确剪裁水波纹。
 */
class BubbleDrawable(
    private var baseColor: Int,
    private var strokeColor: Int = Color.TRANSPARENT,
    private var strokeWidthPx: Float = 0f,
    private var opacityPercent: Int = 100,
    private val isMask: Boolean = false,
    private var dark: Boolean = true,
    private var glassTint: Int = baseColor,
    private var density: Float = 1f,
) : Drawable() {

    private var clampedOpacity = opacityPercent.coerceIn(20, 100)
    private var glassFactor = if (isMask) 0f else computeLiquidGlassFactor(clampedOpacity)

    private var alphaMod = 255
    private var tiltNormX = 0f
    private var tiltNormY = 0f
    private var shadersDirty = true
    private var pathsDirty = true
    private var currentColorFilter: ColorFilter? = null
    internal var cachedRippleColor: Int = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var strokePaint: Paint? = null
    // 液态玻璃专属画笔与路径：仅在 glassFactor > 0.02f 时按需创建，Material You 模式零分配
    private var causticPaint: Paint? = null
    private var bevelPaint: Paint? = null

    private val fillPath = Path()
    private var bevelPath: Path? = null

    private fun ensureStrokePaint(): Paint =
        strokePaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            colorFilter = currentColorFilter
        }.also { strokePaint = it }

    private fun ensureCausticPaint(): Paint =
        causticPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            colorFilter = currentColorFilter
        }.also { causticPaint = it }

    private fun ensureBevelPaint(): Paint =
        bevelPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            colorFilter = currentColorFilter
        }.also { bevelPaint = it }

    /** 原地更新颜色、描边、不透明度与深浅主题，复用现有 Drawable/Path 实例，零视图背景重建开销。 */
    fun updateStyle(
        baseColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float,
        opacityPercent: Int,
        dark: Boolean,
        glassTint: Int,
        density: Float,
    ) {
        if (isMask) return
        val newClamped = opacityPercent.coerceIn(20, 100)
        val newGlass = computeLiquidGlassFactor(newClamped)
        if (this.baseColor == baseColor &&
            this.strokeColor == strokeColor &&
            this.strokeWidthPx == strokeWidthPx &&
            this.clampedOpacity == newClamped &&
            this.dark == dark &&
            this.glassTint == glassTint &&
            this.density == density
        ) {
            return
        }
        val pathGeometryChanged = this.strokeWidthPx != strokeWidthPx ||
            this.density != density ||
            abs(this.glassFactor - newGlass) > 0.001f

        this.baseColor = baseColor
        this.strokeColor = strokeColor
        this.strokeWidthPx = strokeWidthPx
        this.opacityPercent = opacityPercent
        this.clampedOpacity = newClamped
        this.glassFactor = newGlass
        this.dark = dark
        this.glassTint = glassTint
        this.density = density
        if (pathGeometryChanged) {
            pathsDirty = true
        }
        shadersDirty = true
        invalidateSelf()
    }

    /** 接收 PressFeedback 的归一化 3D 按压倾斜坐标，驱动液态玻璃高光实时跟手偏移。 */
    fun setInteractiveTilt(normX: Float, normY: Float) {
        if (isMask || glassFactor <= 0.01f) return
        val cx = normX.coerceIn(-1f, 1f)
        val cy = normY.coerceIn(-1f, 1f)
        val resettingToZero = cx == 0f && cy == 0f && (tiltNormX != 0f || tiltNormY != 0f)
        if (!resettingToZero && abs(cx - tiltNormX) < 0.008f && abs(cy - tiltNormY) < 0.008f) {
            return
        }
        tiltNormX = cx
        tiltNormY = cy
        shadersDirty = true
        invalidateSelf()
    }

    private fun effectiveStrokeWidthPx(): Float =
        if (strokeWidthPx > 0f) strokeWidthPx * (1f - 0.12f * glassFactor) else 0f

    private fun rebuildPaths(bounds: Rect) {
        pathsDirty = false
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            fillPath.reset()
            bevelPath?.reset()
            return
        }

        if (isMask) {
            buildBubblePath(fillPath, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            bevelPath = null
        } else {
            val outerStrokePx = effectiveStrokeWidthPx()
            val halfStroke = if (outerStrokePx > 0f) outerStrokePx / 2f else 0f
            buildBubblePath(
                fillPath,
                bounds.left + halfStroke,
                bounds.top + halfStroke,
                bounds.right - halfStroke,
                bounds.bottom - halfStroke,
            )

            val safeDensity = maxOf(density, 1f)
            val bevelWidthPx = 0.75f * safeDensity
            val bevelInset = outerStrokePx + bevelWidthPx * 0.5f
            if (glassFactor > 0.02f && w > bevelInset * 2.5f && h > bevelInset * 2.5f) {
                val bp = bevelPath ?: Path().also { bevelPath = it }
                buildBubblePath(
                    bp,
                    bounds.left + bevelInset,
                    bounds.top + bevelInset,
                    bounds.right - bevelInset,
                    bounds.bottom - bevelInset,
                )
            } else {
                bevelPath = null
            }
        }
    }

    private fun rebuildShaders() {
        shadersDirty = false
        if (isMask) {
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            return
        }

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        val r = bounds.right.toFloat()
        val btm = bounds.bottom.toFloat()

        val alphaScale = alphaMod / 255f
        val baseAlpha = (255f * (clampedOpacity / 100f) * alphaScale).roundToInt().coerceIn(0, 255)
        val highlightScale = computeGlassHighlightScale(clampedOpacity)
        computeSpecularShiftInto(tempShift, tiltNormX, tiltNormY)
        val shiftXRatio = tempShift[0]
        val shiftYRatio = tempShift[1]
        val shiftBoost = tempShift[2]

        if (w <= 0f || h <= 0f) {
            fillPaint.shader = null
            fillPaint.color = replaceAlpha(baseColor, baseAlpha)
            return
        }

        // 1. 基底图层（Material You 容器表面 ⇄ 液态玻璃通透斜向基底）
        if (glassFactor <= 0.01f) {
            val topC = replaceAlpha(lightenColor(baseColor, 0.05f), baseAlpha)
            val botC = replaceAlpha(baseColor, baseAlpha)
            fillPaint.color = Color.WHITE
            fillPaint.shader = LinearGradient(
                0f, t, 0f, btm,
                topC, botC,
                Shader.TileMode.CLAMP,
            )
            causticPaint = null
            bevelPaint = null
            bevelPath = null
        } else {
            val topRgb = lerpArgb(
                lerpArgb(baseColor, glassTint, 0.16f * glassFactor),
                Color.WHITE,
                (if (dark) 0.10f else 0.34f) * glassFactor,
            )
            val topAlpha = (baseAlpha * (1f + (0.08f + shiftBoost * 0.35f) * glassFactor))
                .roundToInt().coerceIn(0, 255)

            val midRgb = lerpArgb(baseColor, glassTint, 0.08f * glassFactor)
            val midAlpha = (baseAlpha * (1f - 0.14f * glassFactor)).roundToInt().coerceIn(0, 255)

            val botRgb = lerpArgb(baseColor, glassTint, 0.20f * glassFactor)
            val botAlpha = (baseAlpha * (1f + 0.02f * glassFactor)).roundToInt().coerceIn(0, 255)

            colors3[0] = replaceAlpha(topRgb, topAlpha)
            colors3[1] = replaceAlpha(midRgb, midAlpha)
            colors3[2] = replaceAlpha(botRgb, botAlpha)
            fillPaint.color = Color.WHITE
            fillPaint.shader = LinearGradient(
                l + w * (0.05f + shiftXRatio * 0.5f),
                t + h * (0.05f + shiftYRatio * 0.5f),
                l + w * (0.95f + shiftXRatio * 0.5f),
                t + h * (0.95f + shiftYRatio * 0.5f),
                colors3,
                posGlassFill,
                Shader.TileMode.CLAMP,
            )

            // 2. 液态内部轻焦散光斑图层
            if (glassFactor > 0.02f) {
                val cPaint = ensureCausticPaint()
                val cx = l + w * (0.28f + shiftXRatio)
                val cy = t + h * (0.24f + shiftYRatio)
                val causticRadius = maxOf(w, h) * 0.68f
                val causticRgb = lerpArgb(Color.WHITE, glassTint, if (dark) 0.25f else 0.15f)
                val causticAlpha = ((if (dark) 26f else 44f) * glassFactor * highlightScale * (1f + shiftBoost * 1.5f) * alphaScale)
                    .roundToInt().coerceIn(0, 255)

                colors3[0] = replaceAlpha(causticRgb, causticAlpha)
                colors3[1] = replaceAlpha(causticRgb, (causticAlpha * 0.30f).roundToInt())
                colors3[2] = replaceAlpha(causticRgb, 0)
                cPaint.color = Color.WHITE
                cPaint.shader = RadialGradient(
                    cx, cy, causticRadius.coerceAtLeast(1f),
                    colors3,
                    posCaustic,
                    Shader.TileMode.CLAMP,
                )

                // 3. 液态表面张力轻内高光弧（极细柔和反光，不与外描边叠成厚亮边）
                val bPaint = ensureBevelPaint()
                val bevelWidthPx = 0.75f * maxOf(density, 1f)
                bPaint.strokeWidth = bevelWidthPx
                val topHighlightAlpha = ((if (dark) 42f else 68f) * glassFactor * highlightScale * (1f + shiftBoost * 1.15f) * alphaScale)
                    .roundToInt().coerceIn(0, 255)
                val bottomBounceColor = if (dark) {
                    replaceAlpha(glassTint, (16f * glassFactor * highlightScale * alphaScale).roundToInt())
                } else {
                    val shadowRgb = lerpArgb(0xFF141413.toInt(), glassTint, 0.35f)
                    replaceAlpha(shadowRgb, (20f * glassFactor * highlightScale * alphaScale).roundToInt())
                }

                colors4[0] = replaceAlpha(Color.WHITE, topHighlightAlpha)
                colors4[1] = 0x00FFFFFF
                colors4[2] = 0x00000000
                colors4[3] = bottomBounceColor
                bPaint.color = Color.WHITE
                bPaint.shader = LinearGradient(
                    l + w * (0.35f + shiftXRatio * 0.4f),
                    t,
                    l + w * (0.65f - shiftXRatio * 0.4f),
                    btm,
                    colors4,
                    posBevel,
                    Shader.TileMode.CLAMP,
                )
            } else {
                causticPaint = null
                bevelPaint = null
                bevelPath = null
            }
        }

        // 4. 外轮廓描边图层（Material You 微描边 ⇄ 轻盈 135° 菲涅尔折光细边）
        val outerStrokePx = effectiveStrokeWidthPx()
        if (outerStrokePx > 0f) {
            val sPaint = ensureStrokePaint()
            sPaint.strokeWidth = outerStrokePx
            if (glassFactor <= 0.01f) {
                sPaint.shader = null
                val strokeAlpha = (Color.alpha(strokeColor) * (clampedOpacity / 100f) * alphaScale)
                    .roundToInt().coerceIn(0, 255)
                sPaint.color = replaceAlpha(strokeColor, strokeAlpha)
            } else {
                val rimAlphaBase = computeGlassRimAlpha(clampedOpacity, glassFactor)
                val primaryRimAlpha = (rimAlphaBase * (1f + shiftBoost) * alphaScale)
                    .roundToInt().coerceIn(0, 255)

                colors4[0] = replaceAlpha(
                    lerpArgb(strokeColor, Color.WHITE, 0.82f * glassFactor),
                    primaryRimAlpha,
                )
                colors4[1] = replaceAlpha(
                    lerpArgb(strokeColor, Color.WHITE, (if (dark) 0.45f else 0.25f) * glassFactor),
                    (primaryRimAlpha * (0.18f + 0.82f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
                )
                colors4[2] = replaceAlpha(
                    lerpArgb(strokeColor, glassTint, 0.35f * glassFactor),
                    (primaryRimAlpha * (0.10f + 0.90f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
                )
                val stop3Rgb = lerpArgb(strokeColor, lerpArgb(glassTint, Color.WHITE, 0.50f), 0.78f * glassFactor)
                colors4[3] = replaceAlpha(
                    stop3Rgb,
                    (primaryRimAlpha * (0.38f + 0.62f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
                )

                sPaint.color = Color.WHITE
                sPaint.shader = LinearGradient(
                    l + w * shiftXRatio * 0.4f,
                    t + h * shiftYRatio * 0.4f,
                    r + w * shiftXRatio * 0.4f,
                    btm + h * shiftYRatio * 0.4f,
                    colors4,
                    posRim,
                    Shader.TileMode.CLAMP,
                )
            }
        } else {
            strokePaint = null
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPaths(bounds)
        if (!isMask) {
            shadersDirty = true
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        if (pathsDirty || fillPath.isEmpty) {
            rebuildPaths(bounds)
        }
        if (shadersDirty) {
            rebuildShaders()
        }
        canvas.drawPath(fillPath, fillPaint)
        if (!isMask && glassFactor > 0.02f) {
            causticPaint?.let { canvas.drawPath(fillPath, it) }
            val bp = bevelPath
            val bPaint = bevelPaint
            if (bp != null && bPaint != null && !bp.isEmpty) {
                canvas.drawPath(bp, bPaint)
            }
        }
        if (!isMask && !fillPath.isEmpty && effectiveStrokeWidthPx() > 0f) {
            strokePaint?.let { canvas.drawPath(fillPath, it) }
        }
    }

    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        if (alphaMod != clamped) {
            alphaMod = clamped
            shadersDirty = true
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        currentColorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        causticPaint?.colorFilter = colorFilter
        bevelPaint?.colorFilter = colorFilter
        strokePaint?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if ((pathsDirty || fillPath.isEmpty) && bounds.width() > 0 && bounds.height() > 0) {
            rebuildPaths(bounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(fillPath)
        } else {
            @Suppress("DEPRECATION")
            if (fillPath.isConvex) {
                outline.setConvexPath(fillPath)
            } else {
                val radius = minOf(bounds.width(), bounds.height()) / 2f
                outline.setRoundRect(bounds, radius)
            }
        }
    }

    private companion object {
        // 仅在主线程 draw() 同步重建着色器时使用，所有按钮实例共享一份数组，消除每键重复分配
        val colors3 = IntArray(3)
        val colors4 = IntArray(4)
        val tempShift = FloatArray(3)
        val posGlassFill = floatArrayOf(0f, 0.48f, 1f)
        val posCaustic = floatArrayOf(0f, 0.40f, 1f)
        val posBevel = floatArrayOf(0f, 0.20f, 0.78f, 1f)
        val posRim = floatArrayOf(0f, 0.36f, 0.68f, 1f)
    }
}
