package io.github.opentouchpad

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 具有拟真 3D 机械质感的触控板表面。
 * - 结合底座凹槽呈现真实深度；
 * - 带有微妙的金属/磨砂倒角高光与环境暗角；
 * - 在手指按压倾斜时，动态沿倾斜矢量方向实时投射明暗光影，让 3D 形态形变一目了然。
 */
class TouchpadSurfaceView(context: Context) : View(context) {

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val boundsRect = RectF()
    private val clipPath = Path()

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

        // 1. 液态玻璃光学渐变底色（对角线折射：左上透光微亮，右下沉淀深邃）
        val fillAlpha = (255 * opacityPercent / 100).coerceIn(0, 255)
        val topLightColor = lightenColor(baseColor, 0.16f)
        val bottomDepthColor = darkenColor(baseColor, 0.08f)
        val glassBaseShader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.argb(fillAlpha, Color.red(topLightColor), Color.green(topLightColor), Color.blue(topLightColor)),
                Color.argb(fillAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                Color.argb(fillAlpha, Color.red(bottomDepthColor), Color.green(bottomDepthColor), Color.blue(bottomDepthColor)),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        surfacePaint.shader = glassBaseShader
        canvas.drawRect(boundsRect, surfacePaint)
        surfacePaint.shader = null

        // 2. 毛玻璃漫反射透光层（顶部微光漫射与立体透光）
        val restTopAlpha = (36 * opacityPercent / 100).coerceIn(0, 255)
        val restMidAlpha = (12 * opacityPercent / 100).coerceIn(0, 255)
        val restBottomAlpha = (26 * opacityPercent / 100).coerceIn(0, 255)
        val restShader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(restTopAlpha, 255, 255, 255),
                Color.argb(restMidAlpha, 255, 255, 255),
                Color.argb(restBottomAlpha, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        lightingPaint.shader = restShader
        canvas.drawRect(boundsRect, lightingPaint)

        // 3. 动态 3D 按压倾斜透镜光影：随手指触控方向产生流光折射
        val tiltLen = kotlin.math.hypot(tiltNormX, tiltNormY)
        if (tiltIntensity > 0.01f && tiltLen > 0.01f) {
            val cx = w / 2f
            val cy = h / 2f
            // 按压点（凹陷点）
            val sinkX = cx + cx * tiltNormX
            val sinkY = cy + cy * tiltNormY
            // 相对的翘起点
            val raisedX = cx - cx * tiltNormX
            val raisedY = cy - cy * tiltNormY

            val highAlpha = (95 * tiltIntensity * opacityPercent / 100).toInt().coerceIn(0, 255)
            val shadowAlpha = (110 * tiltIntensity * opacityPercent / 100).toInt().coerceIn(0, 255)

            val tiltShader = LinearGradient(
                raisedX, raisedY, sinkX, sinkY,
                intArrayOf(
                    Color.argb(highAlpha, 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.argb(shadowAlpha, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            lightingPaint.shader = tiltShader
            canvas.drawRect(boundsRect, lightingPaint)
        }

        // 4. 顶部内边缘液态高光（模拟真实玻璃边缘内反射）
        val innerGlowAlpha = (48 * opacityPercent / 100).coerceIn(0, 255)
        val innerGlowShader = LinearGradient(
            0f, 0f, 0f, h * 0.35f,
            Color.argb(innerGlowAlpha, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        lightingPaint.shader = innerGlowShader
        canvas.drawRect(boundsRect, lightingPaint)

        canvas.restore()

        // 5. 外部液态玻璃流光高光描边（左上迎光高光，右下环境漫反射）
        val strokeWidth = density * 1.2f
        strokePaint.strokeWidth = strokeWidth
        val isLightBase = colorLuminance(baseColor) > 0.55f

        val highSpecularAlpha = if (isLightBase) {
            (190 * opacityPercent / 100).coerceIn(0, 255)
        } else {
            (160 * opacityPercent / 100).coerceIn(0, 255)
        }
        val lowSpecularAlpha = if (isLightBase) {
            (60 * opacityPercent / 100).coerceIn(0, 255)
        } else {
            (40 * opacityPercent / 100).coerceIn(0, 255)
        }

        val specularStrokeShader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.argb(highSpecularAlpha, 255, 255, 255),
                Color.argb((highSpecularAlpha + lowSpecularAlpha) / 2, 255, 255, 255),
                if (isLightBase) Color.argb(lowSpecularAlpha, 0, 0, 0) else Color.argb(lowSpecularAlpha, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        strokePaint.shader = specularStrokeShader

        val halfStroke = strokeWidth / 2f
        boundsRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        val strokeRadius = (r - halfStroke).coerceAtLeast(0f)
        canvas.drawRoundRect(boundsRect, strokeRadius, strokeRadius, strokePaint)
        strokePaint.shader = null
    }
}
