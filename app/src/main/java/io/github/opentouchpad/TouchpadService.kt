package io.github.opentouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.Choreographer
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
        private const val NOTIFICATION_CHANNEL_ID = "opentouchpad_keepalive"
        private const val NOTIFICATION_ID_KEEPALIVE = 2001

        @SuppressLint("StaticFieldLeak")
        @Volatile
        var instance: TouchpadService? = null
            private set

        @Volatile
        var onPanelResized: ((widthDp: Int, extraHeightDp: Int) -> Unit)? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var cleanedUp = false

    // 热路径内存缓存：避免在 120–240Hz ACTION_MOVE 中反复读写 SharedPreferences / 磁盘 stat
    private var cachedSensitivity = 1.4f
    private var cachedPressFeedback = true
    private var cachedTouchRingEnabled = true
    private var cachedPadEdgeDeadZoneDp = PAD_EDGE_DEAD_ZONE_DEFAULT_DP
    private var cachedLongPressMs = 600
    private var cachedHaptics = true
    private var cachedHasTouchpadBg = false

    // VSync 合并提交 WindowManager.updateViewLayout，消除 240Hz/360Hz 触控下的冗余 Binder IPC 风暴
    private var pendingCursorLayout = false
    private var pendingRingLayout = false
    private var pendingPanelLayout = false
    private var layoutFlushScheduled = false

    private val layoutFlushCallback = Choreographer.FrameCallback {
        layoutFlushScheduled = false
        flushPendingLayoutsNow()
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!this::prefs.isInitialized) return@OnSharedPreferenceChangeListener
        refreshHotPrefs()
        if (key == "minimizeOnKeyboard" || key == "minimizedByKeyboard") {
            syncAccessibilityEventSubscription()
        }
    }

    private var screenStateReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenTurnedOff()
                Intent.ACTION_SCREEN_ON -> syncScreenBoundsIfChanged(rebuildNow = true)
            }
        }
    }

    private var pendingBoundsRebuild = false
    private val deferredBoundsCheckRunnable = Runnable {
        if (!hasActiveTouch()) {
            syncScreenBoundsIfChanged(rebuildNow = true)
        }
    }

    private fun hasActiveTouch(): Boolean =
        padFingerDown || movingPanel || resizingPanel || isDraggingBall

    /**
     * 惰性同步屏幕尺寸：替代常驻 DisplayListener，彻底消除 LTPO 屏幕每次 120↔90↔60↔30↔10↔1Hz
     * 自动换档时唤醒服务主线程；在 ACTION_DOWN 传 rebuildNow = false 仅更新边界不毁窗，
     * 待手势结束（ACTION_UP/CANCEL）再安全重建，杜绝手势中途撕毁接收触摸的窗口。
     */
    private fun syncScreenBoundsIfChanged(rebuildNow: Boolean) {
        if (!::wm.isInitialized || !::prefs.isInitialized) return
        val oldW = screenW
        val oldH = screenH
        refreshScreenMetrics()
        if (screenW != oldW || screenH != oldH) {
            cursorX = clampGestureCoord(cursorX, screenW)
            cursorY = clampGestureCoord(cursorY, screenH)
            if (rebuildNow) {
                pendingBoundsRebuild = false
                buildPanel()
                updateCursor(immediate = true)
            } else {
                pendingBoundsRebuild = true
            }
        }
    }

    private fun flushPendingBoundsRebuild() {
        if (!pendingBoundsRebuild || hasActiveTouch()) return
        pendingBoundsRebuild = false
        buildPanel()
        updateCursor(immediate = true)
    }

    private var activeDialog: AlertDialog? = null
    private val imeBoundsRect = Rect()
    private val panelLocationOnScreen = IntArray(2)
    private var touchRingNeedsRaise = false

    private var isPassThroughActive = false
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var padArea: View? = null
    private var padControlRect = ControlRect(0, 0, 0, 0)
    private var touchpadCustomBgBitmap: android.graphics.Bitmap? = null
    private var miniBallView: View? = null
    private val actionViews = mutableListOf<View>()
    private val actionSlots = mutableListOf<Int>()

    private fun refreshHotPrefs() {
        if (!this::prefs.isInitialized) return
        cachedSensitivity = prefs.sensitivity
        cachedPressFeedback = prefs.pressFeedback
        cachedTouchRingEnabled = prefs.touchRingEnabled
        cachedPadEdgeDeadZoneDp = prefs.padEdgeDeadZoneDp
        cachedLongPressMs = prefs.longPressMs
        cachedHaptics = prefs.haptics
    }

    private fun scheduleLayoutFlush() {
        if (layoutFlushScheduled) return
        layoutFlushScheduled = true
        Choreographer.getInstance().postFrameCallback(layoutFlushCallback)
    }

    private fun flushPendingLayoutsNow() {
        if (pendingCursorLayout) {
            pendingCursorLayout = false
            val p = cursorParams
            val v = cursorView
            if (p != null && v != null) {
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        if (pendingRingLayout) {
            pendingRingLayout = false
            val p = touchRingParams
            val v = touchRing
            if (p != null && v != null) {
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        if (pendingPanelLayout) {
            pendingPanelLayout = false
            val p = panelParams
            val v = panel
            if (p != null && v != null) {
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
    }

    private fun cancelPendingLayouts() {
        if (layoutFlushScheduled) {
            layoutFlushScheduled = false
            runCatching { Choreographer.getInstance().removeFrameCallback(layoutFlushCallback) }
        }
        pendingCursorLayout = false
        pendingRingLayout = false
        pendingPanelLayout = false
    }

    fun reloadTouchpadBackground() {
        (padArea as? TouchpadSurfaceView)?.customBackgroundBitmap = null
        touchpadCustomBgBitmap?.recycle()
        cachedHasTouchpadBg = CustomBackgroundManager.hasTouchpadBg(this)
        touchpadCustomBgBitmap = if (cachedHasTouchpadBg) {
            CustomBackgroundManager.loadBackgroundBitmap(
                CustomBackgroundManager.getTouchpadBgFile(this),
                maxDim = 1200
            )
        } else {
            null
        }
        (padArea as? TouchpadSurfaceView)?.let {
            it.customBackgroundBitmap = touchpadCustomBgBitmap
            it.bgFocusXPercent = prefs.touchpadBgFocusXPercent
            it.bgFocusYPercent = prefs.touchpadBgFocusYPercent
            it.invalidate()
        }
    }

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

    private var customSwipeArmed = false
    private var customSwipeStartX = 0f
    private var customSwipeStartY = 0f
    private var customSwipeEndX = 0f
    private var customSwipeEndY = 0f
    private val swipeVelocityTracker = SwipeVelocityTracker()

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
    private var movingHandleView: View? = null
    private var moveAnchorX = 0f
    private var moveAnchorY = 0f
    private var moveLastClampedX = 0f
    private var moveLastClampedY = 0f
    private var movePendingDx = 0f
    private var movePendingDy = 0f
    private var moveLastEventTimeMs = 0L
    private var moveLastSampleNs = 0L
    private var moveSmoothedVx = 0f
    private var moveSmoothedVy = 0f
    private var panelSpringState = PanelSpringState()
    private var panelDeformRunning = false
    private var panelDeformLastFrameNs = 0L
    private val panelDeformMatrixValues = FloatArray(9)
    private var padFingerDown = false
    private var resizingPanel = false
    private var resizingFromX = 0f
    private var resizingFromY = 0f
    private var resizingPanelWidth = 0
    private var resizingPanelHeight = 0
    private var resizingCurrentWidthDp = 0
    private var resizingCurrentWidthPercent = 0
    private var resizingCurrentExtraHeightDp = 0
    private var resizingLastAppliedWidth = -1
    private var resizingLastAppliedHeight = -1

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
    private var lastMoveHandleTiltMs = -1000L
    private var lastDragRawX = 0f
    private var lastDragRawY = 0f
    private var ballDragLastTimeNs = 0L
    private var ballDragVelocityX = 0f
    private var ballDragVelocityY = 0f
    private var ballDragCurrentRotation = 0f
    private var ballDragHoverSettled = true

    private fun motionEventTimeNs(e: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            e.eventTimeNanos
        } else {
            e.eventTime * 1_000_000L
        }

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
            ballDragHoverSettled = true
            ballDragVelocityX = 0f
            ballDragVelocityY = 0f
            ballDragCurrentRotation = 0f
            ballDragLastTimeNs = 0L
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
        if (!cachedHaptics) return
        runCatching {
            miniBallView?.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS,
                @Suppress("DEPRECATION")
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    private fun ballHapticCancel() {
        if (!cachedHaptics) return
        runCatching {
            (miniBallView ?: panel)?.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                @Suppress("DEPRECATION")
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

    private val longPressRunnable = Runnable {
        if (!moved) {
            longPressFired = true
            customSwipeArmed = true
            customSwipeStartX = cursorX
            customSwipeStartY = cursorY
            customSwipeEndX = cursorX
            customSwipeEndY = cursorY
            swipeVelocityTracker.reset()
            swipeVelocityTracker.addMovement(SystemClock.uptimeMillis(), cursorX, cursorY)
            if (cachedTouchRingEnabled) {
                moveTouchRing(cursorX, cursorY, immediate = true)
                touchRing?.pressUntilRelease()
            }
            haptic()
        }
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        cleanedUp = false
        instance = this

        prefs = Prefs(this)
        refreshHotPrefs()
        cachedHasTouchpadBg = CustomBackgroundManager.hasTouchpadBg(this)
        prefs.registerChangeListener(prefChangeListener)
        syncAccessibilityEventSubscription()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        refreshScreenMetrics()
        cursorX = clampGestureCoord(screenW / 2f, screenW)
        cursorY = clampGestureCoord(screenH / 2f, screenH)
        buildCursor()
        updateCursor(immediate = true)
        buildPanel()
        // 服务被重启时可能正处于「键盘自动收起」状态：键盘已经不在了就立刻还原
        syncKeyboardMinimize()
        updateKeepAliveNotification()

        registerSystemListeners()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupService()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupService()
        super.onDestroy()
    }

    private fun cleanupService() {
        if (cleanedUp) return
        cleanedUp = true
        unregisterSystemListeners()
        if (this::prefs.isInitialized) {
            runCatching { prefs.unregisterChangeListener(prefChangeListener) }
        }
        dismissActiveDialog()
        cancelKeepAliveNotification()
        cancelPendingLayouts()
        pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
        pendingPassThroughDispatchRunnable = null
        main.removeCallbacks(deferredBoundsCheckRunnable)
        pendingBoundsRebuild = false
        main.removeCallbacksAndMessages(null)
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
        cursorView?.let { v ->
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        }
        cursorView = null
        cursorParams = null
        touchpadCustomBgBitmap?.recycle()
        touchpadCustomBgBitmap = null
    }

    private fun registerSystemListeners() {
        if (!screenStateReceiverRegistered) {
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(screenStateReceiver, filter)
                }
                screenStateReceiverRegistered = true
            }
        }
    }

    private fun unregisterSystemListeners() {
        if (screenStateReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenStateReceiverRegistered = false
        }
    }

    private fun onScreenTurnedOff() {
        main.removeCallbacks(longPressRunnable)
        main.removeCallbacks(ballLongPressRunnable)
        main.removeCallbacks(ballSingleTapRunnable)
        main.removeCallbacks(deferredBoundsCheckRunnable)
        pendingBoundsRebuild = false
        cancelPendingLayouts()
        padFingerDown = false
        movingPanel = false
        resizingPanel = false
        isDraggingBall = false
        ballLongPressTriggered = false
        isWaitingForBallDoubleTap = false
        ballSnapAnimator?.cancel()
        ballSnapAnimator = null
        stopPanelDeformation(resetVisuals = true)
        resetBallDeformation(animated = false)
        touchRing?.clear()
        customSwipeArmed = false
        dismissActiveDialog()
    }

    private fun dismissActiveDialog() {
        activeDialog?.let { runCatching { if (it.isShowing) it.dismiss() } }
        activeDialog = null
    }

    /**
     * 动态同步无障碍事件订阅：
     * - 仅当「键盘弹出自动收起」开启或当前处于键盘自动收起态时订阅 TYPE_WINDOWS_CHANGED；
     * - 关闭该开关时不再订阅窗口变更事件，日常使用实现零无效跨进程唤醒。
     */
    private fun syncAccessibilityEventSubscription() {
        if (!this::prefs.isInitialized) return
        runCatching {
            val info = serviceInfo ?: return@runCatching
            val needsWindowEvents = prefs.minimizeOnKeyboard || prefs.minimizedByKeyboard
            val desiredFlags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            val desiredEvents = if (needsWindowEvents) {
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            } else {
                0
            }
            if (info.flags != desiredFlags || info.eventTypes != desiredEvents) {
                info.flags = desiredFlags
                info.eventTypes = desiredEvents
                serviceInfo = info
            }
        }
    }

    override fun onInterrupt() {
        cancelPendingLayouts()
        pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
        pendingPassThroughDispatchRunnable = null
        main.removeCallbacks(deferredBoundsCheckRunnable)
        pendingBoundsRebuild = false
        main.removeCallbacksAndMessages(null)
        padFingerDown = false
        stopPanelDeformation(resetVisuals = true)
        gestureQueue.clear()
        isDispatching = false
        setOverlayTouchable(true)
        cancelDrag()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !shouldReactToWindowsChanged(event.windowChanges)) {
            return
        }
        syncKeyboardMinimize()
    }

    /**
     * 键盘弹出自动收起 / 键盘收起自动还原。
     * 「是不是键盘收起的」存在 Prefs 里：服务被系统重启后也能正确还原；
     * 自动收起期间关掉这个开关，也立即还原，不会一直卡在收起状态。
     */
    fun syncKeyboardMinimize() {
        if (!this::prefs.isInitialized) return
        syncAccessibilityEventSubscription()
        if (!prefs.minimizeOnKeyboard) {
            if (prefs.minimizedByKeyboard) {
                prefs.minimizedByKeyboard = false
                prefs.minimized = false
                syncAccessibilityEventSubscription()
                buildPanel()
            }
            return
        }
        // 已由用户手动收起且非键盘触发时，无需再跨进程查询 getWindows()
        if (prefs.minimized && !prefs.minimizedByKeyboard) return
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
        main.removeCallbacks(deferredBoundsCheckRunnable)
        pendingBoundsRebuild = false
        refreshScreenMetrics(newConfig)
        cursorX = clampGestureCoord(cursorX, screenW)
        cursorY = clampGestureCoord(cursorY, screenH)
        buildPanel()
        updateCursor(immediate = true)
        // 部分定制系统在 onConfigurationChanged 瞬间 maximumWindowMetrics 滞后 1 帧，120ms 后静默兜底校验一次
        main.postDelayed(deferredBoundsCheckRunnable, 120L)
    }

    /** 设置界面改完之后调用：重建所有视图。 */
    fun reload() {
        if (!this::prefs.isInitialized) return
        refreshHotPrefs()
        syncAccessibilityEventSubscription()
        removeTouchRing()
        isPassThroughActive = false
        buildCursor()
        buildPanel()
        updateCursor(immediate = true)
        updateKeepAliveNotification()
    }

    /** 更新常驻通知栏保活。开启后在系统状态栏显示一条静音通知，让系统判定应用处于使用中。 */
    fun updateKeepAliveNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!prefs.keepAliveNotification) {
            nm.cancel(NOTIFICATION_ID_KEEPALIVE)
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(this, 0, openIntent, flags)

        val toggleIntent = Intent(this, TouchpadActionReceiver::class.java).apply {
            action = TouchpadActionReceiver.ACTION_TOGGLE_PANEL
        }
        val togglePi = PendingIntent.getBroadcast(this, 1, toggleIntent, flags)

        val toggleAction = Notification.Action.Builder(
            null,
            getString(R.string.notification_action_toggle),
            togglePi
        ).build()

        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lu_touchpad)
            .setContentTitle(getString(R.string.notification_keepalive_title))
            .setContentText(getString(R.string.notification_keepalive_desc))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(toggleAction)
            .build()

        runCatching {
            nm.notify(NOTIFICATION_ID_KEEPALIVE, notification)
        }
    }

    fun cancelKeepAliveNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(NOTIFICATION_ID_KEEPALIVE) }
    }

    /** 实时平滑更新触控板尺寸与边距（拖拽缩放键或滑动条时调用，绝不重建窗口，零闪烁）。 */
    fun updatePanelGeometry() {
        if (!this::prefs.isInitialized) return
        val root = panel as? FrameLayout ?: return
        if (root === miniBallView) return
        if (!movingPanel) stopPanelDeformation(resetVisuals = true)
        val window = panelParams ?: return
        val widthRange = controlWidthRange(screenW, resources.displayMetrics.density)
        val widthPx = if (prefs.panelWidthDp > 0) {
            dp(prefs.panelWidthDp)
        } else {
            screenW * prefs.padWidthPercent / 100
        }.coerceIn(widthRange.first, widthRange.last)
        val heightPx = panelHeightFor(widthPx, prefs.extraHeightDp)
        val position = clampPanelPosition(window.x, window.y, widthPx, heightPx, screenW, screenH)
        val layoutChanged = window.x != position.x || window.y != position.y ||
            window.width != widthPx || window.height != heightPx
        window.x = position.x
        window.y = position.y
        window.width = widthPx
        window.height = heightPx
        root.layoutParams = window
        layoutControlChildren(root, widthPx, heightPx, updateAppearance = false)
        if (layoutChanged) {
            pendingPanelLayout = true
            scheduleLayoutFlush()
        }
    }

    /** 实时平滑更新触控板颜色、透明度与按键样式（零闪烁、复用 Drawable 零重建）。 */
    fun updatePanelAppearance() {
        if (!this::prefs.isInitialized) return
        val root = panel as? FrameLayout ?: return
        if (root === miniBallView) return
        val window = panelParams ?: return
        layoutControlChildren(root, window.width, window.height, updateAppearance = true)
        refreshDragButtons()
    }

    private fun getAccentColor(dark: Boolean): Int {
        if (prefs.uiAccentColor == COLOR_DYNAMIC || (prefs.dynamicColor && prefs.uiAccentColor == 0)) {
            return resolveDynamicAccentColor(this, dark)
        }
        return resolveUiAccentColor(prefs.uiAccentColor, dark)
    }

    /** 实时平滑更新悬浮球尺寸、透明度与颜色（零闪烁）。 */
    fun updateBallAppearance() {
        if (!this::prefs.isInitialized) return
        val dot = miniBallView ?: return
        val lp = panelParams ?: return
        val size = dp(prefs.floatingBallSizeDp.coerceIn(FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP))
        val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
            ?: getAccentColor(isDarkTheme())
        val light = Color.luminance(ballColor) > 0.55f
        val ballSize = (size * 0.86f).roundToInt()
        val halo = (ballSize * 0.06f).roundToInt().coerceAtLeast(dp(1))

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((ballColor and 0x00FFFFFF) or 0x40000000)
        }
        val disc = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(lighten(ballColor, 0.18f), ballColor, darken(ballColor, 0.06f)),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, dp(1)), if (light) 0x33141413 else 0x55FAF9F5)
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
            prefs.setBallPosition(isLandscape(), position.x, position.y)
            ballAnchorX = position.x
            ballAnchorY = position.y
            pendingPanelLayout = true
            scheduleLayoutFlush()
        }
        dot.requestLayout()
        dot.invalidate()
    }

    /** 实时平滑更新光标大小、透明度与颜色（零闪烁）。 */
    fun updateCursorAppearance() {
        if (!this::prefs.isInitialized) return
        val cv = cursorView ?: return
        val cp = cursorParams ?: return
        val size = dp(prefs.cursorSizeDp.coerceIn(CURSOR_MIN_DP, CURSOR_MAX_DP))
        cv.alpha = prefs.cursorOpacityPercent.coerceIn(10, 100) / 100f
        (cv as? CursorArrowView)?.pointerColor = prefs.cursorColor
        if (cp.width != size || cp.height != size) {
            cp.width = size
            cp.height = size
            pendingCursorLayout = true
            scheduleLayoutFlush()
        }
        cv.invalidate()
    }

    /** 实时平滑更新光圈样式与开关（零闪烁），可选播放预览动画。 */
    fun updateTouchRingAppearance(preview: Boolean = false) {
        if (!this::prefs.isInitialized) return
        refreshHotPrefs()
        val enabled = cachedTouchRingEnabled
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

    private fun refreshScreenMetrics(config: Configuration? = null) {
        val conf = config ?: resources.configuration
        val isLand = isLandscape(conf)
        if (::wm.isInitialized) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                val resolved = resolveScreenBounds(bounds.width(), bounds.height(), isLand, trustWindowMetrics = true)
                screenW = resolved.x
                screenH = resolved.y
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                val resolved = resolveScreenBounds(dm.widthPixels, dm.heightPixels, isLand, trustWindowMetrics = false)
                screenW = resolved.x
                screenH = resolved.y
            }
        } else {
            val dm = resources.displayMetrics
            val resolved = resolveScreenBounds(dm.widthPixels, dm.heightPixels, isLand, trustWindowMetrics = false)
            screenW = resolved.x
            screenH = resolved.y
        }
    }

    private fun applyCutoutMode(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun isLandscape(config: Configuration? = null): Boolean {
        val orient = config?.orientation ?: resources.configuration.orientation
        return orient == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun isDarkTheme(): Boolean {
        val sysDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return prefs.themeMode.resolvesToDark(sysDark)
    }

    private fun buildCursor() {
        cursorView?.let { runCatching { wm.removeView(it) } }
        pendingCursorLayout = false
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
            applyCutoutMode(this)
        }
        runCatching { wm.addView(v, cursorParams) }
        cursorView = v
    }

    private fun buildPanel(animate: Boolean = false) {
        pendingBoundsRebuild = false
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
        if (touchRing != null) {
            touchRing?.clear()
            touchRingNeedsRaise = true
        }
        if (outgoing != null) playTransition(outgoing)
    }

    // ───────────────────────── 展开／收起过渡 ─────────────────────────

    private class Outgoing(val view: View, val lp: WindowManager.LayoutParams)

    private var enteringWithTransition = false

    private val transitionInterpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    private val runningOutgoing = mutableListOf<View>()

    /** 把当前面板/悬浮球从状态里摘出来（窗口暂不移除），供退场动画使用。 */
    private fun detachPanelForTransition(): Outgoing? {
        stopPanelDeformation(resetVisuals = true)
        pendingPanelLayout = false
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
        if (!o.view.isAttachedToWindow) {
            runningOutgoing.remove(o.view)
            runCatching { wm.removeView(o.view) }
            return
        }
        o.view.postOnAnimation {
            runningOutgoing.remove(o.view)
            runCatching { wm.removeView(o.view) }
        }
    }

    /** 当前所有退场中的窗口立即移除（服务销毁、重建时调用）。 */
    private fun cancelTransitions() {
        runningOutgoing.toList().forEach { v ->
            v.animate().cancel()
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
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
        stopPanelDeformation(resetVisuals = false)
        dismissActiveDialog()
        pendingPanelLayout = false
        padArea?.animate()?.cancel()
        padFingerDown = false
        // 拖拽锁定状态（以及跟随光标的圆环）跨面板重建保留，不在这里结束
        panel?.let { v ->
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        }
        panel = null
        panelParams = null
        miniBallView = null
        ballBgView = null
        ballIconView = null
        padArea = null
        padControlRect = ControlRect(0, 0, 0, 0)
        actionViews.clear()
        actionSlots.clear()
        main.removeCallbacks(restoreTouchRunnable)
        main.removeCallbacks(delayedRestoreTouchRunnable)
        pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
        pendingPassThroughDispatchRunnable = null
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
        // 这里把长按计时取消，已经展开的长按圆环收起，避免之后在新面板上误触发、圆环卡住。
        main.removeCallbacks(longPressRunnable)
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
        applyCutoutMode(lp)
        val land = isLandscape()
        val storedX = prefs.getPadX(land)
        val storedY = prefs.getPadY(land)
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
        applyCutoutMode(lp)
        val land = isLandscape()
        val storedX = prefs.getBallX(land)
        val storedY = prefs.getBallY(land)
        val position = if (storedX >= 0 && storedY >= 0) {
            clampFloatingBallPosition(storedX, storedY, size, screenW, screenH)
        } else {
            PanelPosition(screenW - size - dp(18), screenH / 2 - size / 2)
        }
        lp.x = position.x
        lp.y = position.y
        return lp
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFullPanel() {
        val dark = isDarkTheme()
        val p = palette(dark)
        val widthRange = controlWidthRange(screenW, resources.displayMetrics.density)
        val widthPx = if (prefs.panelWidthDp > 0) {
            dp(prefs.panelWidthDp)
        } else {
            screenW * prefs.padWidthPercent / 100
        }.coerceIn(widthRange.first, widthRange.last)
        val heightPx = panelHeightFor(widthPx)

        // 根容器本身不处理触摸：只有设成「移动触控板」的按键可以拖动面板。
        // 使用 DeformablePanelLayout 以支持拖动面板移动时的整板方向形变与弹性回弹。
        val root = DeformablePanelLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.control_surface_content_description)
        }

        val pad = TouchpadSurfaceView(this).apply {
            contentDescription = getString(R.string.touchpad_content_description)
            cameraDistance = 1400f * resources.displayMetrics.density
            isHapticFeedbackEnabled = false
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                handlePadTouch(e)
                true
            }
        }
        padArea = pad
        root.addView(pad)

        // 槽位与布局一一对应；设为「无」的槽位留空，不会让后面的按钮挪位。
        // 背景由 layoutControlChildren 统一一次生成并后续原地更新，避免创建期重复分配。
        val buttonCount = prefs.buttonCount
        val buttons = prefs.buttons
        buttons.take(buttonCount).forEachIndexed { slot, action ->
            val button = when (action) {
                PadAction.NONE -> return@forEachIndexed
                PadAction.MOVE_PANEL -> makeHandle(action.iconRes) { v, e -> handleMoveTouch(v, e) }
                    .also { it.contentDescription = getString(R.string.act_move_panel) }
                PadAction.RESIZE_PANEL -> makeHandle(action.iconRes) { v, e -> handleResizeTouch(v, e) }
                    .also { it.contentDescription = getString(R.string.act_resize_panel) }
                else -> makeActionButton(slot, action)
            }
            actionViews += button
            actionSlots += slot
            root.addView(button)
        }
        layoutControlChildren(root, widthPx, heightPx, updateAppearance = true, precomputedPalette = p, precomputedDark = dark)
        // 拖拽锁定进行中，但新面板上已经没有「拖拽锁定」键（被换掉了）：再也没法按第二下结束，
        // 而拖拽期间触控板不点击、不长按，等于整块失灵。直接结束拖拽（不注入手势）。
        if (dragging && PadAction.DRAG_LOCK !in buttons.take(buttonCount)) cancelDrag()
        if (dragging) refreshDragButtons(precomputedPalette = p, precomputedDark = dark)

        val lp = panelLayoutParams(widthPx, heightPx)
        if (enteringWithTransition) root.alpha = 0f
        runCatching { wm.addView(root, lp) }
        panel = root
        panelParams = lp
        root.post {
            if (panel === root) {
                val land = isLandscape()
                if (prefs.getPadX(land) < 0 || prefs.getPadY(land) < 0) {
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

    private fun applyRectParams(view: View, r: ControlRect) {
        val existing = view.layoutParams as? FrameLayout.LayoutParams
        if (existing != null) {
            if (existing.width == r.w && existing.height == r.h &&
                existing.leftMargin == r.x && existing.topMargin == r.y &&
                existing.gravity == (Gravity.TOP or Gravity.START)
            ) {
                return
            }
            existing.width = r.w
            existing.height = r.h
            existing.gravity = Gravity.TOP or Gravity.START
            existing.leftMargin = r.x
            existing.topMargin = r.y
            view.layoutParams = existing
        } else {
            view.layoutParams = FrameLayout.LayoutParams(r.w, r.h).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = r.x
                topMargin = r.y
            }
        }
    }

    /** 等间距网格：按钮之间、按钮与触控板之间都是同一个间距。 */
    private fun layoutControlChildren(
        root: FrameLayout,
        width: Int,
        height: Int,
        updateAppearance: Boolean = true,
        precomputedPalette: TouchpadPalette? = null,
        precomputedDark: Boolean? = null,
    ) {
        val density = resources.displayMetrics.density
        val buttonCount = prefs.buttonCount
        val layout = computeControlLayout(
            width, height, density, spacingPx(), buttonCount
        )
        padControlRect = layout.pad
        padCornerRadius = layout.button * 0.42f

        padArea?.let { applyRectParams(it, layout.pad) }

        val dark = precomputedDark ?: isDarkTheme()
        val p = if (updateAppearance) (precomputedPalette ?: palette(dark)) else null
        val buttons = prefs.buttons

        (padArea as? TouchpadSurfaceView)?.apply {
            cornerRadiusPx = padCornerRadius
            if (updateAppearance && p != null) {
                isDarkTheme = dark
                baseColor = p.padBg
                strokeColor = p.padStroke
                glassTintColor = p.padTint
                opacityPercent = prefs.opacityPercent
                bgFocusXPercent = prefs.touchpadBgFocusXPercent
                bgFocusYPercent = prefs.touchpadBgFocusYPercent
                if (touchpadCustomBgBitmap == null && cachedHasTouchpadBg) {
                    touchpadCustomBgBitmap = CustomBackgroundManager.loadBackgroundBitmap(
                        CustomBackgroundManager.getTouchpadBgFile(this@TouchpadService),
                        maxDim = 1200
                    )
                }
                customBackgroundBitmap = touchpadCustomBgBitmap
            }
        }

        val strokePx = dp(1).toFloat()
        val opacity = prefs.opacityPercent
        val standardIconPx = (layout.button * 0.56f).roundToInt()
        val minIconPx = dp(8)
        val iconMarginPx = dp(4)

        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            val rect = layout.slots.getOrNull(slot) ?: return@forEachIndexed
            applyRectParams(view, rect)

            if (updateAppearance && p != null) {
                val action = buttons.getOrNull(slot)
                val isHandle = action == PadAction.MOVE_PANEL || action == PadAction.RESIZE_PANEL
                val btnBg = if (isHandle) p.handleBg else p.buttonBg
                val btnStroke = if (isHandle) p.handleStroke else p.buttonStroke
                val btnText = if (isHandle) p.handleText else p.buttonText
                val btnTint = if (isHandle) p.handleTint else p.buttonTint

                applyButtonBackground(view, btnBg, btnStroke, p.ripple, dark, btnTint, strokePx, opacity, density, rect.w, rect.h)
                (view as? ImageView)?.setColorFilter(btnText)
            } else {
                view.background?.setBounds(0, 0, rect.w, rect.h)
            }

            val maxIconPx = (minOf(rect.w, rect.h) - iconMarginPx).coerceAtLeast(minIconPx)
            val iconPx = minOf(standardIconPx, maxIconPx).coerceAtLeast(minIconPx)
            val padX = ((rect.w - iconPx) / 2).coerceAtLeast(0)
            val padY = ((rect.h - iconPx) / 2).coerceAtLeast(0)
            if (view.paddingLeft != padX || view.paddingTop != padY || view.paddingRight != padX || view.paddingBottom != padY) {
                view.setPadding(padX, padY, padX, padY)
            }
        }
        root.requestLayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addMiniDot() {
        if (prefs.hideFloatingBall) {
            panel = null
            panelParams = null
            return
        }
        val size = dp(prefs.floatingBallSizeDp.coerceIn(FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP))
        val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
            ?: getAccentColor(isDarkTheme())
        val light = Color.luminance(ballColor) > 0.55f

        val ballSize = (size * 0.86f).roundToInt()
        val halo = (ballSize * 0.06f).roundToInt().coerceAtLeast(dp(1))

        // 外圈一道半透明光晕，在深/浅背景上都能看清；内圈是带细描边的实色圆
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((ballColor and 0x00FFFFFF) or 0x40000000)
        }
        val disc = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(lighten(ballColor, 0.18f), ballColor, darken(ballColor, 0.06f)),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, dp(1)), if (light) 0x33141413 else 0x55FAF9F5)
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
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        bg.isPressed = false
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
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

    private fun darken(color: Int, amount: Float): Int {
        fun ch(c: Int) = (c * (1f - amount)).roundToInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    // ───────────────────────── 外观 ─────────────────────────

    private fun palette(dark: Boolean): TouchpadPalette =
        resolveTouchpadMaterialPalette(
            context = this,
            customColor = prefs.touchpadColor,
            isDark = dark,
            uiAccentColor = prefs.uiAccentColor,
            dynamicColor = prefs.dynamicColor,
        )

    /** 复用并原地更新气泡按钮的 RippleDrawable/BubbleDrawable，首次不存在时才创建。 */
    private fun applyButtonBackground(
        view: View,
        color: Int,
        stroke: Int,
        ripple: Int,
        dark: Boolean,
        glassTint: Int,
        strokeWidthPx: Float,
        opacityPercent: Int,
        density: Float,
        width: Int,
        height: Int,
    ) {
        val existing = view.background as? RippleDrawable
        val existingContent = existing?.let { rd ->
            (0 until rd.numberOfLayers).firstNotNullOfOrNull { idx ->
                rd.getDrawable(idx) as? BubbleDrawable
            }
        }
        if (existing != null && existingContent != null) {
            existing.setColor(ColorStateList.valueOf(ripple))
            existingContent.updateStyle(
                baseColor = color,
                strokeColor = stroke,
                strokeWidthPx = strokeWidthPx,
                opacityPercent = opacityPercent,
                dark = dark,
                glassTint = glassTint,
                density = density,
            )
            if (width > 0 && height > 0) {
                existing.setBounds(0, 0, width, height)
            }
            return
        }
        val bg = buttonBackground(color, stroke, ripple, dark, glassTint, strokeWidthPx, opacityPercent, density)
        if (width > 0 && height > 0) {
            bg.setBounds(0, 0, width, height)
        }
        view.background = bg
    }

    /** 饱满气泡按钮背景：高不透明度呈 Material You，中低不透明度呈 Apple 液态玻璃，带按压水波纹反馈。 */
    private fun buttonBackground(
        color: Int,
        stroke: Int,
        ripple: Int,
        dark: Boolean = isDarkTheme(),
        glassTint: Int = color,
        strokeWidthPx: Float = dp(1).toFloat(),
        opacityPercent: Int = prefs.opacityPercent,
        density: Float = resources.displayMetrics.density,
    ): Drawable {
        val mask = BubbleDrawable(
            baseColor = Color.WHITE,
            strokeColor = Color.TRANSPARENT,
            strokeWidthPx = 0f,
            opacityPercent = 100,
            isMask = true,
        )
        val content = BubbleDrawable(
            baseColor = color,
            strokeColor = stroke,
            strokeWidthPx = strokeWidthPx,
            opacityPercent = opacityPercent,
            isMask = false,
            dark = dark,
            glassTint = glassTint,
            density = density,
        )
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeHandle(iconRes: Int, onTouch: (View, MotionEvent) -> Boolean): View =
        ImageView(this).apply {
            cameraDistance = 1400f * resources.displayMetrics.density
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
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

    private fun makeActionButton(slot: Int, action: PadAction): View = ImageView(this).apply {
        cameraDistance = 1400f * resources.displayMetrics.density
        setImageResource(action.iconRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isHapticFeedbackEnabled = false
        contentDescription = getString(action.labelRes)
        attachActionButtonBehavior(this, slot, action)
    }

    @SuppressLint("ClickableViewAccessibility")
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
            if (cachedPressFeedback && !isPassThroughActive) {
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
                    syncScreenBoundsIfChanged(rebuildNow = false)
                    isDown = true
                    longPressFired = false
                    downX = event.x
                    downY = event.y
                    v.isPressed = true
                    v.drawableHotspotChanged(event.x, event.y)
                    if (cachedPressFeedback) {
                        PressFeedback.applyButtonDown(
                            v, event,
                            maxTiltX = 8f, maxTiltY = 12f,
                            pressScale = 0.92f, sinkDp = 3f,
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
                    if (cachedPressFeedback) {
                        PressFeedback.applyButtonMove(
                            v, event,
                            maxTiltX = 8f, maxTiltY = 12f,
                            pressScale = 0.92f, sinkDp = 3f,
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    val wasDown = isDown
                    isDown = false
                    v.isPressed = false
                    if (cachedPressFeedback) {
                        PressFeedback.applyButtonRelease(v)
                    }
                    if (longPressFired) {
                        longPressFired = false
                        flushPendingBoundsRebuild()
                        return@setOnTouchListener true
                    }
                    if (wasDown) {
                        val inside = event.x in 0f..w && event.y in 0f..h
                        if (inside) {
                            v.performClick()
                            performAction(action)
                        }
                    }
                    flushPendingBoundsRebuild()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    isDown = false
                    longPressFired = false
                    v.isPressed = false
                    if (cachedPressFeedback) {
                        PressFeedback.applyButtonRelease(v)
                    }
                    flushPendingBoundsRebuild()
                    true
                }

                else -> false
            }
        }
    }

    // ───────────────────────── 按钮长按换动作 ─────────────────────────

    private fun showActionPicker(slot: Int, current: PadAction) {
        dismissActiveDialog()
        val dark = isDarkTheme()
        val accent = getAccentColor(dark)
        val dialog = ActionPicker.build(this, dark, current, actions = PadAction.TOUCHPAD_ACTIONS, accentColor = accent) { picked ->
            val list = replaceSlot(prefs.buttons, slot, picked, prefs.buttonCount)
            if (list == null) {
                toast(getString(R.string.need_move_key))
                return@build
            }
            prefs.buttons = list
            buildPanel()
        }
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        activeDialog = dialog
        runCatching { dialog.show() }.onFailure { activeDialog = null }
    }

    // ───────────────────────── 触控板手势 ─────────────────────────

    private var padCornerRadius = 0f
    /**
     * 这次触摸是从触控板边缘那一圈（含圆角外侧）开始的：整段忽略，防止按旁边按钮时误碰。
     * v0.5.3 及以前，触控板 View 的整个矩形（包括圆角外侧的四个小角）都响应，紧挨着按钮只隔一个按钮间距。
     */
    private var padTouchIgnored = false

    private fun handlePadTouch(e: MotionEvent) {
        if (isPassThroughActive) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                padFingerDown = false
                flushPendingBoundsRebuild()
            }
            return
        }
        val slop = dp(6).toFloat()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            syncScreenBoundsIfChanged(rebuildNow = false)
            val pad = padArea
            padTouchIgnored = pad != null && !insideTouchArea(
                e.x, e.y, pad.width.toFloat(), pad.height.toFloat(), padCornerRadius,
                dp(cachedPadEdgeDeadZoneDp).toFloat(),
            )
            refreshPanelScreenLocation()
        }
        if (padTouchIgnored) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                padTouchIgnored = false
                flushPendingBoundsRebuild()
            }
            return
        }
        val panelX = panelLocationOnScreen[0]
        val panelY = panelLocationOnScreen[1]
        val (normX, normY) = if (padControlRect.w > 0 && padControlRect.h > 0) {
            resolvePadNormalizedCoords(e.rawX, e.rawY, panelX, panelY, padControlRect.x, padControlRect.y, padControlRect.w, padControlRect.h)
        } else {
            val pad = padArea
            val w = pad?.width?.toFloat()?.coerceAtLeast(1f) ?: 1f
            val h = pad?.height?.toFloat()?.coerceAtLeast(1f) ?: 1f
            Pair(((e.x - w / 2f) / (w / 2f)).coerceIn(-1f, 1f), ((e.y - h / 2f) / (h / 2f)).coerceIn(-1f, 1f))
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padFingerDown = true
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadDown(pad, normX, normY, resources.displayMetrics.density)
                    }
                }
                downRawX = e.rawX; downRawY = e.rawY
                lastRawX = e.rawX; lastRawY = e.rawY
                downTime = System.currentTimeMillis()
                moved = false; longPressFired = false
                customSwipeArmed = false
                customSwipeStartX = cursorX
                customSwipeStartY = cursorY
                customSwipeEndX = cursorX
                customSwipeEndY = cursorY
                swipeVelocityTracker.reset()
                main.removeCallbacks(longPressRunnable)
                // 拖拽锁定期间触控板只移动光标，不点击、不长按
                if (!dragging) {
                    if (shouldScheduleLongPress(dragging = false)) {
                        main.postDelayed(longPressRunnable, cachedLongPressMs.toLong())
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadMove(pad, normX, normY, resources.displayMetrics.density)
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
                cursorX = clampGestureCoord(cursorX + dx * cachedSensitivity, screenW)
                cursorY = clampGestureCoord(cursorY + dy * cachedSensitivity, screenH)
                updateCursor()
                if (customSwipeArmed) {
                    customSwipeEndX = cursorX
                    customSwipeEndY = cursorY
                    swipeVelocityTracker.addMovement(SystemClock.uptimeMillis(), cursorX, cursorY)
                    if (cachedTouchRingEnabled) {
                        moveTouchRing(cursorX, cursorY)
                    }
                }
                if (dragging) {
                    appendTrailPoint(dragTrail, cursorX, cursorY, dp(4).toFloat())
                }
            }

            MotionEvent.ACTION_UP -> {
                padFingerDown = false
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadRelease(pad)
                    }
                }
                main.removeCallbacks(longPressRunnable)
                val (vx, vy) = if (customSwipeArmed) {
                    swipeVelocityTracker.computeVelocity(SystemClock.uptimeMillis())
                } else {
                    0f to 0f
                }
                swipeVelocityTracker.reset()
                val distance = kotlin.math.hypot(customSwipeEndX - customSwipeStartX, customSwipeEndY - customSwipeStartY)
                if (dragging) {
                    // 拖拽锁定期间圆环继续跟着光标，等第二次按拖拽锁定再收缩
                    customSwipeArmed = false
                    flushPendingBoundsRebuild()
                    return
                }
                val minSwipeDist = dp(CUSTOM_SWIPE_MIN_DISTANCE_DP).toFloat()
                val outcome = resolvePadTouchOutcome(longPressFired, moved, distance, minSwipeDist)
                // 松手即收缩：触控板长按松手时圆环立即收缩，不再在松手后强行多停留
                if (customSwipeArmed) touchRing?.release()
                when (outcome) {
                    PadTouchOutcome.CUSTOM_SWIPE -> customSwipeAt(
                        customSwipeStartX,
                        customSwipeStartY,
                        customSwipeEndX,
                        customSwipeEndY,
                        vx,
                        vy,
                    )
                    PadTouchOutcome.LONG_PRESS -> longPressAt(cursorX, cursorY, showFeedbackRing = false)
                    PadTouchOutcome.CLICK -> tapOrDouble()
                    PadTouchOutcome.MOVE_ONLY -> Unit
                }
                customSwipeArmed = false
                flushPendingBoundsRebuild()
            }

            MotionEvent.ACTION_CANCEL -> {
                padFingerDown = false
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadRelease(pad)
                    }
                }
                main.removeCallbacks(longPressRunnable)
                swipeVelocityTracker.reset()
                if (customSwipeArmed) touchRing?.release()
                customSwipeArmed = false
                flushPendingBoundsRebuild()
            }
        }
    }

    private fun refreshPanelScreenLocation() {
        val p = panel
        val lp = panelParams
        if (p != null && p.isAttachedToWindow) {
            p.getLocationOnScreen(panelLocationOnScreen)
        } else {
            panelLocationOnScreen[0] = lp?.x ?: 0
            panelLocationOnScreen[1] = lp?.y ?: 0
        }
    }

    private fun tapOrDouble() {
        // The first tap has already been dispatched on release. Dispatching two
        // more gestures for the second release would turn a double tap into
        // three taps.
        tapAt(cursorX, cursorY)
        haptic()
    }

    private fun setBubbleViewTilt(view: View, normX: Float, normY: Float) {
        when (val bg = view.background) {
            is BubbleDrawable -> bg.setInteractiveTilt(normX, normY)
            is RippleDrawable -> {
                for (i in 0 until bg.numberOfLayers) {
                    (bg.getDrawable(i) as? BubbleDrawable)?.setInteractiveTilt(normX, normY)
                }
            }
        }
    }

    private val panelDeformFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!panelDeformRunning) return
            val root = panel as? DeformablePanelLayout
            if (root == null || root === miniBallView) {
                stopPanelDeformation(resetVisuals = false)
                return
            }

            val prevFrameNs = panelDeformLastFrameNs
            panelDeformLastFrameNs = frameTimeNanos
            val frameDt = if (prevFrameNs > 0L) {
                ((frameTimeNanos - prevFrameNs) / 1_000_000_000f).coerceIn(0.004f, 0.040f)
            } else {
                1f / 60f
            }

            val nowMs = SystemClock.uptimeMillis()
            val hasPendingMove = movingPanel && (movePendingDx != 0f || movePendingDy != 0f)
            if (movingPanel) {
                if (hasPendingMove) {
                    val sampleGapNs = if (moveLastSampleNs > 0L) frameTimeNanos - moveLastSampleNs else 0L
                    val sampleDt = resolveDeformSampleDt(sampleGapNs, frameDt)
                    val rawVx = movePendingDx / sampleDt
                    val rawVy = movePendingDy / sampleDt
                    movePendingDx = 0f
                    movePendingDy = 0f
                    moveLastSampleNs = frameTimeNanos

                    val alpha = computeVrrEmaAlpha(sampleDt, 0.032f)
                    moveSmoothedVx += (rawVx - moveSmoothedVx) * alpha
                    moveSmoothedVy += (rawVy - moveSmoothedVy) * alpha
                } else if (nowMs - moveLastEventTimeMs >= resolveDeformDecayDelayMs(frameDt)) {
                    val decay = kotlin.math.exp(-frameDt / 0.045f)
                    moveSmoothedVx *= decay
                    moveSmoothedVy *= decay
                    if (abs(moveSmoothedVx) < 1f && abs(moveSmoothedVy) < 1f) {
                        moveSmoothedVx = 0f
                        moveSmoothedVy = 0f
                    }
                }
            } else {
                movePendingDx = 0f
                movePendingDy = 0f
                moveSmoothedVx = 0f
                moveSmoothedVy = 0f
            }

            val target = if (movingPanel) {
                computePanelDragTarget(moveSmoothedVx, moveSmoothedVy, resources.displayMetrics.density)
            } else {
                PanelDragTarget(0f, 0f)
            }
            val targetGrab = if (movingPanel) 1f else 0f

            panelSpringState = stepPanelDragSpring(
                state = panelSpringState,
                targetX = target.x,
                targetY = target.y,
                targetGrab = targetGrab,
                dtSec = frameDt,
                isDragging = movingPanel,
            )

            val w = root.width.toFloat().takeIf { it > 0f } ?: (panelParams?.width?.toFloat() ?: 0f)
            val h = root.height.toFloat().takeIf { it > 0f } ?: (panelParams?.height?.toFloat() ?: 0f)
            val active = computePanelDragMatrix(
                outMatrix = panelDeformMatrixValues,
                widthPx = w,
                heightPx = h,
                springX = panelSpringState.x,
                springY = panelSpringState.y,
                grabProgress = panelSpringState.grab,
                anchorX = moveAnchorX,
                anchorY = moveAnchorY,
            )
            root.setDeformationMatrix(panelDeformMatrixValues, active, movingHandleView)

            // 同步更新触控板表面与移动把手的动态流光（移动前缘提亮、尾缘微暗）
            val mag = kotlin.math.hypot(panelSpringState.x, panelSpringState.y)
            val tiltX = (-panelSpringState.x).coerceIn(-1f, 1f)
            val tiltY = (-panelSpringState.y).coerceIn(-1f, 1f)
            if (!padFingerDown) {
                (padArea as? TouchpadSurfaceView)?.setTilt(tiltX, tiltY, (mag * 0.65f).coerceIn(0f, 0.8f))
            }
            val shouldPause = movingPanel && !hasPendingMove &&
                abs(moveSmoothedVx) < 0.5f && abs(moveSmoothedVy) < 0.5f &&
                isPanelSpringAtRestForTarget(panelSpringState, target.x, target.y, targetGrab)
            if (shouldPause || shouldUpdateAmbientGlassShaders(nowMs, lastMoveHandleTiltMs, 1f, 1f, 13L)) {
                lastMoveHandleTiltMs = nowMs
                movingHandleView?.let { setBubbleViewTilt(it, tiltX, tiltY) }
            }

            if (!movingPanel && panelSpringState.isSettled) {
                stopPanelDeformation(resetVisuals = true)
                return
            }
            // 按住移动把手但手指完全停顿且弹簧已收敛时，暂停 Choreographer 空转，等待下次移动或松手再唤醒
            if (shouldPause) {
                panelDeformRunning = false
                panelDeformLastFrameNs = 0L
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startPanelDeformationLoop() {
        if (panelDeformRunning) return
        panelDeformRunning = true
        panelDeformLastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(panelDeformFrameCallback)
    }

    private fun stopPanelDeformation(resetVisuals: Boolean) {
        if (panelDeformRunning) {
            panelDeformRunning = false
            runCatching { Choreographer.getInstance().removeFrameCallback(panelDeformFrameCallback) }
        }
        panelDeformLastFrameNs = 0L
        lastMoveHandleTiltMs = -1000L
        movePendingDx = 0f
        movePendingDy = 0f
        moveLastSampleNs = 0L
        moveSmoothedVx = 0f
        moveSmoothedVy = 0f
        panelSpringState = PanelSpringState()
        if (resetVisuals) {
            (panel as? DeformablePanelLayout)?.clearDeformation()
            if (!padFingerDown) {
                (padArea as? TouchpadSurfaceView)?.setTilt(0f, 0f, 0f)
            }
            movingHandleView?.let { setBubbleViewTilt(it, 0f, 0f) }
        }
        movingHandleView = null
    }

    private fun handleMoveTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                syncScreenBoundsIfChanged(rebuildNow = false)
                movingPanel = true
                if (movingHandleView !== v) {
                    movingHandleView?.let { setBubbleViewTilt(it, 0f, 0f) }
                    movingHandleView = v
                }
                movingFromX = e.rawX; movingFromY = e.rawY
                movingPanelX = panelParams?.x ?: 0
                movingPanelY = panelParams?.y ?: 0
                moveLastClampedX = movingPanelX.toFloat()
                moveLastClampedY = movingPanelY.toFloat()
                movePendingDx = 0f
                movePendingDy = 0f
                moveSmoothedVx = 0f
                moveSmoothedVy = 0f
                moveLastSampleNs = 0L
                moveLastEventTimeMs = SystemClock.uptimeMillis()
                val panelW = panel?.width?.toFloat()?.takeIf { it > 0f } ?: (panelParams?.width?.toFloat() ?: 0f)
                val panelH = panel?.height?.toFloat()?.takeIf { it > 0f } ?: (panelParams?.height?.toFloat() ?: 0f)
                val (ax, ay) = computePanelHandleAnchor(
                    handleLeft = v.left.toFloat(),
                    handleTop = v.top.toFloat(),
                    handleWidth = v.width.toFloat(),
                    handleHeight = v.height.toFloat(),
                    panelWidth = panelW,
                    panelHeight = panelH,
                )
                moveAnchorX = ax
                moveAnchorY = ay
                startPanelDeformationLoop()
            }
            MotionEvent.ACTION_MOVE -> if (movingPanel) {
                val lp = panelParams ?: return true
                val panelW = panel?.width ?: 0
                val panelH = panel?.height ?: 0
                val maxX = (screenW - panelW.coerceAtLeast(0)).coerceAtLeast(0).toFloat()
                val maxY = (screenH - panelH.coerceAtLeast(0)).coerceAtLeast(0).toFloat()
                val clampedX = (movingPanelX + (e.rawX - movingFromX)).coerceIn(0f, maxX)
                val clampedY = (movingPanelY + (e.rawY - movingFromY)).coerceIn(0f, maxY)
                val position = clampPanelPosition(
                    movingPanelX + (e.rawX - movingFromX).roundToInt(),
                    movingPanelY + (e.rawY - movingFromY).roundToInt(),
                    panelW,
                    panelH,
                    screenW,
                    screenH,
                )
                if (lp.x != position.x || lp.y != position.y) {
                    lp.x = position.x
                    lp.y = position.y
                    pendingPanelLayout = true
                    scheduleLayoutFlush()
                }

                val stepDx = clampedX - moveLastClampedX
                val stepDy = clampedY - moveLastClampedY
                moveLastClampedX = clampedX
                moveLastClampedY = clampedY
                if (stepDx != 0f || stepDy != 0f) {
                    movePendingDx += stepDx
                    movePendingDy += stepDy
                    moveLastEventTimeMs = SystemClock.uptimeMillis()
                    startPanelDeformationLoop()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                movingPanel = false
                movePendingDx = 0f
                movePendingDy = 0f
                moveSmoothedVx = 0f
                moveSmoothedVy = 0f
                flushPendingLayoutsNow()
                panelParams?.let { lp ->
                    prefs.setPadPosition(isLandscape(), lp.x, lp.y)
                }
                startPanelDeformationLoop()
                flushPendingBoundsRebuild()
            }
        }
        return true
    }

    private fun handleResizeTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                syncScreenBoundsIfChanged(rebuildNow = false)
                stopPanelDeformation(resetVisuals = true)
                resizingPanel = true
                resizingFromX = e.rawX
                resizingFromY = e.rawY
                resizingPanelWidth = panel?.width ?: dp(prefs.panelWidthDp.coerceAtLeast(PANEL_MIN_WIDTH_DP))
                resizingPanelHeight = prefs.extraHeightDp
                resizingCurrentWidthDp = prefs.panelWidthDp
                resizingCurrentWidthPercent = prefs.padWidthPercent
                resizingCurrentExtraHeightDp = resizingPanelHeight
                resizingLastAppliedWidth = panelParams?.width ?: resizingPanelWidth
                resizingLastAppliedHeight = panelParams?.height ?: -1
            }
            MotionEvent.ACTION_MOVE -> if (resizingPanel) {
                val density = resources.displayMetrics.density
                val widthRange = controlWidthRange(screenW, density)
                val width = resizePanelWidth(
                    resizingPanelWidth,
                    (e.rawX - resizingFromX).roundToInt(),
                    widthRange.first,
                    widthRange.last,
                )
                // 横向拖动改宽度（按钮跟着缩放），纵向拖动只改触控板的额外高度
                val extraDp = (resizingPanelHeight +
                    ((e.rawY - resizingFromY) / density).roundToInt())
                    .coerceIn(0, CONTROL_EXTRA_HEIGHT_MAX_DP)
                val height = panelHeightFor(width, extraDp)
                if (width == resizingLastAppliedWidth && height == resizingLastAppliedHeight) {
                    return true
                }
                resizingLastAppliedWidth = width
                resizingLastAppliedHeight = height
                val widthDp = pixelsToDp(width, density)
                resizingCurrentWidthDp = widthDp
                resizingCurrentWidthPercent = if (screenW > 0) (width * 100 / screenW).coerceIn(20, 100) else 96
                resizingCurrentExtraHeightDp = extraDp
                panel?.let { panelView ->
                    val root = panelView as? FrameLayout ?: return@let
                    val window = panelParams ?: return@let
                    window.width = width
                    window.height = height
                    root.layoutParams = window
                    // 缩放过程中颜色与透明度未变，跳过 Drawable 重建，仅原地更新布局参数与边距
                    layoutControlChildren(root, width, height, updateAppearance = false)
                    pendingPanelLayout = true
                    scheduleLayoutFlush()
                }
                onPanelResized?.invoke(widthDp, extraDp)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizingPanel) {
                    resizingPanel = false
                    flushPendingLayoutsNow()
                    prefs.setPanelSize(
                        resizingCurrentWidthDp,
                        resizingCurrentWidthPercent,
                        resizingCurrentExtraHeightDp,
                    )
                    panel?.post { keepPanelInBounds() }
                    onPanelResized?.invoke(resizingCurrentWidthDp, resizingCurrentExtraHeightDp)
                }
                flushPendingBoundsRebuild()
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
                if (p.x != clamped.x || p.y != clamped.y) {
                    p.x = clamped.x
                    p.y = clamped.y
                    panel?.let { runCatching { wm.updateViewLayout(it, p) } }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ballSnapAnimator = null
                    val p = panelParams
                    if (p != null && (p.x != targetX || p.y != targetY)) {
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
                val land = isLandscape()
                ballDragStartX = panelParams?.x ?: prefs.getBallX(land).coerceAtLeast(0)
                ballDragStartY = panelParams?.y ?: prefs.getBallY(land).coerceAtLeast(0)
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
                        if (lp.x != position.x || lp.y != position.y) {
                            lp.x = position.x
                            lp.y = position.y
                            pendingPanelLayout = true
                            scheduleLayoutFlush()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                flushPendingLayoutsNow()
                if (!ballMoved) {
                    haptic()
                    toggleMinimize()
                } else {
                    keepBallInBounds()
                }
                flushPendingBoundsRebuild()
            }
            MotionEvent.ACTION_CANCEL -> {
                flushPendingLayoutsNow()
                keepBallInBounds()
                flushPendingBoundsRebuild()
            }
        }
        return true
    }

    private fun handleBallTouch(v: View, e: MotionEvent): Boolean {
        if (isPassThroughActive) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            syncScreenBoundsIfChanged(rebuildNow = false)
        }
        if (!prefs.ballGesturesEnabled) {
            return handleSimpleBallTouch(v, e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ballSnapAnimator?.cancel()
                ballSnapAnimator = null
                resetBallDeformation(animated = false)
                if (ballAnchorX == 0 && ballAnchorY == 0 && (panelParams?.x ?: 0) != 0) {
                    val land = isLandscape()
                    ballAnchorX = panelParams?.x ?: prefs.getBallX(land).coerceAtLeast(0)
                    ballAnchorY = panelParams?.y ?: prefs.getBallY(land).coerceAtLeast(0)
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
                ballDragHoverSettled = true
                ballLongPressTriggered = false
                ballMoved = false
                ballFromX = e.rawX
                ballFromY = e.rawY
                lastDragRawX = e.rawX
                lastDragRawY = e.rawY
                ballDragLastTimeNs = 0L
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
                val holdDuration = resolveBallLongPressHoldMs(prefs.ballLongPressMs)
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
                        if (lp.x != position.x || lp.y != position.y) {
                            lp.x = position.x
                            lp.y = position.y
                            pendingPanelLayout = true
                            scheduleLayoutFlush()
                        }
                    }

                    // 长按移动动效：使用硬件事件时间戳 + 首包播种 + <1.5ms 粘包位移累积 + 时间常数 EMA，
                    // 保证 60/90/120/144/165/240Hz 显示刷新与高触控采样率下手感完全一致、无首帧跳变尖峰
                    val eventTimeNs = motionEventTimeNs(e)
                    if (ballDragLastTimeNs == 0L) {
                        ballDragLastTimeNs = eventTimeNs
                        lastDragRawX = e.rawX
                        lastDragRawY = e.rawY
                    } else {
                        val rawDtNs = eventTimeNs - ballDragLastTimeNs
                        if (rawDtNs >= 1_500_000L) {
                            val dt = (rawDtNs / 1_000_000_000f).coerceIn(0.0015f, 0.100f)
                            val stepDx = e.rawX - lastDragRawX
                            val stepDy = e.rawY - lastDragRawY
                            lastDragRawX = e.rawX
                            lastDragRawY = e.rawY
                            ballDragLastTimeNs = eventTimeNs

                            val rawVx = stepDx / dt
                            val rawVy = stepDy / dt
                            val velAlpha = computeVrrEmaAlpha(dt, 0.032f)
                            ballDragVelocityX += (rawVx - ballDragVelocityX) * velAlpha
                            ballDragVelocityY += (rawVy - ballDragVelocityY) * velAlpha

                            val speed = kotlin.math.hypot(ballDragVelocityX, ballDragVelocityY)
                            if (speed > dp(30)) {
                                if (ballDragHoverSettled) {
                                    ballDragHoverSettled = false
                                    ballBgView?.animate()?.cancel()
                                    ballIconView?.animate()?.cancel()
                                }
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
                                val rotAlpha = computeVrrEmaAlpha(dt, 0.028f)
                                ballDragCurrentRotation = lerpAngleDeg(ballDragCurrentRotation, motionState.rotationDeg, rotAlpha)
                                ballBgView?.rotation = ballDragCurrentRotation
                                ballBgView?.scaleX = motionState.stretch
                                ballBgView?.scaleY = motionState.squash
                                ballIconView?.translationX = motionState.iconTranslationX
                                ballIconView?.translationY = motionState.iconTranslationY
                            } else if (!ballDragHoverSettled) {
                                // 速度降至悬停或微动时仅触发一次平滑回弹至饱满圆形，避免每帧打断重启
                                ballDragHoverSettled = true
                                ballBgView?.animate()?.cancel()
                                ballBgView?.animate()?.rotation(0f)?.scaleX(1.16f)?.scaleY(1.16f)?.setDuration(120)?.start()
                                ballIconView?.animate()?.cancel()
                                ballIconView?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(120)?.start()
                            }
                        }
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
                            if (lp.x != clamped.x || lp.y != clamped.y) {
                                lp.x = clamped.x
                                lp.y = clamped.y
                                pendingPanelLayout = true
                                scheduleLayoutFlush()
                            }
                        }
                        // 悬浮球手势拖拽形变：沿滑动方向弹性拉伸、垂直方向保体积挤压，内核图标视差跟手
                        applyBallDeformation(dx, dy, maxOffset)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(ballLongPressRunnable)
                flushPendingLayoutsNow()
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                val dist = kotlin.math.hypot(dx, dy)
                val swipeThreshold = ballSwipeTracker.swipeThresholdPx

                if (isDraggingBall) {
                    isDraggingBall = false
                    ballDragLastTimeNs = 0L
                    ballDragHoverSettled = true
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
                flushPendingBoundsRebuild()
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(ballLongPressRunnable)
                main.removeCallbacks(ballSingleTapRunnable)
                flushPendingLayoutsNow()
                isWaitingForBallDoubleTap = false
                ballSwipeTracker.reset()
                resetBallDeformation(animated = true, duration = 180L)
                if (isDraggingBall) {
                    isDraggingBall = false
                    ballDragLastTimeNs = 0L
                    ballDragHoverSettled = true
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
                flushPendingBoundsRebuild()
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
        prefs.setPadPosition(isLandscape(), position.x, position.y)
        panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun keepBallInBounds() {
        val lp = panelParams ?: return
        if (miniBallView == null) return
        val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
        val position = clampFloatingBallPosition(lp.x, lp.y, size, screenW, screenH)
        val movedPos = lp.x != position.x || lp.y != position.y
        lp.x = position.x
        lp.y = position.y
        prefs.setBallPosition(isLandscape(), position.x, position.y)
        ballAnchorX = position.x
        ballAnchorY = position.y
        if (movedPos) {
            panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
        }
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

    /** 具名可取消的 20ms 延迟恢复任务，杜绝连续穿透点击时前一次恢复覆盖下一次穿透态。 */
    private val delayedRestoreTouchRunnable = Runnable {
        setOverlayTouchable(true)
    }

    private var pendingPassThroughDispatchRunnable: Runnable? = null

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
        pendingPanelLayout = false
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun isPointInsidePanel(x: Float, y: Float): Boolean {
        val lp = panelParams ?: return false
        val p = panel ?: return false
        val w = if (p.width > 0) p.width else lp.width
        val h = if (p.height > 0) p.height else lp.height
        refreshPanelScreenLocation()
        return isPointInsideRect(x, y, panelLocationOnScreen[0], panelLocationOnScreen[1], w, h)
    }

    /**
     * 顺序派发注入手势：
     * 1. 仅当光标位于触控板面板范围内时才短暂切换 FLAG_NOT_TOUCHABLE，让光标能精准点击触控板下层内容；
     * 2. 穿透期间锁定 isPassThroughActive 状态，触控板按钮、把手与触控板自身全面拦截任何点击与触摸反馈，彻底杜绝误触；
     * 3. 光标在面板外部（绝大多数常规操作场景）时，面板始终保持可触摸状态，快速连续点击绝不会击穿面板触发底层内容；
     * 4. 使用手势队列串行化派发，并取消上一笔延迟恢复回调，彻底消除快速连续穿透点击时的状态竞态与续接手势空等。
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
            // 取消上一笔手势可能尚未执行的 20ms 恢复回调与兜底定时，杜绝恢复竞态
            main.removeCallbacks(delayedRestoreTouchRunnable)
            main.removeCallbacks(restoreTouchRunnable)
            pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
            pendingPassThroughDispatchRunnable = null

            val alreadyNonTouchable = isPassThroughActive &&
                ((panelParams?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
            setOverlayTouchable(false)
            // 兜底保护：手势预期耗时 + 1.5 秒后无论如何恢复面板可触摸，防止异常时卡在不可触摸状态
            val fallbackTimeout = maxOf(3000L, item.expectedMs + 1500L)
            main.postDelayed(restoreTouchRunnable, fallbackTimeout)

            val runDispatch = Runnable {
                pendingPassThroughDispatchRunnable = null
                val ok = runCatching {
                    dispatchGesture(item.gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            main.removeCallbacks(restoreTouchRunnable)
                            val nextNeedsPassThrough = gestureQueue.firstOrNull()?.needsPassThrough == true
                            if (!item.keepPassThroughAfterComplete && !nextNeedsPassThrough) {
                                // 延迟 20ms 恢复可触摸，确保注入手势的 UP 事件在底层应用中完全处理完毕
                                main.removeCallbacks(delayedRestoreTouchRunnable)
                                main.postDelayed(delayedRestoreTouchRunnable, 20L)
                            }
                            isDispatching = false
                            runCatching { item.onComplete?.invoke() }
                            drainGestureQueue()
                            if (item.keepPassThroughAfterComplete && !isDispatching && gestureQueue.isEmpty()) {
                                setOverlayTouchable(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            main.removeCallbacks(restoreTouchRunnable)
                            main.removeCallbacks(delayedRestoreTouchRunnable)
                            setOverlayTouchable(true)
                            isDispatching = false
                            drainGestureQueue()
                        }
                    }, main)
                }.getOrDefault(false)
                if (!ok) {
                    main.removeCallbacks(restoreTouchRunnable)
                    main.removeCallbacks(delayedRestoreTouchRunnable)
                    setOverlayTouchable(true)
                    isDispatching = false
                    drainGestureQueue()
                }
            }

            if (shouldSkipPassThroughDelay(alreadyNonTouchable)) {
                // 面板已处于 FLAG_NOT_TOUCHABLE（例如拖拽 continueStroke 续接或连续穿透点击），立即派发零间隙
                runDispatch.run()
            } else {
                pendingPassThroughDispatchRunnable = runDispatch
                main.postDelayed(runDispatch, OVERLAY_PASS_THROUGH_DELAY_MS)
            }
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
        val sx = clampGestureCoord(x, screenW)
        val sy = clampGestureCoord(y, screenH)
        if (showFeedbackRing) showTouchRing(sx, sy, ms)
        val overPanel = isPointInsidePanel(sx, sy)
        val path = Path().apply { moveTo(sx, sy) }
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

    private fun customSwipeAt(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ) {
        val minDistance = dp(CUSTOM_SWIPE_MIN_DISTANCE_DP).toFloat()
        val plan = planCustomSwipe(
            startX = clampGestureCoord(startX, screenW),
            startY = clampGestureCoord(startY, screenH),
            endX = clampGestureCoord(endX, screenW),
            endY = clampGestureCoord(endY, screenH),
            velocityX = velocityX,
            velocityY = velocityY,
            minDistancePx = minDistance,
            density = resources.displayMetrics.density,
            screenWidth = screenW,
            screenHeight = screenH,
        ) ?: return

        val overPanel = isPointInsidePanel(plan.startX, plan.startY) ||
            isPointInsidePanel(plan.endX, plan.endY) ||
            isPointInsidePanel(plan.targetX, plan.targetY)

        val path = Path().apply {
            moveTo(plan.startX, plan.startY)
            if (plan.isInertial && (plan.targetX != plan.endX || plan.targetY != plan.endY)) {
                lineTo(plan.endX, plan.endY)
                lineTo(plan.targetX, plan.targetY)
            } else {
                lineTo(plan.endX, plan.endY)
            }
        }

        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, plan.durationMs))
                .build(),
            needsPassThrough = overPanel,
            expectedMs = plan.durationMs,
        )
        haptic()
    }

    private fun swipeBy(dx: Float, dy: Float, ms: Long) {
        val x0 = clampGestureCoord(cursorX, screenW)
        val y0 = clampGestureCoord(cursorY, screenH)
        val x1 = clampGestureCoord(x0 + dx, screenW)
        val y1 = clampGestureCoord(y0 + dy, screenH)
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
        if (cachedTouchRingEnabled) {
            showTouchRing(cursorX, cursorY, 180)
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
        if (cachedTouchRingEnabled) {
            showTouchRing(cursorX, cursorY, 180)
        }
        cancelDrag()
        val start = trail.firstOrNull() ?: return
        // 没移动就再按一次 = 取消
        if (trailLength(trail) < dp(8)) return
        val startX = clampGestureCoord(start.x, screenW)
        val startY = clampGestureCoord(start.y, screenH)
        val endX = clampGestureCoord(cursorX, screenW)
        val endY = clampGestureCoord(cursorY, screenH)
        val overPanel = isPointInsidePanel(startX, startY) || isPointInsidePanel(endX, endY)
        val hold = prefs.cursorHoldMs.toLong()
        val moveMs = dragMoveDurationMs(trailLength(trail))
        val holdPath = Path().apply { moveTo(startX, startY) }
        val holdStroke = GestureDescription.StrokeDescription(holdPath, 0, hold, true)
        val movePath = Path().apply {
            moveTo(startX, startY)
            trail.drop(1).forEach {
                lineTo(clampGestureCoord(it.x, screenW), clampGestureCoord(it.y, screenH))
            }
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
        resolveTouchRingColor(prefs.touchRingColor, isDarkTheme(), getAccentColor(isDarkTheme()))

    /** 常驻的反馈圆环窗口（透明、不可触摸，空闲时自动隐藏），只移动位置，不反复 add/remove。 */
    private fun ensureTouchRing(): Pair<TouchRingView, WindowManager.LayoutParams>? {
        touchRing?.let { v ->
            touchRingParams?.let { lp ->
                if (touchRingNeedsRaise) {
                    touchRingNeedsRaise = false
                    runCatching { wm.removeViewImmediate(v) }
                    runCatching { wm.addView(v, lp) }
                }
                return v to lp
            }
        }
        val radius = dp(TOUCH_RING_RADIUS_DP).toFloat()
        val stroke = (2.5f * resources.displayMetrics.density)
        // 预留充分 padding 容纳高斯动态模糊光晕，确保光晕在窗口内完全自然羽化衰减至 0，绝不发生窗口边缘裁切
        val blurPadding = dp(16)
        val size = ((radius + stroke) * 2).roundToInt() + blurPadding * 2
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
            applyCutoutMode(this)
            x = -size; y = -size
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) return null
        touchRingNeedsRaise = false
        touchRing = v
        touchRingParams = lp
        return v to lp
    }

    private fun moveTouchRing(x: Float, y: Float, immediate: Boolean = false) {
        val (v, lp) = ensureTouchRing() ?: return
        val nx = (x - lp.width / 2f).roundToInt()
        val ny = (y - lp.height / 2f).roundToInt()
        if (lp.x == nx && lp.y == ny) return
        lp.x = nx
        lp.y = ny
        if (immediate) {
            pendingRingLayout = false
            runCatching { wm.updateViewLayout(v, lp) }
        } else {
            pendingRingLayout = true
            scheduleLayoutFlush()
        }
    }

    /**
     * 光标处点击 / 长按的视觉反馈：圆环从内向外展开，结束时从外向内收缩消失。
     * [holdMs] < 250 视为点击，否则圆环保持展开直到按住结束。
     */
    private fun showTouchRing(x: Float, y: Float, holdMs: Long = 0L) {
        if (!cachedTouchRingEnabled) return
        moveTouchRing(x, y, immediate = true)
        val v = touchRing ?: return
        if (holdMs >= 250) v.press(holdMs) else v.tap()
    }

    private fun removeTouchRing() {
        pendingRingLayout = false
        touchRingNeedsRaise = false
        touchRing?.let { v ->
            v.clear()
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        }
        touchRing = null
        touchRingParams = null
    }

    /** 拖拽进行中，面板上的「拖拽锁定」按钮高亮为强调色，提示拖拽已激活、再按一次结束。 */
    private fun refreshDragButtons(
        precomputedPalette: TouchpadPalette? = null,
        precomputedDark: Boolean? = null,
    ) {
        if (actionViews.isEmpty()) return
        val buttons = prefs.buttons
        var hasDragButton = false
        for (index in actionViews.indices) {
            val slot = actionSlots.getOrNull(index) ?: continue
            if (buttons.getOrNull(slot) == PadAction.DRAG_LOCK) {
                hasDragButton = true
                break
            }
        }
        if (!hasDragButton) return

        val dark = precomputedDark ?: isDarkTheme()
        val p = precomputedPalette ?: palette(dark)
        val accent = getAccentColor(dark)
        val accentText = resolveOnAccentColor(accent)
        val (_, accentSoftStrong) = resolveUiAccentSoftColors(accent, dark)
        val strokePx = dp(1).toFloat()
        val opacity = prefs.opacityPercent
        val density = resources.displayMetrics.density

        actionViews.forEachIndexed { index, view ->
            val slot = actionSlots.getOrNull(index) ?: return@forEachIndexed
            if (buttons.getOrNull(slot) != PadAction.DRAG_LOCK) return@forEachIndexed
            val iv = view as? ImageView ?: return@forEachIndexed
            if (dragging) {
                iv.setColorFilter(accentText)
                applyButtonBackground(iv, accent, accentSoftStrong, p.ripple, dark, accent, strokePx, opacity, density, iv.width, iv.height)
            } else {
                iv.setColorFilter(p.buttonText)
                applyButtonBackground(iv, p.buttonBg, p.buttonStroke, p.ripple, dark, p.buttonTint, strokePx, opacity, density, iv.width, iv.height)
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

    /** 让输入法弹出来：把焦点交给当前界面里的输入框（限深限节点数遍历防大型 WebView 卡顿）。 */
    private fun focusEditable() {
        val root = rootInActiveWindow
        if (root == null) {
            toast(getString(R.string.no_input_field))
            return
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = if (focused != null && focused.isEditable) {
            focused
        } else {
            recycleNodeLegacy(focused)
            findEditableBounded(root, maxNodes = 250, maxDepth = 24)
        }
        if (target == null) {
            recycleNodeLegacy(root)
            toast(getString(R.string.no_input_field))
            return
        }
        runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (target !== root) recycleNodeLegacy(target)
        recycleNodeLegacy(root)
    }

    private fun findEditableBounded(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        maxDepth: Int,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (node.isEditable && node.isVisibleToUser) {
                while (queue.isNotEmpty()) {
                    val rem = queue.removeFirst().first
                    if (rem !== root) recycleNodeLegacy(rem)
                }
                return node
            }
            if (depth < maxDepth) {
                val count = node.childCount
                for (i in 0 until count) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to (depth + 1))
                }
            }
            if (node !== root) recycleNodeLegacy(node)
        }
        while (queue.isNotEmpty()) {
            val rem = queue.removeFirst().first
            if (rem !== root) recycleNodeLegacy(rem)
        }
        return null
    }

    private fun recycleNodeLegacy(node: AccessibilityNodeInfo?) {
        if (node != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (prefs.excludeFromRecents) {
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
        runCatching { startActivity(intent) }
    }

    private fun isImeVisible(): Boolean = runCatching {
        val list = windows ?: return false
        val minImeHeight = dp(48)
        var visible = false
        for (i in 0 until list.size) {
            val w = list[i]
            if (!visible && w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                w.getBoundsInScreen(imeBoundsRect)
                if (isEffectiveImeBounds(
                        imeBoundsRect.left,
                        imeBoundsRect.top,
                        imeBoundsRect.right,
                        imeBoundsRect.bottom,
                        minImeHeight,
                    )
                ) {
                    visible = true
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                runCatching { w.recycle() }
            }
        }
        visible
    }.getOrDefault(false)

    // ───────────────────────── 小工具 ─────────────────────────

    private fun updateCursor(immediate: Boolean = false) {
        val p = cursorParams ?: return
        val origin = cursorViewOrigin(cursorX, cursorY, p.width.toFloat())
        if (!immediate && p.x == origin.x && p.y == origin.y) return
        p.x = origin.x
        p.y = origin.y
        if (immediate) {
            pendingCursorLayout = false
            cursorView?.let { runCatching { wm.updateViewLayout(it, p) } }
        } else {
            pendingCursorLayout = true
            scheduleLayoutFlush()
        }
    }

    private fun haptic(view: View? = null) {
        if (!cachedHaptics) return
        runCatching {
            (view ?: miniBallView ?: padArea ?: panel)?.performHapticFeedback(
                android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                @Suppress("DEPRECATION")
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
