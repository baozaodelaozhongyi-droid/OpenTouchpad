package io.github.opentouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * OpenTouchpad 的无障碍服务：在屏幕上放一块触控板 + 一枚光标 + 一排可自定义的大按钮，
 * 用小范围的手指动作去操作整块屏幕。
 *
 * 与"改别人的包"相比的关键差别：这是一个真·无障碍工具，因此可以（也应该）
 * 在 accessibility_service_config.xml 里声明 isAccessibilityTool="true"，
 * Android 16 的 accessibilityDataSensitive 才不会拦掉注入的手势。
 */
class TouchpadService : AccessibilityService() {

    companion object {
        /** 反馈圆环最大半径（dp）：规格要求直径约 22dp，所以半径 11dp。 */
        private const val TOUCH_RING_RADIUS_DP = 11
        /** 穿透点击前等待 FLAG_NOT_TOUCHABLE 真正同步到系统 InputDispatcher 的安全延时（毫秒）。 */
        private const val OVERLAY_PASS_THROUGH_DELAY_MS = 50L

        @Volatile
        var instance: TouchpadService? = null
            private set

        @Volatile
        var onPanelResized: ((widthDp: Int, extraHeightDp: Int) -> Unit)? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var isPassThroughActive = false
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var padArea: View? = null
    private var miniBallView: View? = null
    private val actionViews = mutableListOf<View>()
    private val actionSlots = mutableListOf<Int>()

    private var cursorView: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    private var screenW = 0
    private var screenH = 0
    private var cursorX = 0f
    private var cursorY = 0f

    // 手指状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressFired = false
    private var dwellFired = false

    private var customSwipeArmed = false
    private var customSwipeStartX = 0f
    private var customSwipeStartY = 0f
    private var customSwipeEndX = 0f
    private var customSwipeEndY = 0f

    // 拖拽锁定：第一次按记下起点，之后正常移动光标，第二次按一次性注入「按住→拖到终点→松开」。
    // 不在两次按键之间保持注入的手指按下：真实手指一碰屏幕，系统就会取消正在注入的手势，
    // 结果只剩一次点击（旧版本的问题）。
    private var dragging = false
    private val dragTrail = mutableListOf<TrailPoint>()

    // 面板拖动 / 缩放
    private var movingPanel = false
    private var movingFromX = 0f
    private var movingFromY = 0f
    private var movingPanelX = 0
    private var movingPanelY = 0
    private var resizingPanel = false
    private var resizingFromX = 0f
    private var resizingFromY = 0f
    private var resizingPanelWidth = 0
    private var resizingPanelHeight = 0

    // 悬浮球手势状态与坐标记录
    private var isDraggingBall = false
    private var ballLongPressTriggered = false
    private var ballMoved = false
    private var lastBallTapTime = 0L
    private var isWaitingForBallDoubleTap = false
    private var ballFromX = 0f
    private var ballFromY = 0f
    private var ballX = 0
    private var ballY = 0
    private var ballAnchorX = 0
    private var ballAnchorY = 0
    private var ballDragStartX = 0
    private var ballDragStartY = 0
    private var ballSnapAnimator: ValueAnimator? = null
    private var ballSwipeTracker = BallSwipeTracker(0f, 0f)
    private var ballBgView: View? = null
    private var ballIconView: ImageView? = null
    private var lastDragRawX = 0f
    private var lastDragRawY = 0f
    private var ballDragLastTimeMs = 0L
    private var ballDragVelocityX = 0f
    private var ballDragVelocityY = 0f
    private var ballDragCurrentRotation = 0f

    private val ballSingleTapRunnable = Runnable {
        if (isWaitingForBallDoubleTap) {
            isWaitingForBallDoubleTap = false
            val act = prefs.ballActionSingleTap
            if (act != PadAction.NONE) {
                performAction(act, withHaptic = false)
            }
        }
    }

    private val ballLongPressRunnable = Runnable {
        if (ballMoved) return@Runnable
        val act = prefs.ballActionLongPress
        ballLongPressTriggered = true
        ballHapticLongPress()
        if (act == PadAction.MOVE_BALL) {
            isDraggingBall = true
            ballDragVelocityX = 0f
            ballDragVelocityY = 0f
            ballDragCurrentRotation = 0f
            ballDragLastTimeMs = SystemClock.uptimeMillis()
            lastDragRawX = ballFromX
            lastDragRawY = ballFromY
            ballBgView?.animate()?.cancel()
            ballIconView?.animate()?.cancel()
            ballBgView?.animate()
                ?.scaleX(1.18f)
                ?.scaleY(1.18f)
                ?.setDuration(180)
                ?.setInterpolator(OvershootInterpolator(1.3f))
                ?.start()
            ballIconView?.animate()
                ?.scaleX(1.10f)
                ?.scaleY(1.10f)
                ?.setDuration(180)
                ?.start()
        } else if (act != PadAction.NONE) {
            performAction(act, withHaptic = false)
        }
    }

    private fun ballHapticLongPress() {
        if (!prefs.haptics) return
        runCatching {
            miniBallView?.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    private fun ballHapticCancel() {
        if (!prefs.haptics) return
        runCatching {
            (miniBallView ?: panel)?.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    private fun applyBallDeformation(dx: Float, dy: Float, maxOffset: Float) {
        val deform = computeBallDeformation(dx, dy, maxOffset)
        ballBgView?.rotation = deform.rotationDeg
        ballBgView?.scaleX = deform.stretch
        ballBgView?.scaleY = deform.squash
        ballIconView?.translationX = deform.iconTranslationX
        ballIconView?.translationY = deform.iconTranslationY
    }

    private fun resetBallDeformation(animated: Boolean = true, duration: Long = 220L) {
        if (animated) {
            ballBgView?.animate()
                ?.rotation(0f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(duration)
                ?.setInterpolator(OvershootInterpolator(1.4f))
                ?.start()

            ballIconView?.animate()
                ?.translationX(0f)
                ?.translationY(0f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(duration)
                ?.setInterpolator(OvershootInterpolator(1.4f))
                ?.start()
        } else {
            ballBgView?.animate()?.cancel()
            ballIconView?.animate()?.cancel()
            ballBgView?.rotation = 0f
            ballBgView?.scaleX = 1f
            ballBgView?.scaleY = 1f
            ballIconView?.translationX = 0f
            ballIconView?.translationY = 0f
            ballIconView?.scaleX = 1f
            ballIconView?.scaleY = 1f
        }
    }


    // 键盘弹出时自动最小化的标记存在 Prefs.minimizedByKeyboard（服务重启后也能自动还原）

    // 停留点击触发时的光标位置：同一次触摸里，光标离开这里超过阈值才会再次计时，避免原地连点
    private var dwellAnchorX = Float.NaN
    private var dwellAnchorY = Float.NaN

    private val longPressRunnable = Runnable {
        if (!moved && !dwellFired) {
            longPressFired = true
            customSwipeArmed = true
            customSwipeStartX = cursorX
            customSwipeStartY = cursorY
            customSwipeEndX = cursorX
            customSwipeEndY = cursorY
            cancelDwell()
            if (prefs.touchRingEnabled) {
                moveTouchRing(cursorX, cursorY)
                touchRing?.pressUntilRelease()
            }
            haptic()
        }
    }
    private var dwellRunnable: Runnable? = null

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 用 Java 常量程序化补齐一次配置，不依赖 XML 枚举值的映射：
        // - FLAG_RETRIEVE_INTERACTIVE_WINDOWS 才能用 getWindows() 判断输入法是否弹出
        // - TYPE_WINDOWS_CHANGED 才会在窗口/键盘变化时回调
        runCatching {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            serviceInfo = info
        }

        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        refreshScreenMetrics()
        cursorX = screenW / 2f
        cursorY = screenH / 2f
        buildCursor()
        updateCursor()
        buildPanel()
        // 服务被重启时可能正处于「键盘自动收起」状态：键盘已经不在了就立刻还原
        syncKeyboardMinimize()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        cancelDwell()
        gestureQueue.clear()
        isDispatching = false
        isPassThroughActive = false
        setOverlayTouchable(true)
        instance = null
        onPanelResized = null
        cancelDrag()
        removeTouchRing()
        cancelTransitions()
        removePanel()
        cursorView?.let { runCatching { wm.removeView(it) } }
        cursorView = null
        cursorParams = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        main.removeCallbacksAndMessages(null)
        cancelDwell()
        gestureQueue.clear()
        isDispatching = false
        setOverlayTouchable(true)
        cancelDrag()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        syncKeyboardMinimize()
    }

    /**
     * 键盘弹出自动收起 / 键盘收起自动还原。
     * 「是不是键盘收起的」存在 Prefs 里：服务被系统重启后也能正确还原；
     * 自动收起期间关掉这个开关，也立即还原，不会一直卡在收起状态。
     */
    fun syncKeyboardMinimize() {
        if (!this::prefs.isInitialized) return
        if (!prefs.minimizeOnKeyboard) {
            if (prefs.minimizedByKeyboard) {
                prefs.minimizedByKeyboard = false
                prefs.minimized = false
                buildPanel()
            }
            return
        }
        val ime = isImeVisible()
        if (ime && !prefs.minimized) {
            prefs.minimizedByKeyboard = true
            prefs.minimized = true
            buildPanel()
        } else if (!ime && prefs.minimizedByKeyboard) {
            prefs.minimizedByKeyboard = false
            prefs.minimized = false
            buildPanel()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenMetrics()
        cursorX = cursorX.coerceIn(0f, screenW.toFloat())
        cursorY = cursorY.coerceIn(0f, screenH.toFloat())
        buildPanel()
        updateCursor()
    }

    /** 设置界面改完之后调用：重建所有视图。 */
    fun reload() {
        prefs = Prefs(this)
        removeTouchRing()
        isPassThroughActive = false
        buildCursor()
        buildPanel()
        updateCursor()
    }

    /** 实时平滑更新触控板尺寸与边距（拖拽缩放键或滑动条时调用，绝不重建窗口，零闪烁）。 */
    fun updatePanelGeometry() {
        prefs = Prefs(this)
        val root = panel as? FrameLayout ?: return
        if (root === miniBallView) return
        val window = panelParams ?: return
        val widthRange = controlWidthRange(screenW, resources.displayMetrics.density)
        val widthPx = if (prefs.panelWidthDp > 0) {
            dp(prefs.panelWidthDp)
        } else {
            screenW * prefs.padWidthPercent / 100
        }.coerceIn(widthRange.first, widthRange.last)
        val heightPx = panelHeightFor(widthPx, prefs.extraHeightDp)
        val position = clampPanelPosition(window.x, window.y, widthPx, heightPx, screenW, screenH)
        window.x = position.x
        window.y = position.y
        window.width = widthPx
        window.height = heightPx
        root.layoutParams = window
        layoutControlChildren(root, widthPx, heightPx)
        runCatching { wm.updateViewLayout(root, window) }
        root.requestLayout()
        root.invalidate()
    }

    /** 实时平滑更新触控板透明度与按键样式（零闪烁）。 */
    fun updatePanelAppearance() {
        prefs = Prefs(this)
        val root = panel as? FrameLayout ?: return
        if (root === miniBallView) return
        val window = panelParams ?: return
        layoutControlChildren(root, window.width, window.height)
        root.invalidate()
    }

    /** 实时平滑更新悬浮球尺寸、透明度与颜色（零闪烁）。 */
    fun updateBallAppearance() {
        prefs = Prefs(this)
        val dot = miniBallView ?: return
        val lp = panelParams ?: return
        val size = dp(prefs.floatingBallSizeDp.coerceIn(FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP))
        val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
            ?: if (isDarkTheme()) 0xFFD97757.toInt() else 0xFFC96442.toInt()
        val light = Color.luminance(ballColor) > 0.55f
        val ballSize = (size * 0.86f).roundToInt()
        val halo = (ballSize * 0.06f).roundToInt().coerceAtLeast(dp(1))

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((ballColor and 0x00FFFFFF) or 0x40000000)
        }
        val disc = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(ballColor, 0.08f), ballColor),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, dp(1)), if (light) 0x26141413 else 0x33FAF9F5)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }

        ballBgView?.background = RippleDrawable(
            ColorStateList.valueOf(if (light) 0x26141413 else 0x40FAF9F5),
            LayerDrawable(arrayOf(ring, disc)).apply { setLayerInset(1, halo, halo, halo, halo) },
            mask,
        )
        ballBgView?.layoutParams = FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER)

        val icon = ballIconView
        if (icon != null) {
            icon.imageTintList = ColorStateList.valueOf(if (light) 0xFF141413.toInt() else 0xFFFAF9F5.toInt())
            val iconPadding = (ballSize * 0.04f).roundToInt()
            icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            val iconSize = (ballSize * 0.48f).roundToInt()
            icon.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }

        dot.alpha = ballRestingAlpha()
        if (lp.width != size || lp.height != size) {
            lp.width = size
            lp.height = size
            val position = clampFloatingBallPosition(lp.x, lp.y, size, screenW, screenH)
            lp.x = position.x
            lp.y = position.y
            prefs.ballX = position.x
            prefs.ballY = position.y
            ballAnchorX = position.x
            ballAnchorY = position.y
            runCatching { wm.updateViewLayout(dot, lp) }
        }
        dot.requestLayout()
        dot.invalidate()
    }

    /** 实时平滑更新光标大小、透明度与颜色（零闪烁）。 */
    fun updateCursorAppearance() {
        prefs = Prefs(this)
        val cv = cursorView ?: return
        val cp = cursorParams ?: return
        val size = dp(prefs.cursorSizeDp.coerceIn(CURSOR_MIN_DP, CURSOR_MAX_DP))
        cv.alpha = prefs.cursorOpacityPercent.coerceIn(10, 100) / 100f
        (cv as? CursorArrowView)?.pointerColor = prefs.cursorColor
        if (cp.width != size || cp.height != size) {
            cp.width = size
            cp.height = size
            runCatching { wm.updateViewLayout(cv, cp) }
        }
        cv.invalidate()
    }

    /** 实时平滑更新光圈样式与开关（零闪烁），可选播放预览动画。 */
    fun updateTouchRingAppearance(preview: Boolean = false) {
        prefs = Prefs(this)
        val enabled = prefs.touchRingEnabled
        val color = resolveTouchRingColor()
        touchRing?.let { v ->
            v.accent = color
            if (!enabled) {
                v.clear()
            }
        }
        if (enabled && preview) {
            showTouchRing(cursorX, cursorY, 0L)
        }
    }

    fun toggleMinimize() {
        prefs.minimized = !prefs.minimized
        prefs.minimizedByKeyboard = false
        buildPanel(animate = true)
    }

    // ───────────────────────── 视图构建 ─────────────────────────

    private fun refreshScreenMetrics() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun isDarkTheme(): Boolean = true

    private fun buildCursor() {
        cursorView?.let { runCatching { wm.removeView(it) } }
        val size = dp(prefs.cursorSizeDp.coerceIn(CURSOR_MIN_DP, CURSOR_MAX_DP))
        val v = CursorArrowView(this).apply {
            pointerColor = prefs.cursorColor
            alpha = prefs.cursorOpacityPercent.coerceIn(10, 100) / 100f
        }
        cursorParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }
        runCatching { wm.addView(v, cursorParams) }
        cursorView = v
    }

