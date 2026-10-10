package io.github.opentouchpad

import kotlin.math.pow
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
internal const val CONTROL_BUTTON_MIN_DP = 16

/** 6 列等比例基础单元：按键厚度/基准直径由面板宽度和间距决定。 */
internal fun controlButtonSize(width: Int, density: Float, gapPx: Int): Int {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    return ((width - 2 * edge - 5 * gapPx) / 6)
        .coerceIn((CONTROL_BUTTON_MIN_DP * density).roundToInt(), (CONTROL_BUTTON_MAX_DP * density).roundToInt())
}

/** 触控板区域在最紧凑网格下的最低高度。4/8 键位为 2 单元，12/16/18 键位为紧凑 3 单元。 */
internal fun controlMinPadHeight(b: Int, gap: Int, buttonCount: Int = 16): Int = when (buttonCount) {
    4, 8 -> 2 * b + gap
    12, 16, 18 -> 3 * b + 2 * gap
    else -> 3 * b + 2 * gap
}

/** 网格最紧凑时的面板高度（上下按键 + 间距 + 最低触控板高度）。 */
internal fun controlGridHeight(width: Int, density: Float, gapPx: Int, buttonCount: Int = 16): Int {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    val gap = gapPx.coerceAtLeast(0)
    val b = controlButtonSize(width, density, gap)
    val minPadH = controlMinPadHeight(b, gap, buttonCount)
    return 2 * edge + 2 * b + 2 * gap + minPadH
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
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    val gap = spacingPx.coerceAtLeast(0)
    val b = controlButtonSize(width, density, gap)

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
 */
internal fun resolveTouchRingColor(customColor: Int, isDark: Boolean): Int {
    return if (customColor != 0) customColor else if (isDark) 0xFFD97757.toInt() else 0xFFC96442.toInt()
}

/**
 * 解析设置界面强调色（按钮与滑动条）：0 表示跟随主题默认色（深色模式 0xFFD97757 珊瑚陶土，浅色模式 0xFFC96442 暖陶土）。
 */
internal fun resolveUiAccentColor(customColor: Int, isDark: Boolean): Int {
    return if (customColor != 0) {
        if ((customColor ushr 24) == 0) (customColor or 0xFF000000.toInt()) else customColor
    } else {
        if (isDark) 0xFFD97757.toInt() else 0xFFC96442.toInt()
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

