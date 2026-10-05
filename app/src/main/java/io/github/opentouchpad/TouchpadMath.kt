package io.github.opentouchpad

import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val PANEL_MIN_WIDTH_DP = 110
internal const val CONTROL_MIN_SIZE_DP = 110
internal const val CONTROL_MAX_SIZE_DP = 2400
internal const val FLOATING_BALL_MIN_DP = 16
internal const val FLOATING_BALL_MAX_DP = 140
internal const val CURSOR_MIN_DP = 8
internal const val CURSOR_MAX_DP = 160
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

internal data class PanelPosition(val x: Int, val y: Int)

internal data class CustomSwipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) {
    val distance: Float
        get() = kotlin.math.hypot(endX - startX, endY - startY)
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

internal fun cursorViewOrigin(cursorX: Float, cursorY: Float): PanelPosition =
    PanelPosition(cursorX.roundToInt(), cursorY.roundToInt())

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

internal fun shouldScheduleLongPress(dwellMs: Int, dragging: Boolean): Boolean =
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
 * [slots] has [BUTTON_SLOT_COUNT] entries in the order of [BUTTON_SLOT_CELLS]: TOP 1-4, BOTTOM 1-4,
 * LEFT 1-2, RIGHT 1-2, corners TL, TR, BL, BR, left column upper cell (default move key),
 * right column lower cell (default resize key).
 * Button size and the touchpad rectangle depend only on the panel size; [spacingPx]
 * only changes the distance between neighbouring buttons inside each row/column.
 */
internal data class ControlLayout(
    val button: Int,
    val pad: ControlRect,
    val slots: List<ControlRect>,
)

internal const val CONTROL_EDGE_DP = 1
internal const val CONTROL_BUTTON_MAX_DP = 46
internal const val CONTROL_BUTTON_MIN_DP = 16

/** 6 列 × 5 行网格：按钮直径只由面板宽度和间距决定。 */
internal fun controlButtonSize(width: Int, density: Float, gapPx: Int): Int {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    return ((width - 2 * edge - 5 * gapPx) / 6)
        .coerceIn((CONTROL_BUTTON_MIN_DP * density).roundToInt(), (CONTROL_BUTTON_MAX_DP * density).roundToInt())
}

/** 网格最紧凑时的面板高度（触控板高度 = 3 个按钮 + 2 个间距）。 */
internal fun controlGridHeight(width: Int, density: Float, gapPx: Int): Int {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    return 2 * edge + 5 * controlButtonSize(width, density, gapPx) + 4 * gapPx
}

/**
 * Uniform grid layout. Every neighbour distance — button↔button and button↔touchpad — is
 * exactly [spacingPx], in rows, side columns and corners alike.
 *
 * Columns: corner/left | top 1..4 | corner/right. Rows: corner/top | left/right ×3 | corner/bottom.
 * Left column = slot 16 (default move key), LEFT 1, LEFT 2.
 * Right column = RIGHT 1, RIGHT 2, slot 17 (default resize key).
 * Height above [controlGridHeight] only makes the touchpad taller; side-column buttons stay
 * packed with the same gap and are centred vertically.
 */
internal fun computeControlLayout(width: Int, height: Int, density: Float, spacingPx: Int): ControlLayout {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    val gap = spacingPx.coerceAtLeast(0)
    val b = controlButtonSize(width, density, gap)
    val step = b + gap
    // 宽度被最小按钮尺寸卡住时，整体水平居中
    val gridW = 6 * b + 5 * gap
    val x0 = ((width - gridW) / 2).coerceAtLeast(0)
    val colX = List(6) { x0 + it * step }
    val topY = edge
    val bottomY = (height - edge - b).coerceAtLeast(topY + 4 * step)
    val padTop = topY + step
    val padH = bottomY - gap - padTop
    val pad = ControlRect(colX[1], padTop, 4 * b + 3 * gap, padH)
    // 侧列 3 个按钮按同样间距紧排，并在触控板高度内居中
    val sideStart = padTop + (padH - (3 * b + 2 * gap)) / 2
    val sideY = List(3) { sideStart + it * step }
    fun at(x: Int, y: Int) = ControlRect(x, y, b, b)
    val leftX = colX[0]
    val rightX = colX[5]
    val slots = (1..4).map { at(colX[it], topY) } +
        (1..4).map { at(colX[it], bottomY) } +
        listOf(at(leftX, sideY[1]), at(leftX, sideY[2])) +
        listOf(at(rightX, sideY[0]), at(rightX, sideY[1])) +
        listOf(at(leftX, topY), at(rightX, topY), at(leftX, bottomY), at(rightX, bottomY)) +
        listOf(at(leftX, sideY[0]), at(rightX, sideY[2]))
    return ControlLayout(button = b, pad = pad, slots = slots)
}

internal fun controlHeightRange(screenHeight: Int, density: Float): IntRange {
    val minPx = (CONTROL_MIN_SIZE_DP * density).roundToInt()
    val maxPx = minOf((CONTROL_MAX_SIZE_DP * density).roundToInt(), screenHeight)
    return minPx.coerceAtMost(maxPx)..maxPx
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

internal fun resolveBallLongPressHoldMs(configuredLongPressMs: Int, minSafeMs: Long = 450L): Long {
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
    val swipeThresholdPx: Float,
    val cancelThresholdPx: Float,
) {
    var hasArmedSwipe: Boolean = false
        private set
    var isCancelledByReturn: Boolean = false
        private set

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


