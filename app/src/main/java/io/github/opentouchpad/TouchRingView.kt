package io.github.opentouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 光标点击 / 长按反馈动效视图（灵感源自 BASpark / Blue Archive UI 风格，纯 Android 硬件加速 Canvas 实现）：
 * - 点击 [tap]：
 *   1. 中心实心圆按三次缓出迅速扩散并线性淡出（附带瞬态高光内核与初段径向动态模糊羽化）；
 *   2. 双层错位旋转渐变粗细光弧（两端尖细、中段饱满）快速拉长、旋转扫过并从尾部收束，
 *      颜色从极浅高光白过渡至强调色柔光，辅以强调色外发光层及径向/旋转速度动态模糊残影；
 *   3. 4 枚旋转三角粒子火花沿随机四象限向外爆裂飞散，按指数摩擦减速并自旋消散，
 *      高速初段沿反速度方向拖出 3 级快门曝光动态模糊残影，减速后自动收拢锐利；
 * - 长按 [press] / [pressUntilRelease]：
 *   触发初段火花爆发并展开一对持续旋转的渐变光弧光环（带柔和旋转尾迹动态模糊），松手 [release] 时从尾部平滑收束淡出；
 * - 性能与 VRR/LTPO 友好：
 *   固定对象池 + 复用 Paint/Path/RectF，绘制热路径零对象分配；所有粒子与光弧结束后立即停帧并隐藏窗口。
 */
