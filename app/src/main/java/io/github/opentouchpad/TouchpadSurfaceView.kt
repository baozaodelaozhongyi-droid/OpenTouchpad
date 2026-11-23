package io.github.opentouchpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.roundToInt

/**
 * 触控板双模态材质表面：
 * - 高不透明度（80%–100%）：呈现干净柔和的 Material You 动态色调表面；
 * - 中低不透明度（20%–60%）：呈现 Apple 液态玻璃（焦散透光基底、内部聚光、表面张力内高光弧、135° 菲涅尔折光描边）；
 * - 60%–80% 之间随透明度滑块平滑过渡；
 * - 完整保留自定义触控板底图（Center-Crop + 焦点定位）与 3D 按压倾斜实时流光；
 * - 全硬件 drawRoundRect + BitmapShader 渲染（零 clipPath 模板缓冲开销），按脏状态缓存 Shader 与预分配数组。
 */
class TouchpadSurfaceView(context: Context) : View(context) {

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tiltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val boundsRect = RectF()
    private val bevelRect = RectF()
    private val strokeRect = RectF()
    private val bgMatrix = Matrix()

    // 预分配渐变数组，避免绘制期频繁堆分配
    private val colors3 = IntArray(3)
    private val colors4 = IntArray(4)
    private val posMatYou = floatArrayOf(0f, 0.5f, 1f)
    private val posGlassFill = floatArrayOf(0f, 0.48f, 1f)
    private val posCaustic = floatArrayOf(0f, 0.40f, 1f)
    private val posTilt = floatArrayOf(0f, 0.45f, 1f)
    private val posBevel = floatArrayOf(0f, 0.20f, 0.78f, 1f)
    private val posRim = floatArrayOf(0f, 0.36f, 0.68f, 1f)

    private var cachedBgSource: Bitmap? = null
    private var bgBitmapShader: BitmapShader? = null
    private var bgMatrixDirty = true
    private var lastHasCustomBg = false
    private var surfaceShadersDirty = true
    private var tiltShaderDirty = true
    private var cachedW = 0f
    private var cachedH = 0f
    private var drawBevel = false
    private var drawCaustic = false
    private var drawTilt = false
    private var cachedCornerRadius = 0f
    private var cachedBevelRadius = 0f
    private var cachedStrokeRadius = 0f

