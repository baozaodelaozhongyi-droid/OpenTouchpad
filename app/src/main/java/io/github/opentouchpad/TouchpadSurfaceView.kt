package io.github.opentouchpad

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.roundToInt

/**
 * 触控板双模态材质表面：
 * - 高不透明度（80%–100%）：呈现干净柔和的 Material You 动态色调表面；
 * - 中低不透明度（20%–60%）：呈现 Apple 液态玻璃（焦散透光基底、内部聚光、表面张力内高光弧、135° 菲涅尔折光描边）；
 * - 60%–80% 之间随透明度滑块平滑过渡；
 * - 完整保留自定义触控板底图（Center-Crop + 焦点定位）与 3D 按压倾斜实时流光。
 */
class TouchpadSurfaceView(context: Context) : View(context) {

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val boundsRect = RectF()
    private val bevelRect = RectF()
    private val clipPath = Path()
    private val bgMatrix = Matrix()

    var customBackgroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var bgFocusXPercent: Int = 50
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var bgFocusYPercent: Int = 50
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var baseColor: Int = 0xFF2A2A28.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var strokeColor: Int = 0x26FAF9F5
        set(value) {
            field = value
            invalidate()
        }

    var glassTintColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var isDarkTheme: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var opacityPercent: Int = 100
        set(value) {
            field = value
            invalidate()
        }

    // 动态 3D 倾斜光影参数
    var tiltNormX: Float = 0f
        private set
    var tiltNormY: Float = 0f
        private set
    var tiltIntensity: Float = 0f
        private set

    private var tiltAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    fun setTilt(normX: Float, normY: Float, intensity: Float) {
        tiltAnimator?.cancel()
        tiltNormX = normX.coerceIn(-1f, 1f)
        tiltNormY = normY.coerceIn(-1f, 1f)
        tiltIntensity = intensity.coerceIn(0f, 1f)
        invalidate()
    }

    fun animateTilt(
        targetNormX: Float,
        targetNormY: Float,
        targetIntensity: Float,
        durationMs: Long,
        interpolator: TimeInterpolator = PressFeedback.decelerate,
    ) {
        tiltAnimator?.cancel()
        val startX = tiltNormX
        val startY = tiltNormY
        val startI = tiltIntensity
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { va ->
                val f = va.animatedFraction
                tiltNormX = startX + (targetNormX - startX) * f
                tiltNormY = startY + (targetNormY - startY) * f
                tiltIntensity = startI + (targetIntensity - startI) * f
                invalidate()
            }
        }
        tiltAnimator = anim
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        boundsRect.set(0f, 0f, w, h)
        val r = cornerRadiusPx.coerceIn(0f, minOf(w, h) / 2f)