    private fun buildPanel(animate: Boolean = false) {
        // 用户主动展开/收起时做过渡动画：旧窗口先摘下来单独播放退场，再建新窗口播放入场。
        val outgoing = if (animate) detachPanelForTransition() else null
        removePanel()
        // 新窗口在 addView 之前就设为透明，第一帧不会以完整样子闪一下
        enteringWithTransition = outgoing != null
        // 横屏时按设置自动隐藏整块触控板（光标保留）
        if (isLandscape() && prefs.autoHideLandscape) {
            cursorView?.visibility = View.VISIBLE
            outgoing?.let { finishOutgoing(it) }
            enteringWithTransition = false
            return
        }
        if (prefs.minimized) {
            cursorView?.visibility = View.INVISIBLE
            addMiniDot()
        } else {
            cursorView?.visibility = View.VISIBLE
            addFullPanel()
        }
        enteringWithTransition = false
        raiseTouchRing()
        if (outgoing != null) playTransition(outgoing)
    }

    // ───────────────────────── 展开／收起过渡 ─────────────────────────

    private class Outgoing(val view: View, val lp: WindowManager.LayoutParams)

    private var enteringWithTransition = false

    private val transitionInterpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    private val runningOutgoing = mutableListOf<View>()

    /** 把当前面板/悬浮球从状态里摘出来（窗口暂不移除），供退场动画使用。 */
    private fun detachPanelForTransition(): Outgoing? {
        val v = panel ?: return null
        val lp = panelParams ?: return null
        // 退场期间不再接收触摸，避免误触
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
        panel = null
        panelParams = null
        return Outgoing(v, lp)
    }

    private fun finishOutgoing(o: Outgoing) {
        o.view.animate().cancel()
        // 先让最后一帧变成全透明并提交，再移除窗口，避免系统拿到一张半透明快照
        o.view.alpha = 0f
        o.view.visibility = View.INVISIBLE
        o.view.postOnAnimation {
            runningOutgoing.remove(o.view)
            runCatching { wm.removeView(o.view) }
        }
    }

    /** 当前所有退场中的窗口立即移除（服务销毁、重建时调用）。 */
    private fun cancelTransitions() {
        runningOutgoing.toList().forEach { v ->
            v.animate().cancel()
            runCatching { wm.removeView(v) }
        }
        runningOutgoing.clear()
    }

