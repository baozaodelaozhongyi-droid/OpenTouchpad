package io.github.opentouchpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

/**
 * 点击 / 长按反馈圆环，整个圆环用 Canvas 画：
 * - 视图不设背景，窗口用半透明格式，空闲时什么都不画（完全透明），所以不会出现方形图块；
 * - 半径永远不超过视图短边的一半，描边画在半径内侧，不会被窗口边界裁切；
 * - 窗口由服务常驻，只移动位置，不随每次点击 add/remove；
 * - 展开与缩放过程中叠加一层动态模糊（径向运动拖影与高斯羽化层），静止时保持清晰凝聚。
 *
 * 动画：
 * - [tap]：从内向外展开 [EXPAND_MS] → 停留 [TAP_HOLD_MS] → 从外向内收缩并淡出 [COLLAPSE_MS]
 * - [press]：按住的这段时间里向外展开，按住结束后向内收缩消失
 * - [pressUntilRelease]：展开后一直保持，直到 [release]（自定义滑动、拖拽锁定）
 */
class TouchRingView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 动态模糊专用画笔：环体动态模糊、径向拖尾与填充光晕
    private val blurRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blurTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blurFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var accent: Int = 0xFFC96442.toInt()
        set(value) { field = value; invalidate() }

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

    // BlurMaskFilter 缓存，按 0.5px 量化对齐，避免每帧高频分配
    private var lastRingBlurRadius = -1f
    private var cachedRingBlurFilter: BlurMaskFilter? = null
    private var lastTrailBlurRadius = -1f
    private var cachedTrailBlurFilter: BlurMaskFilter? = null

    init {
        background = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // 软件图层确保 BlurMaskFilter 在全平台与所有 Android 版本稳定高保真渲染
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun getRingBlurFilter(radiusPx: Float): BlurMaskFilter? {
        if (radiusPx < 0.5f) return null
        val q = (radiusPx * 2f).roundToInt() / 2f
        if (q < 0.5f) return null
        if (q != lastRingBlurRadius || cachedRingBlurFilter == null) {
            lastRingBlurRadius = q
            cachedRingBlurFilter = BlurMaskFilter(q, BlurMaskFilter.Blur.NORMAL)
        }
        return cachedRingBlurFilter
    }

    private fun getTrailBlurFilter(radiusPx: Float): BlurMaskFilter? {
        if (radiusPx < 0.5f) return null
        val q = (radiusPx * 2f).roundToInt() / 2f
        if (q < 0.5f) return null
        if (q != lastTrailBlurRadius || cachedTrailBlurFilter == null) {
            lastTrailBlurRadius = q
            cachedTrailBlurFilter = BlurMaskFilter(q, BlurMaskFilter.Blur.NORMAL)
        }
        return cachedTrailBlurFilter
    }

    /** 点击：展开 → 停留 → 收缩淡出。 */
    fun tap() {
        restartFromZero()
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
        val hold = holdMs.coerceAtLeast(EXPAND_MS)
        val expandMs = (hold * (1f - from)).toLong()
        expand(expandMs, thenHoldMs = hold - expandMs)
    }

    /** 展开后保持，直到调用 [release]。 */
    fun pressUntilRelease() {
        restartFromZero()
        expand(EXPAND_MS, thenHoldMs = -1L)
    }

    /** 直接显示为完全展开并保持（不播展开动画），直到 [release]。面板重建后恢复拖拽锁定圆环用。 */
    fun holdExpanded() {
        stopAnimation()
        progress = 1f
        phase = Phase.HOLDING
        invalidate()
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

        // ── 动态模糊层 (Motion Blur Layer) ──
        val blurParams = calculateTouchRingMotionBlur(
            isExpanding = expanding,
            isCollapsing = collapsing,
            progress = progress,
            collapseFromProgress = collapseFromProgress,
            baseAlpha = alpha,
            maxRadius = maxRadius,
            strokeWidthPx = strokeWidthPx,
            density = resources.displayMetrics.density,
        )

        if (blurParams.blurAlpha > 0.01f && blurParams.blurRadius >= 0.5f) {
            val ringFilter = getRingBlurFilter(blurParams.blurRadius)
            val trailFilter = getTrailBlurFilter((blurParams.blurRadius * 0.75f).coerceAtLeast(0.5f))

            // 1. 径向运动拖影带：展开时向内落后，收缩时向外落后
            val trailR = if (blurParams.isExpanding) {
                (r - blurParams.trailLag).coerceAtLeast(0f)
            } else {
                minOf(r + blurParams.trailLag, limit)
            }
            if (trailR > 0f && trailFilter != null) {
                blurTrailPaint.strokeWidth = blurParams.blurStrokeWidth * 0.85f
                blurTrailPaint.color = withAlpha(accent, blurParams.blurAlpha * 0.60f)
                blurTrailPaint.maskFilter = trailFilter
                canvas.drawCircle(cx, cy, trailR, blurTrailPaint)
            }

            // 2. 动态高斯羽化模糊主环：随动速扩散
            if (ringFilter != null) {
                blurRingPaint.strokeWidth = blurParams.blurStrokeWidth
                blurRingPaint.color = withAlpha(accent, blurParams.blurAlpha)
                blurRingPaint.maskFilter = ringFilter
                canvas.drawCircle(cx, cy, (r - sw / 2f).coerceAtLeast(0f), blurRingPaint)
            }

            // 3. 内部填充动态羽化扩散
            if (blurParams.isExpanding && r > blurParams.blurRadius) {
                blurFillPaint.color = withAlpha(accent, blurParams.blurAlpha * FILL_ALPHA * 0.75f)
                blurFillPaint.maskFilter = ringFilter
                canvas.drawCircle(cx, cy, r, blurFillPaint)
            }
        }

        // ── 清晰主体层 (Sharp Core Ring & Fill) ──
        val coreAlpha = if (blurParams.speed > 0f) {
            alpha * (1f - 0.18f * blurParams.speed)
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
        const val EXPAND_MS = 280L
        const val TAP_HOLD_MS = 0L
        const val COLLAPSE_MS = 180L
        private const val MIN_COLLAPSE_MS = 80L
        private const val FILL_ALPHA = 0.18f
    }
}