class TouchRingView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blurStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sparkBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sparkGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val sparkCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arcRect = RectF()
    private val triPath = Path()

    private var accentEndColor: Int = computeSparkRingEndColor(0xFFC96442.toInt())

    var accent: Int = 0xFFC96442.toInt()
        set(value) {
            if (field == value) return
            field = value
            accentEndColor = computeSparkRingEndColor(value)
            if (!isIdle) invalidate()
        }

    /** 中心扩散圆的最大半径（px），所有光弧半径、粒子速度与尺寸均按此自适应缩放。 */
    var maxRadius = 0f

    /** 光弧基准线宽（px）。 */
    var strokeWidthPx = 0f

    private class WaveSlot {
        var active = false
        var ox = 0f
        var oy = 0f
        var life = 0f
        var r = 0f
        var ang = 0f
        var rs = 0f
        var seg0Off = 0f
        var seg0RoundRate = 0f
        var seg1Off = 0f
        var seg1RoundRate = 0f
    }

    private class SparkSlot {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var rot = 0f
        var rs = 0f
        var size = 0f
        var alpha = 0f
    }

    private enum class HoldState { IDLE, EXPANDING, HOLDING, RETRACTING }

    private val waves = Array(MAX_WAVES) { WaveSlot() }
    private val sparks = Array(MAX_SPARKS) { SparkSlot() }
    private var activeWaveCount = 0
    private var activeSparkCount = 0

    private var holdState = HoldState.IDLE
    private var holdExpandProg = 0f
    private var holdRetractProg = 0f
    private var holdAngle = 0f
    private val autoRelease = Runnable { release() }

    private var frameLoopActive = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopActive) return
            val deltaMs = if (lastFrameNanos == 0L) {
                BASE_FRAME_MS
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(0f, MAX_DELTA_MS)
            }
            lastFrameNanos = frameTimeNanos
            stepSimulation(deltaMs)
            if (isIdle) {
                frameLoopActive = false
                lastFrameNanos = 0L
                hideWhenIdle()
                invalidate()
            } else {
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /** 当前是否完全静止（无波纹、无粒子、无长按光环），供服务安全回收空闲窗口。 */
    val isIdle: Boolean
        get() = holdState == HoldState.IDLE && activeWaveCount == 0 && activeSparkCount == 0

    init {
        background = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.INVISIBLE
    }

    private fun ensureVisible() {
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
        }
    }

    private fun hideWhenIdle() {
        if (visibility != View.INVISIBLE) {
            visibility = View.INVISIBLE
        }
    }

    private fun startFrameLoop() {
        ensureVisible()
        if (!frameLoopActive) {
            frameLoopActive = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        invalidate()
    }

    private fun stopFrameLoop() {
        if (frameLoopActive) {
            frameLoopActive = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        lastFrameNanos = 0L
    }

    /** 点击：触发中心扩散圆 + 双旋转渐变光弧 + 4 枚旋转三角粒子火花。 */
    fun tap() {
        spawnClickBurst(includeWave = true)
        startFrameLoop()
    }

    /**
     * 长按 [holdMs] 毫秒：若尚未处于按住态，先释放初段粒子爆发并展开持续旋转光弧，
     * 到达 [holdMs] 后自动从尾部收束淡出。
     */
    fun press(holdMs: Long) {
        removeCallbacks(autoRelease)
        val alreadyHolding = holdState == HoldState.EXPANDING || holdState == HoldState.HOLDING
        if (!alreadyHolding) {
            spawnClickBurst(includeWave = false)
            holdState = HoldState.EXPANDING
            holdExpandProg = 0.08f
            holdRetractProg = 0f
            holdAngle = Random.nextFloat() * TWO_PI
        }
        val duration = holdMs.coerceAtLeast(HOLD_MIN_MS)
        postDelayed(autoRelease, duration)
        startFrameLoop()
    }

    /** 展开旋转光弧并保持，直到调用 [release]（用于触控板长按滑动、拖拽锁定等）。 */
    fun pressUntilRelease() {
        removeCallbacks(autoRelease)
        spawnClickBurst(includeWave = false)
        holdState = HoldState.EXPANDING
        holdExpandProg = 0.08f
        holdRetractProg = 0f
        holdAngle = Random.nextFloat() * TWO_PI
        startFrameLoop()
    }

    /** 松开：长按旋转光弧从尾部平滑收束并淡出。 */
    fun release() {
        removeCallbacks(autoRelease)
        if (holdState == HoldState.EXPANDING || holdState == HoldState.HOLDING) {
            holdState = HoldState.RETRACTING
            holdRetractProg = 0f
            startFrameLoop()
        }
    }

    /**
     * 当悬浮窗整体平移 `(dx, dy)` 时调用（传入相反偏移 `-dx, -dy`），
     * 使已发射的点击波纹与飞散火花留在原屏幕落点，不会随光标突兀瞬移。
     * 长按旋转光环始终居中跟随光标。
     */
    fun offsetParticles(dx: Float, dy: Float) {
        if (activeWaveCount == 0 && activeSparkCount == 0) return
        if (dx == 0f && dy == 0f) return
        for (w in waves) {
            if (w.active) {
                w.ox += dx
                w.oy += dy
            }
        }
        for (s in sparks) {
            if (s.active) {
                s.x += dx
                s.y += dy
            }
        }
    }

    /** 立即清空所有粒子与状态，停帧隐藏。 */
    fun clear() {
        removeCallbacks(autoRelease)
        stopFrameLoop()
        holdState = HoldState.IDLE
        holdExpandProg = 0f
        holdRetractProg = 0f
        for (w in waves) w.active = false
        for (s in sparks) s.active = false
        activeWaveCount = 0
        activeSparkCount = 0
        hideWhenIdle()
        invalidate()
    }

    private fun spawnClickBurst(includeWave: Boolean) {
        val rMax = maxRadius.coerceAtLeast(1f)
        val unitScale = rMax / 26f
        val speedAdjust = rMax / 39f

        if (includeWave) {
            val slot = acquireWaveSlot()
            slot.active = true
            slot.ox = 0f
            slot.oy = 0f
            // 给予半帧初值，保证首帧即刻呈现清晰的高光爆发，无 1 帧空窗
            slot.life = 0.5f
            slot.r = computeSparkFilledCircleRadius(rMax, slot.life / SPARK_FILLED_MAX_LIFE)
            slot.ang = Random.nextFloat() * TWO_PI
            slot.rs = RING_SPEED_CHOICES[Random.nextInt(RING_SPEED_CHOICES.size)]
            slot.seg0Off = 0f
            slot.seg0RoundRate = RING_RADIUS_OFFSET_CHOICES[Random.nextInt(RING_RADIUS_OFFSET_CHOICES.size)]
            slot.seg1Off = (Random.nextFloat() * 3f - 1.5f) * PI_F
            slot.seg1RoundRate = RING_RADIUS_OFFSET_CHOICES[Random.nextInt(RING_RADIUS_OFFSET_CHOICES.size)]
        }

        // 4 枚三角粒子按随机扰动的四象限分布，既保留自然随机感又保证每次点击四向均衡通透
        val baseQuadAngle = Random.nextFloat() * TWO_PI
        for (i in 0 until SPARKS_PER_CLICK) {
            val slot = acquireSparkSlot()
            val angle = baseQuadAngle + i * HALF_PI + (Random.nextFloat() - 0.5f) * 0.9f
            val speed = (4.8f + Random.nextFloat() * 2.0f) * speedAdjust
            slot.active = true
            slot.x = cos(angle) * (rMax * 0.12f)
            slot.y = sin(angle) * (rMax * 0.12f)
            slot.vx = cos(angle) * speed
            slot.vy = sin(angle) * speed
            slot.rot = Random.nextFloat() * TWO_PI
            slot.rs = (Random.nextFloat() - 0.5f) * 0.28f
            slot.size = (4.0f + Random.nextFloat() * 3.0f) * unitScale
            slot.alpha = 1f
        }
    }

    private fun acquireWaveSlot(): WaveSlot {
        var oldest = waves[0]
        for (w in waves) {
            if (!w.active) {
                activeWaveCount++
                return w
            }
            if (w.life > oldest.life) oldest = w
        }
        return oldest
    }

    private fun acquireSparkSlot(): SparkSlot {
        var faintest = sparks[0]
        for (s in sparks) {
            if (!s.active) {
                activeSparkCount++
                return s
            }
            if (s.alpha < faintest.alpha) faintest = s
        }
        return faintest
    }

    private fun stepSimulation(deltaMs: Float) {
        val frameScale = deltaMs / BASE_FRAME_MS
        val rMax = maxRadius.coerceAtLeast(1f)

        // 1. 更新点击波纹与双光弧
        var wCount = 0
        for (w in waves) {
            if (!w.active) continue
            w.life += frameScale
            w.ang -= w.rs * frameScale
            val waveProg = (w.life / SPARK_FILLED_MAX_LIFE).coerceIn(0f, 1f)
            val ringProg = (w.life / SPARK_RING_MAX_LIFE).coerceIn(0f, 1f)
            w.r = computeSparkFilledCircleRadius(rMax, waveProg)
            if (ringProg >= 1f && waveProg >= 1f) {
                w.active = false
            } else {
                wCount++
            }
        }
        activeWaveCount = wCount

        // 2. 更新飞散旋转三角火花
        val friction = SPARK_FRICTION.pow(frameScale)
        var sCount = 0
        for (s in sparks) {
            if (!s.active) continue
            s.x += s.vx * frameScale
            s.y += s.vy * frameScale
            s.vx *= friction
            s.vy *= friction
            s.rot += s.rs * frameScale
            s.alpha -= SPARK_ALPHA_DECAY * frameScale
            if (s.alpha <= 0.005f) {
                s.active = false
            } else {
                sCount++
            }
        }
        activeSparkCount = sCount

        // 3. 更新长按持续旋转光环
        when (holdState) {
            HoldState.IDLE -> Unit
            HoldState.EXPANDING -> {
                holdAngle -= HOLD_ORBIT_SPEED * frameScale
                holdExpandProg += deltaMs / HOLD_EXPAND_MS
                if (holdExpandProg >= 1f) {
                    holdExpandProg = 1f
                    holdState = HoldState.HOLDING
                }
            }
            HoldState.HOLDING -> {
                holdAngle -= HOLD_ORBIT_SPEED * frameScale
            }
            HoldState.RETRACTING -> {
                holdAngle -= HOLD_ORBIT_SPEED * 1.25f * frameScale
                holdRetractProg += deltaMs / HOLD_RETRACT_MS
                if (holdRetractProg >= 1f) {
                    holdRetractProg = 1f
                    holdState = HoldState.IDLE
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isIdle || maxRadius <= 0f) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val limit = minOf(w, h) / 2f - 2f
        if (limit <= 0f) return

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val unitScale = maxRadius / 26f
        val baseStroke = if (strokeWidthPx > 0f) strokeWidthPx else 2.6f * density
        val arcMinW = (baseStroke * 0.14f).coerceAtLeast(0.6f)
        val arcMaxW = (baseStroke * 1.18f).coerceAtLeast(2f)

        // 1. 绘制点击波纹：中心扩散圆 + 双旋转渐变光弧（含径向与旋转动态模糊）
        if (activeWaveCount > 0) {
            val waveMargin = maxRadius + arcMaxW * 3f
            for (wave in waves) {
                if (!wave.active) continue
                val wx = cx + wave.ox
                val wy = cy + wave.oy
                if (wx < -waveMargin || wx > w + waveMargin || wy < -waveMargin || wy > h + waveMargin) continue
                val waveProg = (wave.life / SPARK_FILLED_MAX_LIFE).coerceIn(0f, 1f)
                val ringProg = (wave.life / SPARK_RING_MAX_LIFE).coerceIn(0f, 1f)
                val radialBlur = computeSparkRadialBlurFactor(waveProg)

                // 1a. 中心扩散实心圆 + 初段径向动态模糊羽化 + 瞬态高光内核
                val fillAlpha = (1f - waveProg).coerceIn(0f, 1f)
                val fillR = minOf(wave.r, limit)
                if (fillAlpha > 0.01f && fillR > 0.5f) {
                    if (radialBlur > 0f) {
                        val outerSw = (maxRadius * 0.24f + baseStroke) * radialBlur
                        val outerR = minOf(fillR + outerSw * 0.12f, limit - outerSw * 0.5f).coerceAtLeast(0.5f)
                        blurStrokePaint.strokeWidth = outerSw
                        blurStrokePaint.color = withAlpha(accent, fillAlpha * radialBlur * 0.16f)
                        canvas.drawCircle(wx, wy, outerR, blurStrokePaint)

                        val innerSw = (maxRadius * 0.16f + baseStroke * 0.8f) * radialBlur
                        val innerR = (fillR - maxRadius * 0.10f * radialBlur).coerceAtLeast(0.5f)
                        blurStrokePaint.strokeWidth = innerSw
                        blurStrokePaint.color = withAlpha(accentEndColor, fillAlpha * radialBlur * 0.22f)
                        canvas.drawCircle(wx, wy, innerR, blurStrokePaint)
                    }

                    fillPaint.color = withAlpha(accent, fillAlpha * FILL_CIRCLE_ALPHA * (1f - 0.15f * radialBlur))
                    canvas.drawCircle(wx, wy, fillR, fillPaint)

                    val coreFlash = fillAlpha * fillAlpha * CORE_FLASH_ALPHA
                    if (coreFlash > 0.01f) {
                        fillPaint.color = withAlpha(0xFFFFFFFF.toInt(), coreFlash)
                        canvas.drawCircle(wx, wy, fillR * 0.56f, fillPaint)
                    }
                }

                // 1b. 双旋转渐变粗细光弧（动态模糊拖影 → 外发光底轨 → 高亮主弧）
                val arcAlpha = computeSparkArcAlpha(ringProg)
                if (arcAlpha > 0.01f) {
                    val widthMul = computeSparkArcWidthMul(ringProg)
                    val coreRgb = ringColorFast(ringProg)

                    drawClickArc(
                        canvas = canvas,
                        wx = wx,
                        wy = wy,
                        radius = minOf(wave.r + wave.seg0RoundRate * unitScale, limit - arcMaxW),
                        baseRad = wave.ang + wave.seg0Off,
                        ringProg = ringProg,
                        radialBlur = radialBlur,
                        angularSpeed = wave.rs,
                        minW = arcMinW,
                        maxW = arcMaxW,
                        widthMul = widthMul,
                        coreRgb = coreRgb,
                        alpha = arcAlpha,
                    )
                    drawClickArc(
                        canvas = canvas,
                        wx = wx,
                        wy = wy,
                        radius = minOf(wave.r + wave.seg1RoundRate * unitScale, limit - arcMaxW),
                        baseRad = wave.ang + wave.seg1Off,
                        ringProg = ringProg,
                        radialBlur = radialBlur,
                        angularSpeed = wave.rs,
                        minW = arcMinW,
                        maxW = arcMaxW,
                        widthMul = widthMul,
                        coreRgb = coreRgb,
                        alpha = arcAlpha,
                    )
                }
            }
        }

        // 2. 绘制长按 / 拖拽持续旋转光环（始终居中跟随光标，带旋转尾迹动态模糊）
        if (holdState != HoldState.IDLE) {
            drawHoldHalo(canvas, cx, cy, limit, unitScale, arcMinW, arcMaxW)
        }

        // 3. 绘制飞散旋转三角火花粒子（高速初段附带 3 级方向性速度动态模糊残影）
        if (activeSparkCount > 0) {
            val refSparkSpeed = 5.5f * (maxRadius / 39f)
            for (s in sparks) {
                if (!s.active || s.alpha <= 0.01f || s.size <= 0.5f) continue
                val sx = cx + s.x
                val sy = cy + s.y
                val margin = s.size * 2f
                if (sx < -margin || sx > w + margin || sy < -margin || sy > h + margin) continue

                val sz = s.size
                triPath.reset()
                triPath.moveTo(0f, -sz)
                triPath.lineTo(sz * 0.6f, sz * 0.6f)
                triPath.lineTo(-sz * 0.6f, sz * 0.6f)
                triPath.close()

                val speed = hypot(s.vx, s.vy)
                val blur = computeSparkParticleBlurStrength(speed, refSparkSpeed)

                // 3a. 方向性快门曝光动态模糊：沿反速度与反自旋方向绘制 3 级渐隐残影，减速后自动归零
                if (blur > 0.02f) {
                    val stepScale = 0.62f * (0.65f + 0.55f * blur)
                    for (step in 3 downTo 1) {
                        val lagFrames = step * stepScale
                        val gx = sx - s.vx * lagFrames
                        val gy = sy - s.vy * lagFrames
                        val gRot = s.rot - s.rs * lagFrames
                        val weight = (4 - step) * 0.25f
                        val ghostScale = 1f + 0.11f * step * blur
                        val ghostTint = if (step == 1) accentEndColor else accent

                        canvas.save()
                        canvas.translate(gx, gy)
                        canvas.rotate(gRot * RAD_TO_DEG)
                        canvas.scale(ghostScale, ghostScale)
                        sparkBlurPaint.color = withAlpha(ghostTint, s.alpha * blur * SPARK_BLUR_ALPHA * weight)
                        canvas.drawPath(triPath, sparkBlurPaint)
                        canvas.restore()
                    }
                }

                // 3b. 主体高亮三角碎晶（外层强调色辉光 + 白色高亮内核）
                canvas.save()
                canvas.translate(sx, sy)
                canvas.rotate(s.rot * RAD_TO_DEG)

                sparkGlowPaint.strokeWidth = maxOf(1.6f * density, sz * 0.34f)
                sparkGlowPaint.color = withAlpha(accent, s.alpha * SPARK_GLOW_ALPHA)
                canvas.drawPath(triPath, sparkGlowPaint)

                val coreAlpha = s.alpha * SPARK_CORE_ALPHA * (1f - 0.14f * blur)
                sparkCorePaint.color = withAlpha(0xFFFFFFFF.toInt(), coreAlpha)
                canvas.drawPath(triPath, sparkCorePaint)

                canvas.restore()
            }
        }
    }

    private fun drawClickArc(
        canvas: Canvas,
        wx: Float,
        wy: Float,
        radius: Float,
        baseRad: Float,
        ringProg: Float,
        radialBlur: Float,
        angularSpeed: Float,
        minW: Float,
        maxW: Float,
        widthMul: Float,
        coreRgb: Int,
        alpha: Float,
    ) {
        val startRad: Float
        val sweepRad: Float
        when {
            ringProg <= SPARK_ARC_LEN_STOP_ADD -> {
                val len = CLICK_ARC_LEN_RAD * (ringProg / SPARK_ARC_LEN_STOP_ADD)
                startRad = baseRad + CLICK_ARC_LEN_RAD - len
                sweepRad = len
            }
            ringProg <= SPARK_ARC_LEN_START_DIM -> {
                startRad = baseRad
                sweepRad = CLICK_ARC_LEN_RAD
            }
            else -> {
                val len = CLICK_ARC_LEN_RAD * (1f - (ringProg - SPARK_ARC_LEN_START_DIM) / (1f - SPARK_ARC_LEN_START_DIM))
                startRad = baseRad
                sweepRad = len.coerceAtLeast(0f)
            }
        }
        drawArcMotionBlur(
            canvas = canvas,
            wx = wx,
            wy = wy,
            radius = radius,
            startRad = startRad,
            sweepRad = sweepRad,
            maxW = maxW,
            widthMul = widthMul,
            alpha = alpha,
            radialBlur = radialBlur,
            radialLag = maxRadius * 0.15f * radialBlur,
            angularLag = angularSpeed * 3.2f,
        )
        drawTaperedArc(canvas, wx, wy, radius, startRad, sweepRad, minW, maxW, widthMul, coreRgb, alpha)
    }

    private fun drawHoldHalo(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        limit: Float,
        unitScale: Float,
        arcMinW: Float,
        arcMaxW: Float,
    ) {
        val easeExpand = 1f - (1f - holdExpandProg).let { it * it * it }
        val retractInv = if (holdState == HoldState.RETRACTING) (1f - holdRetractProg).coerceIn(0f, 1f) else 1f
        val radiusBase = minOf(maxRadius * 0.86f * easeExpand, limit - arcMaxW)
        if (radiusBase <= 0.5f) return

        val fillAlpha = 0.15f * easeExpand * retractInv
        if (fillAlpha > 0.01f) {
            fillPaint.color = withAlpha(accent, fillAlpha)
            canvas.drawCircle(cx, cy, radiusBase, fillPaint)
        }

        val sweepRad = HOLD_ARC_LEN_RAD * easeExpand * retractInv
        val alpha = (0.92f * easeExpand * retractInv).coerceIn(0f, 1f)
        val coreRgb = ringColorFast(0.55f)
        val holdRadialBlur = when (holdState) {
            HoldState.EXPANDING -> computeSparkRadialBlurFactor(holdExpandProg)
            HoldState.RETRACTING -> computeSparkRadialBlurFactor(holdRetractProg)
            else -> 0f
        }
        val holdRadialLag = when (holdState) {
            HoldState.EXPANDING -> maxRadius * 0.12f * holdRadialBlur
            HoldState.RETRACTING -> -maxRadius * 0.10f * holdRadialBlur
            else -> 0f
        }
        val holdAngularLag = HOLD_ORBIT_SPEED * (if (holdState == HoldState.RETRACTING) 4.0f else 3.2f)
        val outerR = minOf(radiusBase + 1.5f * unitScale, limit - arcMaxW)

        drawArcMotionBlur(
            canvas = canvas,
            wx = cx,
            wy = cy,
            radius = radiusBase,
            startRad = holdAngle,
            sweepRad = sweepRad,
            maxW = arcMaxW * 0.92f,
            widthMul = 1f,
            alpha = alpha,
            radialBlur = holdRadialBlur,
            radialLag = holdRadialLag,
            angularLag = holdAngularLag,
        )
        drawTaperedArc(
            canvas = canvas,
            wx = cx,
            wy = cy,
            radius = radiusBase,
            baseStartRad = holdAngle,
            sweepRad = sweepRad,
            minW = arcMinW,
            maxW = arcMaxW * 0.92f,
            widthMul = 1f,
            coreRgb = coreRgb,
            alpha = alpha,
        )
        drawArcMotionBlur(
            canvas = canvas,
            wx = cx,
            wy = cy,
            radius = outerR,
            startRad = holdAngle + PI_F,
            sweepRad = sweepRad,
            maxW = arcMaxW * 0.92f,
            widthMul = 1f,
            alpha = alpha,
            radialBlur = holdRadialBlur,
            radialLag = holdRadialLag,
            angularLag = holdAngularLag,
        )
        drawTaperedArc(
            canvas = canvas,
            wx = cx,
            wy = cy,
            radius = outerR,
            baseStartRad = holdAngle + PI_F,
            sweepRad = sweepRad,
            minW = arcMinW,
            maxW = arcMaxW * 0.92f,
            widthMul = 1f,
            coreRgb = coreRgb,
            alpha = alpha,
        )
    }

    /**
     * 硬件加速光弧动态模糊：按径向膨胀滞后与旋转角速度滞后绘制 2 级轻量 5 分段拖影，
     * 静止时直接跳过，运动时呈现丝滑快门拖尾感且无额外离屏图层开销。
     */
    private fun drawArcMotionBlur(
        canvas: Canvas,
        wx: Float,
        wy: Float,
        radius: Float,
        startRad: Float,
        sweepRad: Float,
        maxW: Float,
        widthMul: Float,
        alpha: Float,
        radialBlur: Float,
        radialLag: Float,
        angularLag: Float,
    ) {
        if (radius <= 0.5f || sweepRad < 0.04f || alpha <= 0.01f) return
        if (radialBlur <= 0.01f && abs(angularLag) <= 0.005f) return

        val segStep = 1f / ARC_BLUR_SEG_NUM
        val segSweepDeg = (sweepRad * segStep) * RAD_TO_DEG
        val baseGhostW = maxW * widthMul * (1.35f + 1.35f * radialBlur)
        val baseGhostAlpha = alpha * (0.11f + 0.15f * radialBlur)

        for (step in 2 downTo 1) {
            val lagFrac = step * 0.5f
            val ghostR = (radius - radialLag * lagFrac).coerceAtLeast(1f)
            val ghostStart = startRad + angularLag * lagFrac
            val stepAlpha = baseGhostAlpha * (if (step == 1) 1.0f else 0.52f)
            if (stepAlpha <= 0.008f) continue

            val stepW = baseGhostW * (if (step == 1) 1.0f else 1.28f)
            arcRect.set(wx - ghostR, wy - ghostR, wx + ghostR, wy + ghostR)
            blurStrokePaint.color = withAlpha(accent, stepAlpha)
            for (k in 0 until ARC_BLUR_SEG_NUM) {
                val tm = (k + 0.5f) * segStep
                val wT = (2f - abs(4f * (tm - 0.5f))).coerceIn(0f, 1f)
                blurStrokePaint.strokeWidth = stepW * (0.25f + 0.75f * wT)
                val a0Deg = (ghostStart + sweepRad * (k * segStep)) * RAD_TO_DEG
                canvas.drawArc(arcRect, a0Deg, segSweepDeg, false, blurStrokePaint)
            }
        }
    }

    private fun drawTaperedArc(
        canvas: Canvas,
        wx: Float,
        wy: Float,
        radius: Float,
        baseStartRad: Float,
        sweepRad: Float,
        minW: Float,
        maxW: Float,
        widthMul: Float,
        coreRgb: Int,
        alpha: Float,
    ) {
        if (radius <= 0.5f || sweepRad < 0.02f || alpha <= 0.01f) return
        arcRect.set(wx - radius, wy - radius, wx + radius, wy + radius)
        val segStep = 1f / ARC_SEG_NUM
        val segSweepDeg = (sweepRad * segStep) * RAD_TO_DEG
        val glowColor = withAlpha(accent, alpha * ARC_GLOW_ALPHA)
        val coreColor = withAlpha(coreRgb, alpha)

        // Pass 1: 强调色柔和外发光底轨
        arcGlowPaint.color = glowColor
        for (k in 0 until ARC_SEG_NUM) {
            val tm = (k + 0.5f) * segStep
            val wT = (2f - abs(4f * (tm - 0.5f))).coerceIn(0f, 1f)
            val lw = (minW + (maxW - minW) * wT) * widthMul
            val a0Deg = (baseStartRad + sweepRad * (k * segStep)) * RAD_TO_DEG
            arcGlowPaint.strokeWidth = lw * ARC_GLOW_WIDTH_MUL
            canvas.drawArc(arcRect, a0Deg, segSweepDeg, false, arcGlowPaint)
        }

        // Pass 2: 极浅高光白 → 柔光尾色核心弧线
        arcCorePaint.color = coreColor
        for (k in 0 until ARC_SEG_NUM) {
            val tm = (k + 0.5f) * segStep
            val wT = (2f - abs(4f * (tm - 0.5f))).coerceIn(0f, 1f)
            val lw = (minW + (maxW - minW) * wT) * widthMul
            val a0Deg = (baseStartRad + sweepRad * (k * segStep)) * RAD_TO_DEG
            arcCorePaint.strokeWidth = lw
            canvas.drawArc(arcRect, a0Deg, segSweepDeg, false, arcCorePaint)
        }
    }

    private fun ringColorFast(ringProg: Float): Int {
        val t = (1.2f * ringProg).coerceIn(0f, 1f)
        val er = (accentEndColor ushr 16) and 0xFF
        val eg = (accentEndColor ushr 8) and 0xFF
        val eb = accentEndColor and 0xFF
        val r = (250f * (1f - t) + er * t + 0.5f).toInt().coerceIn(0, 255)
        val g = (252f * (1f - t) + eg * t + 0.5f).toInt().coerceIn(0, 255)
        val b = (252f * (1f - t) + eb * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun withAlpha(c: Int, a: Float): Int =
        (c and 0x00FFFFFF) or ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)

    companion object {
        private const val BASE_FRAME_MS = 1000f / 60f
        private const val MAX_DELTA_MS = 100f

        private const val MAX_WAVES = 4
        private const val MAX_SPARKS = 16
        private const val SPARKS_PER_CLICK = 4
        private const val ARC_SEG_NUM = 10
        private const val ARC_BLUR_SEG_NUM = 5

        private const val PI_F = PI.toFloat()
        private const val TWO_PI = (PI * 2.0).toFloat()
        private const val HALF_PI = (PI * 0.5).toFloat()
        private const val RAD_TO_DEG = (180.0 / PI).toFloat()

        private const val CLICK_ARC_LEN_RAD = 1.1f * PI_F
        private const val HOLD_ARC_LEN_RAD = 0.85f * PI_F
        private const val HOLD_ORBIT_SPEED = 0.055f
        private const val HOLD_EXPAND_MS = 140f
        private const val HOLD_RETRACT_MS = 170f
        private const val HOLD_MIN_MS = 220L

        private const val SPARK_FRICTION = 0.9f
        private const val SPARK_ALPHA_DECAY = 0.032f

        private const val FILL_CIRCLE_ALPHA = 0.72f
        private const val CORE_FLASH_ALPHA = 0.42f
        private const val ARC_GLOW_ALPHA = 0.36f
        private const val ARC_GLOW_WIDTH_MUL = 2.15f
        private const val SPARK_BLUR_ALPHA = 0.42f
        private const val SPARK_GLOW_ALPHA = 0.52f
        private const val SPARK_CORE_ALPHA = 0.95f

        private val RING_SPEED_CHOICES = floatArrayOf(0.015f, 0.04f, 0.07f)
        private val RING_RADIUS_OFFSET_CHOICES = floatArrayOf(0f, 1f, 1.5f, 2f)
    }
}
