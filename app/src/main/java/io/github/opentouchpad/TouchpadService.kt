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

    // 拖拽锁定
    private var dragging = false
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastDragX = 0f
    private var lastDragY = 0f

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
        removePanel()
        cursorView?.let { runCatching { wm.removeView(it) } }
        cursorView = null
        cursorParams = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        main.removeCallbacksAndMessages(null)
        if (dragging) endDrag()
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
        buildPanel()
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
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, cursorParams) }
        cursorView = v
    }

    private fun buildPanel() {
        removePanel()
        // 横屏时按设置自动隐藏整块触控板（光标保留）
        if (isLandscape() && prefs.autoHideLandscape) {
            cursorView?.visibility = View.VISIBLE
            return
        }
        if (prefs.minimized) {
            cursorView?.visibility = View.INVISIBLE
            addMiniDot()
        } else {
            cursorView?.visibility = View.VISIBLE
            addFullPanel()
        }
    }

    private fun removePanel() {
        if (dragging) endDrag()
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
        val heightRange = controlHeightRange(screenH, resources.displayMetrics.density)
        val widthPx = if (prefs.panelWidthDp > 0) {
            dp(prefs.panelWidthDp)
        } else {
            screenW * prefs.padWidthPercent / 100
        }.coerceIn(widthRange.first, widthRange.last)
        val heightPx = dp(prefs.controlHeightDp).coerceIn(heightRange.first, heightRange.last)

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

        val moveGrip = makeHandle(R.drawable.ic_move, dark) { v, e -> handleMoveTouch(v, e) }
        moveGrip.contentDescription = getString(R.string.handle_move)
        root.addView(moveGrip)
        moveGripView = moveGrip
        val resizeGrip = makeHandle(R.drawable.ic_resize, dark) { v, e -> handleResizeTouch(v, e) }
        resizeGrip.contentDescription = getString(R.string.handle_resize)
        root.addView(resizeGrip)
        resizeGripView = resizeGrip
        layoutControlChildren(root, widthPx, heightPx)

        val lp = panelLayoutParams(widthPx, heightPx)
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

    private fun rectParams(r: ControlRect): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(r.w, r.h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = r.x
            topMargin = r.y
        }

    /** 统一布局：按钮大小、触控板大小只由面板尺寸决定；按钮间距只改变按钮之间的距离。 */
    private fun layoutControlChildren(root: FrameLayout, width: Int, height: Int) {
        val layout = computeControlLayout(
            width, height, resources.displayMetrics.density,
            dp(prefs.buttonSpacingDp.coerceIn(0, BUTTON_SPACING_MAX_DP)),
        )
        padArea?.layoutParams = rectParams(layout.pad)
        // 触控板圆角随按钮大小变化，保持和圆形按钮的视觉比例
        (padArea?.background as? GradientDrawable)?.cornerRadius = layout.button * 0.42f
        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            view.layoutParams = rectParams(layout.slots[slot])
            (view as? TextView)?.textSize = minOf(
                prefs.buttonTextSizeSp.toFloat(),
                layout.button / resources.displayMetrics.density * 0.5f,
            )
        }
        val iconInset = (layout.button * 0.27f).roundToInt()
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
        val dot = TextView(this).apply {
            text = "⌁"
            gravity = Gravity.CENTER
            textSize = (size / resources.displayMetrics.density * 0.45f).coerceIn(5f, 40f)
            includeFontPadding = false
            val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
                ?: if (isDarkTheme()) 0xFF6EA8FE.toInt() else 0xFF2563EB.toInt()
            // 浅色球用深色图标，深色球用浅色图标
            setTextColor(if (Color.luminance(ballColor) > 0.55f) 0xFF111827.toInt() else 0xFFF8FAFC.toInt())
            alpha = prefs.floatingBallOpacityPercent.coerceIn(20, 100) / 100f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ballColor)
                setStroke(if (size < dp(28)) dp(1) else dp(2), if (Color.luminance(ballColor) > 0.55f) 0x33000000 else 0x66FFFFFF)
            }
            setOnTouchListener { v, e -> handleBallTouch(v, e) }
            contentDescription = getString(R.string.floating_ball_content_description)
        }
        val lp = ballLayoutParams(size)
        runCatching { wm.addView(dot, lp) }
        panel = dot
        miniBallView = dot
        panelParams = lp
        ballX = lp.x
        ballY = lp.y
        keepBallInBounds()
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
        Palette(
            buttonTop = 0xFF3A3D44.toInt(), buttonBottom = 0xFF2C2F35.toInt(),
            buttonStroke = 0x33FFFFFF, buttonText = 0xFFF1F3F6.toInt(),
            padTop = 0xFF34373D.toInt(), padBottom = 0xFF282A2F.toInt(), padStroke = 0x26FFFFFF,
            gripTop = 0xFF3B4C6B.toInt(), gripBottom = 0xFF2D3B55.toInt(),
            gripStroke = 0x556EA8FE, gripText = 0xFFBFD6FF.toInt(),
            ripple = 0x40FFFFFF,
        )
    } else {
        Palette(
            buttonTop = 0xFFFFFFFF.toInt(), buttonBottom = 0xFFEEF0F3.toInt(),
            buttonStroke = 0x1F000000, buttonText = 0xFF1F2937.toInt(),
            padTop = 0xFFF4F5F7.toInt(), padBottom = 0xFFE4E7EB.toInt(), padStroke = 0x1A000000,
            gripTop = 0xFFE6EEFF.toInt(), gripBottom = 0xFFD5E2FC.toInt(),
            gripStroke = 0x552563EB, gripText = 0xFF1D4ED8.toInt(),
            ripple = 0x26000000,
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

    private fun makeActionButton(slot: Int, action: PadAction, dark: Boolean): View = TextView(this).apply {
        val p = palette(dark)
        text = action.icon
        gravity = Gravity.CENTER
        includeFontPadding = false
        textSize = prefs.buttonTextSizeSp.toFloat()
        setTextColor(p.buttonText)
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
        val labels = PadAction.ALL.map { "${it.icon}  ${getString(it.labelRes)}" }.toTypedArray()
        val checked = PadAction.ALL.indexOf(current)
        val dialogTheme = if (isDarkTheme()) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        val builder = android.app.AlertDialog.Builder(this, dialogTheme)
            .setTitle(R.string.pick_action)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val picked = PadAction.ALL[which]
                val list = prefs.buttons.toMutableList()
                while (list.size <= slot) list.add(PadAction.NONE)
                list[slot] = picked
                prefs.buttons = list
                buildPanel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
        val dialog = builder.create()
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
                if (dragging) {
                    moveDragTo(cursorX, cursorY)
                } else {
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
                if (dragging) moveDragTo(cursorX, cursorY)
                scheduleDwell()
            }

            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPressRunnable)
                cancelDwell()
                val distance = kotlin.math.hypot(customSwipeEndX - customSwipeStartX, customSwipeEndY - customSwipeStartY)
                if (dragging) endDrag()
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
                if (dragging) endDrag()
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
                resizingPanelHeight = panel?.height ?: dp(prefs.controlHeightDp)
            }
            MotionEvent.ACTION_MOVE -> if (resizingPanel) {
                val width = resizePanelWidth(
                    resizingPanelWidth,
                    (e.rawX - resizingFromX).roundToInt(),
                    controlWidthRange(screenW, resources.displayMetrics.density).first,
                    controlWidthRange(screenW, resources.displayMetrics.density).last,
                )
                val height = resizeControlHeight(
                    resizingPanelHeight,
                    (e.rawY - resizingFromY).roundToInt(),
                    controlHeightRange(screenH, resources.displayMetrics.density).first,
                    controlHeightRange(screenH, resources.displayMetrics.density).last,
                )
                prefs.panelWidthDp = pixelsToDp(width, resources.displayMetrics.density)
                prefs.padWidthPercent = (width * 100 / screenW).coerceIn(20, 100)
                prefs.controlHeightDp = pixelsToDp(height, resources.displayMetrics.density)
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

    private fun dispatchPassThrough(gesture: GestureDescription) {
        if (panel == null) {
            dispatch(gesture)
            return
        }
        passThroughCount++
        setOverlayTouchable(false)
        main.removeCallbacks(restoreTouchRunnable)
        // 兜底：无论回调是否到达，最多几秒后恢复面板可触摸。
        main.postDelayed(restoreTouchRunnable, 5000)
        val done = {
            main.post {
                passThroughCount = (passThroughCount - 1).coerceAtLeast(0)
                if (passThroughCount == 0) {
                    main.removeCallbacks(restoreTouchRunnable)
                    setOverlayTouchable(true)
                }
            }
        }
        // 等窗口标志真正生效后再注入手势。
        main.postDelayed({
            val ok = runCatching {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { done() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { done() }
                }, main)
            }.getOrDefault(false)
            if (!ok) done()
        }, OVERLAY_PASS_THROUGH_DELAY_MS)
    }

    private fun tapAt(x: Float, y: Float, ms: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        dispatchPassThrough(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
        )
    }

    private fun longPressAt(x: Float, y: Float) = tapAt(x, y, prefs.longPressMs.toLong() + 200)

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
        lastDragX = cursorX
        lastDragY = cursorY
        val path = Path().apply {
            // A continued stroke must start exactly where the previous one ends.
            moveTo(cursorX, cursorY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1, true)
        activeStroke = stroke
        dispatch(GestureDescription.Builder().addStroke(stroke).build())
        haptic()
    }

    private fun moveDragTo(x: Float, y: Float) {
        val prev = activeStroke ?: return
        val path = Path().apply {
            moveTo(lastDragX, lastDragY)
            lineTo(x, y)
        }
        val next = runCatching { prev.continueStroke(path, 0, 1, true) }.getOrNull() ?: return
        activeStroke = next
        dispatch(GestureDescription.Builder().addStroke(next).build())
        lastDragX = x
        lastDragY = y
    }

    private fun endDrag() {
        val prev = activeStroke
        dragging = false
        activeStroke = null
        if (prev != null) {
            val path = Path().apply {
                moveTo(lastDragX, lastDragY)
                lineTo(lastDragX + 1, lastDragY + 1)
            }
            runCatching {
                dispatch(
                    GestureDescription.Builder()
                        .addStroke(prev.continueStroke(path, 0, 1, false))
                        .build()
                )
            }
        }
        haptic()
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