    var customBackgroundBitmap: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            cachedBgSource = null
            bgBitmapShader = null
            bitmapPaint.shader = null
            bgMatrixDirty = true
            surfaceShadersDirty = true
            invalidate()
        }

    var bgFocusXPercent: Int = 50
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (field == clamped) return
            field = clamped
            bgMatrixDirty = true
            invalidate()
        }

    var bgFocusYPercent: Int = 50
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (field == clamped) return
            field = clamped
            bgMatrixDirty = true
            invalidate()
        }

    var cornerRadiusPx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            surfaceShadersDirty = true
            invalidate()
        }

    var baseColor: Int = 0xFF2A2A28.toInt()
        set(value) {
            if (field == value) return
            field = value
            surfaceShadersDirty = true
            invalidate()
        }

    var strokeColor: Int = 0x26FAF9F5
        set(value) {
            if (field == value) return
            field = value
            surfaceShadersDirty = true
            invalidate()
        }

    var glassTintColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            surfaceShadersDirty = true
            invalidate()
        }

    var isDarkTheme: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            surfaceShadersDirty = true
            invalidate()
        }

    var opacityPercent: Int = 100
        set(value) {
            if (field == value) return
            field = value
            surfaceShadersDirty = true
            tiltShaderDirty = true
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
    private var lastGlassSurfaceUpdateMs: Long = -1000L
    private var pendingGlassSurfaceSync = false
    private var trailingGlassScheduled = false

    private val trailingGlassFlushRunnable = Runnable {
        trailingGlassScheduled = false
        if (!pendingGlassSurfaceSync) return@Runnable
        pendingGlassSurfaceSync = false
        lastGlassSurfaceUpdateMs = SystemClock.uptimeMillis()
        if (computeLiquidGlassFactor(opacityPercent.coerceIn(20, 100)) > 0.01f) {
            surfaceShadersDirty = true
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
    }

    private fun markGlassSurfaceDirtyThrottled(prevIntensity: Float, newIntensity: Float) {
        if (computeLiquidGlassFactor(opacityPercent.coerceIn(20, 100)) <= 0.01f) {
            pendingGlassSurfaceSync = false
            return
        }
        val nowMs = SystemClock.uptimeMillis()
        if (shouldUpdateAmbientGlassShaders(nowMs, lastGlassSurfaceUpdateMs, prevIntensity, newIntensity, 13L)) {
            if (trailingGlassScheduled) {
                removeCallbacks(trailingGlassFlushRunnable)
                trailingGlassScheduled = false
            }
            pendingGlassSurfaceSync = false
            lastGlassSurfaceUpdateMs = nowMs
            surfaceShadersDirty = true
        } else {
            pendingGlassSurfaceSync = true
            if (!trailingGlassScheduled) {
                val delayMs = (13L - (nowMs - lastGlassSurfaceUpdateMs)).coerceIn(1L, 13L)
                trailingGlassScheduled = true
                postDelayed(trailingGlassFlushRunnable, delayMs)
            }
        }
    }

    fun setTilt(normX: Float, normY: Float, intensity: Float) {
        val nx = normX.coerceIn(-1f, 1f)
        val ny = normY.coerceIn(-1f, 1f)
        val ni = intensity.coerceIn(0f, 1f)
        if (!quantizeTiltChanged(tiltNormX, tiltNormY, tiltIntensity, nx, ny, ni)) {
            return
        }
        tiltAnimator?.cancel()
        tiltAnimator = null
        val prevIntensity = tiltIntensity
        tiltNormX = nx
        tiltNormY = ny
        tiltIntensity = ni
        tiltShaderDirty = true
        markGlassSurfaceDirtyThrottled(prevIntensity, ni)
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
                val nx = (startX + (targetNormX - startX) * f).coerceIn(-1f, 1f)
                val ny = (startY + (targetNormY - startY) * f).coerceIn(-1f, 1f)
                val ni = (startI + (targetIntensity - startI) * f).coerceIn(0f, 1f)
                if (f < 1f && !quantizeTiltChanged(tiltNormX, tiltNormY, tiltIntensity, nx, ny, ni, 0.005f)) {
                    return@addUpdateListener
                }
                val prevIntensity = tiltIntensity
                tiltNormX = nx
                tiltNormY = ny
                tiltIntensity = ni
                tiltShaderDirty = true
                markGlassSurfaceDirtyThrottled(prevIntensity, ni)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (canceled) return
                    val finalX = targetNormX.coerceIn(-1f, 1f)
                    val finalY = targetNormY.coerceIn(-1f, 1f)
                    val finalI = targetIntensity.coerceIn(0f, 1f)
                    val changed = tiltNormX != finalX || tiltNormY != finalY || tiltIntensity != finalI
                    if (changed || pendingGlassSurfaceSync) {
                        if (trailingGlassScheduled) {
                            removeCallbacks(trailingGlassFlushRunnable)
                            trailingGlassScheduled = false
                        }
                        pendingGlassSurfaceSync = false
                        lastGlassSurfaceUpdateMs = SystemClock.uptimeMillis()
                        tiltNormX = finalX
                        tiltNormY = finalY
                        tiltIntensity = finalI
                        tiltShaderDirty = true
                        if (computeLiquidGlassFactor(opacityPercent.coerceIn(20, 100)) > 0.01f) {
                            surfaceShadersDirty = true
                        }
                        invalidate()
                    }
                }
            })
        }
        tiltAnimator = anim
        anim.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedW = w.toFloat()
        cachedH = h.toFloat()
        bgMatrixDirty = true
        surfaceShadersDirty = true
        tiltShaderDirty = true
    }

    override fun onDetachedFromWindow() {
        tiltAnimator?.cancel()
        tiltAnimator = null
        if (trailingGlassScheduled) {
            removeCallbacks(trailingGlassFlushRunnable)
            trailingGlassScheduled = false
        }
        pendingGlassSurfaceSync = false
        super.onDetachedFromWindow()
    }

    private fun updateCustomBgShader(w: Float, h: Float, fillAlpha: Int): Boolean {
        val bgBmp = customBackgroundBitmap
        if (bgBmp == null || bgBmp.isRecycled) {
            bitmapPaint.shader = null
            return false
        }
        val srcW = bgBmp.width.toFloat()
        val srcH = bgBmp.height.toFloat()
        if (srcW <= 0f || srcH <= 0f) {
            bitmapPaint.shader = null
            return false
        }
        if (cachedBgSource !== bgBmp || bgBitmapShader == null) {
            cachedBgSource = bgBmp
            bgBitmapShader = BitmapShader(bgBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bitmapPaint.shader = bgBitmapShader
            bgMatrixDirty = true
        }
        if (bgMatrixDirty) {
            bgMatrixDirty = false
            val scale = maxOf(w / srcW, h / srcH)
            val destW = srcW * scale
            val destH = srcH * scale
            val dx = calculateCropOffset(w, destW, bgFocusXPercent)
            val dy = calculateCropOffset(h, destH, bgFocusYPercent)
            bgMatrix.reset()
            bgMatrix.postScale(scale, scale)
            bgMatrix.postTranslate(dx, dy)
            bgBitmapShader?.setLocalMatrix(bgMatrix)
        }
        bitmapPaint.alpha = fillAlpha
        return true
    }

    private fun rebuildSurfaceShaders(w: Float, h: Float, hasCustomBg: Boolean) {
        surfaceShadersDirty = false
        val density = resources.displayMetrics.density
        boundsRect.set(0f, 0f, w, h)
        val r = cornerRadiusPx.coerceIn(0f, minOf(w, h) / 2f)
        cachedCornerRadius = r

        val clampedOpacity = opacityPercent.coerceIn(20, 100)
        val glassFactor = computeLiquidGlassFactor(clampedOpacity)
        val highlightScale = computeGlassHighlightScale(clampedOpacity)
        val effectiveTiltX = (tiltNormX * tiltIntensity).coerceIn(-1f, 1f)
        val effectiveTiltY = (tiltNormY * tiltIntensity).coerceIn(-1f, 1f)
        val shift = computeSpecularShift(effectiveTiltX, effectiveTiltY)
        val tint = if (glassTintColor != 0) glassTintColor else baseColor
        val fillAlpha = (255 * clampedOpacity / 100).coerceIn(0, 255)

        // 1. 底色材质（Material You 合并单层渐变 / 液态玻璃 135° 斜向通透焦散基底）
        if (!hasCustomBg) {
            if (glassFactor <= 0.01f) {
                // Material You 模式（80%–100%）：将极轻哑光顶底光影与基底合并为单次 3-stop 渐变，减少一层 Overdraw
                val topBase = lightenColor(baseColor, 0.04f)
                val topC = replaceAlpha(lerpArgb(topBase, Color.WHITE, 14f / 255f), fillAlpha)
                val midC = replaceAlpha(baseColor, fillAlpha)
                val botBase = darkenColor(baseColor, 0.03f)
                val botC = replaceAlpha(lerpArgb(botBase, Color.BLACK, 10f / 255f), fillAlpha)
                colors3[0] = topC
                colors3[1] = midC
                colors3[2] = botC
                surfacePaint.color = Color.WHITE
                surfacePaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    colors3,
                    posMatYou,
                    Shader.TileMode.CLAMP,
                )
            } else {
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

                colors3[0] = replaceAlpha(topRgb, topAlpha)
                colors3[1] = replaceAlpha(midRgb, midAlpha)
                colors3[2] = replaceAlpha(botRgb, botAlpha)
                surfacePaint.color = Color.WHITE
                surfacePaint.shader = LinearGradient(
                    w * (0.05f + shift.shiftXRatio * 0.5f),
                    h * (0.05f + shift.shiftYRatio * 0.5f),
                    w * (0.95f + shift.shiftXRatio * 0.5f),
                    h * (0.95f + shift.shiftYRatio * 0.5f),
                    colors3,
                    posGlassFill,
                    Shader.TileMode.CLAMP,
                )
            }
        } else {
            surfacePaint.shader = null
        }

        // 2. 液态内部轻焦散光斑（仅液态玻璃区间绘制）
        if (glassFactor > 0.02f) {
            drawCaustic = true
            val cx = w * (0.28f + shift.shiftXRatio)
            val cy = h * (0.24f + shift.shiftYRatio)
            val causticRadius = maxOf(w, h) * 0.68f
            val causticRgb = lerpArgb(Color.WHITE, tint, if (isDarkTheme) 0.25f else 0.15f)
            val customBgDamp = if (hasCustomBg) 0.45f else 1f
            val causticAlpha = ((if (isDarkTheme) 26f else 44f) * glassFactor * highlightScale * customBgDamp * (1f + shift.boost * 1.5f))
                .roundToInt().coerceIn(0, 255)
            colors3[0] = replaceAlpha(causticRgb, causticAlpha)
            colors3[1] = replaceAlpha(causticRgb, (causticAlpha * 0.30f).roundToInt())
            colors3[2] = replaceAlpha(causticRgb, 0)
            causticPaint.color = Color.WHITE
            causticPaint.shader = RadialGradient(
                cx, cy, causticRadius.coerceAtLeast(1f),
                colors3,
                posCaustic,
                Shader.TileMode.CLAMP,
            )
        } else {
            drawCaustic = false
            causticPaint.shader = null
        }

        // 3. 液态表面张力轻内高光弧
        val strokeWidth = (1.0f - 0.12f * glassFactor) * density
        val bevelWidth = 0.75f * density
        val bevelInset = strokeWidth + bevelWidth * 0.5f
        if (glassFactor > 0.02f && w > bevelInset * 2.5f && h > bevelInset * 2.5f) {
            drawBevel = true
            bevelPaint.strokeWidth = bevelWidth
            val topHighlightAlpha = ((if (isDarkTheme) 42f else 68f) * glassFactor * highlightScale * (1f + shift.boost * 1.15f))
                .roundToInt().coerceIn(0, 255)
            val bottomBounceColor = if (isDarkTheme) {
                replaceAlpha(tint, (16f * glassFactor * highlightScale).roundToInt())
            } else {
                val shadowRgb = lerpArgb(0xFF141413.toInt(), tint, 0.35f)
                replaceAlpha(shadowRgb, (20f * glassFactor * highlightScale).roundToInt())
            }
            colors4[0] = replaceAlpha(Color.WHITE, topHighlightAlpha)
            colors4[1] = 0x00FFFFFF
            colors4[2] = 0x00000000
            colors4[3] = bottomBounceColor
            bevelPaint.color = Color.WHITE
            bevelPaint.shader = LinearGradient(
                w * (0.35f + shift.shiftXRatio * 0.4f),
                0f,
                w * (0.65f - shift.shiftXRatio * 0.4f),
                h,
                colors4,
                posBevel,
                Shader.TileMode.CLAMP,
            )
            bevelRect.set(bevelInset, bevelInset, w - bevelInset, h - bevelInset)
            cachedBevelRadius = (r - bevelInset).coerceAtLeast(0f)
        } else {
            drawBevel = false
            bevelPaint.shader = null
        }

        // 4. 外轮廓描边（Material You 微描边 ⇄ 轻盈 135° 菲涅尔折光细边）
        strokePaint.strokeWidth = strokeWidth
        if (glassFactor <= 0.01f) {
            strokePaint.shader = null
            val strokeAlpha = (Color.alpha(strokeColor) * clampedOpacity / 100).coerceIn(0, 255)
            strokePaint.color = replaceAlpha(strokeColor, strokeAlpha)
        } else {
            val rimAlphaBase = computeGlassRimAlpha(clampedOpacity, glassFactor)
            val primaryRimAlpha = (rimAlphaBase * (1f + shift.boost)).roundToInt().coerceIn(0, 255)

            colors4[0] = replaceAlpha(
                lerpArgb(strokeColor, Color.WHITE, 0.82f * glassFactor),
                primaryRimAlpha,
            )
            colors4[1] = replaceAlpha(
                lerpArgb(strokeColor, Color.WHITE, (if (isDarkTheme) 0.45f else 0.25f) * glassFactor),
                (primaryRimAlpha * (0.18f + 0.82f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
            )
            colors4[2] = replaceAlpha(
                lerpArgb(strokeColor, tint, 0.35f * glassFactor),
                (primaryRimAlpha * (0.10f + 0.90f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
            )
            val stop3Rgb = lerpArgb(strokeColor, lerpArgb(tint, Color.WHITE, 0.50f), 0.78f * glassFactor)
            colors4[3] = replaceAlpha(
                stop3Rgb,
                (primaryRimAlpha * (0.38f + 0.62f * (1f - glassFactor))).roundToInt().coerceIn(0, 255),
            )
            strokePaint.color = Color.WHITE
            strokePaint.shader = LinearGradient(
                w * shift.shiftXRatio * 0.4f,
                h * shift.shiftYRatio * 0.4f,
                w * (1f + shift.shiftXRatio * 0.4f),
                h * (1f + shift.shiftYRatio * 0.4f),
                colors4,
                posRim,
                Shader.TileMode.CLAMP,
            )
        }
        val halfStroke = strokeWidth / 2f
        strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        cachedStrokeRadius = (r - halfStroke).coerceAtLeast(0f)
    }

    private fun rebuildTiltShader(w: Float, h: Float) {
        tiltShaderDirty = false
        val tiltLen = kotlin.math.hypot(tiltNormX, tiltNormY)
        if (tiltIntensity <= 0.01f || tiltLen <= 0.01f) {
            drawTilt = false
            tiltPaint.shader = null
            return
        }
        drawTilt = true
        val clampedOpacity = opacityPercent.coerceIn(20, 100)
        val glassFactor = computeLiquidGlassFactor(clampedOpacity)
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

        colors3[0] = Color.argb(highAlpha, 255, 255, 255)
        colors3[1] = Color.TRANSPARENT
        colors3[2] = Color.argb(shadowAlpha, 0, 0, 0)
        tiltPaint.color = Color.WHITE
        tiltPaint.shader = LinearGradient(
            raisedX, raisedY, sinkX, sinkY,
            colors3,
            posTilt,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (w != cachedW || h != cachedH) {
            cachedW = w
            cachedH = h
            bgMatrixDirty = true
            surfaceShadersDirty = true
            tiltShaderDirty = true
        }

        val clampedOpacity = opacityPercent.coerceIn(20, 100)
        val fillAlpha = (255 * clampedOpacity / 100).coerceIn(0, 255)
        val hasCustomBg = updateCustomBgShader(w, h, fillAlpha)
        if (hasCustomBg != lastHasCustomBg) {
            lastHasCustomBg = hasCustomBg
            surfaceShadersDirty = true
        }

        if (surfaceShadersDirty) {
            rebuildSurfaceShaders(w, h, hasCustomBg)
        }
        if (tiltShaderDirty) {
            rebuildTiltShader(w, h)
        }

        val r = cachedCornerRadius
        // 1. 底色 / 自定义壁纸（全硬件圆角矩形绘制，零 clipPath 开销）
        if (hasCustomBg) {
            canvas.drawRoundRect(boundsRect, r, r, bitmapPaint)
        } else {
            canvas.drawRoundRect(boundsRect, r, r, surfacePaint)
        }

        // 2. 液态玻璃内部焦散光斑
        if (drawCaustic) {
            canvas.drawRoundRect(boundsRect, r, r, causticPaint)
        }

        // 3. 动态 3D 按压倾斜流光
        if (drawTilt) {
            canvas.drawRoundRect(boundsRect, r, r, tiltPaint)
        }

        // 4. 液态表面张力内高光弧
        if (drawBevel) {
            canvas.drawRoundRect(bevelRect, cachedBevelRadius, cachedBevelRadius, bevelPaint)
        }

        // 5. 外轮廓描边
        canvas.drawRoundRect(strokeRect, cachedStrokeRadius, cachedStrokeRadius, strokePaint)
    }
}
