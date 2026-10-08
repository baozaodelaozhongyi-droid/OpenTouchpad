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

        // 1. 基础填充（应用不透明度）
        val fillAlpha = (255 * opacityPercent / 100).coerceIn(0, 255)
        surfacePaint.color = Color.argb(fillAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        canvas.drawRect(boundsRect, surfacePaint)

        // 2. 静态常态微光照：上沿微亮，下沿微深，赋予表面自然实体厚度
        val restTopAlpha = (24 * opacityPercent / 100).coerceIn(0, 255)
        val restBottomAlpha = (28 * opacityPercent / 100).coerceIn(0, 255)
        val restShader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(restTopAlpha, 255, 255, 255),
            Color.argb(restBottomAlpha, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        lightingPaint.shader = restShader
        canvas.drawRect(boundsRect, lightingPaint)

        // 3. 动态 3D 按压倾斜光影：按下凹陷端受阴影，翘起翘起端迎光高光
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

            val highAlpha = (85 * tiltIntensity * opacityPercent / 100).toInt().coerceIn(0, 255)
            val shadowAlpha = (115 * tiltIntensity * opacityPercent / 100).toInt().coerceIn(0, 255)

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

        // 4. 倒角内微光立体边缘（Top Bevel 微亮，Bottom Bevel 微暗）
        val bevelHighlightAlpha = (40 * opacityPercent / 100).coerceIn(0, 255)
        val bevelShadowAlpha = (50 * opacityPercent / 100).coerceIn(0, 255)
        val bevelShader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(bevelHighlightAlpha, 255, 255, 255),
            Color.argb(bevelShadowAlpha, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        lightingPaint.shader = bevelShader
        canvas.drawRect(boundsRect, lightingPaint)

        canvas.restore()

        // 5. 外部立体精细描边
        val strokeWidth = density * 1f
        strokePaint.strokeWidth = strokeWidth
        val strokeAlpha = ((Color.alpha(strokeColor) * opacityPercent) / 100).coerceIn(0, 255)
        strokePaint.color = Color.argb(strokeAlpha, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))

        val halfStroke = strokeWidth / 2f
        boundsRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        val strokeRadius = (r - halfStroke).coerceAtLeast(0f)
        canvas.drawRoundRect(boundsRect, strokeRadius, strokeRadius, strokePaint)
    }
}
