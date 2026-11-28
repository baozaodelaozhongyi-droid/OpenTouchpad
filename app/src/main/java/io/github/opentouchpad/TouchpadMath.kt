package io.github.opentouchpad

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val PANEL_MIN_WIDTH_DP = 72
internal const val CONTROL_MIN_SIZE_DP = 72
internal const val CONTROL_MAX_SIZE_DP = 2400
internal const val FLOATING_BALL_MIN_DP = 16
internal const val FLOATING_BALL_MAX_DP = 140
internal const val CURSOR_MIN_DP = 8
internal const val CURSOR_MAX_DP = 160
internal const val CURSOR_SUPERSAMPLE_FACTOR = 2
internal const val CURSOR_MAX_BITMAP_DIM_PX = 1024
internal const val BUTTON_SPACING_MAX_DP = 24
internal const val DEFAULT_PANEL_WIDTH_DP = 168
internal const val CONTROL_EXTRA_HEIGHT_MAX_DP = 400
internal const val DEFAULT_BUTTON_SPACING_DP = 3
internal const val CURSOR_HOLD_MIN_MS = 300
internal const val CURSOR_HOLD_MAX_MS = 3000
internal const val CURSOR_HOLD_DEFAULT_MS = 800
internal const val DRAG_TRAIL_MAX_POINTS = 120
internal const val PAD_EDGE_DEAD_ZONE_DEFAULT_DP = 10
internal const val PAD_EDGE_DEAD_ZONE_MAX_DP = 32

internal const val CUSTOM_SWIPE_MIN_DISTANCE_DP = 2

internal data class PanelPosition(val x: Int, val y: Int)

internal data class CustomSwipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) {
    val distance: Float
        get() = kotlin.math.hypot(endX - startX, endY - startY)
}

internal data class SwipeSample(val timeMs: Long, val x: Float, val y: Float)

internal class SwipeVelocityTracker(
    private val maxAgeMs: Long = 140L,
) {
    private var times = LongArray(INITIAL_CAPACITY)
    private var xs = FloatArray(INITIAL_CAPACITY)
    private var ys = FloatArray(INITIAL_CAPACITY)
    private var head = 0
    private var count = 0

    fun reset() {
        head = 0
        count = 0
    }

    fun addMovement(timeMs: Long, x: Float, y: Float) {
        val cap = times.size
        if (count == cap) {
            if (cap < MAX_CAPACITY) {
                grow()
            } else {
                // 极端高采样爆发达到上限时丢弃最旧样本
                head = (head + 1) % cap
                count--
            }
        }
        val idx = (head + count) % times.size
        times[idx] = timeMs
        xs[idx] = x
        ys[idx] = y
        count++
        prune(timeMs)
    }

    private fun grow() {
        val oldCap = times.size
        val newCap = minOf(oldCap * 2, MAX_CAPACITY)
        val newTimes = LongArray(newCap)
        val newXs = FloatArray(newCap)
        val newYs = FloatArray(newCap)
        val firstPart = minOf(count, oldCap - head)
        System.arraycopy(times, head, newTimes, 0, firstPart)
        System.arraycopy(xs, head, newXs, 0, firstPart)
        System.arraycopy(ys, head, newYs, 0, firstPart)
        val remaining = count - firstPart
        if (remaining > 0) {
            System.arraycopy(times, 0, newTimes, firstPart, remaining)
            System.arraycopy(xs, 0, newXs, firstPart, remaining)
            System.arraycopy(ys, 0, newYs, firstPart, remaining)
        }
        times = newTimes
        xs = newXs
        ys = newYs
        head = 0
    }

    private fun prune(nowMs: Long) {
        val cap = times.size
        while (count > 2 && (nowMs - times[head]) > maxAgeMs) {
            head = (head + 1) % cap
            count--
        }
    }

    /**
     * 计算当前的释放速度（像素 / 秒）。
     * 若最后一次采样距离抬手时间已超过静止门限（80ms），判定用户已停顿，速度归零。
     */
    fun computeVelocity(nowMs: Long, stopThresholdMs: Long = 80L): Pair<Float, Float> {
        prune(nowMs)
        if (count < 2) return 0f to 0f
        val lastIdx = (head + count - 1) % times.size
        val lastTime = times[lastIdx]
        if (nowMs - lastTime > stopThresholdMs) {
            return 0f to 0f
        }
        val firstTime = times[head]
        val dt = (lastTime - firstTime).coerceAtLeast(12L)
        val vx = (xs[lastIdx] - xs[head]) / dt * 1000f
        val vy = (ys[lastIdx] - ys[head]) / dt * 1000f
        return vx to vy
    }

    companion object {
        private const val INITIAL_CAPACITY = 64
        private const val MAX_CAPACITY = 512
    }
}

internal data class CustomSwipePlan(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val targetX: Float,
    val targetY: Float,
    val durationMs: Long,
    val isInertial: Boolean,
)

internal fun planCustomSwipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    velocityX: Float,
    velocityY: Float,
    minDistancePx: Float,
    density: Float,
    screenWidth: Int,
    screenHeight: Int,
): CustomSwipePlan? {
    val dx = endX - startX
    val dy = endY - startY
    val distance = kotlin.math.hypot(dx, dy)
    if (distance < minDistancePx) return null

    val dirX = dx / distance
    val dirY = dy / distance

    // 沿拖动方向投影的释放速度
    val projSpeed = velocityX * dirX + velocityY * dirY
    val minFlingVelocity = 180f * density // 相当于 180 dp/s 轻扫初速度

    if (projSpeed < minFlingVelocity) {
        // 微操与慢速拖动：手势耗时根据移动距离动态计算，微操（几像素）可在 ~100ms 内干脆执行完毕，
        // 避免固定 300ms 匀速拖拽导致底层微调控件因瞬时位移过慢而无法灵敏响应。
        val nonInertialDuration = (100L + (distance / (1.5f * density)).coerceIn(0f, 150f).toLong()).coerceIn(100L, 250L)
        return CustomSwipePlan(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            targetX = endX,
            targetY = endY,
            durationMs = nonInertialDuration,
            isInertial = false,
        )
    }

    // 惯性滑动延伸距离（基于超出初速度的平滑动量，上限不超过 450 dp）
    val extraPx = ((projSpeed - minFlingVelocity) * 0.16f).coerceIn(0f, 450f * density)
    val targetX = clampGestureCoord(endX + dirX * extraPx, screenWidth)
    val targetY = clampGestureCoord(endY + dirY * extraPx, screenHeight)

    // 惯性滑动手势耗时缩短，赋予注入手势高初速度以触发底层系统的原生惯性飞滑
    val speedRatio = ((projSpeed - minFlingVelocity) / (2500f * density)).coerceIn(0f, 1f)
    val durationMs = (220L - (speedRatio * 80L).toLong()).coerceIn(140L, 220L)

    return CustomSwipePlan(
        startX = startX,
        startY = startY,
        endX = endX,
        endY = endY,
        targetX = targetX,
        targetY = targetY,
        durationMs = durationMs,
        isInertial = true,
    )
}

internal fun customSwipeFrom(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    minDistancePx: Float,
): CustomSwipe? {
    val swipe = CustomSwipe(startX, startY, endX, endY)
    return swipe.takeIf { it.distance >= minDistancePx }
}

internal fun cursorHotspotOffset(sizePx: Float): Float =
    if (sizePx > 0f) (sizePx * 0.09f).coerceAtLeast(2f) else 0f

/** 计算光标离屏缓存的超采样倍率：常规尺寸固定 2×，极端超大尺寸按上限封顶防止显存暴涨，且永不低于 1×。 */
internal fun cursorSupersampleScale(
    viewWidthPx: Int,
    viewHeightPx: Int,
    targetFactor: Int = CURSOR_SUPERSAMPLE_FACTOR,
    maxBitmapDimPx: Int = CURSOR_MAX_BITMAP_DIM_PX,
): Float {
    val maxDim = maxOf(viewWidthPx, viewHeightPx)
    if (maxDim <= 0) return 1f
    val target = targetFactor.coerceAtLeast(1).toFloat()
    if (maxBitmapDimPx <= 0) return target
    return minOf(target, (maxBitmapDimPx.toFloat() / maxDim).coerceAtLeast(1f))
}

internal fun cursorViewOrigin(cursorX: Float, cursorY: Float, sizePx: Float = 0f): PanelPosition {
    val o = cursorHotspotOffset(sizePx)
    return PanelPosition((cursorX - o).roundToInt(), (cursorY - o).roundToInt())
}