        clipPath.reset()
        clipPath.addRoundRect(boundsRect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val clampedOpacity = opacityPercent.coerceIn(20, 100)
        val glassFactor = computeLiquidGlassFactor(clampedOpacity)
        val highlightScale = computeGlassHighlightScale(clampedOpacity)
        val effectiveTiltX = (tiltNormX * tiltIntensity).coerceIn(-1f, 1f)
        val effectiveTiltY = (tiltNormY * tiltIntensity).coerceIn(-1f, 1f)
        val shift = computeSpecularShift(effectiveTiltX, effectiveTiltY)
        val tint = if (glassTintColor != 0) glassTintColor else baseColor

        // 1. 底色渲染：优先使用用户自定义背景图片（Center-Crop 填充），未设置时渲染 Material You / 液态玻璃双模态表面
        val fillAlpha = (255 * clampedOpacity / 100).coerceIn(0, 255)
        val bgBmp = customBackgroundBitmap
        val hasCustomBg = bgBmp != null && !bgBmp.isRecycled
        if (hasCustomBg) {
            val srcW = bgBmp!!.width.toFloat()
            val srcH = bgBmp.height.toFloat()
            if (srcW > 0f && srcH > 0f) {
                val scale = maxOf(w / srcW, h / srcH)
                val destW = srcW * scale
                val destH = srcH * scale
                val dx = calculateCropOffset(w, destW, bgFocusXPercent)
                val dy = calculateCropOffset(h, destH, bgFocusYPercent)
                bgMatrix.reset()
                bgMatrix.postScale(scale, scale)
                bgMatrix.postTranslate(dx, dy)
                bitmapPaint.alpha = fillAlpha
                canvas.drawBitmap(bgBmp, bgMatrix, bitmapPaint)
            }
        } else if (glassFactor <= 0.01f) {
            // Material You 模式（80%–100%）：沉稳柔和的动态容器表面
            val topC = replaceAlpha(lightenColor(baseColor, 0.04f), fillAlpha)
            val midC = replaceAlpha(baseColor, fillAlpha)
            val botC = replaceAlpha(darkenColor(baseColor, 0.03f), fillAlpha)
            surfacePaint.color = Color.WHITE
            surfacePaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(topC, midC, botC),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(boundsRect, surfacePaint)
            surfacePaint.shader = null
        } else {
            // Apple 液态玻璃模式（20%–60%，及 60%–80% 平滑过渡）：135° 斜向通透焦散基底
            val topRgb = lerpArgb(
                lerpArgb(baseColor, tint, 0.16f * glassFactor),
                Color.WHITE,
                (if (isDarkTheme) 0.10f else 0.34f) * glassFactor,
            )
            val topAlpha = (fillAlpha * (1f + (0.08f + shift.boost * 0.35f) * glassFactor))
                .roundToInt().coerceIn(0, 255)

            val midRgb = lerpArgb(baseColor, tint, 0.08f * glassFactor)
            val midAlpha = (fillAlpha * (1f - 0.14f * glassFactor)).roundToInt().coerceIn(0, 255)

            val botRgb = lerpArgb(baseColor, tint, 0.20f * glassFactor)
            val botAlpha = (fillAlpha * (1f + 0.02f * glassFactor)).roundToInt().coerceIn(0, 255)

            surfacePaint.color = Color.WHITE
            surfacePaint.shader = LinearGradient(
                w * (0.05f + shift.shiftXRatio * 0.5f),
                h * (0.05f + shift.shiftYRatio * 0.5f),
                w * (0.95f + shift.shiftXRatio * 0.5f),
                h * (0.95f + shift.shiftYRatio * 0.5f),
                intArrayOf(
                    replaceAlpha(topRgb, topAlpha),
                    replaceAlpha(midRgb, midAlpha),
                    replaceAlpha(botRgb, botAlpha),
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(boundsRect, surfacePaint)
            surfacePaint.shader = null
        }

        // 2. 液态内部轻焦散光斑（玻璃模式）或极轻哑光顶边（Material You 模式）
        if (glassFactor > 0.02f) {
            val cx = w * (0.28f + shift.shiftXRatio)
            val cy = h * (0.24f + shift.shiftYRatio)
            val causticRadius = maxOf(w, h) * 0.68f
            val causticRgb = lerpArgb(Color.WHITE, tint, if (isDarkTheme) 0.25f else 0.15f)
            val customBgDamp = if (hasCustomBg) 0.45f else 1f
            val causticAlpha = ((if (isDarkTheme) 26f else 44f) * glassFactor * highlightScale * customBgDamp * (1f + shift.boost * 1.5f))
                .roundToInt().coerceIn(0, 255)
            lightingPaint.color = Color.WHITE
            lightingPaint.shader = RadialGradient(
                cx, cy, causticRadius.coerceAtLeast(1f),
                intArrayOf(
                    replaceAlpha(causticRgb, causticAlpha),
                    replaceAlpha(causticRgb, (causticAlpha * 0.30f).roundToInt()),
                    replaceAlpha(causticRgb, 0),
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(boundsRect, lightingPaint)
            lightingPaint.shader = null
        } else if (!hasCustomBg) {
            val restTopAlpha = (14 * clampedOpacity / 100).coerceIn(0, 255)
            val restBottomAlpha = (10 * clampedOpacity / 100).coerceIn(0, 255)
            lightingPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.argb(restTopAlpha, 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.argb(restBottomAlpha, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.38f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(boundsRect, lightingPaint)
            lightingPaint.shader = null
        }

        // 3. 动态 3D 按压倾斜光影：随手指触控方向产生明暗/折射流光
        val tiltLen = kotlin.math.hypot(tiltNormX, tiltNormY)
        if (tiltIntensity > 0.01f && tiltLen > 0.01f) {
            val cx = w / 2f
            val cy = h / 2f
            val sinkX = cx + cx * tiltNormX
            val sinkY = cy + cy * tiltNormY
            val raisedX = cx - cx * tiltNormX
            val raisedY = cy - cy * tiltNormY

            val highAlpha = ((60f + 40f * glassFactor) * tiltIntensity * clampedOpacity / 100f)
                .roundToInt().coerceIn(0, 255)
            val shadowAlpha = ((80f - 25f * glassFactor) * tiltIntensity * clampedOpacity / 100f)
                .roundToInt().coerceIn(0, 255)

            lightingPaint.shader = LinearGradient(
                raisedX, raisedY, sinkX, sinkY,
                intArrayOf(
                    Color.argb(highAlpha, 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.argb(shadowAlpha, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(boundsRect, lightingPaint)
            lightingPaint.shader = null
        }

        // 4. 液态表面张力轻内高光弧（仅在液态玻璃过渡或激活区绘制，柔和不抢眼）
        val strokeWidth = (1.0f - 0.12f * glassFactor) * density
        val bevelWidth = 0.75f * density
        val bevelInset = strokeWidth + bevelWidth * 0.5f
        if (glassFactor > 0.02f && w > bevelInset * 2.5f && h > bevelInset * 2.5f) {
            bevelPaint.strokeWidth = bevelWidth
            val topHighlightAlpha = ((if (isDarkTheme) 42f else 68f) * glassFactor * highlightScale * (1f + shift.boost * 1.15f))
                .roundToInt().coerceIn(0, 255)
            val bottomBounceColor = if (isDarkTheme) {
                replaceAlpha(tint, (16f * glassFactor * highlightScale).roundToInt())
            } else {
                val shadowRgb = lerpArgb(0xFF141413.toInt(), tint, 0.35f)
                replaceAlpha(shadowRgb, (20f * glassFactor * highlightScale).roundToInt())
            }
            bevelPaint.color = Color.WHITE
            bevelPaint.shader = LinearGradient(
                w * (0.35f + shift.shiftXRatio * 0.4f),
                0f,
                w * (0.65f - shift.shiftXRatio * 0.4f),
                h,
                intArrayOf(
                    replaceAlpha(Color.WHITE, topHighlightAlpha),
                    0x00FFFFFF,
                    0x00000000,
                    bottomBounceColor,
                ),
                floatArrayOf(0f, 0.20f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
            bevelRect.set(bevelInset, bevelInset, w - bevelInset, h - bevelInset)
            val bevelRadius = (r - bevelInset).coerceAtLeast(0f)
            canvas.drawRoundRect(bevelRect, bevelRadius, bevelRadius, bevelPaint)
            bevelPaint.shader = null
        }

        canvas.restore()

        // 5. 外轮廓描边（Material You 微描边 ⇄ 轻盈 135° 菲涅尔折光细边）
        strokePaint.strokeWidth = strokeWidth
        if (glassFactor <= 0.01f) {
            strokePaint.shader = null
            val strokeAlpha = (Color.alpha(strokeColor) * clampedOpacity / 100).coerceIn(0, 255)
            strokePaint.color = replaceAlpha(strokeColor, strokeAlpha)
        } else {
            val rimAlphaBase = computeGlassRimAlpha(clampedOpacity, glassFactor)
            val primaryRimAlpha = (rimAlphaBase * (1f + shift.boost)).roundToInt().coerceIn(0, 255)

            val stop0Color = replaceAlpha(
                lerpArgb(strokeColor, Color.WHITE, 0.82f * glassFactor),
                primaryRimAlpha,
            )
            val stop1Color = replaceAlpha(
                lerpArgb(strokeColor, Color.WHITE, (if (isDarkTheme) 0.45f else 0.25f) * glassFactor),
                (primaryRimAlpha * (0.18f + 0.82f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
            )
            val stop2Color = replaceAlpha(
                lerpArgb(strokeColor, tint, 0.35f * glassFactor),
                (primaryRimAlpha * (0.10f + 0.90f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
            )
            val stop3Rgb = lerpArgb(strokeColor, lerpArgb(tint, Color.WHITE, 0.50f), 0.78f * glassFactor)
            val stop3Color = replaceAlpha(
                stop3Rgb,
                (primaryRimAlpha * (0.38f + 0.62f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
            )

            strokePaint.color = Color.WHITE
            strokePaint.shader = LinearGradient(
                w * shift.shiftXRatio * 0.4f,
                h * shift.shiftYRatio * 0.4f,
                w * (1f + shift.shiftXRatio * 0.4f),
                h * (1f + shift.shiftYRatio * 0.4f),
                intArrayOf(stop0Color, stop1Color, stop2Color, stop3Color),
                floatArrayOf(0f, 0.36f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        val halfStroke = strokeWidth / 2f
        boundsRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        val strokeRadius = (r - halfStroke).coerceAtLeast(0f)
        canvas.drawRoundRect(boundsRect, strokeRadius, strokeRadius, strokePaint)
        strokePaint.shader = null
    }
}