    /**
     * 面板 ⇄ 悬浮球：两者都朝对方所在的位置缩放。
     * 收起：面板缩向悬浮球并淡出，悬浮球从小弹出；展开：悬浮球缩小淡出，面板从悬浮球方向长出来。
     * 系统「动画时长缩放」设为关闭时，ViewPropertyAnimator 会直接跳到终点。
     */
    private fun playTransition(o: Outgoing) {
        val incoming = panel
        val inLp = panelParams
        runningOutgoing += o.view
        // 两个窗口中心点（屏幕坐标）
        val outW = o.view.width.takeIf { it > 0 } ?: o.lp.width
        val outH = o.view.height.takeIf { it > 0 } ?: o.lp.height
        val outCx = o.lp.x + outW / 2f
        val outCy = o.lp.y + outH / 2f
        val collapsing = incoming == null || incoming === miniBallView

        // 退场：朝新窗口中心缩放
        val toward = if (inLp != null) {
            val inW = if (inLp.width > 0) inLp.width else outW
            val inH = if (inLp.height > 0) inLp.height else outH
            (inLp.x + inW / 2f) to (inLp.y + inH / 2f)
        } else outCx to outCy
        o.view.pivotX = (toward.first - o.lp.x).coerceIn(0f, outW.toFloat())
        o.view.pivotY = (toward.second - o.lp.y).coerceIn(0f, outH.toFloat())
        o.view.animate()
            .scaleX(if (collapsing) 0.2f else 0.6f)
            .scaleY(if (collapsing) 0.2f else 0.6f)
            .alpha(0f)
            .setDuration(if (collapsing) 220L else 140L)
            .setInterpolator(transitionInterpolator)
            .withEndAction { finishOutgoing(o) }
            .start()

        if (incoming == null || inLp == null) return
        val targetAlpha = if (incoming === miniBallView) ballRestingAlpha() else 1f
        incoming.alpha = 0f
        incoming.scaleX = if (collapsing) 0.4f else 0.2f
        incoming.scaleY = incoming.scaleX
        incoming.post {
            if (panel !== incoming) {
                incoming.alpha = targetAlpha; incoming.scaleX = 1f; incoming.scaleY = 1f
                return@post
            }
            // 新窗口从旧窗口中心方向长出来
            incoming.pivotX = (outCx - inLp.x).coerceIn(0f, incoming.width.toFloat())
            incoming.pivotY = (outCy - inLp.y).coerceIn(0f, incoming.height.toFloat())
            incoming.animate()
                .scaleX(1f).scaleY(1f).alpha(targetAlpha)
                .setStartDelay(if (collapsing) 120L else 40L)
                .setDuration(if (collapsing) 200L else 260L)
                .setInterpolator(if (collapsing) android.view.animation.OvershootInterpolator(1.6f) else transitionInterpolator)
                .withEndAction {
                    incoming.scaleX = 1f; incoming.scaleY = 1f; incoming.alpha = targetAlpha
                }
                .start()
        }
    }

    private fun removePanel() {
        padArea?.animate()?.cancel()
        // 拖拽锁定状态（以及跟随光标的圆环）跨面板重建保留，不在这里结束
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        miniBallView = null
        ballBgView = null
        ballIconView = null
        padArea = null
        actionViews.clear()
        actionSlots.clear()
        main.removeCallbacks(restoreTouchRunnable)
        main.removeCallbacks(ballLongPressRunnable)
        main.removeCallbacks(ballSingleTapRunnable)
        ballSnapAnimator?.cancel()
        ballSnapAnimator = null
        isWaitingForBallDoubleTap = false
        isDraggingBall = false
        ballLongPressTriggered = false
        ballMoved = false
        ballSwipeTracker.reset()
        movingPanel = false
        resizingPanel = false
        // 手指还按在旧触控板上时面板被重建（转屏 / 键盘弹出 / 改设置），旧窗口收不到 UP：
        // 这里把长按、停留点击计时一并取消，已经展开的长按圆环收起，避免之后在新面板上误触发、圆环卡住。
        main.removeCallbacks(longPressRunnable)
        cancelDwell()
        if (customSwipeArmed) touchRing?.release()
        customSwipeArmed = false
        padTouchIgnored = false
        // 新窗口本来就是可触摸的；上一笔穿透手势的兜底恢复已在上面移除，这里同步清掉拦截标记，
        // 否则面板重建成「无窗口」（收起且隐藏悬浮球 / 横屏隐藏）时标记会一直留着，之后所有按键都失灵。
        isPassThroughActive = false
    }

    private fun panelLayoutParams(w: Int, h: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // 关掉系统默认的窗口进出动画：它会给被移除的窗口拍一帧快照再淡出，
        // 和我们自己的缩放动画叠在一起就是「残影」。
        lp.windowAnimations = 0
        val storedX = prefs.padX
        val storedY = prefs.padY
        if (storedX >= 0 && storedY >= 0) {
            val position = clampPanelPosition(storedX, storedY, w, h, screenW, screenH)
            lp.x = position.x
            lp.y = position.y
        } else {
            lp.x = (screenW - w) / 2
            lp.y = screenH - h - dp(12)
        }
        return lp
    }

    private fun ballLayoutParams(size: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.windowAnimations = 0
        val position = if (prefs.ballX >= 0 && prefs.ballY >= 0) {
            clampFloatingBallPosition(prefs.ballX, prefs.ballY, size, screenW, screenH)
        } else {
            PanelPosition(screenW - size - dp(18), screenH / 2 - size / 2)
        }
        lp.x = position.x
        lp.y = position.y
        return lp
    }

    private fun addFullPanel() {
        val dark = isDarkTheme()
        val widthRange = controlWidthRange(screenW, resources.displayMetrics.density)
        val widthPx = if (prefs.panelWidthDp > 0) {
            dp(prefs.panelWidthDp)
        } else {
            screenW * prefs.padWidthPercent / 100
        }.coerceIn(widthRange.first, widthRange.last)
        val heightPx = panelHeightFor(widthPx)

        // 根容器本身不处理触摸：只有设成「移动触控板」的按键可以拖动面板。
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            contentDescription = getString(R.string.control_surface_content_description)
        }

        val pad = View(this).apply {
            background = padBackground(dark, dp(22).toFloat())
            contentDescription = getString(R.string.touchpad_content_description)
            cameraDistance = 8000f * resources.displayMetrics.density
            isHapticFeedbackEnabled = false
            setOnTouchListener { _, e -> handlePadTouch(e); true }
        }
        padArea = pad
        root.addView(pad)

        // 槽位与布局一一对应；设为「无」的槽位留空，不会让后面的按钮挪位。
        val buttonCount = prefs.buttonCount
        prefs.buttons.take(buttonCount).forEachIndexed { slot, action ->
            val button = when (action) {
                PadAction.NONE -> return@forEachIndexed
                PadAction.MOVE_PANEL -> makeHandle(action.iconRes, dark) { v, e -> handleMoveTouch(v, e) }
                    .also { it.contentDescription = getString(R.string.act_move_panel) }
                PadAction.RESIZE_PANEL -> makeHandle(action.iconRes, dark) { v, e -> handleResizeTouch(v, e) }
                    .also { it.contentDescription = getString(R.string.act_resize_panel) }
                else -> makeActionButton(slot, action, dark)
            }
            actionViews += button
            actionSlots += slot
            root.addView(button)
        }
        layoutControlChildren(root, widthPx, heightPx)
        // 拖拽锁定进行中，但新面板上已经没有「拖拽锁定」键（被换掉了）：再也没法按第二下结束，
        // 而拖拽期间触控板不点击、不长按，等于整块失灵。直接结束拖拽（不注入手势）。
        if (dragging && PadAction.DRAG_LOCK !in prefs.buttons.take(prefs.buttonCount)) cancelDrag()
        refreshDragButtons()

