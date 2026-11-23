package io.github.opentouchpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 点击 / 长按反馈圆环，整个圆环用 Canvas 画：
 * - 视图不设背景，窗口用半透明格式，空闲时什么都不画（完全透明），所以不会出现方形图块；
 * - 半径永远不超过视图短边的一半，描边画在半径内侧，不会被窗口边界裁切；
 * - 窗口由服务常驻，只移动位置，不随每次点击 add/remove；
 * - 展开与缩放过程中采用硬件加速多重采样径向动态模糊（Motion Blur），无额外图层暗斑与方形底衬，静止时保持清晰凝聚。
 *
 * 动画：
 * - [tap]：从内向外展开 [EXPAND_MS] → 停留 [TAP_HOLD_MS] → 从外向内收缩并淡出 [COLLAPSE_MS]
 * - [press]：按住的这段时间里向外展开，按住结束后向内收缩消失
 * - [pressUntilRelease]：展开后一直保持，直到 [release]（自定义滑动、拖拽锁定）
 */
class TouchRingView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // 纯硬件加速高斯动态模糊采样画笔（仅描边模式，无离屏软件图层，无额外圆盘与背景）
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    var accent: Int = 0xFFC96442.toInt()
        set(value) {
            if (field == value) return
            field = value
            if (phase != Phase.IDLE) invalidate()
        }

    /** 完全展开时的外半径（px）。 */
    var maxRadius = 0f
    var strokeWidthPx = 0f

    private enum class Phase { IDLE, EXPANDING, HOLDING, COLLAPSING }

    private var phase = Phase.IDLE
    /** 0 = 不可见，1 = 完全展开。 */
    private var progress = 0f
    private var collapseFromProgress = 1f
    private var collapseFromAlpha = 1f
    private var animator: ValueAnimator? = null
    /** 每次换动画都递增，过期的结束回调直接丢弃。 */
    private var token = 0
    private val autoCollapse = Runnable { collapse() }

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

    /** 点击：展开 → 停留 → 收缩淡出。 */
    fun tap() {
        restartFromZero()
        ensureVisible()
        expand(EXPAND_MS, thenHoldMs = TAP_HOLD_MS)
    }

    /**
     * 长按 [holdMs] 毫秒：按住期间向外展开，结束时向内收缩。
     * 如果圆环已经在展开 / 保持（触控板上原地长按松手），从当前大小接着走，不会先消失再长出来。
     */
    fun press(holdMs: Long) {
        val from = if (phase == Phase.EXPANDING || phase == Phase.HOLDING) progress else 0f
        stopAnimation()
        progress = from
        ensureVisible()
        val hold = holdMs.coerceAtLeast(EXPAND_MS)
        val expandMs = (hold * (1f - from)).toLong()
        expand(expandMs, thenHoldMs = hold - expandMs)
    }

    /** 展开后保持，直到调用 [release]。 */
    fun pressUntilRelease() {
        restartFromZero()
        ensureVisible()
        expand(EXPAND_MS, thenHoldMs = -1L)
    }

    /** 松开：从当前大小向内收缩并淡出。 */
    fun release() {
        if (phase == Phase.EXPANDING || phase == Phase.HOLDING) collapse()
    }

    /** 立即隐藏，不播动画。 */
    fun clear() {
        stopAnimation()
        phase = Phase.IDLE
        progress = 0f
        hideWhenIdle()
        invalidate()
    }

    private fun stopAnimation() {
        token++
        removeCallbacks(autoCollapse)
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
    }

    private fun restartFromZero() {
        stopAnimation()
        progress = 0f
    }

    private fun expand(durationMs: Long, thenHoldMs: Long) {
        val from = progress
        val t = token
        phase = Phase.EXPANDING
        if (durationMs <= 0L || from >= 1f) {
            progress = 1f
            invalidate()
            hold(thenHoldMs)
            return
        }
        animator = ValueAnimator.ofFloat(from, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (t == token) hold(thenHoldMs)
                }
            })
            start()
        }
    }

    /** [ms] <= 0：松手即收缩，立即向内收缩淡出；[ms] < 0：一直保持到 [release]。 */
    private fun hold(ms: Long) {
        phase = Phase.HOLDING
        animator = null
        if (ms == 0L) {
            collapse()
        } else if (ms > 0L) {
            postDelayed(autoCollapse, ms)
        }
    }

    private fun collapse() {
        if (phase == Phase.IDLE || phase == Phase.COLLAPSING) return
        val from = progress.coerceIn(0.01f, 1f)
        collapseFromProgress = from
        collapseFromAlpha = expandAlpha(from)
        stopAnimation()
        val t = token
        phase = Phase.COLLAPSING
        animator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (COLLAPSE_MS * from).toLong().coerceAtLeast(MIN_COLLAPSE_MS)
            // 先快后慢：一开始就明显缩小，不会在原地停着不动
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (t != token) return
                    phase = Phase.IDLE
                    progress = 0f
                    animator = null
                    hideWhenIdle()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun expandAlpha(p: Float) = 0.4f + 0.6f * p

    override fun onDetachedFromWindow() {
        // 窗口被系统摘掉时不留悬空的动画；服务重新 add 后从空闲状态开始
        clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (phase == Phase.IDLE || progress <= 0.001f || maxRadius <= 0f) return
        // 再保险一次：外缘不超过视图短边的一半，任何配置下都不会被窗口裁成方块
        val limit = minOf(width, height) / 2f - 1f
        if (limit <= 0f) return
        val r = minOf(maxRadius, limit) * progress
        val collapsing = phase == Phase.COLLAPSING
        val expanding = phase == Phase.EXPANDING
        val shrink = if (collapsing) 1f - progress / collapseFromProgress else 0f
        val alpha = if (collapsing) collapseFromAlpha * (1f - shrink) else expandAlpha(progress)
        // 收缩时线条略微变粗，像向内收拢；描边画在 r 内侧，外缘正好等于 r
        val sw = minOf(strokeWidthPx * (1f + 0.4f * shrink), r)
        val cx = width / 2f
        val cy = height / 2f

        // ── 动态模糊计算（与 calculateTouchRingMotionBlur 公式严格一致，热路径零对象分配）──
        var blurSpeed = 0f
        var blurAlpha = 0f
        var lag = 0f
        var sigma = 0f
        if (expanding || collapsing) {
            val safeProgress = progress.coerceIn(0f, 1f)
            val rawSpeed = if (expanding) {
                Math.pow((1f - safeProgress).toDouble(), 0.7).toFloat().coerceIn(0f, 1f)
            } else {
                val safeCollapseFrom = collapseFromProgress.coerceIn(0.01f, 1f)
                val s = (1f - safeProgress / safeCollapseFrom).coerceIn(0f, 1f)
                Math.pow((1f - s).toDouble(), 0.7).toFloat().coerceIn(0f, 1f)
            }
            if (rawSpeed > 0.02f) {
                blurSpeed = rawSpeed
                val safeDensity = resources.displayMetrics.density.coerceAtLeast(1f)
                sigma = (strokeWidthPx * 1.5f + 2f * safeDensity) * rawSpeed
                lag = (strokeWidthPx * 0.8f + maxRadius * 0.12f) * rawSpeed
                blurAlpha = (alpha * rawSpeed * 0.72f).coerceIn(0f, 1f)
            }
        }

        // ── 硬件加速高斯动态模糊与径向拖尾层 (Gaussian Motion Blur Layer) ──
        if (blurSpeed > 0.02f && blurAlpha > 0.01f) {
            // 1. 径向运动拖影：沿运动轨迹绘制 2 级半透明渐变微步拖影
            if (lag > 0.5f) {
                val lagSteps = 2
                for (step in 1..lagSteps) {
                    val frac = step / (lagSteps + 1f) // 0.33f, 0.67f
                    val sampleR = if (expanding) {
                        (r - lag * (1f - frac)).coerceAtLeast(0f)
                    } else {
                        minOf(r + lag * (1f - frac), limit)
                    }
                    if (sampleR > 0f) {
                        val lagSw = sw + 0.8f * sigma * frac
                        val lagAlpha = blurAlpha * (0.16f + 0.18f * frac)
                        blurPaint.strokeWidth = lagSw
                        blurPaint.color = withAlpha(accent, lagAlpha)
                        canvas.drawCircle(cx, cy, (sampleR - lagSw / 2f).coerceAtLeast(0f), blurPaint)
                    }
                }
            }

            // 2. 多级高斯羽化柔光晕染：4 级向外平滑递减的高斯卷积描边，产生饱满的高级光学模糊
            val sw1 = sw + 2.6f * sigma
            blurPaint.strokeWidth = sw1
            blurPaint.color = withAlpha(accent, blurAlpha * 0.10f)
            canvas.drawCircle(cx, cy, (r - sw1 / 2f).coerceAtLeast(0f), blurPaint)

            val sw2 = sw + 1.8f * sigma
            blurPaint.strokeWidth = sw2
            blurPaint.color = withAlpha(accent, blurAlpha * 0.18f)
            canvas.drawCircle(cx, cy, (r - sw2 / 2f).coerceAtLeast(0f), blurPaint)

            val sw3 = sw + 1.0f * sigma
            blurPaint.strokeWidth = sw3
            blurPaint.color = withAlpha(accent, blurAlpha * 0.30f)
            canvas.drawCircle(cx, cy, (r - sw3 / 2f).coerceAtLeast(0f), blurPaint)

            val sw4 = sw + 0.4f * sigma
            blurPaint.strokeWidth = sw4
            blurPaint.color = withAlpha(accent, blurAlpha * 0.42f)
            canvas.drawCircle(cx, cy, (r - sw4 / 2f).coerceAtLeast(0f), blurPaint)
        }

        // ── 清晰主体层 (Sharp Core Ring & Fill) ──
        val coreAlpha = if (blurSpeed > 0f) {
            alpha * (1f - 0.15f * blurSpeed)
        } else {
            alpha
        }

        fillPaint.color = withAlpha(accent, coreAlpha * FILL_ALPHA)
        canvas.drawCircle(cx, cy, r, fillPaint)

        ringPaint.strokeWidth = sw
        ringPaint.color = withAlpha(accent, coreAlpha)
        canvas.drawCircle(cx, cy, (r - sw / 2f).coerceAtLeast(0f), ringPaint)
    }

    private fun withAlpha(c: Int, a: Float) =
        (c and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)

    companion object {
        const val EXPAND_MS = 220L
        const val TAP_HOLD_MS = 0L
        const val COLLAPSE_MS = 180L
        private const val MIN_COLLAPSE_MS = 80L
        private const val FILL_ALPHA = 0.18f
    }
}
