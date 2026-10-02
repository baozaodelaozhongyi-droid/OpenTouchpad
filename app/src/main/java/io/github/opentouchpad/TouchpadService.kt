package io.github.opentouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
        private const val OVERLAY_PASS_THROUGH_DELAY_MS = 40L

        @Volatile
        var instance: TouchpadService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var padArea: View? = null
    private var miniBallView: View? = null
    private val actionViews = mutableListOf<View>()
    private val actionSlots = mutableListOf<Int>()
    private var moveGripView: View? = null
    private var resizeGripView: View? = null

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
    private var dragMarker: View? = null

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

    // 收起后的悬浮球拖动，抬手未移动才展开
    private var movingBall = false
    private var ballMoved = false
    private var ballFromX = 0f
    private var ballFromY = 0f
    private var ballX = 0
    private var ballY = 0

    // 键盘弹出时自动最小化（记录是不是"自动"最小化的，好自动还原）
    private var minimizedByKeyboard = false

    private val longPressRunnable = Runnable {
        if (!moved && !dwellFired) {
            longPressFired = true
            customSwipeArmed = true
            customSwipeStartX = cursorX
            customSwipeStartY = cursorY
            customSwipeEndX = cursorX
            customSwipeEndY = cursorY
            cancelDwell()
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
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        cancelDwell()
        instance = null
        cancelDrag()
        cancelTransitions()
        removePanel()
        cursorView?.let { runCatching { wm.removeView(it) } }
        cursorView = null
        cursorParams = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        main.removeCallbacksAndMessages(null)
        cancelDrag()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (!prefs.minimizeOnKeyboard) return
        val ime = isImeVisible()
        if (ime && !prefs.minimized) {
            minimizedByKeyboard = true
            prefs.minimized = true
            buildPanel()
        } else if (!ime && minimizedByKeyboard) {
            minimizedByKeyboard = false
            prefs.minimized = false
            buildPanel()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenMetrics()
        cursorX = cursorX.coerceIn(0f, screenW.toFloat())
        cursorY = cursorY.coerceIn(0f, screenH.toFloat())
        if (this::prefs.isInitialized && prefs.themeMode == ThemeMode.SYSTEM) {
            buildCursor()
        }
        buildPanel()
        updateCursor()
    }

    /** 设置界面改完之后调用：重建所有视图。 */
    fun reload() {
        prefs = Prefs(this)
        buildCursor()
        buildPanel()
        updateCursor()
    }

    fun toggleMinimize() {
        prefs.minimized = !prefs.minimized
        minimizedByKeyboard = false
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

    private fun isDarkTheme(): Boolean = prefs.themeMode.resolvesToDark(
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
    )

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
        // 拖拽锁定状态（起点标记）跨面板重建保留，不在这里结束
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        miniBallView = null
        padArea = null
        actionViews.clear()
        actionSlots.clear()
        moveGripView = null
        resizeGripView = null
        main.removeCallbacks(restoreTouchRunnable)
        passThroughCount = 0
        movingPanel = false
        resizingPanel = false
        movingBall = false
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

        // 根容器本身不处理触摸：只有左侧列顶部的移动键可以拖动面板。
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.control_surface_content_description)
        }

        val pad = View(this).apply {
            background = padBackground(dark, dp(22).toFloat())
            contentDescription = getString(R.string.touchpad_content_description)
            setOnTouchListener { _, e -> handlePadTouch(e); true }
        }
        padArea = pad
        root.addView(pad)

        // 槽位与布局一一对应；设为「无」的槽位留空，不会让后面的按钮挪位。
        prefs.buttons.take(BUTTON_SLOT_COUNT).forEachIndexed { slot, action ->
            if (action == PadAction.NONE) return@forEachIndexed
            val button = makeActionButton(slot, action, dark)
            actionViews += button
            actionSlots += slot
            root.addView(button)
        }

        val moveGrip = makeHandle(R.drawable.ic_lu_move, dark) { v, e -> handleMoveTouch(v, e) }
        moveGrip.contentDescription = getString(R.string.handle_move)
        root.addView(moveGrip)
        moveGripView = moveGrip
        val resizeGrip = makeHandle(R.drawable.ic_lu_move_diagonal_2, dark) { v, e -> handleResizeTouch(v, e) }
        resizeGrip.contentDescription = getString(R.string.handle_resize)
        root.addView(resizeGrip)
        resizeGripView = resizeGrip
        layoutControlChildren(root, widthPx, heightPx)
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
        val grid = controlGridHeight(widthPx, resources.displayMetrics.density, spacingPx())
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
        val layout = computeControlLayout(
            width, height, resources.displayMetrics.density, spacingPx(),
        )
        padArea?.layoutParams = rectParams(layout.pad)
        // 触控板圆角随按钮大小变化，保持和圆形按钮的视觉比例
        (padArea?.background as? GradientDrawable)?.cornerRadius = layout.button * 0.42f
        // 「按钮图标大小」= 线条图标的边长（dp），最多占按钮直径的 56%
        val iconPx = minOf(dp(prefs.buttonTextSizeSp), (layout.button * 0.56f).roundToInt()).coerceAtLeast(dp(8))
        val iconInset = ((layout.button - iconPx) / 2).coerceAtLeast(0)
        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            view.layoutParams = rectParams(layout.slots[slot])
            view.setPadding(iconInset, iconInset, iconInset, iconInset)
        }
        moveGripView?.let { it.layoutParams = rectParams(layout.moveGrip); it.setPadding(iconInset, iconInset, iconInset, iconInset) }
        resizeGripView?.let { it.layoutParams = rectParams(layout.resizeGrip); it.setPadding(iconInset, iconInset, iconInset, iconInset) }
        root.requestLayout()
    }

    private fun addMiniDot() {
        if (prefs.hideFloatingBall) {
            panel = null
            panelParams = null
            return
        }
        val size = dp(prefs.floatingBallSizeDp.coerceIn(FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP))
        val dot = ImageView(this).apply {
            val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
                ?: if (isDarkTheme()) 0xFFD97757.toInt() else 0xFFC96442.toInt()
            val light = Color.luminance(ballColor) > 0.55f
            setImageResource(R.drawable.ic_lu_touchpad)
            // 浅色球用暖黑图标，深色球用象牙白图标
            imageTintList = ColorStateList.valueOf(if (light) 0xFF141413.toInt() else 0xFFFAF9F5.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val halo = (size * 0.06f).roundToInt().coerceAtLeast(dp(1))
            val inset = halo + (size * 0.24f).roundToInt()
            setPadding(inset, inset, inset, inset)
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
            background = RippleDrawable(
                ColorStateList.valueOf(if (light) 0x26141413 else 0x40FAF9F5),
                LayerDrawable(arrayOf(ring, disc)).apply { setLayerInset(1, halo, halo, halo, halo) },
                mask,
            )
            alpha = ballRestingAlpha()
            isClickable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.isPressed = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
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
        panelParams = lp
        ballX = lp.x
        ballY = lp.y
        keepBallInBounds()
    }

    private fun ballRestingAlpha(): Float = prefs.floatingBallOpacityPercent.coerceIn(20, 100) / 100f

    /** 把颜色往白色方向提亮一点，用于渐变顶部。 */
    private fun lighten(color: Int, amount: Float): Int {
        fun ch(c: Int) = (c + (255 - c) * amount).roundToInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    // ───────────────────────── 外观 ─────────────────────────

    /** 面板配色：按钮/触控板用轻微竖向渐变 + 细描边，移动/缩放键用强调色区分。 */
    private class Palette(
        val buttonTop: Int, val buttonBottom: Int, val buttonStroke: Int, val buttonText: Int,
        val padTop: Int, val padBottom: Int, val padStroke: Int,
        val gripTop: Int, val gripBottom: Int, val gripStroke: Int, val gripText: Int,
        val ripple: Int,
    )

    private fun palette(dark: Boolean): Palette = if (dark) {
        // 暖深色：炭灰按钮 + 陶土色移动/缩放键
        Palette(
            buttonTop = 0xFF3A3936.toInt(), buttonBottom = 0xFF2E2D2B.toInt(),
            buttonStroke = 0x26FAF9F5, buttonText = 0xFFFAF9F5.toInt(),
            padTop = 0xFF30302E.toInt(), padBottom = 0xFF262624.toInt(), padStroke = 0x1FFAF9F5,
            gripTop = 0xFF5A3A2E.toInt(), gripBottom = 0xFF4A3026.toInt(),
            gripStroke = 0x66D97757, gripText = 0xFFF0B9A3.toInt(),
            ripple = 0x33FAF9F5,
        )
    } else {
        // 暖浅色：象牙白按钮 + 羊皮纸触控板
        Palette(
            buttonTop = 0xFFFFFFFF.toInt(), buttonBottom = 0xFFF5F4ED.toInt(),
            buttonStroke = 0x33B0AEA5, buttonText = 0xFF141413.toInt(),
            padTop = 0xFFF0EEE6.toInt(), padBottom = 0xFFE8E6DC.toInt(), padStroke = 0x33B0AEA5,
            gripTop = 0xFFF6E3DA.toInt(), gripBottom = 0xFFEFD2C5.toInt(),
            gripStroke = 0x66C96442, gripText = 0xFFC96442.toInt(),
            ripple = 0x26141413,
        )
    }

    private fun gradient(shape: Int, top: Int, bottom: Int, stroke: Int, radiusPx: Float = 0f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(top, prefs.opacityPercent), withAlpha(bottom, prefs.opacityPercent)),
        ).apply {
            this.shape = shape
            if (shape == GradientDrawable.RECTANGLE) cornerRadius = radiusPx
            setStroke(dp(1), scaleAlpha(stroke, prefs.opacityPercent))
        }

    /** 圆形按钮背景，带按压水波纹反馈。 */
    private fun circleBackground(top: Int, bottom: Int, stroke: Int, ripple: Int): Drawable {
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(ripple), gradient(GradientDrawable.OVAL, top, bottom, stroke), mask)
    }

    private fun makeHandle(iconRes: Int, dark: Boolean, onTouch: (View, MotionEvent) -> Boolean): View =
        ImageView(this).apply {
            val p = palette(dark)
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(p.gripText)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = circleBackground(p.gripTop, p.gripBottom, p.gripStroke, p.ripple)
            isClickable = true
            setOnTouchListener { v, e ->
                // 自己处理拖动，同时让水波纹跟随按下/抬起
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; haptic() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                onTouch(v, e)
            }
        }

    private fun makeActionButton(slot: Int, action: PadAction, dark: Boolean): View = ImageView(this).apply {
        val p = palette(dark)
        setImageResource(action.iconRes)
        imageTintList = ColorStateList.valueOf(p.buttonText)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = circleBackground(p.buttonTop, p.buttonBottom, p.buttonStroke, p.ripple)
        isClickable = true
        contentDescription = getString(action.labelRes)
        setOnClickListener { performAction(action) }
        setOnLongClickListener { showActionPicker(slot, action); true }
    }

    private fun padBackground(dark: Boolean, radiusPx: Float): GradientDrawable {
        val p = palette(dark)
        return gradient(GradientDrawable.RECTANGLE, p.padTop, p.padBottom, p.padStroke, radiusPx)
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
        val dialog = ActionPicker.build(this, isDarkTheme(), current) { picked ->
            val list = prefs.buttons.toMutableList()
            while (list.size <= slot) list.add(PadAction.NONE)
            list[slot] = picked
            prefs.buttons = list
            buildPanel()
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        runCatching { dialog.show() }
    }

    // ───────────────────────── 触控板手势 ─────────────────────────

    private fun handlePadTouch(e: MotionEvent) {
        val slop = dp(6).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                lastRawX = e.rawX; lastRawY = e.rawY
                downTime = System.currentTimeMillis()
                moved = false; longPressFired = false; dwellFired = false
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
                }
                if (dragging) appendTrailPoint(dragTrail, cursorX, cursorY, dp(4).toFloat())
                scheduleDwell()
            }

            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPressRunnable)
                cancelDwell()
                val distance = kotlin.math.hypot(customSwipeEndX - customSwipeStartX, customSwipeEndY - customSwipeStartY)
                if (dragging) {
                    customSwipeArmed = false
                    return
                }
                when (resolvePadTouchOutcome(longPressFired, dwellFired, moved, distance, dp(12).toFloat())) {
                    PadTouchOutcome.CUSTOM_SWIPE -> customSwipeAt(customSwipeStartX, customSwipeStartY, customSwipeEndX, customSwipeEndY)
                    PadTouchOutcome.LONG_PRESS -> longPressAt(cursorX, cursorY)
                    PadTouchOutcome.CLICK -> tapOrDouble()
                    PadTouchOutcome.DWELL_CLICK, PadTouchOutcome.MOVE_ONLY -> Unit
                }
                customSwipeArmed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressRunnable)
                cancelDwell()
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
                prefs.panelWidthDp = pixelsToDp(width, resources.displayMetrics.density)
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
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizingPanel = false
                panel?.post { keepPanelInBounds() }
            }
        }
        return true
    }

    private fun handleBallTouch(v: View, e: MotionEvent): Boolean {
        val slop = dp(8).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movingBall = true
                ballMoved = false
                ballFromX = e.rawX
                ballFromY = e.rawY
                ballX = panelParams?.x ?: prefs.ballX.coerceAtLeast(0)
                ballY = panelParams?.y ?: prefs.ballY.coerceAtLeast(0)
            }
            MotionEvent.ACTION_MOVE -> if (movingBall) {
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                if (abs(dx) > slop || abs(dy) > slop) ballMoved = true
                if (!ballMoved) return true
                val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                val position = clampFloatingBallPosition(
                    ballX + dx.roundToInt(), ballY + dy.roundToInt(), size, screenW, screenH,
                )
                panelParams?.let { lp ->
                    lp.x = position.x
                    lp.y = position.y
                    panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    prefs.ballX = position.x
                    prefs.ballY = position.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                movingBall = false
                if (e.actionMasked == MotionEvent.ACTION_UP && !ballMoved) toggleMinimize()
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
        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    // ───────────────────────── 手势注入 ─────────────────────────

    private fun dispatch(gesture: GestureDescription) {
        runCatching { dispatchGesture(gesture, null, null) }
    }

    // 注入的点击/滑动要穿透 OpenTouchpad 自己的面板，落到下面的应用上；
    // 否则光标移到触控板或按钮上点击时，会点到触控板自己。
    private var passThroughCount = 0
    private val restoreTouchRunnable = Runnable {
        passThroughCount = 0
        setOverlayTouchable(true)
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val v = panel ?: return
        val lp = panelParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) lp.flags and flag.inv() else lp.flags or flag
        if (next == lp.flags) return
        lp.flags = next
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /** 面板设为不可触摸，最长 [expectedMs] + 2 秒后无论如何恢复。 */
    private fun beginPassThrough(expectedMs: Long) {
        passThroughCount++
        setOverlayTouchable(false)
        main.removeCallbacks(restoreTouchRunnable)
        main.postDelayed(restoreTouchRunnable, maxOf(5000L, expectedMs + 2000L))
    }

    private fun endPassThrough() {
        main.post {
            passThroughCount = (passThroughCount - 1).coerceAtLeast(0)
            if (passThroughCount == 0) {
                main.removeCallbacks(restoreTouchRunnable)
                setOverlayTouchable(true)
            }
        }
    }

    private fun dispatchPassThrough(gesture: GestureDescription, expectedMs: Long = 1000L) {
        if (panel == null) {
            dispatch(gesture)
            return
        }
        beginPassThrough(expectedMs)
        // 等窗口标志真正生效后再注入手势。
        main.postDelayed({
            val ok = runCatching {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { endPassThrough() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { endPassThrough() }
                }, main)
            }.getOrDefault(false)
            if (!ok) endPassThrough()
        }, OVERLAY_PASS_THROUGH_DELAY_MS)
    }

    private fun tapAt(x: Float, y: Float, ms: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        dispatchPassThrough(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            ms,
        )
    }

    /** 光标处长按：按住时长可在设置里调（300–3000 ms）。 */
    private fun longPressAt(x: Float, y: Float) = tapAt(x, y, prefs.cursorHoldMs.toLong())

    private fun customSwipeAt(startX: Float, startY: Float, endX: Float, endY: Float, ms: Long = 320) {
        val swipe = customSwipeFrom(startX, startY, endX, endY, dp(12).toFloat()) ?: return
        val path = Path().apply {
            moveTo(swipe.startX, swipe.startY)
            lineTo(swipe.endX, swipe.endY)
        }
        dispatchPassThrough(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
        )
        haptic()
    }

    private fun swipeBy(dx: Float, dy: Float, ms: Long) {
        val x0 = cursorX
        val y0 = cursorY
        val x1 = (x0 + dx).coerceIn(0f, screenW.toFloat())
        val y1 = (y0 + dy).coerceIn(0f, screenH.toFloat())
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        dispatchPassThrough(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
        )
    }

    private fun startDrag() {
        dragging = true
        dragTrail.clear()
        dragTrail += TrailPoint(cursorX, cursorY)
        showDragMarker(cursorX, cursorY)
        refreshDragButtons()
    }

    /** 放弃拖拽（不注入任何手势）。 */
    private fun cancelDrag() {
        dragging = false
        dragTrail.clear()
        hideDragMarker()
        refreshDragButtons()
    }

    /**
     * 第二次按下拖拽锁定：在起点按住 [Prefs.cursorHoldMs]，再沿记录的轨迹拖到当前光标，松开。
     * 两段用 continueStroke 连成同一根手指，目标应用看到的是「长按后拖动」。
     */
    private fun endDrag() {
        appendTrailPoint(dragTrail, cursorX, cursorY, 0.5f)
        val trail = dragTrail.toList()
        cancelDrag()
        val start = trail.firstOrNull() ?: return
        // 没移动就再按一次 = 取消
        if (trailLength(trail) < dp(8)) return
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
            dispatchPassThrough(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(movePath, 0, hold + moveMs)).build(), hold + moveMs)
            return
        }
        // 面板先变成不可触摸，整个过程（按住 + 拖动）结束后再恢复
        beginPassThrough(hold + moveMs)
        main.postDelayed({
            val ok = runCatching {
                dispatchGesture(GestureDescription.Builder().addStroke(holdStroke).build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        val ok2 = runCatching {
                            dispatchGesture(GestureDescription.Builder().addStroke(moveStroke).build(), object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) { endPassThrough() }
                                override fun onCancelled(gestureDescription: GestureDescription?) { endPassThrough() }
                            }, main)
                        }.getOrDefault(false)
                        if (!ok2) endPassThrough()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) { endPassThrough() }
                }, main)
            }.getOrDefault(false)
            if (!ok) endPassThrough()
        }, OVERLAY_PASS_THROUGH_DELAY_MS)
    }

    /** 起点标记：一个陶土色小圆环，告诉用户拖拽从哪里开始。不接收触摸。 */
    private fun showDragMarker(x: Float, y: Float) {
        hideDragMarker()
        val size = dp(22)
        val accent = if (isDarkTheme()) 0xFFD97757.toInt() else 0xFFC96442.toInt()
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((accent and 0x00FFFFFF) or 0x33000000)
                setStroke(dp(2), accent)
            }
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
            this.x = (x - size / 2f).roundToInt()
            this.y = (y - size / 2f).roundToInt()
            windowAnimations = 0
        }
        runCatching { wm.addView(v, lp) }
        dragMarker = v
    }

    private fun hideDragMarker() {
        dragMarker?.let { runCatching { wm.removeView(it) } }
        dragMarker = null
    }

    /** 拖拽进行中，面板上的「拖拽锁定」按钮改成陶土色，提示再按一次结束。 */
    private fun refreshDragButtons() {
        val dark = if (this::prefs.isInitialized) isDarkTheme() else false
        val p = palette(dark)
        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            if (prefs.buttons.getOrNull(slot) != PadAction.DRAG_LOCK) return@forEachIndexed
            val iv = view as? ImageView ?: return@forEachIndexed
            if (dragging) {
                iv.background = circleBackground(p.gripTop, p.gripBottom, p.gripStroke, p.ripple)
                iv.imageTintList = ColorStateList.valueOf(p.gripText)
            } else {
                iv.background = circleBackground(p.buttonTop, p.buttonBottom, p.buttonStroke, p.ripple)
                iv.imageTintList = ColorStateList.valueOf(p.buttonText)
            }
        }
    }

    // ───────────────────────── 动作派发 ─────────────────────────

    fun performAction(action: PadAction) {
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
            PadAction.NONE -> Unit
        }
        haptic()
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

    private fun haptic() {
        if (!prefs.haptics) return
        runCatching {
            padArea?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
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