        val lp = panelLayoutParams(widthPx, heightPx)
        if (enteringWithTransition) root.alpha = 0f
        runCatching { wm.addView(root, lp) }
        panel = root
        panelParams = lp
        root.post {
            if (panel === root) {
                if (prefs.padX < 0 || prefs.padY < 0) {
                    val position = clampPanelPosition((screenW - root.width) / 2, screenH - root.height - dp(12), root.width, root.height, screenW, screenH)
                    lp.x = position.x
                    lp.y = position.y
                    runCatching { wm.updateViewLayout(root, lp) }
                } else {
                    keepPanelInBounds()
                }
            }
        }
    }

    private fun spacingPx(): Int = dp(prefs.buttonSpacingDp.coerceIn(0, BUTTON_SPACING_MAX_DP))

    /** 面板高度 = 当前宽度下最紧凑的网格高度 + 用户加的额外高度，不超过屏幕。 */
    private fun panelHeightFor(widthPx: Int, extraDp: Int = prefs.extraHeightDp): Int {
        val grid = controlGridHeight(widthPx, resources.displayMetrics.density, spacingPx(), prefs.buttonCount)
        return (grid + dp(extraDp.coerceIn(0, CONTROL_EXTRA_HEIGHT_MAX_DP))).coerceAtMost(maxOf(grid, screenH))
    }

    private fun rectParams(r: ControlRect): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(r.w, r.h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = r.x
            topMargin = r.y
        }

    /** 等间距网格：按钮之间、按钮与触控板之间都是同一个间距。 */
    private fun layoutControlChildren(root: FrameLayout, width: Int, height: Int) {
        val buttonCount = prefs.buttonCount
        val layout = computeControlLayout(
            width, height, resources.displayMetrics.density, spacingPx(), buttonCount
        )
        padArea?.layoutParams = rectParams(layout.pad)
        // 触控板圆角随按钮大小变化，保持和按钮的视觉比例
        padCornerRadius = layout.button * 0.42f
        padArea?.background = padBackground(isDarkTheme(), padCornerRadius)

        val p = palette(isDarkTheme())

        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            val rect = layout.slots.getOrNull(slot) ?: return@forEachIndexed
            view.layoutParams = rectParams(rect)

            val cornerRadius = minOf(rect.w, rect.h) / 2f
            view.background = buttonBackground(p.buttonBg, p.buttonStroke, p.ripple, cornerRadius)

            val standardIconPx = (layout.button * 0.56f).roundToInt()
            val maxIconPx = (minOf(rect.w, rect.h) - dp(4)).coerceAtLeast(dp(8))
            val iconPx = minOf(standardIconPx, maxIconPx).coerceAtLeast(dp(8))
            val padX = ((rect.w - iconPx) / 2).coerceAtLeast(0)
            val padY = ((rect.h - iconPx) / 2).coerceAtLeast(0)
            view.setPadding(padX, padY, padX, padY)
        }
        root.requestLayout()
    }

    private fun addMiniDot() {
        if (prefs.hideFloatingBall) {
            panel = null
            panelParams = null
            return
        }
        val size = dp(prefs.floatingBallSizeDp.coerceIn(FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP))
        val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
            ?: if (isDarkTheme()) 0xFFD97757.toInt() else 0xFFC96442.toInt()
        val light = Color.luminance(ballColor) > 0.55f

        val ballSize = (size * 0.86f).roundToInt()
        val halo = (ballSize * 0.06f).roundToInt().coerceAtLeast(dp(1))

        // 外圈一道半透明光晕，在深/浅背景上都能看清；内圈是带细描边的实色圆
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((ballColor and 0x00FFFFFF) or 0x40000000)
        }
        val disc = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(ballColor, 0.08f), ballColor),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, dp(1)), if (light) 0x26141413 else 0x33FAF9F5)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }

        val bg = View(this).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(if (light) 0x26141413 else 0x40FAF9F5),
                LayerDrawable(arrayOf(ring, disc)).apply { setLayerInset(1, halo, halo, halo, halo) },
                mask,
            )
            isClickable = false
            isFocusable = false
        }
        val bgLp = FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER)

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_lu_touchpad)
            // 浅色球用暖黑图标，深色球用象牙白图标
            imageTintList = ColorStateList.valueOf(if (light) 0xFF141413.toInt() else 0xFFFAF9F5.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val iconPadding = (ballSize * 0.04f).roundToInt()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            isClickable = false
            isFocusable = false
        }
        val iconSize = (ballSize * 0.48f).roundToInt()
        val iconLp = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)

        val dot = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(bg, bgLp)
            addView(icon, iconLp)
            alpha = ballRestingAlpha()
            isClickable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        bg.isPressed = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        bg.isPressed = false
                    }
                }
                handleBallTouch(v, e)
            }
            contentDescription = getString(R.string.floating_ball_content_description)
        }
        val lp = ballLayoutParams(size)
        if (enteringWithTransition) dot.alpha = 0f
        runCatching { wm.addView(dot, lp) }
        panel = dot
        miniBallView = dot
        ballBgView = bg
        ballIconView = icon
        panelParams = lp
        ballX = lp.x
        ballY = lp.y
        ballAnchorX = lp.x
        ballAnchorY = lp.y
        keepBallInBounds()
    }


    private fun ballRestingAlpha(): Float =
        resolveBallRestingAlpha(prefs.floatingBallOpacityPercent)

    /** 把颜色往白色方向提亮一点，用于渐变顶部。 */
    private fun lighten(color: Int, amount: Float): Int {
        fun ch(c: Int) = (c + (255 - c) * amount).roundToInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    // ───────────────────────── 外观 ─────────────────────────

    /** 面板配色：Material 3 风格，纯色 + 细描边。 */
    private class Palette(
        val buttonBg: Int, val buttonStroke: Int, val buttonText: Int,
        val padBg: Int, val padStroke: Int,
        val ripple: Int,
    )

    private fun palette(dark: Boolean): Palette = if (dark) {
        // M3 暗色：surface-variant
        Palette(
            buttonBg = 0xFF3A3936.toInt(),
            buttonStroke = 0x33FAF9F5, buttonText = 0xFFFAF9F5.toInt(),
            padBg = 0xFF2A2A28.toInt(), padStroke = 0x26FAF9F5,
            ripple = 0x33FAF9F5,
        )
    } else {
        // M3 浅色：surface
        Palette(
            buttonBg = 0xFFF5F4ED.toInt(),
            buttonStroke = 0x40141413, buttonText = 0xFF141413.toInt(),
            padBg = 0xFFE8E6DC.toInt(), padStroke = 0x40141413,
            ripple = 0x26141413,
        )
    }

    private fun solidBackground(shape: Int, color: Int, stroke: Int, radiusPx: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            this.shape = shape
            if (shape == GradientDrawable.RECTANGLE) cornerRadius = radiusPx
            setColor(withAlpha(color, prefs.opacityPercent))
            setStroke(dp(1), scaleAlpha(stroke, prefs.opacityPercent))
        }

    /** 按钮背景，支持胶囊型与圆形（根据传入的圆角半径自适应），带按压水波纹反馈。 */
    private fun buttonBackground(color: Int, stroke: Int, ripple: Int, cornerRadiusPx: Float): Drawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(Color.WHITE)
        }
        val content = solidBackground(GradientDrawable.RECTANGLE, color, stroke, cornerRadiusPx)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    private fun makeHandle(iconRes: Int, dark: Boolean, onTouch: (View, MotionEvent) -> Boolean): View =
        ImageView(this).apply {
            val p = palette(dark)
            setImageResource(iconRes)
            setColorFilter(p.buttonText)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = buttonBackground(p.buttonBg, p.buttonStroke, p.ripple, dp(14).toFloat())
            isClickable = true
            isHapticFeedbackEnabled = false
            setOnTouchListener { v, e ->
                if (isPassThroughActive) return@setOnTouchListener false
                // 自己处理拖动，同时让水波纹跟随按下/抬起
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; haptic(v) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                onTouch(v, e)
            }
        }

    private fun makeActionButton(slot: Int, action: PadAction, dark: Boolean): View = ImageView(this).apply {
        val p = palette(dark)
        setImageResource(action.iconRes)
        setColorFilter(p.buttonText)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = buttonBackground(p.buttonBg, p.buttonStroke, p.ripple, dp(14).toFloat())
        isClickable = true
        isHapticFeedbackEnabled = false
        contentDescription = getString(action.labelRes)
        attachActionButtonBehavior(this, slot, action)
    }

    private fun attachActionButtonBehavior(
        button: View,
        slot: Int,
        action: PadAction,
    ) {
        val slop = dp(6).toFloat()
        var downX = 0f
        var downY = 0f
        var longPressFired = false
        var isDown = false

        val longPressRunnable = Runnable {
            if (!isDown) return@Runnable
            longPressFired = true
            isDown = false
            button.isPressed = false
            if (prefs.pressFeedback && !isPassThroughActive) {
                PressFeedback.applyButtonRelease(button)
            }
            haptic(button)
            showActionPicker(slot, action)
        }

        button.setOnTouchListener { v, event ->
            if (isPassThroughActive) return@setOnTouchListener false
            val w = v.width.toFloat().coerceAtLeast(1f)
            val h = v.height.toFloat().coerceAtLeast(1f)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDown = true
                    longPressFired = false
                    downX = event.x
                    downY = event.y
                    v.isPressed = true
                    v.drawableHotspotChanged(event.x, event.y)
                    if (prefs.pressFeedback) {
                        PressFeedback.applyButtonDown(
                            v, event,
                            maxTiltX = 8f, maxTiltY = 12f,
                            pressScale = 0.92f, sinkDp = 2f,
                        )
                    }
                    if (prefs.longPressButtonToCustomize) {
                        val holdMs = prefs.buttonCustomizeHoldMs.toLong()
                        v.postDelayed(longPressRunnable, holdMs)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDown) return@setOnTouchListener true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val inside = event.x in -slop..(w + slop) && event.y in -slop..(h + slop)
                    if (!inside || kotlin.math.hypot(dx, dy) > slop * 2f) {
                        v.removeCallbacks(longPressRunnable)
                        if (!inside) {
                            v.isPressed = false
                        }
                    } else {
                        v.isPressed = true
                        v.drawableHotspotChanged(event.x, event.y)
                    }
                    if (prefs.pressFeedback) {
                        PressFeedback.applyButtonMove(
                            v, event,
                            maxTiltX = 8f, maxTiltY = 12f,
                            pressScale = 0.92f, sinkDp = 2f,
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    val wasDown = isDown
                    isDown = false
                    v.isPressed = false
                    if (prefs.pressFeedback) {
                        PressFeedback.applyButtonRelease(v)
                    }
                    if (longPressFired) {
                        longPressFired = false
                        return@setOnTouchListener true
                    }
                    if (wasDown) {
                        val inside = event.x in 0f..w && event.y in 0f..h
                        if (inside) {
                            performAction(action)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    isDown = false
                    longPressFired = false
                    v.isPressed = false
                    if (prefs.pressFeedback) {
                        PressFeedback.applyButtonRelease(v)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun padBackground(dark: Boolean, radiusPx: Float): GradientDrawable {
        val p = palette(dark)
        return solidBackground(GradientDrawable.RECTANGLE, p.padBg, p.padStroke, radiusPx)
    }

    /** 按面板不透明度缩放一个已带 alpha 的颜色。 */
    private fun scaleAlpha(color: Int, percent: Int): Int {
        val a = (Color.alpha(color) * percent / 100).coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun withAlpha(color: Int, percent: Int): Int {
        val a = (255 * percent / 100).coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ───────────────────────── 按钮长按换动作 ─────────────────────────

    private fun showActionPicker(slot: Int, current: PadAction) {
        val dialog = ActionPicker.build(this, isDarkTheme(), current, actions = PadAction.TOUCHPAD_ACTIONS) { picked ->
            val list = replaceSlot(prefs.buttons, slot, picked, prefs.buttonCount)
            if (list == null) {
                toast(getString(R.string.need_move_key))
                return@build
            }
            prefs.buttons = list
            buildPanel()
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        runCatching { dialog.show() }
    }

    // ───────────────────────── 触控板手势 ─────────────────────────

    private var padCornerRadius = 0f
    /**
     * 这次触摸是从触控板边缘那一圈（含圆角外侧）开始的：整段忽略，防止按旁边按钮时误碰。
     * v0.5.3 及以前，触控板 View 的整个矩形（包括圆角外侧的四个小角）都响应，紧挨着按钮只隔一个按钮间距。
     */
    private var padTouchIgnored = false

    private fun handlePadTouch(e: MotionEvent) {
        if (isPassThroughActive) return
        val slop = dp(6).toFloat()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val pad = padArea
            padTouchIgnored = pad != null && !insideTouchArea(
                e.x, e.y, pad.width.toFloat(), pad.height.toFloat(), padCornerRadius,
                dp(prefs.padEdgeDeadZoneDp).toFloat(),
            )
        }
        if (padTouchIgnored) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) padTouchIgnored = false
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (prefs.pressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadDown(pad, e.x, e.y, resources.displayMetrics.density)
                    }
                }
                downRawX = e.rawX; downRawY = e.rawY
                lastRawX = e.rawX; lastRawY = e.rawY
                downTime = System.currentTimeMillis()
                moved = false; longPressFired = false; dwellFired = false
                dwellAnchorX = Float.NaN; dwellAnchorY = Float.NaN
                customSwipeArmed = false
                customSwipeStartX = cursorX
                customSwipeStartY = cursorY
                customSwipeEndX = cursorX
                customSwipeEndY = cursorY
                main.removeCallbacks(longPressRunnable)
                // 拖拽锁定期间触控板只移动光标，不点击、不长按、不停留点击
                if (!dragging) {
                    if (shouldScheduleLongPress(prefs.dwellMs, dragging = false)) {
                        main.postDelayed(longPressRunnable, prefs.longPressMs.toLong())
                    }
                    scheduleDwell()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (prefs.pressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadMove(pad, e.x, e.y, resources.displayMetrics.density)
                    }
                }
                val dx = e.rawX - lastRawX
                val dy = e.rawY - lastRawY
                lastRawX = e.rawX; lastRawY = e.rawY
                if (abs(e.rawX - downRawX) > slop || abs(e.rawY - downRawY) > slop) {
                    if (!moved) {
                        moved = true
                        main.removeCallbacks(longPressRunnable)
                    }
                }
                cursorX = (cursorX + dx * prefs.sensitivity).coerceIn(0f, screenW.toFloat())
                cursorY = (cursorY + dy * prefs.sensitivity).coerceIn(0f, screenH.toFloat())
                updateCursor()
                if (customSwipeArmed) {
                    customSwipeEndX = cursorX
                    customSwipeEndY = cursorY
                    if (prefs.touchRingEnabled) {
                        moveTouchRing(cursorX, cursorY)
                    }
                }
                if (dragging) {
                    appendTrailPoint(dragTrail, cursorX, cursorY, dp(4).toFloat())
                    if (prefs.touchRingEnabled) {
                        moveTouchRing(cursorX, cursorY)
                    }
                }
                // 长按已触发（正在做自定义滑动）时不再停留点击：中途停一下不能把滑动变成一次点击。
                // 停留点击触发过之后，光标要先离开触发点才重新计时，手指搁着微微抖动不会原地连点。
                if (!longPressFired && shouldRearmDwell(dwellAnchorX, dwellAnchorY, cursorX, cursorY, dp(8).toFloat())) {
                    dwellAnchorX = Float.NaN
                    dwellAnchorY = Float.NaN
                    scheduleDwell()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (prefs.pressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadRelease(pad)
                    }
                }
                main.removeCallbacks(longPressRunnable)
                cancelDwell()
                val distance = kotlin.math.hypot(customSwipeEndX - customSwipeStartX, customSwipeEndY - customSwipeStartY)
                if (dragging) {
                    // 拖拽锁定期间圆环继续跟着光标，等第二次按拖拽锁定再收缩
                    customSwipeArmed = false
                    return
                }
                val outcome = resolvePadTouchOutcome(longPressFired, dwellFired, moved, distance, dp(12).toFloat())
                // 松手即收缩：触控板长按松手时圆环立即收缩，不再在松手后强行多停留
                if (customSwipeArmed) touchRing?.release()
                when (outcome) {
                    PadTouchOutcome.CUSTOM_SWIPE -> customSwipeAt(customSwipeStartX, customSwipeStartY, customSwipeEndX, customSwipeEndY)
                    PadTouchOutcome.LONG_PRESS -> longPressAt(cursorX, cursorY, showFeedbackRing = false)
                    PadTouchOutcome.CLICK -> tapOrDouble()
                    PadTouchOutcome.DWELL_CLICK, PadTouchOutcome.MOVE_ONLY -> Unit
                }
                customSwipeArmed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (prefs.pressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadRelease(pad)
                    }
                }
                main.removeCallbacks(longPressRunnable)
                cancelDwell()
                if (customSwipeArmed) touchRing?.release()
                customSwipeArmed = false
            }
        }
    }

    /** 停留点击：手指在触控板上停住不动，自动点一下光标位置。 */
    private fun scheduleDwell() {
        val ms = prefs.dwellMs
        if (ms <= 0 || dragging) return
        cancelDwell()
        val r = Runnable {
            dwellFired = true
            dwellAnchorX = cursorX
            dwellAnchorY = cursorY
            main.removeCallbacks(longPressRunnable)
            tapAt(cursorX, cursorY)
            haptic()
        }
        dwellRunnable = r
        main.postDelayed(r, ms.toLong())
    }

    private fun cancelDwell() {
        dwellRunnable?.let { main.removeCallbacks(it) }
        dwellRunnable = null
    }

    private fun tapOrDouble() {
        // The first tap has already been dispatched on release. Dispatching two
        // more gestures for the second release would turn a double tap into
        // three taps.
        tapAt(cursorX, cursorY)
        haptic()
    }

    private fun handleMoveTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movingPanel = true
                movingFromX = e.rawX; movingFromY = e.rawY
                movingPanelX = panelParams?.x ?: 0
                movingPanelY = panelParams?.y ?: 0
            }
            MotionEvent.ACTION_MOVE -> if (movingPanel) {
                val lp = panelParams ?: return true
                val position = clampPanelPosition(
                    movingPanelX + (e.rawX - movingFromX).roundToInt(),
                    movingPanelY + (e.rawY - movingFromY).roundToInt(),
                    panel?.width ?: 0,
                    panel?.height ?: 0,
                    screenW,
                    screenH,
                )
                lp.x = position.x
                lp.y = position.y
                panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                prefs.padX = lp.x
                prefs.padY = lp.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> movingPanel = false
        }
        return true
    }

    private fun handleResizeTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizingPanel = true
                resizingFromX = e.rawX
                resizingFromY = e.rawY
                resizingPanelWidth = panel?.width ?: dp(prefs.panelWidthDp.coerceAtLeast(PANEL_MIN_WIDTH_DP))
                resizingPanelHeight = prefs.extraHeightDp
            }
            MotionEvent.ACTION_MOVE -> if (resizingPanel) {
                val width = resizePanelWidth(
                    resizingPanelWidth,
                    (e.rawX - resizingFromX).roundToInt(),
                    controlWidthRange(screenW, resources.displayMetrics.density).first,
                    controlWidthRange(screenW, resources.displayMetrics.density).last,
                )
                // 横向拖动改宽度（按钮跟着缩放），纵向拖动只改触控板的额外高度
                val extraDp = (resizingPanelHeight +
                    ((e.rawY - resizingFromY) / resources.displayMetrics.density).roundToInt())
                    .coerceIn(0, CONTROL_EXTRA_HEIGHT_MAX_DP)
                val height = panelHeightFor(width, extraDp)
                val widthDp = pixelsToDp(width, resources.displayMetrics.density)
                prefs.panelWidthDp = widthDp
                prefs.padWidthPercent = (width * 100 / screenW).coerceIn(20, 100)
                prefs.extraHeightDp = extraDp
                panel?.let { panelView ->
                    val root = panelView as? FrameLayout ?: return@let
                    val window = panelParams ?: return@let
                    window.width = width
                    window.height = height
                    root.layoutParams = window
                    layoutControlChildren(root, width, height)
                    runCatching { wm.updateViewLayout(root, window) }
                    root.requestLayout()
                }
                onPanelResized?.invoke(widthDp, extraDp)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizingPanel = false
                panel?.post { keepPanelInBounds() }
                onPanelResized?.invoke(prefs.panelWidthDp, prefs.extraHeightDp)
            }
        }
        return true
    }

    private fun snapBallBackToAnchor(onComplete: (() -> Unit)? = null) {
        ballSnapAnimator?.cancel()
        val lp = panelParams ?: return
        val startX = lp.x
        val startY = lp.y
        val targetX = ballAnchorX
        val targetY = ballAnchorY

        resetBallDeformation(animated = true, duration = 220L)

        if (startX == targetX && startY == targetY) {
            onComplete?.invoke()
            return
        }

        val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = OvershootInterpolator(1.25f)
            addUpdateListener { va ->
                val frac = (va.animatedValue as? Float) ?: va.animatedFraction
                val p = panelParams ?: return@addUpdateListener
                val curX = (startX + (targetX - startX) * frac).roundToInt()
                val curY = (startY + (targetY - startY) * frac).roundToInt()
                val clamped = clampFloatingBallPosition(curX, curY, size, screenW, screenH)
                p.x = clamped.x
                p.y = clamped.y
                panel?.let { runCatching { wm.updateViewLayout(it, p) } }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ballSnapAnimator = null
                    val p = panelParams
                    if (p != null) {
                        p.x = targetX
                        p.y = targetY
                        panel?.let { runCatching { wm.updateViewLayout(it, p) } }
                    }
                    onComplete?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    ballSnapAnimator = null
                }
            })
        }
        ballSnapAnimator = anim
        anim.start()
    }

    /**
     * 关闭悬浮球手势时的极简模式：
     * - 点击悬浮球直接展开触控板（0ms 延迟，无需等待双击超时）；
     * - 拖动悬浮球直接实时移动位置（无需长按触发，无阻尼手势判定与误触发）。
     */
    private fun handleSimpleBallTouch(v: View, e: MotionEvent): Boolean {
        val slop = dp(6).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ballSnapAnimator?.cancel()
                ballSnapAnimator = null
                resetBallDeformation(animated = false)
                ballMoved = false
                ballFromX = e.rawX
                ballFromY = e.rawY
                ballDragStartX = panelParams?.x ?: prefs.ballX.coerceAtLeast(0)
                ballDragStartY = panelParams?.y ?: prefs.ballY.coerceAtLeast(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                if (!ballMoved && kotlin.math.hypot(dx, dy) >= slop) {
                    ballMoved = true
                }
                if (ballMoved) {
                    val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                    val position = clampFloatingBallPosition(
                        ballDragStartX + dx.roundToInt(),
                        ballDragStartY + dy.roundToInt(),
                        size,
                        screenW,
                        screenH,
                    )
                    panelParams?.let { lp ->
                        lp.x = position.x
                        lp.y = position.y
                        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                        prefs.ballX = position.x
                        prefs.ballY = position.y
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!ballMoved) {
                    haptic()
                    toggleMinimize()
                } else {
                    keepBallInBounds()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                keepBallInBounds()
            }
        }
        return true
    }

    private fun handleBallTouch(v: View, e: MotionEvent): Boolean {
        if (isPassThroughActive) return false
        if (!prefs.ballGesturesEnabled) {
            return handleSimpleBallTouch(v, e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ballSnapAnimator?.cancel()
                ballSnapAnimator = null
                resetBallDeformation(animated = false)
                if (ballAnchorX == 0 && ballAnchorY == 0 && (panelParams?.x ?: 0) != 0) {
                    ballAnchorX = panelParams?.x ?: prefs.ballX.coerceAtLeast(0)
                    ballAnchorY = panelParams?.y ?: prefs.ballY.coerceAtLeast(0)
                }
                panelParams?.let { lp ->
                    if (lp.x != ballAnchorX || lp.y != ballAnchorY) {
                        lp.x = ballAnchorX
                        lp.y = ballAnchorY
                        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    }
                }

                val ballSize = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                val swipeThreshold = maxOf(dp(16).toFloat(), ballSize * 0.4f)
                val cancelThreshold = resolveBallCancelThreshold(swipeThreshold, dp(8).toFloat())
                ballSwipeTracker = BallSwipeTracker(swipeThreshold, cancelThreshold)
                ballSwipeTracker.reset()

                isDraggingBall = false
                ballLongPressTriggered = false
                ballMoved = false
                ballFromX = e.rawX
                ballFromY = e.rawY
                lastDragRawX = e.rawX
                lastDragRawY = e.rawY
                ballDragLastTimeMs = SystemClock.uptimeMillis()
                ballDragVelocityX = 0f
                ballDragVelocityY = 0f
                ballDragCurrentRotation = 0f
                ballDragStartX = ballAnchorX
                ballDragStartY = ballAnchorY
                ballX = ballAnchorX
                ballY = ballAnchorY

                // 若之前在等待双击的单击超时，在第二次按下时立即移除单击任务，避免双击误触发单次操作
                if (isWaitingForBallDoubleTap) {
                    main.removeCallbacks(ballSingleTapRunnable)
                }
                main.removeCallbacks(ballLongPressRunnable)
                val holdDuration = resolveBallLongPressHoldMs(prefs.longPressMs)
                main.postDelayed(ballLongPressRunnable, holdDuration)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                val dist = kotlin.math.hypot(dx, dy)
                val slop = dp(6).toFloat()

                if (shouldCancelBallLongPress(dist, slop) && !ballMoved) {
                    ballMoved = true
                    main.removeCallbacks(ballLongPressRunnable)
                }

                if (isDraggingBall) {
                    val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                    val position = clampFloatingBallPosition(
                        ballDragStartX + dx.roundToInt(), ballDragStartY + dy.roundToInt(), size, screenW, screenH,
                    )
                    panelParams?.let { lp ->
                        lp.x = position.x
                        lp.y = position.y
                        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                        prefs.ballX = position.x
                        prefs.ballY = position.y
                    }

                    // 长按移动动效：速度感应果冻形变、跟手倾斜与内核图标视差动效
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - ballDragLastTimeMs).coerceIn(8L, 100L) / 1000f
                    val stepDx = e.rawX - lastDragRawX
                    val stepDy = e.rawY - lastDragRawY
                    lastDragRawX = e.rawX
                    lastDragRawY = e.rawY
                    ballDragLastTimeMs = now

                    val rawVx = stepDx / dt
                    val rawVy = stepDy / dt
                    ballDragVelocityX = ballDragVelocityX * 0.60f + rawVx * 0.40f
                    ballDragVelocityY = ballDragVelocityY * 0.60f + rawVy * 0.40f

                    val speed = kotlin.math.hypot(ballDragVelocityX, ballDragVelocityY)
                    if (speed > dp(30)) {
                        val dirX = ballDragVelocityX / speed
                        val dirY = ballDragVelocityY / speed
                        val motionState = computeDragMotionState(
                            speedPxPerSec = speed,
                            dirX = dirX,
                            dirY = dirY,
                            baseScale = 1.16f,
                            refSpeedPxPerSec = dp(500).toFloat(),
                            maxExtraStretch = 0.22f,
                            maxSquash = 0.18f,
                            maxParallaxPx = dp(5).toFloat(),
                        )
                        ballDragCurrentRotation = lerpAngleDeg(ballDragCurrentRotation, motionState.rotationDeg, 0.45f)
                        ballBgView?.rotation = ballDragCurrentRotation
                        ballBgView?.scaleX = motionState.stretch
                        ballBgView?.scaleY = motionState.squash
                        ballIconView?.translationX = motionState.iconTranslationX
                        ballIconView?.translationY = motionState.iconTranslationY
                    } else {
                        // 速度较低（悬停或微动）时，平滑回弹至饱满圆形
                        ballBgView?.animate()?.cancel()
                        ballBgView?.animate()?.scaleX(1.16f)?.scaleY(1.16f)?.setDuration(120)?.start()
                        ballIconView?.animate()?.cancel()
                        ballIconView?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(120)?.start()
                    }
                } else {
                    if (ballSwipeTracker.onMove(dist)) {
                        ballHapticCancel()
                    }
                    if (ballMoved) {
                        // 滑动手势滑动动效：以阻尼弹性位移跟手，直观展现滑动方向与距离感
                        val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                        val maxOffset = size * 0.45f
                        val offset = computeDampedSwipeOffset(dx, dy, maxOffset)
                        val targetX = (ballAnchorX + offset.x).roundToInt()
                        val targetY = (ballAnchorY + offset.y).roundToInt()
                        val clamped = clampFloatingBallPosition(targetX, targetY, size, screenW, screenH)
                        panelParams?.let { lp ->
                            lp.x = clamped.x
                            lp.y = clamped.y
                            panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                        }
                        // 悬浮球手势拖拽形变：沿滑动方向弹性拉伸、垂直方向保体积挤压，内核图标视差跟手
                        applyBallDeformation(dx, dy, maxOffset)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(ballLongPressRunnable)
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                val dist = kotlin.math.hypot(dx, dy)
                val swipeThreshold = ballSwipeTracker.swipeThresholdPx

                if (isDraggingBall) {
                    isDraggingBall = false
                    ballBgView?.animate()?.cancel()
                    ballIconView?.animate()?.cancel()
                    ballBgView?.animate()
                        ?.rotation(0f)
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.setDuration(220)
                        ?.setInterpolator(OvershootInterpolator(1.35f))
                        ?.start()
                    ballIconView?.animate()
                        ?.translationX(0f)
                        ?.translationY(0f)
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.setDuration(200)
                        ?.start()
                    keepBallInBounds()
                } else if (ballLongPressTriggered) {
                    ballLongPressTriggered = false
                    snapBallBackToAnchor()
                } else if (ballSwipeTracker.isCancelledByReturn) {
                    // 用户拖回中心打断手势：不触发滑动动作，也不触发点击，平滑复位
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                    snapBallBackToAnchor()
                } else if (ballSwipeTracker.hasArmedSwipe || dist >= swipeThreshold) {
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false

                    val dir = resolveSwipeDirection(dx, dy)
                    val act = resolveBallSwipeAction(
                        direction = dir,
                        upAction = prefs.ballActionSwipeUp,
                        downAction = prefs.ballActionSwipeDown,
                        leftAction = prefs.ballActionSwipeLeft,
                        rightAction = prefs.ballActionSwipeRight,
                    )
                    snapBallBackToAnchor()
                    if (act != PadAction.NONE) {
                        haptic()
                        performAction(act, withHaptic = false)
                    }
                } else if (!ballMoved) {
                    snapBallBackToAnchor()
                    val doubleAction = prefs.ballActionDoubleTap
                    if (doubleAction == PadAction.NONE) {
                        // 未开启双击功能：单击完全零延迟，立即震动并触发动作
                        val act = prefs.ballActionSingleTap
                        if (act != PadAction.NONE) {
                            haptic()
                            performAction(act, withHaptic = false)
                        }
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val doubleTapTimeout = 190L
                        if (isWaitingForBallDoubleTap && (now - lastBallTapTime < 350L)) {
                            // 第二次点击抬起：立即震动并触发双击动作
                            main.removeCallbacks(ballSingleTapRunnable)
                            isWaitingForBallDoubleTap = false
                            haptic()
                            performAction(doubleAction, withHaptic = false)
                        } else {
                            // 第一次点击抬起：立即震动反馈（0ms 消除感知延迟），启动紧凑双击等待
                            haptic()
                            lastBallTapTime = now
                            isWaitingForBallDoubleTap = true
                            main.removeCallbacks(ballSingleTapRunnable)
                            main.postDelayed(ballSingleTapRunnable, doubleTapTimeout)
                        }
                    }
                } else {
                    // 微小拖动但未达到滑动阈值，不派发点击，平滑复位
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                    snapBallBackToAnchor()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(ballLongPressRunnable)
                main.removeCallbacks(ballSingleTapRunnable)
                isWaitingForBallDoubleTap = false
                ballSwipeTracker.reset()
                resetBallDeformation(animated = true, duration = 180L)
                if (isDraggingBall) {
                    isDraggingBall = false
                    ballBgView?.animate()?.cancel()
                    ballIconView?.animate()?.cancel()
                    ballBgView?.animate()?.rotation(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                    ballIconView?.animate()?.translationX(0f)?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                    keepBallInBounds()
                } else {
                    snapBallBackToAnchor()
                }
                ballLongPressTriggered = false
                ballMoved = false
            }
        }
        return true
    }

    private fun keepPanelInBounds() {
        val lp = panelParams ?: return
        val position = clampPanelPosition(
            lp.x,
            lp.y,
            panel?.width ?: 0,
            panel?.height ?: 0,
            screenW,
            screenH,
        )
        if (position.x == lp.x && position.y == lp.y) return
        lp.x = position.x
        lp.y = position.y
        prefs.padX = position.x
        prefs.padY = position.y
        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun keepBallInBounds() {
        val lp = panelParams ?: return
        if (miniBallView == null) return
        val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
        val position = clampFloatingBallPosition(lp.x, lp.y, size, screenW, screenH)
        lp.x = position.x
        lp.y = position.y
        prefs.ballX = position.x
        prefs.ballY = position.y
        ballAnchorX = position.x
        ballAnchorY = position.y
        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    // ───────────────────────── 手势注入与队列 ─────────────────────────

    private data class QueuedGesture(
        val gesture: GestureDescription,
        val needsPassThrough: Boolean,
        val expectedMs: Long = 500L,
        val keepPassThroughAfterComplete: Boolean = false,
        val onComplete: (() -> Unit)? = null,
    )

    private var isDispatching = false
    private val gestureQueue = ArrayDeque<QueuedGesture>()

    private val restoreTouchRunnable = Runnable {
        isPassThroughActive = false
        setOverlayTouchable(true)
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        // 先同步拦截标记：即使此刻没有面板窗口（收起且隐藏悬浮球 / 横屏隐藏），恢复时也要清掉，
        // 否则之后重建出来的面板会一直处于「穿透中」而忽略所有触摸。
        isPassThroughActive = !touchable
        val v = panel ?: return
        val lp = panelParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) lp.flags and flag.inv() else lp.flags or flag
        if (next == lp.flags) return
        lp.flags = next
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun isPointInsidePanel(x: Float, y: Float): Boolean {
        val lp = panelParams ?: return false
        val p = panel ?: return false
        val w = if (p.width > 0) p.width else lp.width
        val h = if (p.height > 0) p.height else lp.height
        return isPointInsideRect(x, y, lp.x, lp.y, w, h)
    }

    /**
     * 顺序派发注入手势：
     * 1. 仅当光标位于触控板面板范围内时才短暂切换 FLAG_NOT_TOUCHABLE，让光标能精准点击触控板下层内容；
     * 2. 穿透期间锁定 isPassThroughActive 状态，触控板按钮、把手与触控板自身全面拦截任何点击与触摸反馈，彻底杜绝误触；
     * 3. 光标在面板外部（绝大多数常规操作场景）时，面板始终保持可触摸状态，快速连续点击绝不会击穿面板触发底层内容；
     * 4. 使用手势队列串行化派发，避免快速连续点击因前一笔手势未结束而导致后续点击被系统拒绝或丢失。
     */
    private fun dispatch(
        gesture: GestureDescription,
        needsPassThrough: Boolean = false,
        expectedMs: Long = 500L,
        keepPassThroughAfterComplete: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        gestureQueue.addLast(QueuedGesture(gesture, needsPassThrough, expectedMs, keepPassThroughAfterComplete, onComplete))
        drainGestureQueue()
    }

    private fun drainGestureQueue() {
        if (isDispatching) return
        val item = gestureQueue.removeFirstOrNull() ?: return
        isDispatching = true

        if (item.needsPassThrough && panel != null && panelParams != null) {
            // 光标位于触控板面板内，需要穿透面板点击下层内容
            setOverlayTouchable(false)
            main.removeCallbacks(restoreTouchRunnable)
            // 兜底保护：手势预期耗时 + 1.5 秒后无论如何恢复面板可触摸，防止异常时卡在不可触摸状态
            val fallbackTimeout = maxOf(3000L, item.expectedMs + 1500L)
            main.postDelayed(restoreTouchRunnable, fallbackTimeout)

            main.postDelayed({
                val ok = runCatching {
                    dispatchGesture(item.gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            main.removeCallbacks(restoreTouchRunnable)
                            if (!item.keepPassThroughAfterComplete) {
                                // 延迟 20ms 恢复可触摸，确保注入手势的 UP 事件在底层应用中完全处理完毕，
                                // 避免面板瞬间切回可触摸而误抓到残余事件或按钮触发
                                main.postDelayed({
                                    setOverlayTouchable(true)
                                }, 20L)
                            }
                            isDispatching = false
                            runCatching { item.onComplete?.invoke() }
                            drainGestureQueue()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            main.removeCallbacks(restoreTouchRunnable)
                            setOverlayTouchable(true)
                            isDispatching = false
                            drainGestureQueue()
                        }
                    }, main)
                }.getOrDefault(false)
                if (!ok) {
                    main.removeCallbacks(restoreTouchRunnable)
                    setOverlayTouchable(true)
                    isDispatching = false
                    drainGestureQueue()
                }
            }, OVERLAY_PASS_THROUGH_DELAY_MS)
        } else {
            // 光标在触控板外部，直接派发手势。面板始终保持可触摸，绝不设置 FLAG_NOT_TOUCHABLE，
            // 彻底杜绝快速连续点击时物理手指穿透面板触发底层内容。
            val ok = runCatching {
                dispatchGesture(item.gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        isDispatching = false
                        runCatching { item.onComplete?.invoke() }
                        drainGestureQueue()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        isDispatching = false
                        drainGestureQueue()
                    }
                }, main)
            }.getOrDefault(false)
            if (!ok) {
                isDispatching = false
                drainGestureQueue()
            }
        }
    }

    private fun tapAt(x: Float, y: Float, ms: Long = 50, showFeedbackRing: Boolean = true) {
        if (showFeedbackRing) showTouchRing(x, y, ms)
        val overPanel = isPointInsidePanel(x, y)
        val path = Path().apply { moveTo(x, y) }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            needsPassThrough = overPanel,
            expectedMs = ms,
        )
    }

    /** 光标处长按：按住时长可在设置里调（300–3000 ms）。 */
    private fun longPressAt(x: Float, y: Float, showFeedbackRing: Boolean = true) =
        tapAt(x, y, prefs.cursorHoldMs.toLong(), showFeedbackRing)

    private fun customSwipeAt(startX: Float, startY: Float, endX: Float, endY: Float, ms: Long = 320) {
        val swipe = customSwipeFrom(startX, startY, endX, endY, dp(12).toFloat()) ?: return
        val overPanel = isPointInsidePanel(swipe.startX, swipe.startY) || isPointInsidePanel(swipe.endX, swipe.endY)
        val path = Path().apply {
            moveTo(swipe.startX, swipe.startY)
            lineTo(swipe.endX, swipe.endY)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            needsPassThrough = overPanel,
            expectedMs = ms,
        )
        haptic()
    }

    private fun swipeBy(dx: Float, dy: Float, ms: Long) {
        val x0 = cursorX
        val y0 = cursorY
        val x1 = (x0 + dx).coerceIn(0f, screenW.toFloat())
        val y1 = (y0 + dy).coerceIn(0f, screenH.toFloat())
        val overPanel = isPointInsidePanel(x0, y0) || isPointInsidePanel(x1, y1)
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            needsPassThrough = overPanel,
            expectedMs = ms,
        )
    }

    private fun startDrag() {
        dragging = true
        dragTrail.clear()
        dragTrail += TrailPoint(cursorX, cursorY)
        // 起点显示长按圆环，之后跟着光标走（handlePadTouch 的 ACTION_MOVE）
        if (prefs.touchRingEnabled) {
            moveTouchRing(cursorX, cursorY)
            touchRing?.pressUntilRelease()
        }
        refreshDragButtons()
    }

    /** 结束拖拽状态（不注入任何手势），圆环在当前位置收缩。 */
    private fun cancelDrag() {
        dragging = false
        dragTrail.clear()
        touchRing?.release()
        refreshDragButtons()
    }

    /**
     * 第二次按下拖拽锁定：在起点按住 [Prefs.cursorHoldMs]，再沿记录的轨迹拖到当前光标，松开。
     * 两段用 continueStroke 连成同一根手指，目标应用看到的是「长按后拖动」。
     * 跟随光标的圆环在终点收缩。
     */
    private fun endDrag() {
        appendTrailPoint(dragTrail, cursorX, cursorY, 0.5f)
        val trail = dragTrail.toList()
        if (prefs.touchRingEnabled) {
            touchRing?.let { moveTouchRing(cursorX, cursorY) }
        }
        cancelDrag()
        val start = trail.firstOrNull() ?: return
        // 没移动就再按一次 = 取消
        if (trailLength(trail) < dp(8)) return
        val overPanel = isPointInsidePanel(start.x, start.y) || isPointInsidePanel(cursorX, cursorY)
        val hold = prefs.cursorHoldMs.toLong()
        val moveMs = dragMoveDurationMs(trailLength(trail))
        val holdPath = Path().apply { moveTo(start.x, start.y) }
        val holdStroke = GestureDescription.StrokeDescription(holdPath, 0, hold, true)
        val movePath = Path().apply {
            moveTo(start.x, start.y)
            trail.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val moveStroke = runCatching { holdStroke.continueStroke(movePath, 0, moveMs, false) }.getOrNull()
        if (moveStroke == null) {
            // 极少数机型不支持续接：退化为一笔「慢速拖动」
            dispatch(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(movePath, 0, hold + moveMs)).build(),
                needsPassThrough = overPanel,
                expectedMs = hold + moveMs,
            )
            return
        }
        dispatch(
            GestureDescription.Builder().addStroke(holdStroke).build(),
            needsPassThrough = overPanel,
            expectedMs = hold,
            keepPassThroughAfterComplete = true,
        ) {
            dispatch(
                GestureDescription.Builder().addStroke(moveStroke).build(),
                needsPassThrough = overPanel,
                expectedMs = moveMs,
            )
        }
    }

    // ───────────────────────── 点击反馈 ─────────────────────────

    private var touchRing: TouchRingView? = null
    private var touchRingParams: WindowManager.LayoutParams? = null

    private fun resolveTouchRingColor(): Int =
        resolveTouchRingColor(prefs.touchRingColor, isDarkTheme())

    /** 常驻的反馈圆环窗口（透明、不可触摸），只移动位置，不反复 add/remove。 */
    private fun ensureTouchRing(): Pair<TouchRingView, WindowManager.LayoutParams>? {
        touchRing?.let { v -> touchRingParams?.let { return v to it } }
        val radius = dp(TOUCH_RING_RADIUS_DP).toFloat()
        val stroke = (2.5f * resources.displayMetrics.density)
        val size = ((radius + stroke) * 2).roundToInt() + dp(4)
        val v = TouchRingView(this).apply {
            accent = resolveTouchRingColor()
            maxRadius = radius
            strokeWidthPx = stroke
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            x = -size; y = -size
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) return null
        touchRing = v
        touchRingParams = lp
        return v to lp
    }

    private fun moveTouchRing(x: Float, y: Float) {
        val (v, lp) = ensureTouchRing() ?: return
        lp.x = (x - lp.width / 2f).roundToInt()
        lp.y = (y - lp.height / 2f).roundToInt()
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /**
     * 光标处点击 / 长按的视觉反馈：圆环从内向外展开，结束时从外向内收缩消失。
     * [holdMs] < 250 视为点击，否则圆环保持展开直到按住结束。
     */
    private fun showTouchRing(x: Float, y: Float, holdMs: Long) {
        if (!prefs.touchRingEnabled) return
        // 拖拽锁定进行中，圆环正跟着光标标记拖拽；别的点击不抢走它
        if (dragging) return
        moveTouchRing(x, y)
        val v = touchRing ?: return
        if (holdMs >= 250) v.press(holdMs) else v.tap()
    }

    /**
     * 面板重建后把圆环窗口重新放到最上层（只在重建时做，平时不动它）。
     * 拖拽锁定进行中时，圆环直接恢复成展开状态并留在光标处。
     */
    private fun raiseTouchRing() {
        val v = touchRing
        val lp = touchRingParams
        if (v != null && lp != null) {
            v.clear()
            runCatching { wm.removeViewImmediate(v) }
            runCatching { wm.addView(v, lp) }
        }
        if (dragging && prefs.touchRingEnabled) {
            moveTouchRing(cursorX, cursorY)
            touchRing?.holdExpanded()
        }
    }

    private fun removeTouchRing() {
        touchRing?.let { it.clear(); runCatching { wm.removeView(it) } }
        touchRing = null
        touchRingParams = null
    }

    /** 拖拽进行中，面板上的「拖拽锁定」按钮改成陶土色，提示再按一次结束。 */
    private fun refreshDragButtons() {
        val dark = isDarkTheme()
        val p = palette(dark)
        val accentBg = if (dark) 0xFF5A3A2E.toInt() else 0xFFF6E3DA.toInt()
        val accentStroke = if (dark) 0x66D97757 else 0x66C96442
        val accentText = if (dark) 0xFFF0B9A3.toInt() else 0xFFC96442.toInt()
        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            if (prefs.buttons.getOrNull(slot) != PadAction.DRAG_LOCK) return@forEachIndexed
            val iv = view as? ImageView ?: return@forEachIndexed
            val radius = minOf(view.width, view.height).takeIf { it > 0 }?.let { it / 2f } ?: dp(14).toFloat()
            if (dragging) {
                iv.background = buttonBackground(accentBg, accentStroke, p.ripple, radius)
                iv.setColorFilter(accentText)
            } else {
                iv.background = buttonBackground(p.buttonBg, p.buttonStroke, p.ripple, radius)
                iv.setColorFilter(p.buttonText)
            }
        }
    }

    // ───────────────────────── 动作派发 ─────────────────────────

    fun performAction(action: PadAction, withHaptic: Boolean = true) {
        when (action) {
            PadAction.CLICK -> tapAt(cursorX, cursorY)
            PadAction.LONG_PRESS -> longPressAt(cursorX, cursorY)
            PadAction.DRAG_LOCK -> if (dragging) endDrag() else startDrag()

            PadAction.SCROLL_UP -> swipeBy(0f, -dp(prefs.scrollDistanceDp).toFloat(), 150)
            PadAction.SCROLL_DOWN -> swipeBy(0f, dp(prefs.scrollDistanceDp).toFloat(), 150)
            PadAction.SCROLL_LEFT -> swipeBy(-dp(prefs.scrollDistanceDp).toFloat(), 0f, 150)
            PadAction.SCROLL_RIGHT -> swipeBy(dp(prefs.scrollDistanceDp).toFloat(), 0f, 150)

            PadAction.SWIPE_UP -> swipeBy(0f, -dp(prefs.swipeDistanceDp).toFloat(), 320)
            PadAction.SWIPE_DOWN -> swipeBy(0f, dp(prefs.swipeDistanceDp).toFloat(), 320)
            PadAction.SWIPE_LEFT -> swipeBy(-dp(prefs.swipeDistanceDp).toFloat(), 0f, 320)
            PadAction.SWIPE_RIGHT -> swipeBy(dp(prefs.swipeDistanceDp).toFloat(), 0f, 320)

            PadAction.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            PadAction.POWER -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            PadAction.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            PadAction.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            PadAction.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            PadAction.SCREENSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else {
                    toast(getString(R.string.screenshot_needs_p))
                }
            }
            PadAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            PadAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            PadAction.KEYBOARD -> focusEditable()
            PadAction.SETTINGS -> openSettings()
            PadAction.MINIMIZE -> toggleMinimize()
            PadAction.MOVE_PANEL, PadAction.RESIZE_PANEL, PadAction.MOVE_BALL -> Unit
            PadAction.NONE -> Unit
        }
        if (withHaptic) {
            haptic()
        }
    }

    private fun global(action: Int) {
        runCatching { performGlobalAction(action) }
    }

    private fun adjustVolume(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
    }

    /** 让输入法弹出来：把焦点交给当前界面里的输入框。 */
    private fun focusEditable() {
        val root = rootInActiveWindow
        if (root == null) {
            toast(getString(R.string.no_input_field))
            return
        }
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditable(root)
        if (target == null) {
            toast(getString(R.string.no_input_field))
            return
        }
        runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = runCatching { findEditable(node.getChild(i)) }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun isImeVisible(): Boolean = runCatching {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    // ───────────────────────── 小工具 ─────────────────────────

    private fun updateCursor() {
        val p = cursorParams ?: return
        val origin = cursorViewOrigin(cursorX, cursorY)
        p.x = origin.x
        p.y = origin.y
        cursorView?.let { runCatching { wm.updateViewLayout(it, p) } }
    }

    private fun haptic(view: View? = null) {
        if (!prefs.haptics) return
        runCatching {
            (view ?: miniBallView ?: padArea ?: panel)?.performHapticFeedback(
                android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** 供设置界面查询：无障碍服务当前是否由系统连接着。 */
    fun isConnected(): Boolean = instance != null

    /** 打开系统无障碍设置页（给 MainActivity 用）。 */
    fun openAccessibilitySettingsFromService() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
