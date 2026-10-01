package io.github.opentouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把"屏幕一小块区域"变成整台手机的操作手柄。
 *
 * 设计要点：
 *  - 手势一律通过 AccessibilityService.dispatchGesture 注入（所以必须声明 isAccessibilityTool=true，
 *    否则 Android 16 会拒绝在权限弹窗等敏感界面上执行）。
 *  - 拖拽用 StrokeDescription(willContinue=true) + continueStroke() 实现真正的"按住不放"。
 *  - 不需要悬浮窗权限：面板与光标都是 TYPE_ACCESSIBILITY_OVERLAY。
 */
class TouchpadService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TouchpadService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var cursorView: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    private var screenW = 0
    private var screenH = 0
    private var cursorX = 0f
    private var cursorY = 0f

    // 触摸状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false
    private var longPressFired = false
    private var dwellFired = false
    private var lastTapTime = 0L

    // 拖拽状态
    private var dragging = false
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastDragX = 0f
    private var lastDragY = 0f

    private val longPressRunnable = Runnable { fireLongPress() }
    private val dwellRunnable = Runnable { fireDwell() }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        cursorX = screenW / 2f
        cursorY = screenH / 2f
        buildOverlay()
    }

    /** 本 App 不需要读取窗口内容，事件回调留空以减少开销。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        cursorX = cursorX.coerceIn(0f, screenW.toFloat())
        cursorY = cursorY.coerceIn(0f, screenH.toFloat())
        buildOverlay()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    /** MainActivity 改完设置后调用，重建界面。 */
    fun reload() {
        if (::prefs.isInitialized) buildOverlay()
    }

    fun togglePanel() {
        prefs.panelVisible = !prefs.panelVisible
        buildOverlay()
    }

    // ───────────────────────── 悬浮界面 ─────────────────────────

    private fun buildOverlay() {
        removeOverlay()
        buildCursor()
        if (prefs.panelVisible) buildPanel()
    }

    private fun buildCursor() {
        val size = dp(prefs.cursorSizeDp)
        val v = View(this).apply { background = getDrawable(R.drawable.cursor_dot) }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        cursorParams = lp
        cursorView = v
        runCatching { wm.addView(v, lp) }
        updateCursor()
    }

    private fun buildPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panel_bg)
        }

        // 触控区
        val pad = View(this)
        pad.setOnTouchListener { _, e ->
            handlePadTouch(e)
            true
        }
        root.addView(pad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildActionRow())
        root.addView(buildNavRow())

        val lp = WindowManager.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(prefs.padHeightDp),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(8)
        }
        panelParams = lp
        panel = root
        runCatching { wm.addView(root, lp) }
    }

    private fun buildActionRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addButton(row, "●") { tapAt(cursorX, cursorY) }
        addButton(row, "◎") { longPressAt(cursorX, cursorY) }
        addButton(row, "✥") { if (dragging) endDrag() else startDrag() }
        addButton(row, "↑") { swipe(cursorX, cursorY + dp(100), 0f, -dp(220).toFloat()) }
        addButton(row, "↓") { swipe(cursorX, cursorY - dp(100), 0f, dp(220).toFloat()) }
        addButton(row, "⤢") { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
        return row
    }

    private fun buildNavRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addButton(row, "◀") { performGlobalAction(GLOBAL_ACTION_BACK) }
        addButton(row, "⌂") { performGlobalAction(GLOBAL_ACTION_HOME) }
        addButton(row, "▢") { performGlobalAction(GLOBAL_ACTION_RECENTS) }
        addButton(row, "✕") {
            prefs.panelVisible = false
            buildOverlay()
        }
        return row
    }

    private inline fun addButton(row: LinearLayout, label: String, crossinline action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = 16f
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
            setOnClickListener {
                haptic()
                action()
            }
        }
        row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun removeOverlay() {
        panel?.let { runCatching { wm.removeView(it) } }
        cursorView?.let { runCatching { wm.removeView(it) } }
        panel = null
        cursorView = null
        dragging = false
        activeStroke = null
    }

    private fun updateCursor() {
        val v = cursorView ?: return
        val lp = cursorParams ?: return
        val size = dp(prefs.cursorSizeDp)
        lp.x = (cursorX - size / 2f).roundToInt()
        lp.y = (cursorY - size / 2f).roundToInt()
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ───────────────────────── 触摸 → 手势 ─────────────────────────

    private fun handlePadTouch(e: MotionEvent) {
        val slop = dp(prefs.slopDp).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                lastRawX = e.rawX; lastRawY = e.rawY
                moved = false; longPressFired = false; dwellFired = false
                if (dragging) moveDragTo(cursorX, cursorY)
                main.removeCallbacks(longPressRunnable)
                main.postDelayed(longPressRunnable, prefs.longPressMs.toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastRawX
                val dy = e.rawY - lastRawY
                lastRawX = e.rawX; lastRawY = e.rawY
                if (abs(e.rawX - downRawX) > slop || abs(e.rawY - downRawY) > slop) {
                    if (!moved) {
                        moved = true
                        main.removeCallbacks(longPressRunnable)
                        scheduleDwell()
                    }
                }
                cursorX = (cursorX + dx * prefs.sensitivity).coerceIn(0f, screenW.toFloat())
                cursorY = (cursorY + dy * prefs.sensitivity).coerceIn(0f, screenH.toFloat())
                updateCursor()
                if (dragging) moveDragTo(cursorX, cursorY)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressRunnable)
                main.removeCallbacks(dwellRunnable)
                if (dragging) {
                    endDrag()
                } else if (!moved && !longPressFired && !dwellFired) {
                    tapOrDouble()
                }
            }
        }
    }

    private fun scheduleDwell() {
        main.removeCallbacks(dwellRunnable)
        val ms = prefs.dwellMs
        if (ms > 0) main.postDelayed(dwellRunnable, ms.toLong())
    }

    private fun fireDwell() {
        if (dwellFired || longPressFired) return
        dwellFired = true
        haptic()
        tapAt(cursorX, cursorY)
    }

    private fun fireLongPress() {
        if (longPressFired || dwellFired) return
        longPressFired = true
        haptic()
        longPressAt(cursorX, cursorY)
    }

    private fun tapOrDouble() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < prefs.doubleTapMs) {
            lastTapTime = 0L
            tapAt(cursorX, cursorY)
            main.postDelayed({ tapAt(cursorX, cursorY) }, 120)
        } else {
            lastTapTime = now
            tapAt(cursorX, cursorY)
        }
    }

    private fun haptic() {
        if (!prefs.haptics) return
        runCatching { panel?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }

    // ───────────────────────── 手势注入 ─────────────────────────

    private fun dispatch(g: GestureDescription) {
        runCatching { dispatchGesture(g, null, null) }
    }

    private fun tapAt(x: Float, y: Float, ms: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
        )
    }

    private fun longPressAt(x: Float, y: Float) = tapAt(x, y, prefs.longPressMs + 120L)

    private fun swipe(x: Float, y: Float, dx: Float, dy: Float, ms: Long = 200) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + dx, y + dy)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
        )
    }

    /** 开始拖拽：按下不放，之后靠 continueStroke 跟随光标。 */
    fun startDrag() {
        if (dragging) return
        dragging = true
        lastDragX = cursorX
        lastDragY = cursorY
        val path = Path().apply {
            moveTo(cursorX, cursorY)
            lineTo(cursorX + 1f, cursorY + 1f)
        }
        val stroke = runCatching {
            GestureDescription.StrokeDescription(path, 0, 1, true)
        }.getOrNull() ?: run {
            dragging = false
            return
        }
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

    fun endDrag() {
        val prev = activeStroke
        dragging = false
        activeStroke = null
        if (prev != null) {
            val path = Path().apply {
                moveTo(lastDragX, lastDragY)
                lineTo(lastDragX + 1f, lastDragY + 1f)
            }
            val finish = runCatching { prev.continueStroke(path, 0, 1, false) }.getOrNull()
            if (finish != null) {
                dispatch(GestureDescription.Builder().addStroke(finish).build())
            }
        }
        haptic()
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