internal fun clampPanelPosition(
    x: Int,
    y: Int,
    panelWidth: Int,
    panelHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): PanelPosition {
    val maxX = (screenWidth - panelWidth.coerceAtLeast(0)).coerceAtLeast(0)
    val maxY = (screenHeight - panelHeight.coerceAtLeast(0)).coerceAtLeast(0)
    return PanelPosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

internal fun pixelsToDp(pixels: Int, density: Float): Int =
    if (density > 0f) (pixels / density).roundToInt().coerceAtLeast(1) else pixels

internal fun shouldScheduleLongPress(dragging: Boolean): Boolean =
    !dragging

internal fun resizePanelHeight(startPx: Int, deltaY: Int, minPx: Int, maxPx: Int): Int =
    (startPx - deltaY).coerceIn(minPx, maxPx)

internal fun resizePanelWidth(startPx: Int, deltaX: Int, minPx: Int, maxPx: Int): Int =
    (startPx + deltaX).coerceIn(minPx, maxPx)

internal fun clampFloatingBallPosition(
    x: Int,
    y: Int,
    ballSize: Int,
    screenWidth: Int,
    screenHeight: Int,
): PanelPosition {
    val maxX = (screenWidth - ballSize.coerceAtLeast(0)).coerceAtLeast(0)
    val maxY = (screenHeight - ballSize.coerceAtLeast(0)).coerceAtLeast(0)
    return PanelPosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

internal fun controlWidthRange(screenWidth: Int, density: Float): IntRange {
    val minPx = (CONTROL_MIN_SIZE_DP * density).roundToInt()
    val maxPx = minOf((CONTROL_MAX_SIZE_DP * density).roundToInt(), screenWidth)
    return minPx.coerceAtMost(maxPx)..maxPx
}

internal fun resizeControlHeight(startPx: Int, deltaY: Int, minPx: Int, maxPx: Int): Int =
    (startPx + deltaY).coerceIn(minPx, maxPx)

internal data class ControlRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * Geometry of the full control surface.
 * [slots] has [buttonCount] entries for Top, Bottom, Left, and Right edges.
 * Button size and the touchpad rectangle depend only on the panel size; [spacingPx]
 * only changes the distance between neighbouring buttons inside each row/column.
 */
internal data class ControlLayout(
    val button: Int,
    val pad: ControlRect,
    val slots: List<ControlRect>,
    val buttonCount: Int = slots.size,
)

internal const val CONTROL_EDGE_DP = 1
internal const val CONTROL_BUTTON_MAX_DP = 46
internal const val CONTROL_BUTTON_MIN_DP = 9

internal data class ControlGridMetrics(
    val edge: Int,
    val button: Int,
    val gap: Int,
)

/**
 * 统一解析 6 列等比例网格的边留白、按键尺寸与实际间距：
 * 在面板较窄或用户调大按键间距时，严格保证 `6 * button + 5 * gap <= width - 2 * edge`，
 * 避免右侧按键或触控板被窗口裁切。
 */
internal fun resolveControlGridMetrics(width: Int, density: Float, spacingPx: Int): ControlGridMetrics {
    val edge = (CONTROL_EDGE_DP * density).roundToInt().coerceAtLeast(0)
    val availableW = (width - 2 * edge).coerceAtLeast(6)
    val minB = (CONTROL_BUTTON_MIN_DP * density).roundToInt().coerceAtLeast(1).coerceAtMost(availableW / 6)
    val maxB = (CONTROL_BUTTON_MAX_DP * density).roundToInt().coerceAtLeast(minB)
    val requestedGap = spacingPx.coerceAtLeast(0)
    val maxGap = ((availableW - 6 * minB) / 5).coerceAtLeast(0)
    val gap = requestedGap.coerceAtMost(maxGap)
    val button = ((availableW - 5 * gap) / 6).coerceIn(minB, maxB)
    return ControlGridMetrics(edge = edge, button = button, gap = gap)
}

/** 6 列等比例基础单元：按键厚度/基准直径由面板宽度和间距决定。 */
internal fun controlButtonSize(width: Int, density: Float, gapPx: Int): Int =
    resolveControlGridMetrics(width, density, gapPx).button

/** 触控板区域在最紧凑网格下的最低高度。4/8 键位为 2 单元，12/16/18 键位为紧凑 3 单元（16 键大间距时保底侧键高度）。 */
internal fun controlMinPadHeight(b: Int, gap: Int, buttonCount: Int = 16): Int = when (buttonCount) {
    4, 8 -> 2 * b + gap
    16 -> {
        val minSideButton = ((b * 3) / 5).coerceAtLeast(1)
        maxOf(3 * b + 2 * gap, 4 * minSideButton + 3 * gap)
    }
    12, 18 -> 3 * b + 2 * gap
    else -> 3 * b + 2 * gap
}

/** 网格最紧凑时的面板高度（上下按键 + 间距 + 最低触控板高度）。 */
internal fun controlGridHeight(width: Int, density: Float, gapPx: Int, buttonCount: Int = 16): Int {
    val metrics = resolveControlGridMetrics(width, density, gapPx)
    val minPadH = controlMinPadHeight(metrics.button, metrics.gap, buttonCount)
    return 2 * metrics.edge + 2 * metrics.button + 2 * metrics.gap + minPadH
}

internal fun edgeItemOffsetAndSize(index: Int, totalSpan: Int, count: Int, gap: Int): Pair<Int, Int> {
    if (count <= 1) return 0 to totalSpan
    val available = totalSpan - (count - 1) * gap
    val start = index * available / count + index * gap
    val end = (index + 1) * available / count + index * gap
    return start to (end - start)
}

/**
 * 自选键位布局计算：
 * 支持 4 / 8 / 12 / 16 / 18 键位。
 * 4 / 8 / 12 / 16 键位分布在触控板的 上、下、左、右 四条边上（每条边 count / 4 个按键）。
 * 4 / 8 / 12 键位为胶囊型/气泡型按钮，16 键位压缩为圆形按钮。
 * 18 键位为经典 6 列 × 5 行网格环形按键布局（含四角、移动键与缩放键）。
 */
internal fun computeControlLayout(
    width: Int,
    height: Int,
    density: Float,
    spacingPx: Int,
    buttonCount: Int = 16,
): ControlLayout {
    val metrics = resolveControlGridMetrics(width, density, spacingPx)
    val edge = metrics.edge
    val gap = metrics.gap
    val b = metrics.button

    if (buttonCount == 18) {
        val step = b + gap
        val gridW = 6 * b + 5 * gap
        val x0 = ((width - gridW) / 2).coerceAtLeast(0)
        val colX = List(6) { x0 + it * step }
        val topY = edge
        val minPadH = controlMinPadHeight(b, gap, 18)
        val minBottomY = topY + b + gap + minPadH + gap
        val bottomY = (height - edge - b).coerceAtLeast(minBottomY)
        val padTop = topY + step
        val padH = bottomY - gap - padTop
        val pad = ControlRect(colX[1], padTop, 4 * b + 3 * gap, padH)

        val sideRects = (0..2).map { i ->
            val (off, size) = edgeItemOffsetAndSize(i, padH, 3, gap)
            (padTop + off) to size
        }
        val leftX = colX[0]
        val rightX = colX[5]
        fun at(x: Int, y: Int) = ControlRect(x, y, b, b)
        fun sideAt(x: Int, index: Int) = ControlRect(x, sideRects[index].first, b, sideRects[index].second)

        val slots = (1..4).map { at(colX[it], topY) } +
            (1..4).map { at(colX[it], bottomY) } +
            listOf(sideAt(leftX, 1), sideAt(leftX, 2)) +
            listOf(sideAt(rightX, 0), sideAt(rightX, 1)) +
            listOf(at(leftX, topY), at(rightX, topY), at(leftX, bottomY), at(rightX, bottomY)) +
            listOf(sideAt(leftX, 0), sideAt(rightX, 2))
        return ControlLayout(button = b, pad = pad, slots = slots, buttonCount = 18)
    }

    val span = 4 * b + 3 * gap
    val gridW = 6 * b + 5 * gap
    val x0 = ((width - gridW) / 2).coerceAtLeast(0)

    val leftX = x0
    val padX = x0 + b + gap
    val rightX = padX + span + gap

    val minPadH = controlMinPadHeight(b, gap, buttonCount)
    val topY = edge
    val minBottomY = topY + b + gap + minPadH + gap
    val bottomY = (height - edge - b).coerceAtLeast(minBottomY)
    val padTop = topY + b + gap
    val padH = bottomY - gap - padTop
    val pad = ControlRect(padX, padTop, span, padH)

    val edgeCount = (buttonCount / 4).coerceIn(1, 4)

    val topSlots = (0 until edgeCount).map { i ->
        val (off, size) = edgeItemOffsetAndSize(i, span, edgeCount, gap)
        ControlRect(padX + off, topY, size, b)
    }

    val bottomSlots = (0 until edgeCount).map { i ->
        val (off, size) = edgeItemOffsetAndSize(i, span, edgeCount, gap)
        ControlRect(padX + off, bottomY, size, b)
    }

    val leftSlots = (0 until edgeCount).map { i ->
        val (off, size) = edgeItemOffsetAndSize(i, padH, edgeCount, gap)
        ControlRect(leftX, padTop + off, b, size)
    }

    val rightSlots = (0 until edgeCount).map { i ->
        val (off, size) = edgeItemOffsetAndSize(i, padH, edgeCount, gap)
        ControlRect(rightX, padTop + off, b, size)
    }

    val slots = topSlots + bottomSlots + leftSlots + rightSlots
    return ControlLayout(button = b, pad = pad, slots = slots, buttonCount = buttonCount)
}

internal fun controlHeightRange(screenHeight: Int, density: Float): IntRange {
    val minPx = (CONTROL_MIN_SIZE_DP * density).roundToInt()
    val maxPx = minOf((CONTROL_MAX_SIZE_DP * density).roundToInt(), screenHeight)
    return minPx.coerceAtMost(maxPx)..maxPx
}

/**
 * 长按面板弹出的动作选择弹窗最大高度：
 * 竖屏贴底占下半屏（约 52% 屏高），确保不依赖触控板时顶部第一项也处于单手可触达区域；
 * 横屏屏幕较矮，放宽至约 82% 屏高以保证列表可顺畅浏览滚动。
 */
internal fun computeActionPickerMaxHeight(
    screenHeightPx: Int,
    isLandscape: Boolean,
    density: Float,
): Int {
    if (screenHeightPx <= 0) return 0
    val ratio = if (isLandscape) 0.82f else 0.52f
    val target = (screenHeightPx * ratio).roundToInt()
    val minComfortable = (240f * density.coerceAtLeast(1f)).roundToInt()
    val hardMax = (screenHeightPx * 0.88f).roundToInt().coerceAtLeast(1)
    return maxOf(target, minOf(minComfortable, hardMax)).coerceIn(1, hardMax)
}

internal data class TrailPoint(val x: Float, val y: Float)

/** 记录拖拽轨迹：离上一个点超过 [minStepPx] 才追加，点数超过上限时隔一个删一个（保留首尾）。 */
internal fun appendTrailPoint(trail: MutableList<TrailPoint>, x: Float, y: Float, minStepPx: Float) {
    val last = trail.lastOrNull()
    if (last != null && kotlin.math.hypot(x - last.x, y - last.y) < minStepPx) return
    trail += TrailPoint(x, y)
    if (trail.size > DRAG_TRAIL_MAX_POINTS) {
        val kept = trail.filterIndexed { i, _ -> i == 0 || i == trail.lastIndex || i % 2 == 0 }
        trail.clear()
        trail += kept
    }
}

internal fun trailLength(trail: List<TrailPoint>): Float =
    trail.zipWithNext { a, b -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }.sum()

/** 拖拽移动段时长：按路径长度 ≈ 1.2 px/ms，限制在 250–1500 ms。 */
internal fun dragMoveDurationMs(lengthPx: Float): Long =
    (lengthPx * 5f / 6f).roundToLong().coerceIn(250L, 1500L)

/**
 * 触控板边缘防误触。坐标相对触控板自身：触控板是 (0,0)-(w,h)、圆角半径 [radius] 的圆角矩形。
 * 把它整体向内缩 [edge] 得到「有效区」（圆角半径同步缩成 radius - edge）。
 * 按下点不在有效区里——落在宽 [edge] 的边缘带里，或者在圆角外侧——返回 false，这一整次触摸都忽略。
 *
 * - [edge] <= 0：关闭，整个矩形都响应（和 v0.5.3 及以前一样，连圆角外侧的小角也算）。
 * - 触控板很小时，边缘带每边最多占短边的 1/3，中间至少留 1/3，不会整块失灵。
 */
internal fun insideTouchArea(x: Float, y: Float, w: Float, h: Float, radius: Float, edge: Float): Boolean {
    if (edge <= 0f) return true
    val d = minOf(edge, minOf(w, h) / 3f)
    val l = d
    val t = d
    val r = w - d
    val b = h - d
    if (x < l || x > r || y < t || y > b) return false
    val rr = (radius - d).coerceIn(0f, minOf(r - l, b - t) / 2f)
    val cx = x.coerceIn(l + rr, r - rr)
    val cy = y.coerceIn(t + rr, b - rr)
    return kotlin.math.hypot(x - cx, y - cy) <= rr
}

internal fun isPointInsideRect(px: Float, py: Float, x: Int, y: Int, w: Int, h: Int): Boolean =
    w > 0 && h > 0 && px >= x && px <= x + w && py >= y && py <= y + h

internal enum class ThemeTransitionAction {
    NO_OP,
    START_REVEAL,
    REVERSE_TO_BASE,
    EXPAND_REVEAL,
}

internal fun resolveThemeTransitionAction(
    baseDark: Boolean,
    currentRevealDark: Boolean?,
    targetDark: Boolean,
): ThemeTransitionAction = when {
    currentRevealDark != null -> {
        if (targetDark == currentRevealDark) ThemeTransitionAction.EXPAND_REVEAL
        else ThemeTransitionAction.REVERSE_TO_BASE
    }
    targetDark == baseDark -> ThemeTransitionAction.NO_OP
    else -> ThemeTransitionAction.START_REVEAL
}

internal fun computeRevealDuration(
    fromRadius: Float,
    toRadius: Float,
    maxRadius: Float,
    baseDurationMs: Long = 380L,
): Long {
    val distance = kotlin.math.abs(toRadius - fromRadius)
    val maxDist = maxRadius.coerceAtLeast(1f)
    return (baseDurationMs * (distance / maxDist)).roundToLong().coerceIn(100L, baseDurationMs)
}

/**
 * 计算设置页自定义壁纸的半透明主题遮罩颜色（保留 [pageColor] 的 RGB，按 [scrimPercent] 映射 Alpha）。
 */
internal fun computeSettingsBgScrimColor(pageColor: Int, scrimPercent: Int): Int {
    val alpha = (255 * scrimPercent.coerceIn(0, 100) / 100).coerceIn(0, 255)
    return (alpha shl 24) or (pageColor and 0x00FFFFFF)
}

/**
 * 圆形主题扩散动效进行中，判断位于 ([targetX], [targetY]) 的系统栏（状态栏 / 导航栏）
 * 是否已被扩散圆波覆盖，从而在圆波抵达系统栏瞬间同步切换图标深浅，回退时亦随之还原。
 */
internal fun resolveRevealSystemBarDark(
    baseDark: Boolean,
    revealDark: Boolean,
    radius: Float,
    cx: Float,
    cy: Float,
    targetX: Float,
    targetY: Float,
): Boolean {
    if (radius <= 0f) return baseDark
    val dist = kotlin.math.hypot((targetX - cx).toDouble(), (targetY - cy).toDouble()).toFloat()
    return if (radius >= dist) revealDark else baseDark
}

internal enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

internal fun resolveSwipeDirection(dx: Float, dy: Float): SwipeDirection {
    return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
        if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
    } else {
        if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
    }
}

internal fun isSwipeGesture(dx: Float, dy: Float, thresholdPx: Float): Boolean {
    return kotlin.math.hypot(dx, dy) >= thresholdPx
}

internal fun resolveBallSwipeAction(
    direction: SwipeDirection,
    upAction: PadAction,
    downAction: PadAction,
    leftAction: PadAction,
    rightAction: PadAction,
): PadAction = when (direction) {
    SwipeDirection.UP -> upAction
    SwipeDirection.DOWN -> downAction
    SwipeDirection.LEFT -> leftAction
    SwipeDirection.RIGHT -> rightAction
}

internal fun resolveBallLongPressHoldMs(configuredLongPressMs: Int, minSafeMs: Long = 200L): Long {
    return maxOf(configuredLongPressMs.toLong(), minSafeMs)
}

internal fun shouldCancelBallLongPress(distPx: Float, slopPx: Float): Boolean {
    return distPx > slopPx
}

internal data class SwipeOffset(val x: Float, val y: Float)

/**
 * 计算悬浮球手势滑动时的弹性阻尼位移。
 * 阻尼采用指数衰减曲线：位移越远阻力越大，平滑渐进逼近 [maxOffsetPx]，
 * 既保证手势跟手直观，又防止悬浮球被过度拉离原始锚点。
 */
internal fun computeDampedSwipeOffset(
    dx: Float,
    dy: Float,
    maxOffsetPx: Float,
    dampingFactor: Float = 1.5f,
): SwipeOffset {
    val dist = kotlin.math.hypot(dx, dy)
    if (dist <= 0.001f || maxOffsetPx <= 0f) return SwipeOffset(0f, 0f)
    val scale = maxOffsetPx * (1f - kotlin.math.exp(-dist / (maxOffsetPx * dampingFactor)))
    val ratio = scale / dist
    return SwipeOffset(dx * ratio, dy * ratio)
}

internal fun resolveBallCancelThreshold(swipeThresholdPx: Float, minCancelPx: Float): Float {
    val preferred = maxOf(minCancelPx, swipeThresholdPx * 0.5f)
    return minOf(preferred, swipeThresholdPx * 0.75f)
}

/**
 * 悬浮球手势打断与取消状态判定。
 * 具备迟滞区间（Hysteresis）：滑动划出阈值进入 Armed 状态，
 * 用户拖回中心判定圈（<= cancelThreshold）时触发打断并震动反馈；
 * 若未松手重新向外滑，可再次 Armed，有效防止误触并支持后悔撤销。
 */
internal class BallSwipeTracker(
    swipeThresholdPx: Float,
    cancelThresholdPx: Float,
) {
    var swipeThresholdPx: Float = swipeThresholdPx
        private set
    var cancelThresholdPx: Float = cancelThresholdPx
        private set
    var hasArmedSwipe: Boolean = false
        private set
    var isCancelledByReturn: Boolean = false
        private set

    fun configure(swipeThresholdPx: Float, cancelThresholdPx: Float) {
        this.swipeThresholdPx = swipeThresholdPx
        this.cancelThresholdPx = cancelThresholdPx
        reset()
    }

    /**
     * 根据当前与触摸起点的欧氏距离更新状态。
     * @return true 当且仅当本次移动刚跨入中心打断圈，需要立即派发震动提示手势已打断。
     */
    fun onMove(distPx: Float): Boolean {
        if (distPx >= swipeThresholdPx) {
            hasArmedSwipe = true
            isCancelledByReturn = false
            return false
        }
        if (hasArmedSwipe && distPx <= cancelThresholdPx) {
            hasArmedSwipe = false
            isCancelledByReturn = true
            return true
        }
        return false
    }

    fun reset() {
        hasArmedSwipe = false
        isCancelledByReturn = false
    }
}

/**
 * 悬浮球拖拽形变参数。
 * stretch: 沿拖拽方向的主轴伸长比例（>= 1.0）
 * squash: 垂直于拖拽方向的次轴压扁比例（<= 1.0，保体积保面积）
 * rotationDeg: 形变主轴朝向角度（0..360度）
 * iconTranslationX: 内核图标视差轻微跟手 X 偏移
 * iconTranslationY: 内核图标视差轻微跟手 Y 偏移
 */
internal data class BallDeformation(
    val stretch: Float,
    val squash: Float,
    val rotationDeg: Float,
    val iconTranslationX: Float,
    val iconTranslationY: Float,
)

/**
 * 根据位移向量 (dx, dy) 及参考距离计算悬浮球果冻拉伸形变。
 */
internal fun computeBallDeformation(
    dx: Float,
    dy: Float,
    maxOffsetPx: Float,
    maxStretch: Float = 0.22f,
    maxSquash: Float = 0.16f,
    parallaxRatio: Float = 0.10f,
): BallDeformation {
    val dist = kotlin.math.hypot(dx, dy)
    if (dist < 0.5f || maxOffsetPx <= 0f) {
        return BallDeformation(
            stretch = 1f,
            squash = 1f,
            rotationDeg = 0f,
            iconTranslationX = 0f,
            iconTranslationY = 0f,
        )
    }

    val angleDeg = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

    // 归一化位移并使用平滑饱和曲线，确保大距离拖动时平稳柔和，不发生突变或过度形变
    val normalized = (dist / maxOffsetPx).coerceIn(0f, 2.5f)
    val tension = normalized / (1f + 0.6f * normalized)

    val stretch = 1f + maxStretch * tension
    // 保体积面积约束：squash 随 stretch 缩减，保证视觉面积平衡
    val squash = (1f / (1f + maxStretch * tension * 0.9f)).coerceAtLeast(1f - maxSquash)

    // 内核图标视差跟手偏移（给用户水滴液体内部核心微移的纵深立体感）
    val maxParallax = maxOffsetPx * 0.25f
    val currentParallax = (dist * parallaxRatio).coerceAtMost(maxParallax)
    val dirX = dx / dist
    val dirY = dy / dist
    val iconTx = dirX * currentParallax
    val iconTy = dirY * currentParallax

    return BallDeformation(
        stretch = stretch,
        squash = squash,
        rotationDeg = angleDeg,
        iconTranslationX = iconTx,
        iconTranslationY = iconTy,
    )
}

/**
 * 饱满气泡微凸外弧量计算：
 * - 当长宽比为 1.0（正圆）时，外凸量为 0（保持完美纯正圆）；
 * - 当长宽比拉长时，两端严格保持 100% 圆润平滑的半球形穹顶，长边向外呈现柔和微凸的气泡张力弧度；
 * - 严格杜绝两端收缩变尖（消除橄榄球/柠檬尖角）。
 */
internal fun bubbleBulgeAmount(aspectRatio: Float, baseRadius: Float): Float {
    val rho = aspectRatio.coerceAtLeast(1f)
    return minOf(baseRadius * 0.08f, maxOf(0f, (rho - 1f) * baseRadius * 0.035f))
}

internal data class DragMotionState(
    val stretch: Float,
    val squash: Float,
    val rotationDeg: Float,
    val iconTranslationX: Float,
    val iconTranslationY: Float,
)

/**
 * 悬浮球长按拖拽时的速度感应果冻形变计算。
 *
 * @param speedPxPerSec 当前平滑移动速度（像素/秒）
 * @param dirX 运动方向单位向量 X
 * @param dirY 运动方向单位向量 Y
 * @param baseScale 悬浮球按住浮起时的基础放大系数（默认 1.16f）
 * @param maxExtraStretch 运动时的最大额外拉伸比（默认 0.22f）
 * @param maxSquash 运动时的垂直轴最大保体积压缩比（默认 0.18f）
 * @param maxParallaxPx 图标最大视差惯性滞后距离（默认 14f 像素）
 */
internal fun computeDragMotionState(
    speedPxPerSec: Float,
    dirX: Float,
    dirY: Float,
    baseScale: Float = 1.16f,
    refSpeedPxPerSec: Float = 1200f,
    maxExtraStretch: Float = 0.22f,
    maxSquash: Float = 0.18f,
    maxParallaxPx: Float = 14f,
): DragMotionState {
    if (speedPxPerSec < 10f || refSpeedPxPerSec <= 0f) {
        return DragMotionState(
            stretch = baseScale,
            squash = baseScale,
            rotationDeg = 0f,
            iconTranslationX = 0f,
            iconTranslationY = 0f,
        )
    }

    val normalized = (speedPxPerSec / refSpeedPxPerSec).coerceIn(0f, 3f)
    val tension = normalized / (1f + 0.6f * normalized)

    val stretch = baseScale * (1f + maxExtraStretch * tension)
    val squash = baseScale * (1f / (1f + maxSquash * tension))

    val angleDeg = Math.toDegrees(kotlin.math.atan2(dirY.toDouble(), dirX.toDouble())).toFloat()

    // 惯性视差：内核图标沿反方向产生滞后漂浮感（Lag behind motion）
    val parallaxDist = maxParallaxPx * tension
    val iconTx = -dirX * parallaxDist
    val iconTy = -dirY * parallaxDist

    return DragMotionState(
        stretch = stretch,
        squash = squash,
        rotationDeg = angleDeg,
        iconTranslationX = iconTx,
        iconTranslationY = iconTy,
    )
}

/** 角度平滑插值（处理 0° 与 360° 环绕过渡）。 */
internal fun lerpAngleDeg(from: Float, to: Float, factor: Float): Float {
    var diff = (to - from) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    return (from + diff * factor.coerceIn(0f, 1f)) % 360f
}

/**
 * 解析光圈颜色：0 表示跟随主题默认色（深色模式 0xFFD97757 珊瑚陶土，浅色模式 0xFFC96442 暖陶土）。
 * 若配置了界面强调色 [fallbackAccent]，默认跟随界面强调色。
 */
internal fun resolveTouchRingColor(customColor: Int, isDark: Boolean, fallbackAccent: Int = 0): Int {
    if (customColor != 0) return customColor
    if (fallbackAccent != 0) {
        return if ((fallbackAccent ushr 24) == 0) (fallbackAccent or 0xFF000000.toInt()) else fallbackAccent
    }
    return if (isDark) 0xFFD97757.toInt() else 0xFFC96442.toInt()
}

/** 哨兵值：表示跟随系统壁纸 Material You Monet 动态色彩提取。 */
internal const val COLOR_DYNAMIC = -2

/**
 * 解析设置界面强调色（按钮与滑动条）：0 表示跟随主题默认色（深色模式 0xFFD97757 珊瑚陶土，浅色模式 0xFFC96442 暖陶土）；COLOR_DYNAMIC 表示动态取色（无上下文时回退为默认色）。
 */
internal fun resolveUiAccentColor(customColor: Int, isDark: Boolean): Int {
    return when (customColor) {
        0, COLOR_DYNAMIC -> if (isDark) 0xFFD97757.toInt() else 0xFFC96442.toInt()
        else -> if ((customColor ushr 24) == 0) (customColor or 0xFF000000.toInt()) else customColor
    }
}

/**
 * 根据强调色解析前景文字/图标高对比度颜色（象牙白或暖黑）。
 */
internal fun resolveOnAccentColor(accentColor: Int): Int {
    return if (colorLuminance(accentColor) > 0.55f) 0xFF141413.toInt() else 0xFFFAF9F5.toInt()
}

/**
 * 根据强调色解析半透明柔和色与柔和强调描边色（用于开关轨道、选框高亮背景等）。
 */
internal fun resolveUiAccentSoftColors(accentColor: Int, isDark: Boolean): Pair<Int, Int> {
    val rgb = accentColor and 0x00FFFFFF
    val softAlpha = if (isDark) 0x33 else 0x1F
    val strongAlpha = if (isDark) 0x80 else 0x66
    return Pair((softAlpha shl 24) or rgb, (strongAlpha shl 24) or rgb)
}

/**
 * 解析悬浮球静止与交互状态下的不透明度，始终保持用户设定的透明度（20%–100%），
 * 杜绝在触摸、长按或拖拽时突然跳变至 100% 满透明度导致画面刺眼变亮。
 */
internal fun resolveBallRestingAlpha(opacityPercent: Int): Float =
    opacityPercent.coerceIn(20, 100) / 100f

internal const val BUTTON_CUSTOMIZE_HOLD_MIN_MS = 300
internal const val BUTTON_CUSTOMIZE_HOLD_MAX_MS = 3000
internal const val BUTTON_CUSTOMIZE_HOLD_DEFAULT_MS = 1000

/**
 * 解析长按触控板按钮自定义动作的判定时长，范围 300–3000 ms（最长 3 秒），默认 1000 ms。
 */
internal fun resolveButtonCustomizeHoldMs(configuredMs: Int): Int =
    configuredMs.coerceIn(BUTTON_CUSTOMIZE_HOLD_MIN_MS, BUTTON_CUSTOMIZE_HOLD_MAX_MS)

internal data class TouchpadPalette(
    val buttonBg: Int,
    val buttonStroke: Int,
    val buttonText: Int,
    val padBg: Int,
    val padStroke: Int,
    val ripple: Int,
    val handleBg: Int = buttonBg,
    val handleStroke: Int = buttonStroke,
    val handleText: Int = buttonText,
    val padTint: Int = buttonBg,
    val buttonTint: Int = buttonBg,
    val handleTint: Int = handleBg,
)

internal fun colorLuminance(color: Int): Float {
    fun toLinear(c: Int): Float {
        val s = c / 255f
        return if (s <= 0.04045f) s / 12.92f else Math.pow((s + 0.055) / 1.055, 2.4).toFloat()
    }
    val r = toLinear((color ushr 16) and 0xFF)
    val g = toLinear((color ushr 8) and 0xFF)
    val b = toLinear(color and 0xFF)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

internal fun lightenColor(color: Int, amount: Float): Int {
    val a = (color ushr 24) and 0xFF
    val r = (color ushr 16) and 0xFF
    val g = (color ushr 8) and 0xFF
    val b = color and 0xFF
    fun ch(c: Int) = (c + (255 - c) * amount).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

internal fun darkenColor(color: Int, amount: Float): Int {
    val a = (color ushr 24) and 0xFF
    val r = (color ushr 16) and 0xFF
    val g = (color ushr 8) and 0xFF
    val b = color and 0xFF
    fun ch(c: Int) = (c * (1f - amount)).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/**
 * 解析触控板调色板。
 * 0 表示跟随主题默认配色（暗色采用 M3 暗灰与象牙白字，浅色采用暖灰与暗黑字）。
 * 非 0 则根据自定义颜色自动计算板面凹陷底色、按钮浮起背景、高对比度图标前景及水波纹效果。
 */
internal fun resolveTouchpadPalette(customColor: Int, isDark: Boolean = true): TouchpadPalette {
    if (customColor == 0) {
        return if (isDark) {
            TouchpadPalette(
                buttonBg = 0xFF3A3936.toInt(),
                buttonStroke = 0x33FAF9F5,
                buttonText = 0xFFFAF9F5.toInt(),
                padBg = 0xFF2A2A28.toInt(),
                padStroke = 0x26FAF9F5,
                ripple = 0x33FAF9F5,
            )
        } else {
            TouchpadPalette(
                buttonBg = 0xFFF5F4ED.toInt(),
                buttonStroke = 0x40141413,
                buttonText = 0xFF141413.toInt(),
                padBg = 0xFFE8E6DC.toInt(),
                padStroke = 0x40141413,
                ripple = 0x26141413,
            )
        }
    }

    val opaqueColor = if ((customColor ushr 24) == 0) (customColor or 0xFF000000.toInt()) else customColor
    val isLight = colorLuminance(opaqueColor) > 0.55f
    return if (isLight) {
        TouchpadPalette(
            buttonBg = opaqueColor,
            buttonStroke = 0x33141413,
            buttonText = 0xFF141413.toInt(),
            padBg = darkenColor(opaqueColor, 0.06f),
            padStroke = 0x26141413,
            ripple = 0x26141413,
        )
    } else {
        TouchpadPalette(
            buttonBg = lightenColor(opaqueColor, 0.12f),
            buttonStroke = 0x33FAF9F5,
            buttonText = 0xFFFAF9F5.toInt(),
            padBg = opaqueColor,
            padStroke = 0x26FAF9F5,
            ripple = 0x33FAF9F5,
        )
    }
}

/**
 * 根据原始触摸屏幕绝对坐标 [rawX], [rawY]，以及触控板底座在屏幕上的绝对位置与尺寸，
 * 计算无透视畸变、无反馈回路的归一化按压坐标 [-1f, 1f]。
 */
internal fun resolvePadNormalizedCoords(
    rawX: Float,
    rawY: Float,
    panelX: Int,
    panelY: Int,
    padRectX: Int,
    padRectY: Int,
    padRectW: Int,
    padRectH: Int,
): Pair<Float, Float> {
    val centerX = panelX + padRectX + padRectW / 2f
    val centerY = panelY + padRectY + padRectH / 2f
    val halfW = (padRectW / 2f).coerceAtLeast(1f)
    val halfH = (padRectH / 2f).coerceAtLeast(1f)
    val normX = ((rawX - centerX) / halfW).coerceIn(-1f, 1f)
    val normY = ((rawY - centerY) / halfH).coerceIn(-1f, 1f)
    return Pair(normX, normY)
}

/** 根据横竖屏朝向生成独立的偏好设置存储键名 */
internal fun floatingBallPrefKey(isLandscape: Boolean, isY: Boolean): String {
    val suffix = if (isLandscape) "landscape" else "portrait"
    val axis = if (isY) "Y" else "X"
    return "ball${axis}_$suffix"
}

internal fun panelPrefKey(isLandscape: Boolean, isY: Boolean): String {
    val suffix = if (isLandscape) "landscape" else "portrait"
    val axis = if (isY) "Y" else "X"
    return "pad${axis}_$suffix"
}

/**
 * 光圈动态模糊参数：
 * 展开与缩放过程中计算动态模糊、径向拖尾与边缘羽化参数。
 */
internal data class TouchRingMotionBlur(
    val speed: Float,            // 0f (静止) .. 1f (极速)
    val blurRadius: Float,       // 动态模糊羽化半径 (px)
    val trailLag: Float,         // 径向拖影位移量 (px)，展开时向内落后，收缩时向外落后
    val blurStrokeWidth: Float,  // 模糊环描边宽度 (px)
    val blurAlpha: Float,        // 模糊层不透明度 0f .. 1f
    val isExpanding: Boolean,    // true: 正在展开, false: 正在收缩
)

internal fun calculateTouchRingMotionBlur(
    isExpanding: Boolean,
    isCollapsing: Boolean,
    progress: Float,
    collapseFromProgress: Float = 1f,
    baseAlpha: Float = 1f,
    maxRadius: Float = 0f,
    strokeWidthPx: Float = 0f,
    density: Float = 1f,
): TouchRingMotionBlur {
    if (!isExpanding && !isCollapsing) {
        return TouchRingMotionBlur(
            speed = 0f,
            blurRadius = 0f,
            trailLag = 0f,
            blurStrokeWidth = 0f,
            blurAlpha = 0f,
            isExpanding = false,
        )
    }

    val safeProgress = progress.coerceIn(0f, 1f)
    val speed = if (isExpanding) {
        (1f - safeProgress).pow(0.7f).coerceIn(0f, 1f)
    } else {
        val safeCollapseFrom = collapseFromProgress.coerceIn(0.01f, 1f)
        val shrink = (1f - safeProgress / safeCollapseFrom).coerceIn(0f, 1f)
        (1f - shrink).pow(0.7f).coerceIn(0f, 1f)
    }

    if (speed <= 0.02f) {
        return TouchRingMotionBlur(
            speed = 0f,
            blurRadius = 0f,
            trailLag = 0f,
            blurStrokeWidth = 0f,
            blurAlpha = 0f,
            isExpanding = isExpanding,
        )
    }

    val safeDensity = density.coerceAtLeast(1f)
    // 动态模糊半径：随动速放大，最高可达 ~1.5 倍描边加 2dp 弥散羽化
    val maxBlurRadius = strokeWidthPx * 1.5f + 2f * safeDensity
    val blurRadius = maxBlurRadius * speed

    // 径向拖影位移：展开时向内落后，收缩时向外落后
    val maxTrailLag = strokeWidthPx * 0.8f + maxRadius * 0.12f
    val trailLag = maxTrailLag * speed

    // 模糊描边宽度覆盖径向运动轨迹
    val blurStrokeWidth = strokeWidthPx + trailLag * 1.2f

    // 模糊层透明度随动速动态渐变
    val blurAlpha = (baseAlpha * speed * 0.72f).coerceIn(0f, 1f)

    return TouchRingMotionBlur(
        speed = speed,
        blurRadius = blurRadius,
        trailLag = trailLag,
        blurStrokeWidth = blurStrokeWidth,
        blurAlpha = blurAlpha,
        isExpanding = isExpanding,
    )
}

internal const val SPARK_FILLED_MAX_LIFE = 16f
internal const val SPARK_RING_MAX_LIFE = 23f
internal const val SPARK_ARC_LEN_STOP_ADD = 0.1f
internal const val SPARK_ARC_LEN_START_DIM = 0.4f

/**
 * 计算光弧尾色：将强调色与纯白按 1:2 混合，还原 BASpark / Blue Archive 风格的清透高光感。
 */
internal fun computeSparkRingEndColor(accentColor: Int): Int {
    val r = (((accentColor ushr 16) and 0xFF) + 510) / 3
    val g = (((accentColor ushr 8) and 0xFF) + 510) / 3
    val b = ((accentColor and 0xFF) + 510) / 3
    return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

/**
 * 计算光弧在生命周期 [ringProgress] (0..1) 处的颜色：
 * 从极浅高光白 (250, 252, 252) 平滑过渡至强调色柔光尾色。
 */
internal fun computeSparkRingColorAt(accentColor: Int, ringProgress: Float): Int {
    val t = (1.2f * ringProgress).coerceIn(0f, 1f)
    val end = computeSparkRingEndColor(accentColor)
    val er = (end ushr 16) and 0xFF
    val eg = (end ushr 8) and 0xFF
    val eb = end and 0xFF
    val r = (250f * (1f - t) + er * t).roundToInt().coerceIn(0, 255)
    val g = (252f * (1f - t) + eg * t).roundToInt().coerceIn(0, 255)
    val b = (252f * (1f - t) + eb * t).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 计算中心扩散实心圆半径：三次缓出 (1 - (1 - p)^3)。
 */
internal fun computeSparkFilledCircleRadius(maxRadius: Float, waveProgress: Float): Float {
    val rMax = maxRadius.coerceAtLeast(0f)
    val p = waveProgress.coerceIn(0f, 1f)
    val inv = 1f - p
    val ease = 1f - inv * inv * inv
    return rMax * ease
}

/**
 * 计算光弧分段粗细权重：两端尖细 (0)、中段饱满 (1) 的梯形轮廓。
 */
internal fun computeSparkArcTaperWeight(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    return (2f - kotlin.math.abs(4f * (clamped - 0.5f))).coerceIn(0f, 1f)
}

internal data class SparkArcSpan(
    val startRad: Float,
    val sweepRad: Float,
)

/**
 * 计算光弧三段式弧长演变：
 * - [0, 0.1]：从前端快速拉长至满弧长；
 * - (0.1, 0.4]：保持满弧长旋转扫过；
 * - (0.4, 1.0]：从尾部平滑收束至 0。
 */
internal fun computeSparkArcSpan(
    baseRad: Float,
    segLenRad: Float,
    ringProgress: Float,
): SparkArcSpan {
    val p = ringProgress.coerceIn(0f, 1f)
    val full = segLenRad.coerceAtLeast(0f)
    return when {
        p <= SPARK_ARC_LEN_STOP_ADD -> {
            val len = full * (p / SPARK_ARC_LEN_STOP_ADD)
            val end = baseRad + full
            SparkArcSpan(startRad = end - len, sweepRad = len)
        }
        p <= SPARK_ARC_LEN_START_DIM -> {
            SparkArcSpan(startRad = baseRad, sweepRad = full)
        }
        else -> {
            val len = full * (1f - (p - SPARK_ARC_LEN_START_DIM) / (1f - SPARK_ARC_LEN_START_DIM))
            SparkArcSpan(startRad = baseRad, sweepRad = len.coerceAtLeast(0f))
        }
    }
}

/**
 * 光弧生命周期末段的线宽衰减系数（0.8 之后平滑略微收细）。
 */
internal fun computeSparkArcWidthMul(ringProgress: Float): Float {
    val p = ringProgress.coerceIn(0f, 1f)
    return (-0.8f * (p - 0.8f) + 1f).coerceIn(0f, 1f)
}

/**
 * 光弧生命周期不透明度：保持前期高对比通透感，并在最后 12% 平滑淡出至 0 避免残点跳变。
 */
internal fun computeSparkArcAlpha(ringProgress: Float): Float {
    val p = ringProgress.coerceIn(0f, 1f)
    val base = (1.1f - 0.3f * p).coerceIn(0f, 1f)
    if (p <= 0.88f) return base
    val tail = ((1f - p) / 0.12f).coerceIn(0f, 1f)
    return base * tail
}

/**
 * 计算足以容纳旋转光弧外扩光晕与四散三角火花的悬浮窗安全边长（px），确保任何屏幕密度下均不裁切。
 */
internal fun computeSparkWindowSizePx(
    maxRadius: Float,
    strokeWidthPx: Float,
    density: Float,
): Int {
    val r = maxRadius.coerceAtLeast(1f)
    val sw = strokeWidthPx.coerceAtLeast(1f)
    val d = density.coerceAtLeast(1f)
    // 三角粒子最大飞行半径约 1.68 * maxRadius，加上粒子半尺寸 (0.27 * maxRadius) 与外发光描边，半窗预留 2.25 * r + 2 * sw + 4dp
    val halfSize = kotlin.math.ceil(r * 2.25f + sw * 2f + 4f * d).toInt()
    return (halfSize * 2).coerceAtLeast(16)
}

/**
 * 计算 BASpark 中心圆与光弧的径向动态模糊强度（基于三次缓出导数 (1 - p)^2）：
 * 初段极速膨胀时为 1.0，随减速平滑衰减至 0（静止/尾段保持锐利）。
 */
internal fun computeSparkRadialBlurFactor(waveProgress: Float): Float {
    val p = waveProgress.coerceIn(0f, 1f)
    val inv = 1f - p
    val speed = inv * inv
    return if (speed <= 0.02f) 0f else speed
}

/**
 * 计算三角火花粒子的方向性速度动态模糊强度：
 * 当瞬时飞行速度低于参考速度的 12% 时返回 0（完全锐利），高速爆裂初段平滑升至 1.0。
 */
internal fun computeSparkParticleBlurStrength(
    speedPxPerFrame: Float,
    refSpeedPxPerFrame: Float,
): Float {
    if (refSpeedPxPerFrame <= 0f || speedPxPerFrame <= 0f) return 0f
    val norm = (speedPxPerFrame / refSpeedPxPerFrame).coerceIn(0f, 1.35f)
    if (norm <= 0.12f) return 0f
    return ((norm - 0.12f) / 0.88f).coerceIn(0f, 1f)
}


/**
 * 计算背景图片在容器中按 Center-Crop 配合焦点百分比对齐时的平移偏移量。
 * @param containerSize 容器大小 (例如 width 或 height)
 * @param scaledImageSize 缩放后的图片大小 (例如 destW 或 destH)
 * @param focusPercent 焦点百分比 (0 ~ 100)，0 为左/顶对齐，50 为居中，100 为右/底对齐
 */
internal fun calculateCropOffset(
    containerSize: Float,
    scaledImageSize: Float,
    focusPercent: Int,
): Float {
    val clampedFocus = (focusPercent.coerceIn(0, 100)) / 100f
    return (containerSize - scaledImageSize) * clampedFocus
}

/**
 * 根据面板不透明度百分比计算 Apple 液态玻璃混合系数（0 = 纯 Material You，1 = 纯液态玻璃）。
 * - 80%–100%（偏实心）：纯 Material You 材质（0f）
 * - 20%–60%（半透明/高透光）：纯 Apple 液态玻璃材质（1f）
 * - 60%–80%：Smoothstep 三次 Hermite 平滑过渡
 */
internal fun computeLiquidGlassFactor(opacityPercent: Int): Float {
    val clamped = opacityPercent.coerceIn(20, 100)
    if (clamped >= 80) return 0f
    if (clamped <= 60) return 1f
    val t = (80f - clamped) / 20f
    return t * t * (3f - 2f * t)
}

/**
 * 计算外轮廓高光描边的主 Alpha 值（0..255）。
 * 在 Material You 模式下随不透明度柔和缩放；在液态玻璃模式下，
 * 边缘的菲涅尔镜面反光保持克制通透（76..124 之间），在低不透明度下仍清晰勾勒轮廓且不刺眼。
 */
internal fun computeGlassRimAlpha(opacityPercent: Int, glassFactor: Float): Int {
    val clamped = opacityPercent.coerceIn(20, 100)
    val gf = glassFactor.coerceIn(0f, 1f)
    val materialRim = 72f * (clamped / 100f)
    val glassRim = 76f + ((clamped - 20) / 80f) * 48f
    return (materialRim + (glassRim - materialRim) * gf).roundToInt().coerceIn(0, 255)
}

/**
 * 计算液态玻璃内部高光（焦散光斑、表面张力内弧）随不透明度的缩放系数（0.45f..1.0f）。
 * 在低不透明度下进一步收敛高光与反光强度，消除发白雾感与厚重亮边。
 */
internal fun computeGlassHighlightScale(opacityPercent: Int): Float {
    val clamped = opacityPercent.coerceIn(20, 100)
    return 0.45f + ((clamped - 20) / 80f) * 0.55f
}

/** 按压 3D 倾斜时的液态玻璃高光流动偏移与亮度增益。 */
internal data class SpecularShift(val shiftXRatio: Float, val shiftYRatio: Float, val boost: Float)

internal fun computeSpecularShift(normX: Float, normY: Float, maxRatio: Float = 0.28f): SpecularShift {
    val cx = normX.coerceIn(-1f, 1f)
    val cy = normY.coerceIn(-1f, 1f)
    val shiftX = if (cx == 0f) 0f else -cx * maxRatio
    val shiftY = if (cy == 0f) 0f else -cy * maxRatio
    val boost = (kotlin.math.hypot(cx, cy).coerceIn(0f, 1.4f) / 1.4f) * 0.08f
    return SpecularShift(shiftXRatio = shiftX, shiftYRatio = shiftY, boost = boost)
}

/** 替换 ARGB 颜色的 Alpha 通道（纯位运算，兼容本地 JVM 单元测试）。 */
internal fun replaceAlpha(color: Int, alpha: Int): Int {
    val a = alpha.coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

/** 在两个 ARGB 颜色之间进行四通道线性插值（纯位运算，兼容本地 JVM 单元测试）。 */
internal fun lerpArgb(colorA: Int, colorB: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    val aA = (colorA ushr 24) and 0xFF
    val rA = (colorA ushr 16) and 0xFF
    val gA = (colorA ushr 8) and 0xFF
    val bA = colorA and 0xFF

    val aB = (colorB ushr 24) and 0xFF
    val rB = (colorB ushr 16) and 0xFF
    val gB = (colorB ushr 8) and 0xFF
    val bB = colorB and 0xFF

    val a = (aA + (aB - aA) * f).roundToInt().coerceIn(0, 255)
    val r = (rA + (rB - rA) * f).roundToInt().coerceIn(0, 255)
    val g = (gA + (gB - gA) * f).roundToInt().coerceIn(0, 255)
    val b = (bA + (bB - bA) * f).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

// ───────────────────────── 触控板面板拖动整体形变物理模型 ─────────────────────────

/** 面板拖动形变目标矢量（单位圆内，hypot(x, y) <= 1f）。 */
internal data class PanelDragTarget(val x: Float, val y: Float)

/**
 * 将面板拖动瞬时速度（像素/秒）平滑饱和映射到单位圆内的形变目标矢量。
 * - 低于 [deadzoneDpPerSec] 忽略微颤，避免静止抖动；
 * - 常规拖动速度（~420 dp/s）即可呈现清晰饱满的方向形变；
 * - 高速甩动平滑渐进饱和至 1.0，避免过度失真。
 */
internal fun computePanelDragTarget(
    vxPxPerSec: Float,
    vyPxPerSec: Float,
    density: Float,
    deadzoneDpPerSec: Float = 25f,
    refSpeedDpPerSec: Float = 420f,
): PanelDragTarget {
    val safeDensity = density.coerceAtLeast(1f)
    val speed = kotlin.math.hypot(vxPxPerSec, vyPxPerSec)
    val deadzone = deadzoneDpPerSec * safeDensity
    val refSpeed = (refSpeedDpPerSec * safeDensity).coerceAtLeast(1f)
    if (speed <= deadzone) return PanelDragTarget(0f, 0f)

    val effectiveSpeed = speed - deadzone
    val normalized = (effectiveSpeed / refSpeed).coerceIn(0f, 4f)
    val tension = (normalized / (1f + 0.55f * normalized)).coerceIn(0f, 1f)
    val invSpeed = 1f / speed
    return PanelDragTarget(
        x = vxPxPerSec * invSpeed * tension,
        y = vyPxPerSec * invSpeed * tension,
    )
}

/**
 * 计算移动把手中心点相对整个面板中心的归一化锚点坐标 [-1f, 1f]²。
 * 用于根据抓握位置产生物理真实的惯性摆动（力矩 torque = anchor × motion）。
 */
internal fun computePanelHandleAnchor(
    handleLeft: Float,
    handleTop: Float,
    handleWidth: Float,
    handleHeight: Float,
    panelWidth: Float,
    panelHeight: Float,
): Pair<Float, Float> {
    if (panelWidth <= 0f || panelHeight <= 0f) return 0f to 0f
    val halfW = (panelWidth / 2f).coerceAtLeast(1f)
    val halfH = (panelHeight / 2f).coerceAtLeast(1f)
    val hcx = handleLeft + handleWidth / 2f
    val hcy = handleTop + handleHeight / 2f
    val ax = ((hcx - halfW) / halfW).coerceIn(-1f, 1f)
    val ay = ((hcy - halfH) / halfH).coerceIn(-1f, 1f)
    return ax to ay
}

/** 触控板整体形变的弹簧动力学状态。 */
internal data class PanelSpringState(
    val x: Float = 0f,
    val y: Float = 0f,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val grab: Float = 0f,
    val grabVel: Float = 0f,
) {
    val isSettled: Boolean
        get() = kotlin.math.abs(x) < 0.002f &&
            kotlin.math.abs(y) < 0.002f &&
            kotlin.math.abs(vx) < 0.015f &&
            kotlin.math.abs(vy) < 0.015f &&
            kotlin.math.abs(grab) < 0.002f &&
            kotlin.math.abs(grabVel) < 0.015f
}

/**
 * 半隐式欧拉子步弹簧积分：
 * - 拖动中：高响应阻尼弹簧紧跟手指方向与速度；
 * - 停顿或松手时：欠阻尼弹性弹簧自然回弹，呈现出 Q 弹果冻惯性收尾，绝不僵硬卡顿。
 */
internal fun stepPanelDragSpring(
    state: PanelSpringState,
    targetX: Float,
    targetY: Float,
    targetGrab: Float,
    dtSec: Float,
    isDragging: Boolean,
): PanelSpringState {
    val clampedDt = dtSec.coerceIn(0.001f, 0.040f)
    val subSteps = if (clampedDt > 0.016f) 2 else 1
    val stepDt = clampedDt / subSteps

    val motionOmega = if (isDragging) 20f else 17f
    val motionZeta = if (isDragging) 0.68f else 0.50f
    val motionK = motionOmega * motionOmega
    val motionC = 2f * motionZeta * motionOmega

    val grabOmega = if (isDragging) 24f else 20f
    val grabZeta = if (isDragging) 0.85f else 0.92f
    val grabK = grabOmega * grabOmega
    val grabC = 2f * grabZeta * grabOmega

    var x = state.x
    var y = state.y
    var vx = state.vx
    var vy = state.vy
    var grab = state.grab
    var grabVel = state.grabVel

    repeat(subSteps) {
        val ax = motionK * (targetX - x) - motionC * vx
        val ay = motionK * (targetY - y) - motionC * vy
        vx += ax * stepDt
        vy += ay * stepDt
        x += vx * stepDt
        y += vy * stepDt

        val aGrab = grabK * (targetGrab - grab) - grabC * grabVel
        grabVel += aGrab * stepDt
        grab = (grab + grabVel * stepDt).coerceIn(0f, 1.15f)
    }

    // 安全限幅，防止极端输入产生数值发散
    val mag = kotlin.math.hypot(x, y)
    if (mag > 1.35f) {
        val s = 1.35f / mag
        x *= s
        y *= s
    }

    return PanelSpringState(
        x = x,
        y = y,
        vx = vx,
        vy = vy,
        grab = grab,
        grabVel = grabVel,
    )
}

/** 使用 3×3 齐次矩阵（Android Matrix 行优先顺序）投影二维点 (x, y)。 */
internal fun projectMatrixPoint(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
    val w = m[6] * x + m[7] * y + m[8]
    val invW = if (kotlin.math.abs(w) > 1e-6f) 1f / w else 1f
    val px = (m[0] * x + m[1] * y + m[2]) * invW
    val py = (m[3] * x + m[4] * y + m[5]) * invW
    return px to py
}

/** 写入 3×3 单位矩阵。 */
internal fun writeIdentityMatrix(out: FloatArray) {
    out[0] = 1f; out[1] = 0f; out[2] = 0f
    out[3] = 0f; out[4] = 1f; out[5] = 0f
    out[6] = 0f; out[7] = 0f; out[8] = 1f
}

/**
 * 构建触控板整体拖动形变的 3×3 像素空间齐次矩阵（直接写入 [outMatrix]，热路径零对象分配）。
 *
 * 在归一化面板空间 [-1, 1]² 内按物理顺序组合四重形变，保证任意宽高比下视觉均衡：
 * 1. 抓握弹性微收（grabShrink）：手指按住把手时内收留出形变缓冲空间，松手弹性复原；
 * 2. 任意方向各向异性果冻拉伸/挤压（R(θ)·S(stretch, squash)·R(-θ)）：沿移动方向柔韧拉长、垂直方向保面积收窄，不旋转翻转图标；
 * 3. 把手力矩惯性微摆（torque = anchor × motion）：以移动把手为受力点产生轻盈的拖拽摆角；
 * 4. 方向性 3D 透视倾斜与尾迹收束（leading lift / trailing taper）+ 细微惯性中心滞后；
 * 5. 四角精确包络限幅（fitScale）：保证四个角点严格落在窗口 [0, W] × [0, H] 内部，彻底杜绝 Surface 边缘硬裁切。
 *
 * @return true 表示存在有效形变，需在 Canvas 上应用；false 表示已处于静止恒等态。
 */
internal fun computePanelDragMatrix(
    outMatrix: FloatArray,
    widthPx: Float,
    heightPx: Float,
    springX: Float,
    springY: Float,
    grabProgress: Float,
    anchorX: Float = 0f,
    anchorY: Float = 0f,
    grabShrink: Float = 0.075f,
    maxStretch: Float = 0.080f,
    maxSquash: Float = 0.080f,
    maxSwingDeg: Float = 3.6f,
    maxTiltDeg: Float = 13.5f,
    cameraDistNorm: Float = 3.8f,
    maxLagNorm: Float = 0.022f,
    safeLimit: Float = 0.992f,
): Boolean {
    if (widthPx <= 0f || heightPx <= 0f) {
        writeIdentityMatrix(outMatrix)
        return false
    }

    val clampedGrab = grabProgress.coerceIn(0f, 1.15f)
    val mag = kotlin.math.hypot(springX, springY).coerceAtMost(1.35f)
    if (clampedGrab < 0.001f && mag < 0.001f) {
        writeIdentityMatrix(outMatrix)
        return false
    }

    val baseScale = (1f - grabShrink * clampedGrab).coerceIn(0.80f, 1f)

    // 1. 方向各向异性拉伸与挤压矩阵 M_strain * baseScale
    val s00: Float
    val s01: Float
    val s11: Float
    val dirX: Float
    val dirY: Float
    if (mag > 1e-4f) {
        dirX = springX / mag
        dirY = springY / mag
        val stretch = 1f + maxStretch * mag
        val squash = 1f / (1f + maxSquash * mag)
        s00 = (stretch * dirX * dirX + squash * dirY * dirY) * baseScale
        s11 = (stretch * dirY * dirY + squash * dirX * dirX) * baseScale
        s01 = (stretch - squash) * dirX * dirY * baseScale
    } else {
        dirX = 0f
        dirY = 0f
        s00 = baseScale
        s01 = 0f
        s11 = baseScale
    }

    // 2. 把手力矩惯性微摆 M_swing * (M_strain * baseScale)
    val torque = (anchorX * springY - anchorY * springX).coerceIn(-1.2f, 1.2f)
    val swingRad = Math.toRadians((torque * maxSwingDeg).toDouble())
    val cosPhi = kotlin.math.cos(swingRad).toFloat()
    val sinPhi = kotlin.math.sin(swingRad).toFloat()

    val a00 = cosPhi * s00 - sinPhi * s01
    val a01 = cosPhi * s01 - sinPhi * s11
    val a10 = sinPhi * s00 + cosPhi * s01
    val a11 = sinPhi * s01 + cosPhi * s11

    // 3. 方向性 3D 透视倾斜（前缘抬升展宽，尾缘退远收束）
    val p00: Float
    val p01: Float
    val p10: Float
    val p11: Float
    val p20: Float
    val p21: Float
    if (mag > 1e-4f) {
        val tiltRad = Math.toRadians((maxTiltDeg * mag.coerceAtMost(1.25f)).toDouble())
        val cosAlpha = kotlin.math.cos(tiltRad).toFloat()
        val sinAlpha = kotlin.math.sin(tiltRad).toFloat()
        val safeCam = cameraDistNorm.coerceAtLeast(2.0f)
        p00 = cosAlpha * dirX * dirX + dirY * dirY
        p01 = (cosAlpha - 1f) * dirX * dirY
        p10 = p01
        p11 = dirX * dirX + cosAlpha * dirY * dirY
        p20 = -(sinAlpha / safeCam) * dirX
        p21 = -(sinAlpha / safeCam) * dirY
    } else {
        p00 = 1f; p01 = 0f
        p10 = 0f; p11 = 1f
        p20 = 0f; p21 = 0f
    }

    // B = M_persp * A
    val b00 = p00 * a00 + p01 * a10
    val b01 = p00 * a01 + p01 * a11
    val b10 = p10 * a00 + p11 * a10
    val b11 = p10 * a01 + p11 * a11
    val b20 = p20 * a00 + p21 * a10
    val b21 = p20 * a01 + p21 * a11

    // 4. 惯性中心微滞后 T_lag * B
    val lagU = -springX * maxLagNorm
    val lagV = -springY * maxLagNorm

    var n00 = b00 + lagU * b20
    var n01 = b01 + lagU * b21
    var n02 = lagU
    var n10 = b10 + lagV * b20
    var n11 = b11 + lagV * b21
    var n12 = lagV
    val n20 = b20
    val n21 = b21
    val n22 = 1f

    // 5. 四角精确包络限幅，确保所有顶点严格落在 [-safeLimit, +safeLimit]² 内，零窗口裁切
    var maxAbsCoord = 0f
    for (c in 0 until 4) {
        val cu = if (c == 0 || c == 3) -1f else 1f
        val cv = if (c < 2) -1f else 1f
        val cw = n20 * cu + n21 * cv + n22
        val invW = if (kotlin.math.abs(cw) > 1e-5f) 1f / cw else 1f
        val pu = kotlin.math.abs((n00 * cu + n01 * cv + n02) * invW)
        val pv = kotlin.math.abs((n10 * cu + n11 * cv + n12) * invW)
        if (pu > maxAbsCoord) maxAbsCoord = pu
        if (pv > maxAbsCoord) maxAbsCoord = pv
    }

    if (maxAbsCoord > safeLimit) {
        val fitScale = safeLimit / maxAbsCoord
        n00 *= fitScale
        n01 *= fitScale
        n02 *= fitScale
        n10 *= fitScale
        n11 *= fitScale
        n12 *= fitScale
    }

    // 6. 共轭映射回像素坐标空间：M_pixel = P * M_norm * P⁻¹
    //    其中 P = diag(hw, hh, 1) + translate(hw, hh)，P⁻¹ = diag(1/hw, 1/hh, 1) + translate(-1, -1)
    val hw = widthPx / 2f
    val hh = heightPx / 2f
    val invHw = 1f / hw
    val invHh = 1f / hh

    // 先算 Q = P * M_norm
    val q00 = hw * n00 + hw * n20
    val q01 = hw * n01 + hw * n21
    val q02 = hw * n02 + hw * n22
    val q10 = hh * n10 + hh * n20
    val q11 = hh * n11 + hh * n21
    val q12 = hh * n12 + hh * n22

    // 再算 M_pixel = Q * P⁻¹
    outMatrix[0] = q00 * invHw
    outMatrix[1] = q01 * invHh
    outMatrix[2] = q02 - q00 - q01

    outMatrix[3] = q10 * invHw
    outMatrix[4] = q11 * invHh
    outMatrix[5] = q12 - q10 - q11

    outMatrix[6] = n20 * invHw
    outMatrix[7] = n21 * invHh
    outMatrix[8] = n22 - n20 - n21

    return true
}

// ───────────────────────── 兼容性与性能热路径纯逻辑 ─────────────────────────

/**
 * 将注入手势坐标严格钳制在系统有效像素区间 [0, screenSize - 1] 内：
 * Android 10–16 部分机型 InputDispatcher 会静默丢弃 x >= screenWidth 或 y >= screenHeight 的手势。
 */
internal fun clampGestureCoord(value: Float, screenSize: Int): Float {
    val maxCoord = (screenSize - 1).coerceAtLeast(0).toFloat()
    if (!value.isFinite()) return 0f
    return value.coerceIn(0f, maxCoord)
}

/**
 * 过滤与输入法显隐无关的 TYPE_WINDOWS_CHANGED 变更位（API 28+）。
 * 仅对窗口新增、移除、边界改变、层级改变或活跃切换响应，跳过标题/焦点/PiP/子窗口等无关变更。
 * [windowChanges] 为 0（API < 28 或系统未填充细粒度位）时默认放行。
 */
internal const val IME_RELEVANT_WINDOW_CHANGES = 1 or 2 or 8 or 16 or 32 // ADDED | REMOVED | BOUNDS | LAYER | ACTIVE

internal fun shouldReactToWindowsChanged(windowChanges: Int): Boolean =
    windowChanges == 0 || (windowChanges and IME_RELEVANT_WINDOW_CHANGES) != 0

/**
 * 判断 TYPE_INPUT_METHOD 窗口在屏幕上的边界是否为真实可见的输入法面板：
 * 过滤部分 ROM（三星、Gboard 悬浮模式、国产输入法）收起后仍残留的空矩形或极窄导航条占位窗口。
 */
internal fun isEffectiveImeBounds(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    minHeightPx: Int,
): Boolean {
    val w = right - left
    val h = bottom - top
    return w > 0 && h >= minHeightPx.coerceAtLeast(1)
}

/**
 * 解析屏幕逻辑宽高：
 * - Android 11+ (API 30+) [trustWindowMetrics] 为 true，直接信任 maximumWindowMetrics.bounds，
 *   不再按横竖屏配置强行互换宽高，避免折叠屏展开态/近似方形内屏被错误颠倒；
 * - Android 8–10 [trustWindowMetrics] 为 false，保留旧版 getRealMetrics 旋转不同步时的兜底互换。
 */
internal fun resolveScreenBounds(
    metricsWidth: Int,
    metricsHeight: Int,
    isLandscapeConfig: Boolean,
    trustWindowMetrics: Boolean,
): PanelPosition {
    var w = metricsWidth.coerceAtLeast(0)
    var h = metricsHeight.coerceAtLeast(0)
    if (!trustWindowMetrics && w != h) {
        if ((isLandscapeConfig && w < h) || (!isLandscapeConfig && w > h)) {
            val tmp = w
            w = h
            h = tmp
        }
    }
    return PanelPosition(w, h)
}

/**
 * 判断当前是否已处于穿透态（FLAG_NOT_TOUCHABLE 已生效），用于续接手势（continueStroke）
 * 或队列连续穿透点击时跳过重复的 50ms 等待。
 */
internal fun shouldSkipPassThroughDelay(alreadyNonTouchable: Boolean): Boolean = alreadyNonTouchable

/**
 * 3D 倾斜流光变化量化过滤：忽略肉眼不可见的微小浮点抖动，回零时必刷新。
 */
internal fun quantizeTiltChanged(
    prevX: Float,
    prevY: Float,
    prevIntensity: Float,
    newX: Float,
    newY: Float,
    newIntensity: Float,
    threshold: Float = 0.008f,
): Boolean {
    val resettingToZero = newX == 0f && newY == 0f && newIntensity == 0f &&
        (prevX != 0f || prevY != 0f || prevIntensity != 0f)
    if (resettingToZero) return true
    return kotlin.math.abs(newX - prevX) >= threshold ||
        kotlin.math.abs(newY - prevY) >= threshold ||
        kotlin.math.abs(newIntensity - prevIntensity) >= threshold
}

/**
 * 判断面板拖动形变弹簧是否已在当前目标值收敛静止（用于按住移动把手停顿时暂停 Choreographer 空转）。
 */
internal fun isPanelSpringAtRestForTarget(
    state: PanelSpringState,
    targetX: Float,
    targetY: Float,
    targetGrab: Float,
): Boolean =
    kotlin.math.abs(state.x - targetX) < 0.002f &&
        kotlin.math.abs(state.y - targetY) < 0.002f &&
        kotlin.math.abs(state.vx) < 0.015f &&
        kotlin.math.abs(state.vy) < 0.015f &&
        kotlin.math.abs(state.grab - targetGrab) < 0.002f &&
        kotlin.math.abs(state.grabVel) < 0.015f

/**
 * 将 android.media.ExifInterface 方向枚举映射为顺时针旋转角度（0 / 90 / 180 / 270）。
 */
internal fun resolveExifRotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
    6 -> 90   // ExifInterface.ORIENTATION_ROTATE_90
    3 -> 180  // ExifInterface.ORIENTATION_ROTATE_180
    8 -> 270  // ExifInterface.ORIENTATION_ROTATE_270
    else -> 0
}

/**
 * 液态玻璃 4-Shader 环境流光重建节流（适配 60/90/120/144/165Hz 多档位刷新率）：
 * - 按下首帧（prevIntensity <= 0.01f）与抬手回零（newIntensity <= 0.01f）永不节流；
 * - 默认阈值 13ms：60Hz 屏（16.67ms）抗 ±3ms 抖动稳跑 60Hz，120Hz 稳 60Hz（2:1），144Hz 稳 72Hz（2:1），
 *   彻底避免 16ms 阈值在 60Hz 屏误掉到 30Hz 或 144Hz 退化到 48Hz 的拍频卡顿；
 * - 仅用于节流高开销液态玻璃环境着色器，光标与主 3D 倾斜仍保持满刷原生刷新率。
 */
internal fun shouldUpdateAmbientGlassShaders(
    nowMs: Long,
    lastUpdateMs: Long,
    prevIntensity: Float,
    newIntensity: Float,
    minIntervalMs: Long = 13L,
): Boolean {
    if (minIntervalMs <= 0L) return true
    val isEdgeTransition = (prevIntensity <= 0.01f && newIntensity > 0.01f) ||
        (newIntensity <= 0.01f && prevIntensity > 0.01f)
    if (isEdgeTransition) return true
    val elapsed = nowMs - lastUpdateMs
    return elapsed < 0L || elapsed >= minIntervalMs
}

/**
 * 刷新率 / 触控采样率无关的指数平滑系数：alpha = 1 - exp(-dtSec / tauSec)。
 * 严格满足数学极限：dtSec <= 0 时返回 0（时间未流逝保持旧值），tauSec <= 0 时返回 1（瞬时跟踪）。
 */
internal fun computeVrrEmaAlpha(dtSec: Float, tauSec: Float): Float {
    if (dtSec <= 0f) return 0f
    if (tauSec <= 0f) return 1f
    return (1.0 - kotlin.math.exp((-dtSec / tauSec).toDouble())).toFloat().coerceIn(0f, 1f)
}

/**
 * 整板拖动形变单步速度采样 dt：覆盖 240Hz 超高刷屏（~4.17ms）到 20Hz 低刷帧（~50ms）。
 */
internal fun resolveDeformSampleDt(sampleGapNs: Long, frameDt: Float): Float =
    if (sampleGapNs in 3_500_000L..55_000_000L) {
        sampleGapNs / 1_000_000_000f
    } else {
        frameDt.coerceIn(0.004f, 0.040f)
    }

/**
 * 整板拖动速度自然衰减的静默判定窗口（ms）：
 * 低刷（如 30Hz，帧间隔 33.3ms）下自动拉宽至 1.6×帧间隔，避免相邻两帧间误衰减。
 */
internal fun resolveDeformDecayDelayMs(frameDt: Float): Long =
    maxOf(32L, (frameDt * 1000f * 1.6f).toLong())

// ───────────────────────── 内存优化与零分配热路径纯逻辑 ─────────────────────────

/** 按 Center-Crop 规则计算出的底图解码尺寸及是否已达分辨率上限。 */
internal data class CropDecodePlan(
    val targetWidth: Int,
    val targetHeight: Int,
    val reachedCeiling: Boolean,
)

/**
 * 按 Center-Crop 最小覆盖规则计算运行期解码目标尺寸（只缩不放，长边不超过 [maxLongEdge]）：
 * - 保证任意长宽比（9:20 竖屏立绘、21:9 宽图等）在目标容器内渲染时均保持 >= 1:1 像素清晰度；
 * - 严格保留原图纵横比以保证焦点偏移（bgFocusX/YPercent）与完整原图 100% 一致；
 * - [CropDecodePlan.reachedCeiling] 指示是否已解至源图 1:1 或触及 [maxLongEdge] 上限，避免后续放大反复无效重解。
 */
internal fun computeCenterCropDecodeSize(
    srcW: Int,
    srcH: Int,
    targetW: Int,
    targetH: Int,
    maxLongEdge: Int = 1200,
): CropDecodePlan {
    if (srcW <= 0 || srcH <= 0) return CropDecodePlan(0, 0, reachedCeiling = true)
    val safeTargetW = targetW.coerceAtLeast(1)
    val safeTargetH = targetH.coerceAtLeast(1)
    val safeMaxLong = maxLongEdge.coerceAtLeast(1)
    val maxSrc = maxOf(srcW, srcH)

    val cropScale = maxOf(safeTargetW.toFloat() / srcW, safeTargetH.toFloat() / srcH)
    val capScale = safeMaxLong.toFloat() / maxSrc
    val limitScale = minOf(capScale, 1f)
    val effectiveScale = minOf(cropScale, limitScale)
    val reachedCeiling = cropScale >= limitScale - 1e-4f

    if (effectiveScale >= 1f) {
        return CropDecodePlan(srcW, srcH, reachedCeiling = true)
    }
    val dw = (srcW * effectiveScale).roundToInt().coerceIn(1, srcW)
    val dh = (srcH * effectiveScale).roundToInt().coerceIn(1, srcH)
    return CropDecodePlan(dw, dh, reachedCeiling = reachedCeiling)
}

/** 判断触控板放大后是否需要对已缓存的自定义底图按需升采样。 */
internal fun shouldUpsampleTouchpadBg(
    padW: Int,
    padH: Int,
    loadedTargetW: Int,
    loadedTargetH: Int,
    reachedCeiling: Boolean,
    thresholdRatio: Float = 1.15f,
): Boolean {
    if (reachedCeiling || padW <= 0 || padH <= 0) return false
    if (loadedTargetW <= 0 || loadedTargetH <= 0) return true
    return padW > loadedTargetW * thresholdRatio || padH > loadedTargetH * thresholdRatio
}

/**
 * 写入单位水平渐变 (0,0)->(1,0) 到倾斜渐变 (raisedX, raisedY)->(sinkX, sinkY) 的保角正交相似矩阵：
 * M = [[dx, -dy, ax], [dy, dx, ay], [0, 0, 1]]，在任意非正方形宽高比 w != h 及退化轴下
 * 与 LinearGradient(raisedX, raisedY, sinkX, sinkY) 100% 代数恒等，零三角函数与堆分配。
 */
internal fun writeTiltGradientMatrix(
    outMatrix: FloatArray,
    widthPx: Float,
    heightPx: Float,
    tiltNormX: Float,
    tiltNormY: Float,
): Boolean {
    if (widthPx <= 0f || heightPx <= 0f) return false
    val dx = widthPx * tiltNormX
    val dy = heightPx * tiltNormY
    if (dx * dx + dy * dy <= 1e-6f) return false
    val ax = (widthPx - dx) * 0.5f
    val ay = (heightPx - dy) * 0.5f
    outMatrix[0] = dx; outMatrix[1] = -dy; outMatrix[2] = ax
    outMatrix[3] = dy; outMatrix[4] = dx; outMatrix[5] = ay
    outMatrix[6] = 0f; outMatrix[7] = 0f; outMatrix[8] = 1f
    return true
}

internal fun clampCoordToScreen(coord: Int, viewSize: Int, screenSize: Int): Int {
    val maxCoord = (screenSize - viewSize.coerceAtLeast(0)).coerceAtLeast(0)
    return coord.coerceIn(0, maxCoord)
}

internal fun cursorViewOriginCoord(cursorCoord: Float, sizePx: Float): Int =
    (cursorCoord - cursorHotspotOffset(sizePx)).roundToInt()

/** 热路径零分配版本：[out][0] = normX, [out][1] = normY。 */
internal fun resolvePadNormalizedCoordsInto(
    out: FloatArray,
    rawX: Float,
    rawY: Float,
    panelX: Int,
    panelY: Int,
    padRectX: Int,
    padRectY: Int,
    padRectW: Int,
    padRectH: Int,
) {
    val centerX = panelX + padRectX + padRectW / 2f
    val centerY = panelY + padRectY + padRectH / 2f
    val halfW = (padRectW / 2f).coerceAtLeast(1f)
    val halfH = (padRectH / 2f).coerceAtLeast(1f)
    out[0] = ((rawX - centerX) / halfW).coerceIn(-1f, 1f)
    out[1] = ((rawY - centerY) / halfH).coerceIn(-1f, 1f)
}

/** 热路径零分配版本：[out][0] = shiftXRatio, [out][1] = shiftYRatio, [out][2] = boost。 */
internal fun computeSpecularShiftInto(
    out: FloatArray,
    normX: Float,
    normY: Float,
    maxRatio: Float = 0.28f,
) {
    val cx = normX.coerceIn(-1f, 1f)
    val cy = normY.coerceIn(-1f, 1f)
    out[0] = if (cx == 0f) 0f else -cx * maxRatio
    out[1] = if (cy == 0f) 0f else -cy * maxRatio
    out[2] = (kotlin.math.hypot(cx, cy).coerceIn(0f, 1.4f) / 1.4f) * 0.08f
}

/** 热路径零分配版本：[out][0] = x, [out][1] = y。 */
internal fun computeDampedSwipeOffsetInto(
    out: FloatArray,
    dx: Float,
    dy: Float,
    maxOffsetPx: Float,
    dampingFactor: Float = 1.5f,
) {
    val dist = kotlin.math.hypot(dx, dy)
    if (dist <= 0.001f || maxOffsetPx <= 0f) {
        out[0] = 0f
        out[1] = 0f
        return
    }
    val scale = maxOffsetPx * (1f - kotlin.math.exp(-dist / (maxOffsetPx * dampingFactor)))
    val ratio = scale / dist
    out[0] = dx * ratio
    out[1] = dy * ratio
}

/** 热路径零分配版本：[out] = [stretch, squash, rotationDeg, iconTranslationX, iconTranslationY]。 */
internal fun computeBallDeformationInto(
    out: FloatArray,
    dx: Float,
    dy: Float,
    maxOffsetPx: Float,
    maxStretch: Float = 0.22f,
    maxSquash: Float = 0.16f,
    parallaxRatio: Float = 0.10f,
) {
    val dist = kotlin.math.hypot(dx, dy)
    if (dist < 0.5f || maxOffsetPx <= 0f) {
        out[0] = 1f; out[1] = 1f; out[2] = 0f; out[3] = 0f; out[4] = 0f
        return
    }
    val angleDeg = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val normalized = (dist / maxOffsetPx).coerceIn(0f, 2.5f)
    val tension = normalized / (1f + 0.6f * normalized)
    val stretch = 1f + maxStretch * tension
    val squash = (1f / (1f + maxStretch * tension * 0.9f)).coerceAtLeast(1f - maxSquash)
    val maxParallax = maxOffsetPx * 0.25f
    val currentParallax = (dist * parallaxRatio).coerceAtMost(maxParallax)
    val invDist = 1f / dist
    out[0] = stretch
    out[1] = squash
    out[2] = angleDeg
    out[3] = dx * invDist * currentParallax
    out[4] = dy * invDist * currentParallax
}

/** 热路径零分配版本：[out] = [stretch, squash, rotationDeg, iconTranslationX, iconTranslationY]。 */
internal fun computeDragMotionStateInto(
    out: FloatArray,
    speedPxPerSec: Float,
    dirX: Float,
    dirY: Float,
    baseScale: Float = 1.16f,
    refSpeedPxPerSec: Float = 1200f,
    maxExtraStretch: Float = 0.22f,
    maxSquash: Float = 0.18f,
    maxParallaxPx: Float = 14f,
) {
    if (speedPxPerSec < 10f || refSpeedPxPerSec <= 0f) {
        out[0] = baseScale; out[1] = baseScale; out[2] = 0f; out[3] = 0f; out[4] = 0f
        return
    }
    val normalized = (speedPxPerSec / refSpeedPxPerSec).coerceIn(0f, 3f)
    val tension = normalized / (1f + 0.6f * normalized)
    val stretch = baseScale * (1f + maxExtraStretch * tension)
    val squash = baseScale * (1f / (1f + maxSquash * tension))
    val angleDeg = Math.toDegrees(kotlin.math.atan2(dirY.toDouble(), dirX.toDouble())).toFloat()
    val parallaxDist = maxParallaxPx * tension
    out[0] = stretch
    out[1] = squash
    out[2] = angleDeg
    out[3] = -dirX * parallaxDist
    out[4] = -dirY * parallaxDist
}

/** 热路径零分配版本：[out][0] = targetX, [out][1] = targetY。 */
internal fun computePanelDragTargetInto(
    out: FloatArray,
    vxPxPerSec: Float,
    vyPxPerSec: Float,
    density: Float,
    deadzoneDpPerSec: Float = 25f,
    refSpeedDpPerSec: Float = 420f,
) {
    val safeDensity = density.coerceAtLeast(1f)
    val speed = kotlin.math.hypot(vxPxPerSec, vyPxPerSec)
    val deadzone = deadzoneDpPerSec * safeDensity
    val refSpeed = (refSpeedDpPerSec * safeDensity).coerceAtLeast(1f)
    if (speed <= deadzone) {
        out[0] = 0f
        out[1] = 0f
        return
    }
    val effectiveSpeed = speed - deadzone
    val normalized = (effectiveSpeed / refSpeed).coerceIn(0f, 4f)
    val tension = (normalized / (1f + 0.55f * normalized)).coerceIn(0f, 1f)
    val invSpeed = 1f / speed
    out[0] = vxPxPerSec * invSpeed * tension
    out[1] = vyPxPerSec * invSpeed * tension
}

/** 可原地更新的面板弹簧状态，供 Choreographer 60–240Hz 热路径零分配使用。 */
internal class MutablePanelSpringState(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var grab: Float = 0f,
    var grabVel: Float = 0f,
) {
    val isSettled: Boolean
        get() = kotlin.math.abs(x) < 0.002f &&
            kotlin.math.abs(y) < 0.002f &&
            kotlin.math.abs(vx) < 0.015f &&
            kotlin.math.abs(vy) < 0.015f &&
            kotlin.math.abs(grab) < 0.002f &&
            kotlin.math.abs(grabVel) < 0.015f

    fun reset() {
        x = 0f; y = 0f; vx = 0f; vy = 0f; grab = 0f; grabVel = 0f
    }

    fun isAtRestForTarget(targetX: Float, targetY: Float, targetGrab: Float): Boolean =
        kotlin.math.abs(x - targetX) < 0.002f &&
            kotlin.math.abs(y - targetY) < 0.002f &&
            kotlin.math.abs(vx) < 0.015f &&
            kotlin.math.abs(vy) < 0.015f &&
            kotlin.math.abs(grab - targetGrab) < 0.002f &&
            kotlin.math.abs(grabVel) < 0.015f
}

/** 与 [stepPanelDragSpring] 公式完全一致的原地步进版本（零对象分配）。 */
internal fun stepPanelDragSpringInPlace(
    state: MutablePanelSpringState,
    targetX: Float,
    targetY: Float,
    targetGrab: Float,
    dtSec: Float,
    isDragging: Boolean,
) {
    val clampedDt = dtSec.coerceIn(0.001f, 0.040f)
    val subSteps = if (clampedDt > 0.016f) 2 else 1
    val stepDt = clampedDt / subSteps

    val motionOmega = if (isDragging) 20f else 17f
    val motionZeta = if (isDragging) 0.68f else 0.50f
    val motionK = motionOmega * motionOmega
    val motionC = 2f * motionZeta * motionOmega

    val grabOmega = if (isDragging) 24f else 20f
    val grabZeta = if (isDragging) 0.85f else 0.92f
    val grabK = grabOmega * grabOmega
    val grabC = 2f * grabZeta * grabOmega

    var x = state.x
    var y = state.y
    var vx = state.vx
    var vy = state.vy
    var grab = state.grab
    var grabVel = state.grabVel

    repeat(subSteps) {
        val ax = motionK * (targetX - x) - motionC * vx
        val ay = motionK * (targetY - y) - motionC * vy
        vx += ax * stepDt
        vy += ay * stepDt
        x += vx * stepDt
        y += vy * stepDt

        val aGrab = grabK * (targetGrab - grab) - grabC * grabVel
        grabVel += aGrab * stepDt
        grab = (grab + grabVel * stepDt).coerceIn(0f, 1.15f)
    }

    val mag = kotlin.math.hypot(x, y)
    if (mag > 1.35f) {
        val s = 1.35f / mag
        x *= s
        y *= s
    }

    state.x = x
    state.y = y
    state.vx = vx
    state.vy = vy
    state.grab = grab
    state.grabVel = grabVel
}

// ───────────────────────── 交互响应可靠性与手势派发纯逻辑 ─────────────────────────

/** 悬浮球双击等待时长（ms）：覆盖正常单手拇指双击节奏，同时保持单击响应干脆。 */
internal const val BALL_DOUBLE_TAP_TIMEOUT_MS = 260L

/** 主线程短暂抖动时的双击宽限时长（ms）：只要单击回调尚未触发，允许第二次按下平滑衔接为双击。 */
internal const val BALL_DOUBLE_TAP_GRACE_MS = 100L

/**
 * 判断悬浮球第二次按下（ACTION_DOWN）是否落在双击判定窗口内。
 * 在 ACTION_DOWN（而非 ACTION_UP）就锁定双击资格，避免第二次按住稍久时因抬手总耗时超出上限而误判失效。
 */
internal fun isBallSecondTapDownWithinWindow(
    isWaitingForDoubleTap: Boolean,
    lastTapUpMs: Long,
    secondTapDownMs: Long,
    timeoutMs: Long = BALL_DOUBLE_TAP_TIMEOUT_MS,
    graceMs: Long = BALL_DOUBLE_TAP_GRACE_MS,
): Boolean {
    if (!isWaitingForDoubleTap) return false
    val elapsed = secondTapDownMs - lastTapUpMs
    val maxWindow = timeoutMs.coerceAtLeast(0L) + graceMs.coerceAtLeast(0L)
    return elapsed in 0L..maxWindow
}

/** 悬浮球点击容差（px）：至少不低于系统 scaledTouchSlop 与最小防微抖阈值，减少轻点滚动被静默吞掉。 */
internal fun resolveBallTapSlopPx(scaledTouchSlopPx: Float, minSlopPx: Float): Float =
    maxOf(scaledTouchSlopPx.coerceAtLeast(0f), minSlopPx.coerceAtLeast(0f))

/**
 * 悬浮球第二次抬手（ACTION_UP）是否应触发双击动作：
 * 只要第二次按下已锁定双击资格，且未进入滑动阈值或滑回取消区，即视为有效双击，消除 6dp–25dp 误判死区。
 */
internal fun shouldTreatBallReleaseAsDoubleTap(
    doubleTapArmed: Boolean,
    hasArmedSwipe: Boolean,
    isCancelledByReturn: Boolean,
    distPx: Float,
    swipeThresholdPx: Float,
): Boolean =
    doubleTapArmed && !hasArmedSwipe && !isCancelledByReturn && distPx < swipeThresholdPx

/**
 * 基于按钮未形变屏幕矩形 + 触摸容差 [slopPx] 判定点击落点：
 * 完全免疫 PressFeedback 3D 倾斜、0.92× 缩放与 3dp 下沉造成的 View 局部坐标偏移，杜绝点击按钮边缘无响应。
 */
internal fun isPointInsideButtonHitTarget(
    rawX: Float,
    rawY: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    slopPx: Float,
): Boolean {
    if (width <= 0 || height <= 0) return false
    val safeSlop = slopPx.coerceAtLeast(0f)
    return rawX >= left - safeSlop &&
        rawX <= left + width + safeSlop &&
        rawY >= top - safeSlop &&
        rawY <= top + height + safeSlop
}

/**
 * 判定按钮点击落点是否有效：
 * 1. 手指相对按下点几乎未滑动（位移 <= [slopPx]），即使面板正处于展开缩放动画或快速连点回弹期也绝不丢点击；
 * 2. 否则回退到按钮未形变屏幕矩形 + 容差 [slopPx] 判定，保留拖离按钮外取消点击的直觉手势。
 */
internal fun isButtonReleaseWithinHitTarget(
    rawX: Float,
    rawY: Float,
    downRawX: Float,
    downRawY: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    slopPx: Float,
): Boolean {
    val safeSlop = slopPx.coerceAtLeast(0f)
    if (kotlin.math.hypot(rawX - downRawX, rawY - downRawY) <= safeSlop) return true
    return isPointInsideButtonHitTarget(rawX, rawY, left, top, width, height, safeSlop)
}

/**
 * 判定注入手势坐标是否可能命中面板窗口（合并 View.getLocationOnScreen 与 LayoutParams 目标坐标，
 * 并留出 [marginPx] 边界余量），杜绝移动面板后或过渡帧期间误判导致注入手势点到自身悬浮窗。
 */
internal fun isPointInsidePanelBounds(
    px: Float,
    py: Float,
    screenX: Int,
    screenY: Int,
    paramX: Int,
    paramY: Int,
    width: Int,
    height: Int,
    marginPx: Float = 2f,
): Boolean {
    if (width <= 0 || height <= 0) return false
    val m = marginPx.coerceAtLeast(0f)
    val inScreen = px >= screenX - m && px <= screenX + width + m &&
        py >= screenY - m && py <= screenY + height + m
    if (inScreen) return true
    return px >= paramX - m && px <= paramX + width + m &&
        py >= paramY - m && py <= paramY + height + m
}

/**
 * 计算任意注入手势（穿透或直发）的统一看门狗超时时长（ms）：
 * 若系统异常丢弃 GestureResultCallback，到期自动解锁 isDispatching 并恢复可触摸，杜绝手势队列永久死锁。
 */
internal fun resolveGestureWatchdogTimeoutMs(
    expectedMs: Long,
    needsPassThrough: Boolean,
    passThroughDelayMs: Long = 50L,
): Long {
    val base = maxOf(2500L, expectedMs.coerceAtLeast(0L) + 1500L)
    return if (needsPassThrough) base + passThroughDelayMs.coerceAtLeast(0L) else base
}

/**
 * 判断此刻是否应暂缓惰性面板重建：
 * 只要存在任何活跃手指触摸、按钮按压、悬浮球双击等待、或手势派发/穿透过程，均延后至交互彻底结束后再重建。
 */
internal fun shouldDeferPanelRebuild(
    padFingerDown: Boolean,
    movingPanel: Boolean,
    resizingPanel: Boolean,
    isDraggingBall: Boolean,
    ballTouchDown: Boolean,
    isWaitingForBallDoubleTap: Boolean,
    actionButtonDownCount: Int,
    isDispatchingGesture: Boolean,
    isPassThroughActive: Boolean,
): Boolean =
    padFingerDown ||
        movingPanel ||
        resizingPanel ||
        isDraggingBall ||
        ballTouchDown ||
        isWaitingForBallDoubleTap ||
        actionButtonDownCount > 0 ||
        isDispatchingGesture ||
        isPassThroughActive

// ───────────────────────── 零延迟光标穿孔与快速连击恢复纯逻辑 ─────────────────────────

internal const val TAP_STROKE_DURATION_MS = 22L
internal const val TAP_COALESCE_GAP_MS = 45L // >= 40ms ViewConfiguration.DOUBLE_TAP_MIN_TIME
internal const val CANCELLED_TAP_EXPIRE_MS = 300L
internal const val MAX_COALESCED_TAPS = 3
internal const val CURSOR_PINHOLE_RADIUS_PX = 3
internal const val PINHOLE_SYNC_SETTLE_MS = 32L

private fun appendPinholeRect(
    outRects: IntArray,
    currentCount: Int,
    px: Float,
    py: Float,
    ox: Int,
    oy: Int,
    panelW: Int,
    panelH: Int,
    r: Int,
    m: Float,
): Int {
    if (px < ox - m || px > ox + panelW + m || py < oy - m || py > oy + panelH + m) {
        return currentCount
    }
    val cx = (px - ox).roundToInt()
    val cy = (py - oy).roundToInt()
    val left = (cx - r).coerceIn(0, panelW)
    val top = (cy - r).coerceIn(0, panelH)
    val right = (cx + r + 1).coerceIn(0, panelW)
    val bottom = (cy + r + 1).coerceIn(0, panelH)
    if (right <= left || bottom <= top) return currentCount

    for (j in 0 until currentCount) {
        val base = j * 4
        if (outRects[base] == left &&
            outRects[base + 1] == top &&
            outRects[base + 2] == right &&
            outRects[base + 3] == bottom
        ) {
            return currentCount
        }
    }

    val writeBase = currentCount * 4
    if (writeBase + 3 >= outRects.size) return currentCount
    outRects[writeBase] = left
    outRects[writeBase + 1] = top
    outRects[writeBase + 2] = right
    outRects[writeBase + 3] = bottom
    return currentCount + 1
}

/**
 * Computes panel-local cursor pinhole rectangles `[left, top, right, bottom]` into [outRects]
 * for up to [pointCount] `(x, y)` pairs in [screenPoints], deduplicating identical rects with
 * zero heap allocations.
 */
internal fun computePanelPinholeRectsInto(
    outRects: IntArray,
    screenPoints: FloatArray,
    pointCount: Int,
    screenX: Int,
    screenY: Int,
    paramX: Int,
    paramY: Int,
    panelW: Int,
    panelH: Int,
    radiusPx: Int = CURSOR_PINHOLE_RADIUS_PX,
    marginPx: Float = 2f,
): Int {
    if (panelW <= 0 || panelH <= 0 || pointCount <= 0) return 0
    val r = radiusPx.coerceAtLeast(1)
    val m = if (marginPx.isNaN()) 0f else marginPx.coerceAtLeast(0f)
    val maxPoints = minOf(pointCount, screenPoints.size / 2)
    val hasDualOrigin = screenX != paramX || screenY != paramY
    var count = 0
    for (i in 0 until maxPoints) {
        val px = screenPoints[2 * i]
        val py = screenPoints[2 * i + 1]
        if (!px.isFinite() || !py.isFinite()) continue
        count = appendPinholeRect(outRects, count, px, py, paramX, paramY, panelW, panelH, r, m)
        if (hasDualOrigin) {
            count = appendPinholeRect(outRects, count, px, py, screenX, screenY, panelW, panelH, r, m)
        }
    }
    return count
}

/**
 * Compares two packed pinhole rectangle buffers `[left, top, right, bottom, ...]` without
 * allocating.
 */
internal fun arePinholeRectsEqual(
    a: IntArray,
    aCount: Int,
    b: IntArray,
    bCount: Int,
): Boolean {
    if (aCount != bCount || aCount < 0) return false
    if (aCount > a.size / 4 || bCount > b.size / 4) return false
    val totalInts = aCount * 4
    for (idx in 0 until totalInts) {
        if (a[idx] != b[idx]) return false
    }
    return true
}

private fun isPointCoveredForOrigin(
    startX: Float,
    startY: Float,
    committedRects: IntArray,
    committedRectCount: Int,
    ox: Int,
    oy: Int,
    panelW: Int,
    panelH: Int,
    m: Float,
): Boolean {
    if (startX < ox - m || startX > ox + panelW + m || startY < oy - m || startY > oy + panelH + m) {
        return true
    }
    if (committedRectCount <= 0) return false
    val rx = (startX - ox).roundToInt().coerceIn(0, panelW - 1)
    val ry = (startY - oy).roundToInt().coerceIn(0, panelH - 1)
    val fx = kotlin.math.floor((startX - ox).toDouble()).toInt().coerceIn(0, panelW - 1)
    val fy = kotlin.math.floor((startY - oy).toDouble()).toInt().coerceIn(0, panelH - 1)

    val limit = minOf(committedRectCount, committedRects.size / 4)
    var roundCovered = false
    var floorCovered = false
    for (i in 0 until limit) {
        val base = i * 4
        val left = committedRects[base]
        val top = committedRects[base + 1]
        val right = committedRects[base + 2]
        val bottom = committedRects[base + 3]
        if (!roundCovered && rx in left until right && ry in top until bottom) {
            roundCovered = true
        }
        if (!floorCovered && fx in left until right && fy in top until bottom) {
            floorCovered = true
        }
        if (roundCovered && floorCovered) return true
    }
    return false
}

/**
 * Returns true if `(startX, startY)` is safely outside the panel or fully covered (under both
 * round and floor quantization) by [committedRects] for both `(paramX, paramY)` and
 * `(screenX, screenY)`.
 */
internal fun isScreenPointCoveredByPinholeRects(
    startX: Float,
    startY: Float,
    committedRects: IntArray,
    committedRectCount: Int,
    screenX: Int,
    screenY: Int,
    paramX: Int,
    paramY: Int,
    panelW: Int,
    panelH: Int,
    marginPx: Float = 2f,
): Boolean {
    if (!startX.isFinite() || !startY.isFinite()) return false
    if (panelW <= 0 || panelH <= 0) return true
    val m = if (marginPx.isNaN()) 0f else marginPx.coerceAtLeast(0f)
    if (!isPointCoveredForOrigin(startX, startY, committedRects, committedRectCount, paramX, paramY, panelW, panelH, m)) {
        return false
    }
    if ((screenX != paramX || screenY != paramY) &&
        !isPointCoveredForOrigin(startX, startY, committedRects, committedRectCount, screenX, screenY, panelW, panelH, m)
    ) {
        return false
    }
    return true
}

/**
 * Computes remaining settle delay (ms) after the last pinhole region commit before dispatching an
 * injected gesture without full-window pass-through.
 */
internal fun resolvePinholeSettleDelayMs(
    lastPinholeCommitUptimeMs: Long,
    nowUptimeMs: Long,
    settleMs: Long = PINHOLE_SYNC_SETTLE_MS,
): Long {
    val safeSettleMs = settleMs.coerceAtLeast(0L)
    if (lastPinholeCommitUptimeMs <= 0L) return safeSettleMs
    val elapsed = (nowUptimeMs - lastPinholeCommitUptimeMs).coerceAtLeast(0L)
    return (safeSettleMs - elapsed).coerceAtLeast(0L)
}

/**
 * Returns the stroke start offset (ms) for tap [tapIndex] within a coalesced multi-tap gesture.
 */
internal fun computeCoalescedTapStartTimeMs(
    tapIndex: Int,
    strokeDurationMs: Long = TAP_STROKE_DURATION_MS,
    gapMs: Long = TAP_COALESCE_GAP_MS,
): Long {
    if (tapIndex <= 0) return 0L
    val safeStroke = strokeDurationMs.coerceAtLeast(1L)
    val safeGap = gapMs.coerceAtLeast(0L)
    return tapIndex.toLong() * (safeStroke + safeGap)
}

/**
 * Returns the total gesture duration (ms) for [tapCount] coalesced taps separated by [gapMs].
 */
internal fun computeCoalescedTapTotalDurationMs(
    tapCount: Int,
    strokeDurationMs: Long = TAP_STROKE_DURATION_MS,
    gapMs: Long = TAP_COALESCE_GAP_MS,
): Long {
    if (tapCount <= 0) return 0L
    val safeStroke = strokeDurationMs.coerceAtLeast(1L)
    val safeGap = gapMs.coerceAtLeast(0L)
    return (tapCount - 1).toLong() * (safeStroke + safeGap) + safeStroke
}

/**
 * Fixed-capacity FIFO buffer of recently cancelled tap coordinates for rapid-click recovery,
 * using pre-allocated primitive arrays so hot paths perform zero heap allocations.
 */
internal class CancelledTapTracker(
    private val maxTaps: Int = MAX_COALESCED_TAPS - 1,
    private val expireMs: Long = CANCELLED_TAP_EXPIRE_MS,
) {
    private val capacity = maxTaps.coerceAtLeast(1)
    private val safeExpireMs = expireMs.coerceAtLeast(0L)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val times = LongArray(capacity)

    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun pruneExpired(nowUptimeMs: Long) {
        var writeIdx = 0
        for (i in 0 until size) {
            val t = times[i]
            if (nowUptimeMs >= t && (nowUptimeMs - t) <= safeExpireMs) {
                if (writeIdx != i) {
                    xs[writeIdx] = xs[i]
                    ys[writeIdx] = ys[i]
                    times[writeIdx] = t
                }
                writeIdx++
            }
        }
        size = writeIdx
    }

    fun recordCancelledTap(x: Float, y: Float, nowUptimeMs: Long) {
        pruneExpired(nowUptimeMs)
        if (!x.isFinite() || !y.isFinite()) return
        if (size == capacity) {
            for (i in 1 until capacity) {
                xs[i - 1] = xs[i]
                ys[i - 1] = ys[i]
                times[i - 1] = times[i]
            }
            size = capacity - 1
        }
        xs[size] = x
        ys[size] = y
        times[size] = nowUptimeMs
        size++
    }

    fun drainForCoalescedTapInto(
        outCoords: FloatArray,
        nowUptimeMs: Long,
        includeNewTap: Boolean,
        newX: Float = 0f,
        newY: Float = 0f,
    ): Int {
        pruneExpired(nowUptimeMs)
        var written = 0
        for (i in 0 until size) {
            if (outCoords.size < (written + 1) * 2) break
            outCoords[written * 2] = xs[i]
            outCoords[written * 2 + 1] = ys[i]
            written++
        }
        if (includeNewTap && newX.isFinite() && newY.isFinite() && outCoords.size >= (written + 1) * 2) {
            outCoords[written * 2] = newX
            outCoords[written * 2 + 1] = newY
            written++
        }
        clear()
        return written
    }
}

// ───────────────────────── 光标动态增益、物理微动效与边缘触感状态机 ─────────────────────────

internal const val POINTER_ACCEL_MIN_PERCENT_MIN = 50
internal const val POINTER_ACCEL_MIN_PERCENT_MAX = 100
internal const val POINTER_ACCEL_MIN_PERCENT_DEFAULT = 100
internal const val POINTER_ACCEL_MAX_PERCENT_MIN = 100
internal const val POINTER_ACCEL_MAX_PERCENT_MAX = 250
internal const val POINTER_ACCEL_MAX_PERCENT_DEFAULT = 145
internal const val POINTER_ACCEL_THRESHOLD_MIN_DP_PER_SEC = 60
internal const val POINTER_ACCEL_THRESHOLD_MAX_DP_PER_SEC = 400
internal const val POINTER_ACCEL_THRESHOLD_DEFAULT_DP_PER_SEC = 160

internal const val POINTER_ACCEL_DEFAULT_MIN_MULTIPLIER = POINTER_ACCEL_MIN_PERCENT_DEFAULT / 100f
internal const val POINTER_ACCEL_DEFAULT_MAX_MULTIPLIER = POINTER_ACCEL_MAX_PERCENT_DEFAULT / 100f
internal const val POINTER_ACCEL_DEFAULT_THRESHOLD_DP_PER_SEC = POINTER_ACCEL_THRESHOLD_DEFAULT_DP_PER_SEC.toFloat()

/**
 * 两段式 C1 连续动态指针加速度曲线（纯函数、热路径零分配）：
 * - 第一段（慢移微瞄至常速区 `0 <= v <= thresh`）：
 *   - 若 `minM >= 0.999f`，全区间严格恒等于 `1.0f`（零减速死区、零提前提速）；
 *   - 若 `minM < 0.999f`，在 `slowKnee = min(18, thresh * 0.25)` 以下保持 `minM`，
 *     在 `(slowKnee, thresh)` 内用三次 Hermite `t^2 * (3 - 2t)` 平滑插值至 `1.0f`（两端导数均为 0）。
 * - 第二段（快滑提速区 `v > thresh`）：
 *   - 若 `maxM <= 1.001f`，返回 `1.0f`；
 *   - 否则按 `ratio = u^2 / (1 + u^2)`（`rampScale = max(140, thresh * 1.25)`）从 `1.0f` 平滑递增至 `maxM`，
 *     在 `v = thresh` 处值为 `1.0f` 且右导数为 `0`，全局 C1 连续、单调不减、渐进有界不超调。
 */
internal fun computePointerAccelerationMultiplier(
    fingerSpeedDpPerSec: Float,
    minMultiplier: Float = POINTER_ACCEL_DEFAULT_MIN_MULTIPLIER,
    maxMultiplier: Float = POINTER_ACCEL_DEFAULT_MAX_MULTIPLIER,
    thresholdDpPerSec: Float = POINTER_ACCEL_DEFAULT_THRESHOLD_DP_PER_SEC,
): Float {
    if (!fingerSpeedDpPerSec.isFinite()) return 1f
    val safeMin = if (minMultiplier.isFinite()) minMultiplier else POINTER_ACCEL_DEFAULT_MIN_MULTIPLIER
    val safeMax = if (maxMultiplier.isFinite()) maxMultiplier else POINTER_ACCEL_DEFAULT_MAX_MULTIPLIER
    val safeThresh = if (thresholdDpPerSec.isFinite()) thresholdDpPerSec else POINTER_ACCEL_DEFAULT_THRESHOLD_DP_PER_SEC

    val minM = safeMin.coerceIn(0.50f, 1.00f)
    val maxM = maxOf(minM, safeMax.coerceIn(1.00f, 2.50f))
    val thresh = safeThresh.coerceIn(60f, 400f)
    val v = fingerSpeedDpPerSec.coerceAtLeast(0f)

    if (v <= thresh) {
        if (minM >= 0.999f) return 1f
        val slowKnee = minOf(18f, thresh * 0.25f)
        if (v <= slowKnee) return minM
        val t = ((v - slowKnee) / (thresh - slowKnee)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return (minM + (1f - minM) * smooth).coerceIn(minM, 1f)
    }

    if (maxM <= 1.001f) return 1f
    val rampScale = maxOf(140f, thresh * 1.25f)
    val u = (v - thresh) / rampScale
    if (u >= 1e4f) return maxM
    val u2 = u * u
    val ratio = u2 / (1f + u2)
    return (1f + (maxM - 1f) * ratio).coerceIn(1f, maxM)
}

/**
 * VRR / 高触控采样率自适应指针弹道状态（全基本类型原地更新，热路径零分配）：
 * - 首包播种时间戳并将初始倍率置为 `1.0f`，避免落指首帧跳变或滞后；
 * - `< 1.5ms` 合包/粘包先累积位移，避免极短 dt 放大瞬时速度噪声；
 * - 使用**无符号标量速率**（`hypot(pendingDxPx, pendingDyPx)`）做 VRR 自适应 EMA 滤波驱动动态倍率，
 *   消除折返或画圈时有符号分量正负抵消掉回 0 速引发的急刹缺陷；
 * - 有符号速度向量 `smoothedVxDpPerSec / smoothedVyDpPerSec` 独立维护，仅供光标顺风微摆使用。
 */
internal class MutablePointerBallisticsState {
    var lastEventTimeNs: Long = 0L
        private set
    var pendingDxPx: Float = 0f
        private set
    var pendingDyPx: Float = 0f
        private set
    var smoothedVxDpPerSec: Float = 0f
        private set
    var smoothedVyDpPerSec: Float = 0f
        private set
    var smoothedSpeedDpPerSec: Float = 0f
        private set
    var multiplier: Float = 1f
        private set

    fun reset() {
        lastEventTimeNs = 0L
        pendingDxPx = 0f
        pendingDyPx = 0f
        smoothedVxDpPerSec = 0f
        smoothedVyDpPerSec = 0f
        smoothedSpeedDpPerSec = 0f
        multiplier = 1f
    }

    fun onMove(
        dxPx: Float,
        dyPx: Float,
        eventTimeNs: Long,
        density: Float,
        minMultiplier: Float = POINTER_ACCEL_DEFAULT_MIN_MULTIPLIER,
        maxMultiplier: Float = POINTER_ACCEL_DEFAULT_MAX_MULTIPLIER,
        thresholdDpPerSec: Float = POINTER_ACCEL_DEFAULT_THRESHOLD_DP_PER_SEC,
    ): Float {
        if (!dxPx.isFinite() || !dyPx.isFinite()) return multiplier
        if (lastEventTimeNs == 0L) {
            lastEventTimeNs = eventTimeNs
            pendingDxPx = 0f
            pendingDyPx = 0f
            smoothedVxDpPerSec = 0f
            smoothedVyDpPerSec = 0f
            smoothedSpeedDpPerSec = 0f
            multiplier = 1f
            return multiplier
        }
        val rawDtNs = eventTimeNs - lastEventTimeNs
        if (rawDtNs < 0L) {
            lastEventTimeNs = eventTimeNs
            pendingDxPx = 0f
            pendingDyPx = 0f
            return multiplier
        }
        pendingDxPx += dxPx
        pendingDyPx += dyPx
        if (rawDtNs < 1_500_000L) {
            return multiplier
        }
        val safeDensity = density.coerceAtLeast(1f)
        val dtSec = (rawDtNs / 1_000_000_000f).coerceAtLeast(0.0015f)
        val rawVxDp = (pendingDxPx / safeDensity) / dtSec
        val rawVyDp = (pendingDyPx / safeDensity) / dtSec
        val rawScalarSpeedDp = (kotlin.math.hypot(pendingDxPx, pendingDyPx) / safeDensity) / dtSec
        pendingDxPx = 0f
        pendingDyPx = 0f
        lastEventTimeNs = eventTimeNs

        // 方向反转过零时立即清零残留反向速度，消除光标微摆方向滞后（不影响标量速率）
        if (rawVxDp * smoothedVxDpPerSec < 0f) {
            smoothedVxDpPerSec = 0f
        }
        if (rawVyDp * smoothedVyDpPerSec < 0f) {
            smoothedVyDpPerSec = 0f
        }

        val clampedDtSec = dtSec.coerceAtMost(0.5f)
        val vecAlpha = computeVrrEmaAlpha(clampedDtSec, 0.028f)
        smoothedVxDpPerSec += (rawVxDp - smoothedVxDpPerSec) * vecAlpha
        smoothedVyDpPerSec += (rawVyDp - smoothedVyDpPerSec) * vecAlpha

        // 无符号标量速率恒 >= 0，手指来回折返或画圈时不会正负抵消掉回 0 速造成急刹
        val speedAlpha = computeVrrEmaAlpha(clampedDtSec, 0.022f)
        smoothedSpeedDpPerSec += (rawScalarSpeedDp - smoothedSpeedDpPerSec) * speedAlpha
        multiplier = computePointerAccelerationMultiplier(
            fingerSpeedDpPerSec = smoothedSpeedDpPerSec,
            minMultiplier = minMultiplier,
            maxMultiplier = maxMultiplier,
            thresholdDpPerSec = thresholdDpPerSec,
        )
        return multiplier
    }
}

internal const val PRESS_SPRING_IMPULSE_VEL = 20.0f
internal const val PRESS_SPRING_IMPULSE_CUTOFF = 0.35f
internal const val PRESS_SPRING_OMEGA = 18.0f
internal const val PRESS_SPRING_ZETA = 0.84f
internal const val PRESS_TILT_OMEGA = 22.0f
internal const val PRESS_TILT_ZETA = 0.88f

/**
 * 触控板与面板按键的连续二阶质量-弹簧-阻尼按压状态（全基本类型原地更新，热路径零分配）。
 */
internal class MutablePressSpringState(
    var press: Float = 0f,
    var pressVel: Float = 0f,
    var tiltX: Float = 0f,
    var tiltVelX: Float = 0f,
    var tiltY: Float = 0f,
    var tiltVelY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = kotlin.math.abs(press) < 0.002f &&
            kotlin.math.abs(pressVel) < 0.02f &&
            kotlin.math.abs(tiltX) < 0.003f &&
            kotlin.math.abs(tiltVelX) < 0.02f &&
            kotlin.math.abs(tiltY) < 0.003f &&
            kotlin.math.abs(tiltVelY) < 0.02f

    fun applyDownImpulse(impulseVel: Float = PRESS_SPRING_IMPULSE_VEL) {
        if (press < PRESS_SPRING_IMPULSE_CUTOFF && pressVel < impulseVel) {
            pressVel = impulseVel
        }
    }

    fun isSettled(targetPress: Float, targetTiltX: Float, targetTiltY: Float): Boolean =
        kotlin.math.abs(press - targetPress) < 0.003f &&
            kotlin.math.abs(pressVel) < 0.03f &&
            kotlin.math.abs(tiltX - targetTiltX) < 0.004f &&
            kotlin.math.abs(tiltVelX) < 0.03f &&
            kotlin.math.abs(tiltY - targetTiltY) < 0.004f &&
            kotlin.math.abs(tiltVelY) < 0.03f

    fun snapTo(targetPress: Float, targetTiltX: Float, targetTiltY: Float) {
        press = targetPress.coerceIn(0f, 1f)
        pressVel = 0f
        tiltX = targetTiltX.coerceIn(-1f, 1f)
        tiltVelX = 0f
        tiltY = targetTiltY.coerceIn(-1f, 1f)
        tiltVelY = 0f
    }

    fun reset() {
        press = 0f
        pressVel = 0f
        tiltX = 0f
        tiltVelX = 0f
        tiltY = 0f
        tiltVelY = 0f
    }
}

/**
 * 原地步进按压与 3D 倾斜弹簧（子步欧拉积分，近临界阻尼，VRR 60–240Hz 一致，零堆分配）。
 */
internal fun stepPressSpringInPlace(
    state: MutablePressSpringState,
    targetPress: Float,
    targetTiltX: Float,
    targetTiltY: Float,
    dtSec: Float,
) {
    val safeTargetPress = targetPress.coerceIn(0f, 1f)
    val safeTargetTiltX = targetTiltX.coerceIn(-1f, 1f)
    val safeTargetTiltY = targetTiltY.coerceIn(-1f, 1f)

    val clampedDt = dtSec.coerceIn(0.001f, 0.040f)
    val subSteps = when {
        clampedDt > 0.032f -> 5
        clampedDt > 0.024f -> 4
        clampedDt > 0.016f -> 3
        clampedDt > 0.008f -> 2
        else -> 1
    }
    val stepDt = clampedDt / subSteps

    val pressK = PRESS_SPRING_OMEGA * PRESS_SPRING_OMEGA
    val pressC = 2f * PRESS_SPRING_ZETA * PRESS_SPRING_OMEGA
    val tiltK = PRESS_TILT_OMEGA * PRESS_TILT_OMEGA
    val tiltC = 2f * PRESS_TILT_ZETA * PRESS_TILT_OMEGA

    var press = state.press
    var pressVel = state.pressVel
    var tiltX = state.tiltX
    var tiltVelX = state.tiltVelX
    var tiltY = state.tiltY
    var tiltVelY = state.tiltVelY

    repeat(subSteps) {
        val aPress = pressK * (safeTargetPress - press) - pressC * pressVel
        pressVel += aPress * stepDt
        press += pressVel * stepDt
        if (press < -0.04f) {
            press = -0.04f
            if (pressVel < 0f) pressVel = 0f
        } else if (press > 1.08f) {
            press = 1.08f
            if (pressVel > 0f) pressVel = 0f
        }

        val aTiltX = tiltK * (safeTargetTiltX - tiltX) - tiltC * tiltVelX
        tiltVelX += aTiltX * stepDt
        tiltX += tiltVelX * stepDt
        if (tiltX < -1.1f) {
            tiltX = -1.1f
            if (tiltVelX < 0f) tiltVelX = 0f
        } else if (tiltX > 1.1f) {
            tiltX = 1.1f
            if (tiltVelX > 0f) tiltVelX = 0f
        }

        val aTiltY = tiltK * (safeTargetTiltY - tiltY) - tiltC * tiltVelY
        tiltVelY += aTiltY * stepDt
        tiltY += tiltVelY * stepDt
        if (tiltY < -1.1f) {
            tiltY = -1.1f
            if (tiltVelY < 0f) tiltVelY = 0f
        } else if (tiltY > 1.1f) {
            tiltY = 1.1f
            if (tiltVelY > 0f) tiltVelY = 0f
        }
    }

    state.press = press
    state.pressVel = pressVel
    state.tiltX = tiltX
    state.tiltVelX = tiltVelX
    state.tiltY = tiltY
    state.tiltVelY = tiltVelY
}

internal const val CURSOR_DYNAMICS_MIN_SCALE = 0.85f
internal const val CURSOR_DYNAMICS_MAX_SCALE = 1.03f
internal const val CURSOR_DYNAMICS_MAX_BANK_DEG = 3.5f
internal const val CURSOR_DYNAMICS_MAX_ANGLE_DEG = 3.5f
internal const val CURSOR_PRESENCE_LIFT_SCALE = 1.0f
internal const val CURSOR_CLICK_PECK_MIN_SCALE = 0.90f
internal const val CURSOR_LONG_PRESS_CHARGE_DELAY_MS = 150L
internal const val CURSOR_LONG_PRESS_CHARGE_MIN_SCALE = 0.89f
internal const val CURSOR_ARMED_GRIP_SCALE = 0.94f
internal const val CURSOR_EDGE_SQUISH_MAX = 0.05f
internal const val CURSOR_BANK_DEADZONE_DP_PER_SEC = 55f
internal const val CURSOR_BANK_REF_DP_PER_SEC = 480f
internal const val CURSOR_BLOCKED_SHAKE_DURATION_SEC = 0.22f
internal const val CURSOR_BLOCKED_SHAKE_AMPLITUDE_DEG = 3.2f

/**
 * 计算长按蓄力前馈进度（0f..1f）：
 * 静止按住触控板满 [delayMs] (150ms) 后平滑从 0 升至 1（在 [longPressMs] 时封顶）。
 */
internal fun computeCursorLongPressChargeProgress(
    elapsedMs: Long,
    longPressMs: Long,
    delayMs: Long = CURSOR_LONG_PRESS_CHARGE_DELAY_MS,
): Float {
    if (elapsedMs <= delayMs || longPressMs <= delayMs) return 0f
    val span = (longPressMs - delayMs).toFloat()
    val t = ((elapsedMs - delayMs) / span).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 根据长按蓄力进度计算目标缩放比例（从 1.0f 平滑内聚至 0.89f）。
 */
internal fun computeCursorLongPressChargeScale(chargeProgress: Float): Float {
    val p = chargeProgress.coerceIn(0f, 1f)
    return (CURSOR_PRESENCE_LIFT_SCALE + (CURSOR_LONG_PRESS_CHARGE_MIN_SCALE - CURSOR_PRESENCE_LIFT_SCALE) * p)
        .coerceIn(CURSOR_DYNAMICS_MIN_SCALE, CURSOR_DYNAMICS_MAX_SCALE)
}

/**
 * 计算高速移动顺风微摆目标角（严格封顶在 ±3.5° 内，慢速微瞄死区内保持 0°）。
 */
internal fun computeCursorVelocityBankDeg(
    vxDpPerSec: Float,
    maxBankDeg: Float = CURSOR_DYNAMICS_MAX_BANK_DEG,
    deadzoneDpPerSec: Float = CURSOR_BANK_DEADZONE_DP_PER_SEC,
    refSpeedDpPerSec: Float = CURSOR_BANK_REF_DP_PER_SEC,
): Float {
    if (!vxDpPerSec.isFinite() || maxBankDeg <= 0f || refSpeedDpPerSec <= 0f) return 0f
    val absV = kotlin.math.abs(vxDpPerSec)
    if (absV <= deadzoneDpPerSec) return 0f
    val effective = (absV - deadzoneDpPerSec) / refSpeedDpPerSec
    val norm = effective / (1f + 0.65f * effective)
    val mag = (maxBankDeg * norm).coerceIn(0f, maxBankDeg)
    return if (vxDpPerSec >= 0f) mag else -mag
}

/**
 * 计算撞边继续外推时的法向挤压量（0f..1f，对应最多 5% 的柔和微挤压）。
 */
internal fun computeCursorEdgePushAmount(
    rawNextCoord: Float,
    maxCoord: Float,
    stepPx: Float,
    density: Float,
    refPushDp: Float = 8f,
): Float {
    if (!rawNextCoord.isFinite() || !stepPx.isFinite() || maxCoord <= 1f) return 0f
    val safeDensity = density.coerceAtLeast(1f)
    val refPx = (refPushDp * safeDensity).coerceAtLeast(1f)
    val pushingMin = rawNextCoord <= 0f && stepPx < 0f
    val pushingMax = rawNextCoord >= maxCoord && stepPx > 0f
    if (!pushingMin && !pushingMax) return 0f
    val overshoot = if (pushingMin) -rawNextCoord else rawNextCoord - maxCoord
    val pushPx = maxOf(overshoot, kotlin.math.abs(stepPx))
    val norm = pushPx / refPx
    return (norm / (1f + 0.5f * norm)).coerceIn(0f, 1f)
}

/**
 * 计算安全界面拦截受阻微抖角（阻尼正弦衰减，严格限幅在 ±3.5° 内）。
 */
internal fun computeCursorBlockedShakeDeg(
    elapsedSec: Float,
    durationSec: Float = CURSOR_BLOCKED_SHAKE_DURATION_SEC,
    amplitudeDeg: Float = CURSOR_BLOCKED_SHAKE_AMPLITUDE_DEG,
): Float {
    if (!elapsedSec.isFinite() || elapsedSec <= 0f || elapsedSec >= durationSec || durationSec <= 0f) {
        return 0f
    }
    val u = (elapsedSec / durationSec).coerceIn(0f, 1f)
    val envelope = (1f - u) * (1f - u)
    val wave = kotlin.math.sin(u * 5.0 * kotlin.math.PI).toFloat()
    return (amplitudeDeg * envelope * wave).coerceIn(-CURSOR_DYNAMICS_MAX_ANGLE_DEG, CURSOR_DYNAMICS_MAX_ANGLE_DEG)
}

/**
 * 可原地更新的光标本体动力学状态（零堆分配，严格满足 0.85f..1.03f 缩放包络与 ±3.5° 角度包络）。
 */
internal class MutableCursorDynamicsState(
    var scale: Float = 1f,
    var scaleVel: Float = 0f,
    var bankDeg: Float = 0f,
    var bankVelDeg: Float = 0f,
    var squishX: Float = 0f,
    var squishVelX: Float = 0f,
    var squishY: Float = 0f,
    var squishVelY: Float = 0f,
    var shakeElapsedSec: Float = -1f,
    var shakeDeg: Float = 0f,
) {
    val renderScaleX: Float
        get() = (scale * (1f - CURSOR_EDGE_SQUISH_MAX * squishX.coerceIn(0f, 1f)))
            .coerceIn(CURSOR_DYNAMICS_MIN_SCALE, CURSOR_DYNAMICS_MAX_SCALE)

    val renderScaleY: Float
        get() = (scale * (1f - CURSOR_EDGE_SQUISH_MAX * squishY.coerceIn(0f, 1f)))
            .coerceIn(CURSOR_DYNAMICS_MIN_SCALE, CURSOR_DYNAMICS_MAX_SCALE)

    val renderAngleDeg: Float
        get() = (bankDeg + shakeDeg).coerceIn(-CURSOR_DYNAMICS_MAX_ANGLE_DEG, CURSOR_DYNAMICS_MAX_ANGLE_DEG)

    val isIdentity: Boolean
        get() = shakeElapsedSec < 0f &&
            kotlin.math.abs(renderScaleX - 1f) < 0.001f &&
            kotlin.math.abs(renderScaleY - 1f) < 0.001f &&
            kotlin.math.abs(renderAngleDeg) < 0.02f

    fun triggerClickPeck() {
        if (scaleVel > -4.9f) {
            scaleVel = -4.9f
        }
    }

    fun triggerLatchPop(fromCharge: Boolean) {
        val targetVel = if (fromCharge) 1.4f else 0.9f
        if (scaleVel < targetVel) {
            scaleVel = targetVel
        }
    }

    fun triggerBlockedShake() {
        shakeElapsedSec = 0f
        shakeDeg = 0f
    }

    fun isSettled(
        targetScale: Float,
        targetBankDeg: Float,
        targetSquishX: Float,
        targetSquishY: Float,
    ): Boolean =
        shakeElapsedSec < 0f &&
            kotlin.math.abs(scale - targetScale) < 0.0015f &&
            kotlin.math.abs(scaleVel) < 0.01f &&
            kotlin.math.abs(bankDeg - targetBankDeg) < 0.04f &&
            kotlin.math.abs(bankVelDeg) < 0.4f &&
            kotlin.math.abs(squishX - targetSquishX) < 0.003f &&
            kotlin.math.abs(squishVelX) < 0.02f &&
            kotlin.math.abs(squishY - targetSquishY) < 0.003f &&
            kotlin.math.abs(squishVelY) < 0.02f

    fun snapTo(
        targetScale: Float,
        targetBankDeg: Float,
        targetSquishX: Float,
        targetSquishY: Float,
    ) {
        scale = targetScale.coerceIn(CURSOR_DYNAMICS_MIN_SCALE, CURSOR_DYNAMICS_MAX_SCALE)
        scaleVel = 0f
        bankDeg = targetBankDeg.coerceIn(-CURSOR_DYNAMICS_MAX_BANK_DEG, CURSOR_DYNAMICS_MAX_BANK_DEG)
        bankVelDeg = 0f
        squishX = targetSquishX.coerceIn(0f, 1f)
        squishVelX = 0f
        squishY = targetSquishY.coerceIn(0f, 1f)
        squishVelY = 0f
        shakeElapsedSec = -1f
        shakeDeg = 0f
    }

    fun reset() {
        snapTo(1f, 0f, 0f, 0f)
    }
}

/**
 * 原地步进光标本体弹簧动力学（子步欧拉积分，近临界阻尼，适应 30–240Hz 任意 VRR 帧间隔，零堆分配）。
 */
internal fun stepCursorDynamicsInPlace(
    state: MutableCursorDynamicsState,
    targetScale: Float,
    targetBankDeg: Float,
    targetSquishX: Float,
    targetSquishY: Float,
    dtSec: Float,
) {
    val safeTargetScale = targetScale.coerceIn(CURSOR_DYNAMICS_MIN_SCALE, CURSOR_DYNAMICS_MAX_SCALE)
    val safeTargetBank = targetBankDeg.coerceIn(-CURSOR_DYNAMICS_MAX_BANK_DEG, CURSOR_DYNAMICS_MAX_BANK_DEG)
    val safeTargetSqX = targetSquishX.coerceIn(0f, 1f)
    val safeTargetSqY = targetSquishY.coerceIn(0f, 1f)

    val clampedDt = dtSec.coerceIn(0.001f, 0.040f)
    val subSteps = when {
        clampedDt > 0.032f -> 5
        clampedDt > 0.024f -> 4
        clampedDt > 0.016f -> 3
        clampedDt > 0.008f -> 2
        else -> 1
    }
    val stepDt = clampedDt / subSteps

    val scaleOmega = 18f
    val scaleZeta = 0.85f
    val scaleK = scaleOmega * scaleOmega
    val scaleC = 2f * scaleZeta * scaleOmega

    val bankOmega = 20f
    val bankZeta = 0.90f
    val bankK = bankOmega * bankOmega
    val bankC = 2f * bankZeta * bankOmega

    val squishOmega = 22f
    val squishZeta = 0.88f
    val squishK = squishOmega * squishOmega
    val squishC = 2f * squishZeta * squishOmega

    var scale = state.scale
    var scaleVel = state.scaleVel
    var bank = state.bankDeg
    var bankVel = state.bankVelDeg
    var sqX = state.squishX
    var sqVelX = state.squishVelX
    var sqY = state.squishY
    var sqVelY = state.squishVelY

    repeat(subSteps) {
        val aScale = scaleK * (safeTargetScale - scale) - scaleC * scaleVel
        scaleVel += aScale * stepDt
        scale += scaleVel * stepDt
        if (scale < CURSOR_DYNAMICS_MIN_SCALE) {
            scale = CURSOR_DYNAMICS_MIN_SCALE
            if (scaleVel < 0f) scaleVel = 0f
        } else if (scale > CURSOR_DYNAMICS_MAX_SCALE) {
            scale = CURSOR_DYNAMICS_MAX_SCALE
            if (scaleVel > 0f) scaleVel = 0f
        }

        val aBank = bankK * (safeTargetBank - bank) - bankC * bankVel
        bankVel += aBank * stepDt
        bank = (bank + bankVel * stepDt).coerceIn(-CURSOR_DYNAMICS_MAX_BANK_DEG, CURSOR_DYNAMICS_MAX_BANK_DEG)

        val aSqX = squishK * (safeTargetSqX - sqX) - squishC * sqVelX
        sqVelX += aSqX * stepDt
        sqX = (sqX + sqVelX * stepDt).coerceIn(0f, 1.1f)

        val aSqY = squishK * (safeTargetSqY - sqY) - squishC * sqVelY
        sqVelY += aSqY * stepDt
        sqY = (sqY + sqVelY * stepDt).coerceIn(0f, 1.1f)
    }

    state.scale = scale
    state.scaleVel = scaleVel
    state.bankDeg = bank
    state.bankVelDeg = bankVel
    state.squishX = sqX.coerceIn(0f, 1f)
    state.squishVelX = sqVelX
    state.squishY = sqY.coerceIn(0f, 1f)
    state.squishVelY = sqVelY

    if (state.shakeElapsedSec >= 0f) {
        val nextElapsed = state.shakeElapsedSec + clampedDt
        if (nextElapsed >= CURSOR_BLOCKED_SHAKE_DURATION_SEC) {
            state.shakeElapsedSec = -1f
            state.shakeDeg = 0f
        } else {
            state.shakeElapsedSec = nextElapsed
            state.shakeDeg = computeCursorBlockedShakeDeg(nextElapsed)
        }
    }
}

internal const val CURSOR_EDGE_NONE = 0
internal const val CURSOR_EDGE_LEFT = 1 shl 0
internal const val CURSOR_EDGE_RIGHT = 1 shl 1
internal const val CURSOR_EDGE_TOP = 1 shl 2
internal const val CURSOR_EDGE_BOTTOM = 1 shl 3
internal const val CURSOR_EDGE_REARM_DISTANCE_DP = 14f
internal const val CURSOR_EDGE_HAPTIC_COOLDOWN_MS = 180L

/**
 * 屏幕四边撞边触觉迟滞冷却状态机（零分配）：
 * - 同一轴首次触碰边界触发一次微刻度（返回非 0 边缘掩码）；
 * - 贴边滑动或微颤绝不连续重复触发；
 * - 同一轴必须离开边界至少 [rearmDistanceDp] (14dp) 且距上次触发 >= [cooldownMs] (180ms) 后才重新武装。
 */
internal class CursorEdgeHapticTracker(
    private val rearmDistanceDp: Float = CURSOR_EDGE_REARM_DISTANCE_DP,
    private val cooldownMs: Long = CURSOR_EDGE_HAPTIC_COOLDOWN_MS,
) {
    var leftArmed: Boolean = true
        private set
    var rightArmed: Boolean = true
        private set
    var topArmed: Boolean = true
        private set
    var bottomArmed: Boolean = true
        private set

    private var lastXTriggerMs: Long = -1L
    private var lastYTriggerMs: Long = -1L

    fun syncInitialPosition(x: Float, y: Float, maxX: Float, maxY: Float) {
        val edgeEps = 0.5f
        leftArmed = maxX > 1f && x > edgeEps
        rightArmed = maxX > 1f && x < maxX - edgeEps
        topArmed = maxY > 1f && y > edgeEps
        bottomArmed = maxY > 1f && y < maxY - edgeEps
    }

    fun reset() {
        leftArmed = true
        rightArmed = true
        topArmed = true
        bottomArmed = true
        lastXTriggerMs = -1L
        lastYTriggerMs = -1L
    }

    fun onCursorMoved(
        x: Float,
        y: Float,
        maxX: Float,
        maxY: Float,
        density: Float,
        nowMs: Long,
    ): Int {
        if (!x.isFinite() || !y.isFinite() || maxX <= 1f || maxY <= 1f) return CURSOR_EDGE_NONE
        val safeDensity = density.coerceAtLeast(1f)
        val rearmPxX = minOf(rearmDistanceDp * safeDensity, maxX * 0.4f)
        val rearmPxY = minOf(rearmDistanceDp * safeDensity, maxY * 0.4f)
        val edgeEps = 0.5f

        var triggered = CURSOR_EDGE_NONE

        if (x >= rearmPxX) leftArmed = true
        if (x <= maxX - rearmPxX) rightArmed = true

        if (x <= edgeEps) {
            if (leftArmed && (lastXTriggerMs < 0L || nowMs - lastXTriggerMs >= cooldownMs)) {
                triggered = triggered or CURSOR_EDGE_LEFT
                lastXTriggerMs = nowMs
            }
            leftArmed = false
        } else if (x >= maxX - edgeEps) {
            if (rightArmed && (lastXTriggerMs < 0L || nowMs - lastXTriggerMs >= cooldownMs)) {
                triggered = triggered or CURSOR_EDGE_RIGHT
                lastXTriggerMs = nowMs
            }
            rightArmed = false
        }

        if (y >= rearmPxY) topArmed = true
        if (y <= maxY - rearmPxY) bottomArmed = true

        if (y <= edgeEps) {
            if (topArmed && (lastYTriggerMs < 0L || nowMs - lastYTriggerMs >= cooldownMs)) {
                triggered = triggered or CURSOR_EDGE_TOP
                lastYTriggerMs = nowMs
            }
            topArmed = false
        } else if (y >= maxY - edgeEps) {
            if (bottomArmed && (lastYTriggerMs < 0L || nowMs - lastYTriggerMs >= cooldownMs)) {
                triggered = triggered or CURSOR_EDGE_BOTTOM
                lastYTriggerMs = nowMs
            }
            bottomArmed = false
        }

        return triggered
    }
}

