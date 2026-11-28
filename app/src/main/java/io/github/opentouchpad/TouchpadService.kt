package io.github.opentouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
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
import android.graphics.Region
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
import android.view.ViewConfiguration
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

@TargetApi(Build.VERSION_CODES.TIRAMISU)
private object Api33TouchableRegion {
    fun apply(view: View, region: Region?): Boolean = runCatching {
        val sc = view.rootSurfaceControl ?: return false
        sc.setTouchableRegion(region)
        true
    }.getOrDefault(false)
}

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
        /** 反馈光环最大扩散半径（dp）：适配旋转光弧与四散三角粒子比例。 */
        private const val TOUCH_RING_RADIUS_DP = 16
        /** 穿透点击前等待 FLAG_NOT_TOUCHABLE 真正同步到系统 InputDispatcher 的安全延时（毫秒）。 */
        private const val OVERLAY_PASS_THROUGH_DELAY_MS = 50L
        /** 待派发非续接手势队列上限，防止极端连点造成积压。 */
        private const val MAX_GESTURE_QUEUE_SIZE = 8
        private const val NOTIFICATION_CHANNEL_ID = "opentouchpad_keepalive"
        private const val NOTIFICATION_ID_KEEPALIVE = 2001
        /** 面板收起/隐藏后延迟释放触控板自定义底图的防抖时长（避开 220ms 退场动画与快速反复切换）。 */
        private const val TOUCHPAD_BG_RELEASE_DELAY_MS = 4000L
        /** 滑块调整面板尺寸停止后按需升采样触控板底图的防抖时长。 */
        private const val TOUCHPAD_BG_UPSAMPLE_DEBOUNCE_MS = 200L

        private val OVERSHOOT_125 = OvershootInterpolator(1.25f)
        private val OVERSHOOT_130 = OvershootInterpolator(1.3f)
        private val OVERSHOOT_135 = OvershootInterpolator(1.35f)
        private val OVERSHOOT_140 = OvershootInterpolator(1.4f)
        private val OVERSHOOT_160 = OvershootInterpolator(1.6f)

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
    private var cachedPointerAccelEnabled = false
    private var cachedPointerAccelMinMul = POINTER_ACCEL_DEFAULT_MIN_MULTIPLIER
    private var cachedPointerAccelMaxMul = POINTER_ACCEL_DEFAULT_MAX_MULTIPLIER
    private var cachedPointerAccelThresholdDpPerSec = POINTER_ACCEL_DEFAULT_THRESHOLD_DP_PER_SEC
    private var cachedPressFeedback = true
    private var cachedTouchRingEnabled = true
    private var cachedPadEdgeDeadZoneDp = PAD_EDGE_DEAD_ZONE_DEFAULT_DP
    private var cachedLongPressMs = 600
    private var cachedHaptics = true
    private var cachedEdgeHaptics = true
    private var cachedHasTouchpadBg = false
    private var cachedScaledTouchSlopPx = 0f

    // VSync 合并提交 WindowManager.updateViewLayout，消除 240Hz/360Hz 触控下的冗余 Binder IPC 风暴
    private var pendingCursorLayout = false
    private var pendingRingLayout = false
    private var pendingPanelLayout = false
    private var pendingTouchableRegionUpdate = false
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

    private var lastPanelScreenW = 0
    private var lastPanelScreenH = 0
    private var lastPanelLandscape = false
    private var lastPanelDark = false
    private var lastPanelDensity = 0f
    private var actionButtonDownCount = 0
    private var activeButtonCancelCallback: (() -> Unit)? = null
    private var pendingBoundsRebuild = false
    private var pendingKeyboardMinimizeSync = false
    private val deferredBoundsCheckRunnable = Runnable {
        if (!shouldDeferRebuildNow()) {
            syncScreenBoundsIfChanged(rebuildNow = true)
        } else {
            syncScreenBoundsIfChanged(rebuildNow = false)
        }
    }
    private val flushBoundsRebuildRunnable = Runnable {
        if (shouldDeferRebuildNow()) return@Runnable
        if (pendingBoundsRebuild) {
            pendingBoundsRebuild = false
            pendingKeyboardMinimizeSync = false
            buildPanel()
            syncCursorSizeForCurrentDensity()
            updateCursor(immediate = true)
            syncKeyboardMinimize()
        } else if (pendingKeyboardMinimizeSync) {
            pendingKeyboardMinimizeSync = false
            syncKeyboardMinimize()
        }
    }

    private fun shouldDeferRebuildNow(): Boolean = shouldDeferPanelRebuild(
        padFingerDown = padFingerDown,
        movingPanel = movingPanel,
        resizingPanel = resizingPanel,
        isDraggingBall = isDraggingBall,
        ballTouchDown = ballTouchDown,
        isWaitingForBallDoubleTap = isWaitingForBallDoubleTap,
        actionButtonDownCount = actionButtonDownCount,
        isDispatchingGesture = isDispatching || pendingPassThroughDispatchRunnable != null,
        isPassThroughActive = isPassThroughActive,
    )

    /**
     * 惰性同步屏幕尺寸：替代常驻 DisplayListener，彻底消除 LTPO 屏幕每次 120↔90↔60↔30↔10↔1Hz
     * 自动换档时唤醒服务主线程；在 ACTION_DOWN 传 rebuildNow = false 仅更新边界不毁窗，
     * 待手势与派发彻底结束后再异步安全重建，杜绝手势中途或 ACTION_UP 同步撕毁接收触摸的窗口。
     */
    private fun syncScreenBoundsIfChanged(rebuildNow: Boolean) {
        if (!::wm.isInitialized || !::prefs.isInitialized) return
        val oldW = screenW
        val oldH = screenH
        refreshScreenMetrics()
        if (screenW != oldW || screenH != oldH) {
            cursorX = clampGestureCoord(cursorX, screenW)
            cursorY = clampGestureCoord(cursorY, screenH)
            edgeHapticTracker.syncInitialPosition(
                cursorX,
                cursorY,
                (screenW - 1).coerceAtLeast(0).toFloat(),
                (screenH - 1).coerceAtLeast(0).toFloat(),
            )
            if (rebuildNow && !shouldDeferRebuildNow()) {
                pendingBoundsRebuild = false
                main.removeCallbacks(flushBoundsRebuildRunnable)
                buildPanel()
                syncCursorSizeForCurrentDensity()
                updateCursor(immediate = true)
            } else {
                pendingBoundsRebuild = true
            }
        }
    }

    private fun flushPendingBoundsRebuild() {
        if ((!pendingBoundsRebuild && !pendingKeyboardMinimizeSync) || shouldDeferRebuildNow()) return
        main.removeCallbacks(flushBoundsRebuildRunnable)
        // 异步投递至下一轮消息循环：确保当前 ACTION_UP 完成 finishInputEvent 且已入队的穿透手势先完成，不中途毁窗
        main.post(flushBoundsRebuildRunnable)
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
        cachedPointerAccelEnabled = prefs.pointerAccelEnabled
        cachedPointerAccelMinMul = prefs.pointerAccelMinPercent / 100f
        cachedPointerAccelMaxMul = prefs.pointerAccelMaxPercent / 100f
        cachedPointerAccelThresholdDpPerSec = prefs.pointerAccelThresholdDpPerSec.toFloat()
        if (!cachedPointerAccelEnabled) {
            pointerBallistics.reset()
        }
        cachedPressFeedback = prefs.pressFeedback
        cachedTouchRingEnabled = prefs.touchRingEnabled
        cachedPadEdgeDeadZoneDp = prefs.padEdgeDeadZoneDp
        cachedLongPressMs = prefs.longPressMs
        cachedHaptics = prefs.haptics
        cachedEdgeHaptics = prefs.edgeHaptics
        if (!cachedHaptics || !cachedEdgeHaptics) {
            edgeHapticTracker.reset()
        }
        (cursorView as? CursorArrowView)?.dynamicsEnabled = cachedPressFeedback
    }

    private val panelTouchableRegion = Region()
    private val pinholePointsScratch = FloatArray(24)
    private val pinholeRectsScratch = IntArray(64)
    private val committedPinholeRects = IntArray(64)
    private var committedPinholeRectCount = 0
    private var committedPinholePanelW = 0
    private var committedPinholePanelH = 0
    private var hasCustomTouchableRegionApplied = false
    private var lastPinholeCommitUptimeMs = 0L

    private fun scheduleLayoutFlush() {
        if (layoutFlushScheduled) return
        layoutFlushScheduled = true
        Choreographer.getInstance().postFrameCallback(layoutFlushCallback)
    }

    private fun flushPendingLayoutsNow() {
        var panelMovedOrResized = false
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
                panelMovedOrResized = true
            }
        }
        if (pendingTouchableRegionUpdate || (panelMovedOrResized && isFullPanelVisible())) {
            flushPanelTouchableRegionNow()
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
        pendingTouchableRegionUpdate = false
    }

    private fun resetPinholeRegionState() {
        committedPinholeRectCount = 0
        committedPinholePanelW = 0
        committedPinholePanelH = 0
        hasCustomTouchableRegionApplied = false
        lastPinholeCommitUptimeMs = 0L
        pendingTouchableRegionUpdate = false
    }

    private fun scheduleTouchableRegionUpdate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isFullPanelVisible()) return
        pendingTouchableRegionUpdate = true
        scheduleLayoutFlush()
    }

    private fun appendPinholePoint(index: Int, x: Float, y: Float): Int {
        if (index * 2 + 1 >= pinholePointsScratch.size) return index
        pinholePointsScratch[index * 2] = x
        pinholePointsScratch[index * 2 + 1] = y
        return index + 1
    }

    private fun appendPinholePoints(startIndex: Int, points: FloatArray): Int {
        var count = startIndex
        var i = 0
        while (i + 1 < points.size && count * 2 + 1 < pinholePointsScratch.size) {
            pinholePointsScratch[count * 2] = points[i]
            pinholePointsScratch[count * 2 + 1] = points[i + 1]
            count++
            i += 2
        }
        return count
    }

    private fun collectPinholeCandidatePoints(): Int {
        var count = appendPinholePoint(0, cursorX, cursorY)
        if (customSwipeArmed) {
            count = appendPinholePoint(count, customSwipeStartX, customSwipeStartY)
        }
        if (dragging && dragTrail.isNotEmpty()) {
            count = appendPinholePoint(count, dragTrail[0].x, dragTrail[0].y)
        }
        activeGestureItem?.let { active ->
            count = appendPinholePoints(count, active.startPoints)
        }
        for (queued in gestureQueue) {
            if (count * 2 + 1 >= pinholePointsScratch.size) break
            count = appendPinholePoints(count, queued.startPoints)
        }
        return count
    }

    private fun flushPanelTouchableRegionNow() {
        pendingTouchableRegionUpdate = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!isFullPanelVisible()) return
        val p = panel ?: return
        val lp = panelParams ?: return
        if (!p.isAttachedToWindow) return
        val w = if (p.width > 0) p.width else lp.width
        val h = if (p.height > 0) p.height else lp.height
        if (w <= 0 || h <= 0) return

        val pointCount = collectPinholeCandidatePoints()
        refreshPanelScreenLocation()
        val newCount = computePanelPinholeRectsInto(
            outRects = pinholeRectsScratch,
            screenPoints = pinholePointsScratch,
            pointCount = pointCount,
            screenX = panelLocationOnScreen[0],
            screenY = panelLocationOnScreen[1],
            paramX = lp.x,
            paramY = lp.y,
            panelW = w,
            panelH = h,
        )
        if (newCount == 0) {
            if (hasCustomTouchableRegionApplied && Api33TouchableRegion.apply(p, null)) {
                hasCustomTouchableRegionApplied = false
                committedPinholeRectCount = 0
                committedPinholePanelW = 0
                committedPinholePanelH = 0
                lastPinholeCommitUptimeMs = 0L
            }
            return
        }
        if (hasCustomTouchableRegionApplied &&
            committedPinholePanelW == w &&
            committedPinholePanelH == h &&
            arePinholeRectsEqual(committedPinholeRects, committedPinholeRectCount, pinholeRectsScratch, newCount)
        ) {
            return
        }
        var anyPointUncoveredBeforeCommit = !hasCustomTouchableRegionApplied ||
            committedPinholePanelW != w ||
            committedPinholePanelH != h
        if (!anyPointUncoveredBeforeCommit) {
            for (i in 0 until pointCount) {
                val px = pinholePointsScratch[2 * i]
                val py = pinholePointsScratch[2 * i + 1]
                if (!isScreenPointCoveredByPinholeRects(
                        startX = px,
                        startY = py,
                        committedRects = committedPinholeRects,
                        committedRectCount = committedPinholeRectCount,
                        screenX = panelLocationOnScreen[0],
                        screenY = panelLocationOnScreen[1],
                        paramX = lp.x,
                        paramY = lp.y,
                        panelW = w,
                        panelH = h,
                    )
                ) {
                    anyPointUncoveredBeforeCommit = true
                    break
                }
            }
        }
        panelTouchableRegion.set(0, 0, w, h)
        for (i in 0 until newCount) {
            val base = i * 4
            panelTouchableRegion.op(
                pinholeRectsScratch[base],
                pinholeRectsScratch[base + 1],
                pinholeRectsScratch[base + 2],
                pinholeRectsScratch[base + 3],
                Region.Op.DIFFERENCE,
            )
        }
        if (Api33TouchableRegion.apply(p, panelTouchableRegion)) {
            if (anyPointUncoveredBeforeCommit || lastPinholeCommitUptimeMs <= 0L) {
                lastPinholeCommitUptimeMs = SystemClock.uptimeMillis()
            }
            System.arraycopy(pinholeRectsScratch, 0, committedPinholeRects, 0, newCount * 4)
            committedPinholeRectCount = newCount
            committedPinholePanelW = w
            committedPinholePanelH = h
            hasCustomTouchableRegionApplied = true
        }
    }

    private fun areItemStartPointsCoveredByPinhole(item: QueuedGesture): Boolean {
        val p = panel ?: return false
        val lp = panelParams ?: return false
        val w = if (p.width > 0) p.width else lp.width
        val h = if (p.height > 0) p.height else lp.height
        if (w <= 0 || h <= 0) return false
        refreshPanelScreenLocation()
        val points = item.startPoints
        var i = 0
        while (i + 1 < points.size) {
            if (!isScreenPointCoveredByPinholeRects(
                    startX = points[i],
                    startY = points[i + 1],
                    committedRects = committedPinholeRects,
                    committedRectCount = committedPinholeRectCount,
                    screenX = panelLocationOnScreen[0],
                    screenY = panelLocationOnScreen[1],
                    paramX = lp.x,
                    paramY = lp.y,
                    panelW = w,
                    panelH = h,
                )
            ) {
                return false
            }
            i += 2
        }
        return hasCustomTouchableRegionApplied || !isAnyPointInsidePanel(points)
    }

    private var touchpadBgDecodedTargetW = 0
    private var touchpadBgDecodedTargetH = 0
    private var touchpadBgReachedCeiling = false

    private val releaseTouchpadBgRunnable = Runnable {
        releaseTouchpadBgIfSafe()
    }

    private val bgUpsampleRunnable = Runnable {
        maybeUpsampleTouchpadBg()
    }

    private fun isFullPanelVisible(): Boolean = panel != null && panel !== miniBallView

    private fun loadTouchpadBgForRect(targetW: Int, targetH: Int) {
        val safeW = if (targetW > 0) targetW else dp(120)
        val safeH = if (targetH > 0) targetH else dp(90)
        val decoded = CustomBackgroundManager.loadBackgroundBitmapForCrop(
            CustomBackgroundManager.getTouchpadBgFile(this),
            targetW = safeW,
            targetH = safeH,
            maxLongEdge = 1200,
        )
        // 旧图若仍被 attached 的 TouchpadSurfaceView / RenderThread 持有，不可同步 recycle，交给 NativeAllocationRegistry 回收
        touchpadCustomBgBitmap = decoded?.bitmap
        touchpadBgDecodedTargetW = if (decoded != null) safeW else 0
        touchpadBgDecodedTargetH = if (decoded != null) safeH else 0
        touchpadBgReachedCeiling = decoded?.reachedCeiling ?: true
    }

    private fun releaseTouchpadBgIfSafe() {
        main.removeCallbacks(releaseTouchpadBgRunnable)
        main.removeCallbacks(bgUpsampleRunnable)
        if (isFullPanelVisible() || runningOutgoing.isNotEmpty()) return
        (padArea as? TouchpadSurfaceView)?.customBackgroundBitmap = null
        touchpadCustomBgBitmap?.recycle()
        touchpadCustomBgBitmap = null
        touchpadBgDecodedTargetW = 0
        touchpadBgDecodedTargetH = 0
        touchpadBgReachedCeiling = false
    }

    private fun scheduleTouchpadBgRelease() {
        if (touchpadCustomBgBitmap == null) return
        main.removeCallbacks(releaseTouchpadBgRunnable)
        main.postDelayed(releaseTouchpadBgRunnable, TOUCHPAD_BG_RELEASE_DELAY_MS)
    }

    private fun maybeUpsampleTouchpadBg() {
        if (!cachedHasTouchpadBg || !isFullPanelVisible()) return
        val pad = padArea as? TouchpadSurfaceView ?: return
        if (!shouldUpsampleTouchpadBg(
                padW = padControlRect.w,
                padH = padControlRect.h,
                loadedTargetW = touchpadBgDecodedTargetW,
                loadedTargetH = touchpadBgDecodedTargetH,
                reachedCeiling = touchpadBgReachedCeiling,
            )
        ) {
            return
        }
        loadTouchpadBgForRect(padControlRect.w, padControlRect.h)
        pad.customBackgroundBitmap = touchpadCustomBgBitmap
    }

    fun reloadTouchpadBackground() {
        main.removeCallbacks(releaseTouchpadBgRunnable)
        main.removeCallbacks(bgUpsampleRunnable)
        cachedHasTouchpadBg = CustomBackgroundManager.hasTouchpadBg(this)
        if (!isFullPanelVisible()) {
            // 最小化/隐藏态不提前解码占内存；仅在无退场动画持有旧图时才同步 recycle
            if (runningOutgoing.isEmpty()) {
                touchpadCustomBgBitmap?.recycle()
            }
            touchpadCustomBgBitmap = null
            touchpadBgDecodedTargetW = 0
            touchpadBgDecodedTargetH = 0
            touchpadBgReachedCeiling = false
            return
        }
        if (cachedHasTouchpadBg) {
            loadTouchpadBgForRect(padControlRect.w, padControlRect.h)
        } else {
            touchpadCustomBgBitmap = null
            touchpadBgDecodedTargetW = 0
            touchpadBgDecodedTargetH = 0
            touchpadBgReachedCeiling = false
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

    // 热路径复用的基本类型临时缓冲，消除 120–360Hz ACTION_MOVE / Choreographer 短命堆分配
    private val padNormOut = FloatArray(2)
    private val panelDragTargetOut = FloatArray(2)
    private val ballOffsetOut = FloatArray(2)
    private val ballDeformOut = FloatArray(5)

    // 按钮水波纹 ColorStateList 缓存：18 键共享同一实例
    private var cachedRippleColor = 0
    private var cachedRippleCsl: ColorStateList? = null

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
    private val pointerBallistics = MutablePointerBallisticsState()
    private val edgeHapticTracker = CursorEdgeHapticTracker()
    private var lastRejectHapticMs = -1000L

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
    private val panelSpringState = MutablePanelSpringState()
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
    private var ballTouchDown = false
    private var isDraggingBall = false
    private var ballLongPressTriggered = false
    private var ballMoved = false
    private var lastBallTapTime = 0L
    private var isWaitingForBallDoubleTap = false
    private var ballDoubleTapArmed = false
    private var ballFromX = 0f
    private var ballFromY = 0f
    private var ballX = 0
    private var ballY = 0
    private var ballAnchorX = 0
    private var ballAnchorY = 0
    private var ballDragStartX = 0
    private var ballDragStartY = 0
    private var ballSnapAnimator: ValueAnimator? = null
    private val ballSwipeTracker = BallSwipeTracker(0f, 0f)
    private var ballBgView: View? = null
    private var ballIconView: ImageView? = null
    private var ballRingDrawable: GradientDrawable? = null
    private var ballDiscDrawable: GradientDrawable? = null
    private var ballLayerDrawable: LayerDrawable? = null
    private var ballRippleDrawable: RippleDrawable? = null
    private val ballDiscColors = IntArray(3)
    private var ballCachedColor = 0
    private var ballCachedSize = 0
    private var ballCachedHalo = -1
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
            flushPendingBoundsRebuild()
        }
    }

    private val ballLongPressRunnable = Runnable {
        if (ballMoved || !ballTouchDown || isPassThroughActive) return@Runnable
        val act = prefs.ballActionLongPress
        isWaitingForBallDoubleTap = false
        ballDoubleTapArmed = false
        main.removeCallbacks(ballSingleTapRunnable)
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
                ?.setInterpolator(OVERSHOOT_130)
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
        computeBallDeformationInto(ballDeformOut, dx, dy, maxOffset)
        ballBgView?.rotation = ballDeformOut[2]
        ballBgView?.scaleX = ballDeformOut[0]
        ballBgView?.scaleY = ballDeformOut[1]
        ballIconView?.translationX = ballDeformOut[3]
        ballIconView?.translationY = ballDeformOut[4]
    }

    private fun resetBallDeformation(animated: Boolean = true, duration: Long = 220L) {
        if (animated) {
            ballBgView?.animate()
                ?.rotation(0f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(duration)
                ?.setInterpolator(OVERSHOOT_140)
                ?.start()

            ballIconView?.animate()
                ?.translationX(0f)
                ?.translationY(0f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(duration)
                ?.setInterpolator(OVERSHOOT_140)
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
        if (!moved && padFingerDown && !isPassThroughActive) {
            longPressFired = true
            customSwipeArmed = true
            customSwipeStartX = cursorX
            customSwipeStartY = cursorY
            customSwipeEndX = cursorX
            customSwipeEndY = cursorY
            flushPanelTouchableRegionNow()
            swipeVelocityTracker.reset()
            swipeVelocityTracker.addMovement(SystemClock.uptimeMillis(), cursorX, cursorY)
            if (cachedTouchRingEnabled) {
                moveTouchRing(cursorX, cursorY, immediate = true)
                touchRing?.pressUntilRelease()
            }
            (cursorView as? CursorArrowView)?.onLongPressLatched()
            hapticLatch()
        }
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        cleanedUp = false
        instance = this

        prefs = Prefs(this)
        refreshHotPrefs()
        cachedScaledTouchSlopPx = runCatching { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }.getOrDefault(0f)
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
        main.removeCallbacks(deferredBoundsCheckRunnable)
        main.removeCallbacks(flushBoundsRebuildRunnable)
        pendingBoundsRebuild = false
        pendingKeyboardMinimizeSync = false
        resetGestureDispatchState(clearQueue = true)
        main.removeCallbacksAndMessages(null)
        instance = null
        onPanelResized = null
        cancelDrag()
        removeTouchRing()
        cancelTransitions(immediate = true)
        removePanel(immediate = true)
        pointerBallistics.reset()
        edgeHapticTracker.reset()
        (cursorView as? CursorArrowView)?.resetDynamics()
        cursorView?.let { v ->
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        }
        cursorView = null
        cursorParams = null
        touchpadCustomBgBitmap?.recycle()
        touchpadCustomBgBitmap = null
        touchpadBgDecodedTargetW = 0
        touchpadBgDecodedTargetH = 0
        touchpadBgReachedCeiling = false
        cachedRippleCsl = null
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
        main.removeCallbacks(flushBoundsRebuildRunnable)
        main.removeCallbacks(bgUpsampleRunnable)
        pendingBoundsRebuild = false
        pendingKeyboardMinimizeSync = false
        cancelPendingLayouts()
        activeButtonCancelCallback?.invoke()
        activeButtonCancelCallback = null
        actionButtonDownCount = 0
        padFingerDown = false
        padTouchIgnored = false
        movingPanel = false
        resizingPanel = false
        ballTouchDown = false
        isDraggingBall = false
        ballLongPressTriggered = false
        isWaitingForBallDoubleTap = false
        ballDoubleTapArmed = false
        ballMoved = false
        ballSwipeTracker.reset()
        ballSnapAnimator?.cancel()
        ballSnapAnimator = null
        stopPanelDeformation(resetVisuals = true)
        padArea?.let { PressFeedback.cancel(it) }
        actionViews.forEach { PressFeedback.cancel(it) }
        resetBallDeformation(animated = false)
        pointerBallistics.reset()
        edgeHapticTracker.reset()
        (cursorView as? CursorArrowView)?.resetDynamics()
        customSwipeArmed = false
        // 息屏时清空挂起/未完结的手势派发，防止系统息屏吞掉 GestureResultCallback 导致亮屏后永久死锁或卡在不可触摸态
        resetGestureDispatchState(clearQueue = true)
        // 始终清理退场过渡窗并恢复当前入场面板的静息透明度，避免息屏中断过渡动画导致透明可触摸窗残留
        cancelTransitions(immediate = false)
        panel?.let { current ->
            current.animate().cancel()
            current.scaleX = 1f
            current.scaleY = 1f
            current.alpha = if (current === miniBallView) ballRestingAlpha() else 1f
        }
        if (touchRing?.isIdle == true && !dragging) {
            removeTouchRing()
        } else {
            touchRing?.clear()
        }
        // 仅在面板未展开（最小化/隐藏）时回收触控板底图；展开态底图按实际尺寸已极小，避免亮屏未重建导致丢图
        if (!isFullPanelVisible()) {
            releaseTouchpadBgIfSafe()
        }
        if (cursorView?.visibility != View.VISIBLE) {
            (cursorView as? CursorArrowView)?.releaseCachedBitmap()
        }
        dismissActiveDialog()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 注意：退出 MainActivity 回到桌面使用触控板时系统也会分发 TRIM_MEMORY_UI_HIDDEN，
        // 因此触控板处于展开可见态时绝不释放 touchpadCustomBgBitmap。
        if (!isFullPanelVisible() && runningOutgoing.isEmpty()) {
            releaseTouchpadBgIfSafe()
        }
        if (cursorView?.visibility != View.VISIBLE) {
            (cursorView as? CursorArrowView)?.releaseCachedBitmap()
        }
        val ringIdle = touchRing?.isIdle == true && !customSwipeArmed && !dragging
        if (ringIdle && (!isFullPanelVisible() || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)) {
            removeTouchRing()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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
        main.removeCallbacks(deferredBoundsCheckRunnable)
        main.removeCallbacks(flushBoundsRebuildRunnable)
        pendingBoundsRebuild = false
        pendingKeyboardMinimizeSync = false
        activeButtonCancelCallback?.invoke()
        activeButtonCancelCallback = null
        actionButtonDownCount = 0
        padFingerDown = false
        padTouchIgnored = false
        movingPanel = false
        resizingPanel = false
        ballTouchDown = false
        isDraggingBall = false
        isWaitingForBallDoubleTap = false
        ballDoubleTapArmed = false
        stopPanelDeformation(resetVisuals = true)
        resetGestureDispatchState(clearQueue = true)
        main.removeCallbacksAndMessages(null)
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
        if (!prefs.minimizeOnKeyboard) {
            if (prefs.minimizedByKeyboard) {
                if (shouldDeferRebuildNow()) {
                    pendingKeyboardMinimizeSync = true
                    return
                }
                pendingKeyboardMinimizeSync = false
                prefs.minimizedByKeyboard = false
                prefs.minimized = false
                syncAccessibilityEventSubscription()
                buildPanel()
            } else {
                pendingKeyboardMinimizeSync = false
                syncAccessibilityEventSubscription()
            }
            return
        }
        // 已由用户手动收起且非键盘触发时，无需再跨进程查询 getWindows()
        if (prefs.minimized && !prefs.minimizedByKeyboard) {
            pendingKeyboardMinimizeSync = false
            return
        }
        val ime = isImeVisible()
        if (ime && !prefs.minimized) {
            if (shouldDeferRebuildNow()) {
                pendingKeyboardMinimizeSync = true
                return
            }
            pendingKeyboardMinimizeSync = false
            prefs.minimizedByKeyboard = true
            prefs.minimized = true
            buildPanel()
        } else if (!ime && prefs.minimizedByKeyboard) {
            if (shouldDeferRebuildNow()) {
                pendingKeyboardMinimizeSync = true
                return
            }
            pendingKeyboardMinimizeSync = false
            prefs.minimizedByKeyboard = false
            prefs.minimized = false
            buildPanel()
        } else {
            pendingKeyboardMinimizeSync = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.removeCallbacks(deferredBoundsCheckRunnable)
        cachedScaledTouchSlopPx = runCatching { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
            .getOrDefault(cachedScaledTouchSlopPx)
        refreshScreenMetrics(newConfig)
        cursorX = clampGestureCoord(cursorX, screenW)
        cursorY = clampGestureCoord(cursorY, screenH)
        val newLand = isLandscape(newConfig)
        val newDark = isDarkTheme()
        val newDensity = resources.displayMetrics.density
        val visualChanged = screenW != lastPanelScreenW ||
            screenH != lastPanelScreenH ||
            newLand != lastPanelLandscape ||
            newDark != lastPanelDark ||
            abs(newDensity - lastPanelDensity) > 0.01f
        if (visualChanged) {
            if (shouldDeferRebuildNow()) {
                pendingBoundsRebuild = true
            } else {
                pendingBoundsRebuild = false
                main.removeCallbacks(flushBoundsRebuildRunnable)
                buildPanel()
            }
            syncCursorSizeForCurrentDensity()
            updateCursor(immediate = true)
        }
        // 部分定制系统在 onConfigurationChanged 瞬间 maximumWindowMetrics 滞后 1 帧，120ms 后静默兜底校验一次
        main.postDelayed(deferredBoundsCheckRunnable, 120L)
    }

    /** 设置界面改完之后调用：重建所有视图。 */
    fun reload() {
        if (!this::prefs.isInitialized) return
        refreshHotPrefs()
        syncAccessibilityEventSubscription()
        removeTouchRing()
        resetGestureDispatchState(clearQueue = true)
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
        val clampedX = clampCoordToScreen(window.x, widthPx, screenW)
        val clampedY = clampCoordToScreen(window.y, heightPx, screenH)
        val layoutChanged = window.x != clampedX || window.y != clampedY ||
            window.width != widthPx || window.height != heightPx
        window.x = clampedX
        window.y = clampedY
        window.width = widthPx
        window.height = heightPx
        root.layoutParams = window
        layoutControlChildren(root, widthPx, heightPx, updateAppearance = false)
        if (layoutChanged) {
            pendingPanelLayout = true
            scheduleLayoutFlush()
        }
        if (cachedHasTouchpadBg && !touchpadBgReachedCeiling) {
            main.removeCallbacks(bgUpsampleRunnable)
            main.postDelayed(bgUpsampleRunnable, TOUCHPAD_BG_UPSAMPLE_DEBOUNCE_MS)
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

    /** 实时平滑更新悬浮球尺寸、透明度与颜色（零闪烁、原地复用 Drawable 与 LayoutParams）。 */
    fun updateBallAppearance() {
        if (!this::prefs.isInitialized) return
        val dot = miniBallView ?: return
        val lp = panelParams ?: return
        dot.alpha = ballRestingAlpha()

        val size = dp(prefs.floatingBallSizeDp.coerceIn(FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP))
        val ballColor = prefs.floatingBallColor.takeIf { it != 0 }
            ?: getAccentColor(isDarkTheme())
        val sizeChanged = size != ballCachedSize
        val colorChanged = ballColor != ballCachedColor
        if (!sizeChanged && !colorChanged && ballRippleDrawable != null) {
            return
        }

        val light = Color.luminance(ballColor) > 0.55f
        val ballSize = (size * 0.86f).roundToInt()
        val halo = (ballSize * 0.06f).roundToInt().coerceAtLeast(dp(1))

        val ring = ballRingDrawable
        val disc = ballDiscDrawable
        val layer = ballLayerDrawable
        val ripple = ballRippleDrawable
        if (ring != null && disc != null && layer != null && ripple != null) {
            if (colorChanged) {
                ring.setColor((ballColor and 0x00FFFFFF) or 0x40000000)
                ballDiscColors[0] = lighten(ballColor, 0.18f)
                ballDiscColors[1] = ballColor
                ballDiscColors[2] = darken(ballColor, 0.06f)
                disc.colors = ballDiscColors
                disc.setStroke(maxOf(1, dp(1)), if (light) 0x33141413 else 0x55FAF9F5)
                ripple.setColor(ColorStateList.valueOf(if (light) 0x26141413 else 0x40FAF9F5))
                ballIconView?.imageTintList = ColorStateList.valueOf(if (light) 0xFF141413.toInt() else 0xFFFAF9F5.toInt())
                ballCachedColor = ballColor
            }
            if (halo != ballCachedHalo) {
                layer.setLayerInset(1, halo, halo, halo, halo)
                ballCachedHalo = halo
                if (!sizeChanged && !layer.bounds.isEmpty) {
                    val b = layer.bounds
                    layer.setBounds(b.left, b.top, b.right, b.bottom)
                }
                layer.invalidateSelf()
            }
        } else {
            val newRing = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((ballColor and 0x00FFFFFF) or 0x40000000)
            }
            ballDiscColors[0] = lighten(ballColor, 0.18f)
            ballDiscColors[1] = ballColor
            ballDiscColors[2] = darken(ballColor, 0.06f)
            val newDisc = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                ballDiscColors,
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(maxOf(1, dp(1)), if (light) 0x33141413 else 0x55FAF9F5)
            }
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            val newLayer = LayerDrawable(arrayOf(newRing, newDisc)).apply { setLayerInset(1, halo, halo, halo, halo) }
            val newRipple = RippleDrawable(
                ColorStateList.valueOf(if (light) 0x26141413 else 0x40FAF9F5),
                newLayer,
                mask,
            )
            ballRingDrawable = newRing
            ballDiscDrawable = newDisc
            ballLayerDrawable = newLayer
            ballRippleDrawable = newRipple
            ballCachedColor = ballColor
            ballCachedHalo = halo
            ballBgView?.background = newRipple
            ballIconView?.imageTintList = ColorStateList.valueOf(if (light) 0xFF141413.toInt() else 0xFFFAF9F5.toInt())
        }

        if (sizeChanged) {
            ballCachedSize = size
            ballBgView?.let { bg ->
                val bgLp = (bg.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER)
                bgLp.width = ballSize
                bgLp.height = ballSize
                bg.layoutParams = bgLp
            }
            ballIconView?.let { icon ->
                val iconPadding = (ballSize * 0.04f).roundToInt()
                icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                val iconSize = (ballSize * 0.48f).roundToInt()
                val iconLp = (icon.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                iconLp.width = iconSize
                iconLp.height = iconSize
                icon.layoutParams = iconLp
            }
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                val clampedX = clampCoordToScreen(lp.x, size, screenW)
                val clampedY = clampCoordToScreen(lp.y, size, screenH)
                lp.x = clampedX
                lp.y = clampedY
                prefs.setBallPosition(isLandscape(), clampedX, clampedY)
                ballAnchorX = clampedX
                ballAnchorY = clampedY
                pendingPanelLayout = true
                scheduleLayoutFlush()
            }
            dot.requestLayout()
        }
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
            val sizePx = size.toFloat()
            cp.x = cursorViewOriginCoord(cursorX, sizePx)
            cp.y = cursorViewOriginCoord(cursorY, sizePx)
            pendingCursorLayout = true
            scheduleLayoutFlush()
        }
        cv.invalidate()
    }

    /** 屏幕密度或显示尺寸变化时同步光标窗口像素尺寸，供紧随其后的 updateCursor(immediate = true) 提交。 */
    private fun syncCursorSizeForCurrentDensity() {
        if (!this::prefs.isInitialized) return
        val cp = cursorParams ?: return
        val size = dp(prefs.cursorSizeDp.coerceIn(CURSOR_MIN_DP, CURSOR_MAX_DP))
        if (cp.width != size || cp.height != size) {
            cp.width = size
            cp.height = size
            cursorView?.invalidate()
        }
    }

    /** 实时平滑更新点击特效颜色与开关（零闪烁），关闭时彻底移除悬浮窗释放显存，不在设置切换时触发屏幕预览。 */
    fun updateTouchRingAppearance() {
        if (!this::prefs.isInitialized) return
        refreshHotPrefs()
        val enabled = cachedTouchRingEnabled
        if (!enabled) {
            removeTouchRing()
            return
        }
        touchRing?.accent = resolveTouchRingColor()
    }

    fun toggleMinimize() {
        val currentlyMinimized = when {
            isFullPanelVisible() -> false
            miniBallView != null -> true
            else -> prefs.minimized
        }
        pendingKeyboardMinimizeSync = false
        prefs.minimized = !currentlyMinimized
        prefs.minimizedByKeyboard = false
        buildPanel(animate = true, dismissDialog = false)
    }

    // ───────────────────────── 视图构建 ─────────────────────────

    private fun refreshScreenMetrics(config: Configuration? = null) {
        val conf = config ?: resources.configuration
        val isLand = isLandscape(conf)
        val resolved = if (::wm.isInitialized) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                resolveScreenBounds(bounds.width(), bounds.height(), isLand, trustWindowMetrics = true)
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                resolveScreenBounds(dm.widthPixels, dm.heightPixels, isLand, trustWindowMetrics = false)
            }
        } else {
            val dm = resources.displayMetrics
            resolveScreenBounds(dm.widthPixels, dm.heightPixels, isLand, trustWindowMetrics = false)
        }
        if (resolved.x > 0 && resolved.y > 0) {
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
            dynamicsEnabled = cachedPressFeedback
            setDragArmed(dragging, triggerPop = false)
        }
        cursorParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            applyCutoutMode(this)
        }
        runCatching { wm.addView(v, cursorParams) }
        cursorView = v
    }

    private fun buildPanel(animate: Boolean = false, dismissDialog: Boolean = true) {
        pendingBoundsRebuild = false
        main.removeCallbacks(flushBoundsRebuildRunnable)
        lastPanelScreenW = screenW
        lastPanelScreenH = screenH
        lastPanelLandscape = isLandscape()
        lastPanelDark = isDarkTheme()
        lastPanelDensity = resources.displayMetrics.density
        // 用户主动展开/收起时做过渡动画：旧窗口先摘下来单独播放退场，再建新窗口播放入场。
        val outgoing = if (animate) detachPanelForTransition() else null
        removePanel(dismissDialog = dismissDialog)
        // 新窗口在 addView 之前就设为透明，第一帧不会以完整样子闪一下
        enteringWithTransition = outgoing != null
        // 横屏时按设置自动隐藏整块触控板（光标保留）
        if (isLandscape() && prefs.autoHideLandscape) {
            cursorView?.visibility = View.VISIBLE
            outgoing?.let { finishOutgoing(it) }
            enteringWithTransition = false
            scheduleTouchpadBgRelease()
            if (touchRing?.isIdle == true && !customSwipeArmed && !dragging) {
                removeTouchRing()
            }
            return
        }
        if (prefs.minimized) {
            // 最小化悬浮球态不需要光标：切为 GONE 释放窗口 Surface 并安全丢弃预渲染位图引用
            pointerBallistics.reset()
            (cursorView as? CursorArrowView)?.resetDynamics()
            cursorView?.visibility = View.GONE
            (cursorView as? CursorArrowView)?.releaseCachedBitmap()
            addMiniDot()
            scheduleTouchpadBgRelease()
            if (touchRing?.isIdle == true && !customSwipeArmed && !dragging) {
                removeTouchRing()
            }
        } else {
            main.removeCallbacks(releaseTouchpadBgRunnable)
            cursorView?.visibility = View.VISIBLE
            addFullPanel()
        }
        enteringWithTransition = false
        if (!dismissDialog && activeDialog?.isShowing == true && isFullPanelVisible()) {
            raiseCursorAboveOverlays()
        }
        if (touchRing != null) {
            touchRing?.clear()
            touchRingNeedsRaise = true
        }
        if (outgoing != null) playTransition(outgoing)
    }

    // ───────────────────────── 展开／收起过渡 ─────────────────────────

    private class Outgoing(val view: View, val lp: WindowManager.LayoutParams) {
        var finished = false
    }

    private var enteringWithTransition = false

    private val transitionInterpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    private val runningOutgoing = mutableListOf<View>()

    /** 把当前面板/悬浮球从状态里摘出来（窗口暂不移除），供退场动画使用。 */
    private fun detachPanelForTransition(): Outgoing? {
        stopPanelDeformation(resetVisuals = true)
        pendingPanelLayout = false
        passThroughSynced = false
        resetPinholeRegionState()
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
        if (o.finished) return
        o.finished = true
        o.view.animate().setListener(null).cancel()
        // 先让最后一帧变成全透明并提交，再移除窗口，避免系统拿到一张半透明快照
        o.view.alpha = 0f
        o.view.visibility = View.INVISIBLE
        if (!o.view.isAttachedToWindow) {
            runningOutgoing.remove(o.view)
            runCatching { wm.removeView(o.view) }
            return
        }
        o.view.postOnAnimation {
            if (runningOutgoing.remove(o.view) || o.view.isAttachedToWindow) {
                runCatching { wm.removeView(o.view) }
            }
        }
    }

    /** 当前所有退场中的窗口立即移除（服务销毁、息屏、重建时调用）。 */
    private fun cancelTransitions(immediate: Boolean = false) {
        runningOutgoing.toList().forEach { v ->
            v.animate().setListener(null).cancel()
            v.alpha = 0f
            v.visibility = View.INVISIBLE
            if (immediate) {
                runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
            } else {
                runCatching { wm.removeView(v) }
            }
        }
        runningOutgoing.clear()
    }

    /**
     * 面板 ⇄ 悬浮球：两者都朝对方所在的位置缩放。
     * 收起：面板缩向悬浮球并淡出，悬浮球从小弹出；展开：悬浮球缩小淡出，面板从悬浮球方向长出来。
     * 系统关闭动画时长缩放时直接落位，杜绝新窗口滞留在透明可触摸状态。
     */
    private fun playTransition(o: Outgoing) {
        val incoming = panel
        val inLp = panelParams
        val targetAlpha = if (incoming === miniBallView) ballRestingAlpha() else 1f
        runningOutgoing += o.view
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishOutgoing(o)
            if (incoming != null) {
                incoming.alpha = targetAlpha
                incoming.scaleX = 1f
                incoming.scaleY = 1f
            }
            return
        }
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
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishOutgoing(o)
                }

                override fun onAnimationCancel(animation: Animator) {
                    finishOutgoing(o)
                }
            })
            .start()

        if (incoming == null || inLp == null) return
        incoming.alpha = 0f
        incoming.scaleX = if (collapsing) 0.4f else 0.2f
        incoming.scaleY = incoming.scaleX
        incoming.post {
            if (panel !== incoming) {
                return@post
            }
            if (!ValueAnimator.areAnimatorsEnabled()) {
                incoming.scaleX = 1f
                incoming.scaleY = 1f
                incoming.alpha = targetAlpha
                return@post
            }
            // 新窗口从旧窗口中心方向长出来
            incoming.pivotX = (outCx - inLp.x).coerceIn(0f, incoming.width.toFloat())
            incoming.pivotY = (outCy - inLp.y).coerceIn(0f, incoming.height.toFloat())
            incoming.animate()
                .scaleX(1f).scaleY(1f).alpha(targetAlpha)
                .setStartDelay(if (collapsing) 120L else 40L)
                .setDuration(if (collapsing) 200L else 260L)
                .setInterpolator(if (collapsing) OVERSHOOT_160 else transitionInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (panel === incoming) {
                            incoming.scaleX = 1f
                            incoming.scaleY = 1f
                            incoming.alpha = targetAlpha
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (panel === incoming) {
                            incoming.scaleX = 1f
                            incoming.scaleY = 1f
                            incoming.alpha = targetAlpha
                        }
                    }
                })
                .start()
        }
    }

    private fun removePanel(immediate: Boolean = false, dismissDialog: Boolean = true) {
        stopPanelDeformation(resetVisuals = false)
        if (dismissDialog) dismissActiveDialog()
        pendingPanelLayout = false
        main.removeCallbacks(bgUpsampleRunnable)
        activeButtonCancelCallback?.invoke()
        activeButtonCancelCallback = null
        actionButtonDownCount = 0
        padArea?.let {
            PressFeedback.cancel(it)
            it.animate().cancel()
        }
        actionViews.forEach { PressFeedback.cancel(it) }
        padFingerDown = false
        // 拖拽锁定状态（以及跟随光标的圆环）跨面板重建保留，不在这里结束
        panel?.let { v ->
            v.animate().setListener(null).cancel()
            if (immediate) {
                runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
            } else {
                // 使用异步 removeView：避免在 ACTION_UP 同步执行 removeViewImmediate 提前销毁 InputChannel
                runCatching { wm.removeView(v) }
            }
        }
        panel = null
        panelParams = null
        miniBallView = null
        ballBgView = null
        ballIconView = null
        ballRingDrawable = null
        ballDiscDrawable = null
        ballLayerDrawable = null
        ballRippleDrawable = null
        ballCachedColor = 0
        ballCachedSize = 0
        ballCachedHalo = -1
        padArea = null
        padControlRect = ControlRect(0, 0, 0, 0)
        actionViews.clear()
        actionSlots.clear()
        main.removeCallbacks(delayedRestoreTouchRunnable)
        // 若上一笔穿透手势尚处于 50ms 延时等待（尚未调用系统 dispatchGesture），取消它时必须同步解锁 isDispatching，
        // 否则 isDispatching 将永久卡在 true，导致此后所有触控板点击/滑动/桌面点应用全部死锁无响应！
        pendingPassThroughDispatchRunnable?.let { pending ->
            main.removeCallbacks(pending)
            pendingPassThroughDispatchRunnable = null
            cancelGestureWatchdog()
            gestureDispatchGen++
            activeGestureItem = null
            isDispatching = false
            if (gestureQueue.isNotEmpty()) {
                main.post { drainGestureQueue() }
            }
        }
        main.removeCallbacks(ballLongPressRunnable)
        main.removeCallbacks(ballSingleTapRunnable)
        ballSnapAnimator?.cancel()
        ballSnapAnimator = null
        ballTouchDown = false
        isWaitingForBallDoubleTap = false
        ballDoubleTapArmed = false
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
        // 新窗口本来就是可触摸的；同步清掉穿透标记与同步状态，避免重建后面板一直处于穿透态而忽略触摸。
        passThroughSynced = false
        isPassThroughActive = false
        resetPinholeRegionState()
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
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (panel === root) {
                    flushPanelTouchableRegionNow()
                }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        if (runCatching { wm.addView(root, lp) }.isFailure) {
            padArea = null
            actionViews.clear()
            actionSlots.clear()
            return
        }
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
                flushPanelTouchableRegionNow()
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
                if (cachedHasTouchpadBg && (
                        touchpadCustomBgBitmap == null ||
                            shouldUpsampleTouchpadBg(
                                padW = layout.pad.w,
                                padH = layout.pad.h,
                                loadedTargetW = touchpadBgDecodedTargetW,
                                loadedTargetH = touchpadBgDecodedTargetH,
                                reachedCeiling = touchpadBgReachedCeiling,
                            )
                        )
                ) {
                    loadTouchpadBgForRect(layout.pad.w, layout.pad.h)
                }
                customBackgroundBitmap = touchpadCustomBgBitmap
            }
        }

        val strokePx = dp(1).toFloat()
        val opacity = prefs.opacityPercent
        val standardIconPx = (layout.button * 0.56f).roundToInt()
        val minIconPx = dp(5)
        val iconMarginPx = dp(3)

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
        ballDiscColors[0] = lighten(ballColor, 0.18f)
        ballDiscColors[1] = ballColor
        ballDiscColors[2] = darken(ballColor, 0.06f)
        val disc = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            ballDiscColors,
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, dp(1)), if (light) 0x33141413 else 0x55FAF9F5)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        val layer = LayerDrawable(arrayOf(ring, disc)).apply { setLayerInset(1, halo, halo, halo, halo) }
        val rippleBg = RippleDrawable(
            ColorStateList.valueOf(if (light) 0x26141413 else 0x40FAF9F5),
            layer,
            mask,
        )
        ballRingDrawable = ring
        ballDiscDrawable = disc
        ballLayerDrawable = layer
        ballRippleDrawable = rippleBg
        ballCachedColor = ballColor
        ballCachedSize = size
        ballCachedHalo = halo

        val bg = View(this).apply {
            background = rippleBg
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
                        if (!isPassThroughActive) {
                            v.isPressed = true
                            bg.isPressed = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        bg.isPressed = false
                        if (!isPassThroughActive) v.performClick()
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
        if (runCatching { wm.addView(dot, lp) }.isFailure) {
            ballRingDrawable = null
            ballDiscDrawable = null
            ballLayerDrawable = null
            ballRippleDrawable = null
            return
        }
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

    private fun rippleColorStateList(ripple: Int): ColorStateList {
        val cached = cachedRippleCsl
        if (cached != null && cachedRippleColor == ripple) return cached
        return ColorStateList.valueOf(ripple).also {
            cachedRippleColor = ripple
            cachedRippleCsl = it
        }
    }

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
            if (existingContent.cachedRippleColor != ripple) {
                existingContent.cachedRippleColor = ripple
                existing.setColor(rippleColorStateList(ripple))
            }
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
        val mask = BubbleMaskDrawable()
        val content = BubbleDrawable(
            baseColor = color,
            strokeColor = stroke,
            strokeWidthPx = strokeWidthPx,
            opacityPercent = opacityPercent,
            isMask = false,
            dark = dark,
            glassTint = glassTint,
            density = density,
        ).apply {
            cachedRippleColor = ripple
        }
        return RippleDrawable(rippleColorStateList(ripple), content, mask)
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
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (isPassThroughActive) return@setOnTouchListener false
                        v.isPressed = true
                        haptic(v)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isPassThroughActive && !movingPanel && !resizingPanel) return@setOnTouchListener false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                    }
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
        val slop = maxOf(cachedScaledTouchSlopPx, dp(8).toFloat())
        var downRawX = 0f
        var downRawY = 0f
        var btnScreenLeft = 0
        var btnScreenTop = 0
        var btnWidth = 0
        var btnHeight = 0
        var longPressFired = false
        var isDown = false

        var cancelSelf: (() -> Unit)? = null

        val longPressRunnable = Runnable {
            if (!isDown || isPassThroughActive || !button.isAttachedToWindow) return@Runnable
            longPressFired = true
            isDown = false
            actionButtonDownCount = (actionButtonDownCount - 1).coerceAtLeast(0)
            if (activeButtonCancelCallback === cancelSelf) {
                activeButtonCancelCallback = null
            }
            button.isPressed = false
            if (cachedPressFeedback) {
                PressFeedback.applyButtonRelease(button)
            }
            haptic(button)
            showActionPicker(slot, action)
        }

        val resetState: (Boolean) -> Unit = { releaseVisual ->
            button.removeCallbacks(longPressRunnable)
            if (isDown) {
                isDown = false
                actionButtonDownCount = (actionButtonDownCount - 1).coerceAtLeast(0)
            }
            if (activeButtonCancelCallback === cancelSelf) {
                activeButtonCancelCallback = null
            }
            longPressFired = false
            button.isPressed = false
            if (releaseVisual && cachedPressFeedback) {
                PressFeedback.applyButtonRelease(button)
            }
        }
        cancelSelf = { resetState(true) }

        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                resetState(false)
            }
        })

        button.setOnTouchListener { v, event ->
            if (isPassThroughActive) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDown || v.isPressed) {
                            resetState(true)
                            flushCancelledTapsIfIdle()
                            flushPendingBoundsRebuild()
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDown) return@setOnTouchListener true
                    }
                }
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    syncScreenBoundsIfChanged(rebuildNow = false)
                    refreshPanelScreenLocation()
                    btnScreenLeft = panelLocationOnScreen[0] + v.left
                    btnScreenTop = panelLocationOnScreen[1] + v.top
                    btnWidth = v.width
                    btnHeight = v.height
                    downRawX = event.rawX
                    downRawY = event.rawY
                    if (!isDown) {
                        isDown = true
                        actionButtonDownCount++
                    }
                    activeButtonCancelCallback = cancelSelf
                    longPressFired = false
                    v.isPressed = true
                    v.drawableHotspotChanged(event.x, event.y)
                    if (cachedPressFeedback) {
                        PressFeedback.applyButtonDown(v, event)
                    }
                    if (prefs.longPressButtonToCustomize) {
                        val holdMs = prefs.buttonCustomizeHoldMs.toLong()
                        v.removeCallbacks(longPressRunnable)
                        v.postDelayed(longPressRunnable, holdMs)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDown) return@setOnTouchListener true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    // 使用未形变屏幕矩形 + 容差（或原地微动 <= slop），免疫 PressFeedback 下沉、3D 倾斜及展开缩放动画期间的坐标偏移
                    val inside = isButtonReleaseWithinHitTarget(
                        event.rawX, event.rawY,
                        downRawX, downRawY,
                        btnScreenLeft, btnScreenTop, btnWidth, btnHeight,
                        slop,
                    )
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
                        PressFeedback.applyButtonMove(v, event)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    val wasDown = isDown
                    val wasLongPressed = longPressFired
                    if (isDown) {
                        isDown = false
                        actionButtonDownCount = (actionButtonDownCount - 1).coerceAtLeast(0)
                    }
                    if (activeButtonCancelCallback === cancelSelf) {
                        activeButtonCancelCallback = null
                    }
                    longPressFired = false
                    v.isPressed = false
                    if (cachedPressFeedback) {
                        PressFeedback.applyButtonRelease(v)
                    }
                    if (wasLongPressed) {
                        flushCancelledTapsIfIdle()
                        flushPendingBoundsRebuild()
                        return@setOnTouchListener true
                    }
                    if (wasDown) {
                        // 抬手同样采用未形变屏幕矩形 + 容差（或原地微动 <= slop），杜绝按在按键边缘或展开动画期被误判为出界而无响应
                        val inside = isButtonReleaseWithinHitTarget(
                            event.rawX, event.rawY,
                            downRawX, downRawY,
                            btnScreenLeft, btnScreenTop, btnWidth, btnHeight,
                            slop,
                        )
                        if (inside) {
                            v.performClick()
                            performAction(action)
                        }
                    }
                    flushCancelledTapsIfIdle()
                    flushPendingBoundsRebuild()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    resetState(true)
                    flushCancelledTapsIfIdle()
                    flushPendingBoundsRebuild()
                    true
                }

                else -> isDown
            }
        }
    }

    // ───────────────────────── 按钮长按换动作 ─────────────────────────

    private fun showActionPicker(slot: Int, current: PadAction) {
        dismissActiveDialog()
        val dark = isDarkTheme()
        val accent = getAccentColor(dark)
        val dialog = ActionPicker.build(
            this,
            dark,
            current,
            actions = PadAction.TOUCHPAD_ACTIONS,
            accentColor = accent,
            allowOverlayAbove = true,
        ) { picked ->
            val list = replaceSlot(prefs.buttons, slot, picked, prefs.buttonCount)
            if (list == null) {
                toast(getString(R.string.need_move_key))
                return@build
            }
            prefs.buttons = list
            if (isFullPanelVisible()) {
                if (shouldDeferRebuildNow()) {
                    pendingBoundsRebuild = true
                } else {
                    buildPanel()
                }
            }
        }
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        activeDialog = dialog
        val shown = runCatching { dialog.show() }.isSuccess
        if (shown) {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            raiseOverlaysAboveDialog()
        } else {
            activeDialog = null
        }
    }

    /**
     * 将悬浮触控板面板与光标提升至长按自定义菜单之上，
     * 弹窗期间触控板与光标保持清晰可见且可直接操作（移动光标、点选/滚动选项、拖动面板）。
     */
    private fun raiseOverlaysAboveDialog() {
        val p = panel
        val lp = panelParams
        if (p != null && lp != null && isFullPanelVisible()) {
            stopPanelDeformation(resetVisuals = true)
            pendingPanelLayout = false
            passThroughSynced = false
            isPassThroughActive = false
            resetPinholeRegionState()
            if (p.isAttachedToWindow) {
                runCatching { wm.removeViewImmediate(p) }.onFailure { runCatching { wm.removeView(p) } }
            }
            if (runCatching { wm.addView(p, lp) }.isSuccess) {
                p.post {
                    if (panel === p) {
                        flushPanelTouchableRegionNow()
                    }
                }
            }
        }
        raiseCursorAboveOverlays()
    }

    private fun raiseCursorAboveOverlays() {
        val cv = cursorView
        val cp = cursorParams
        if (cv != null && cp != null && cv.visibility == View.VISIBLE) {
            pendingCursorLayout = false
            if (cv.isAttachedToWindow) {
                runCatching { wm.removeViewImmediate(cv) }.onFailure { runCatching { wm.removeView(cv) } }
            }
            runCatching { wm.addView(cv, cp) }
        }
        if (touchRing != null) {
            touchRingNeedsRaise = true
        }
    }

    // ───────────────────────── 触控板手势 ─────────────────────────

    private var padCornerRadius = 0f
    /**
     * 这次触摸是从触控板边缘那一圈（含圆角外侧）开始的：整段忽略，防止按旁边按钮时误碰。
     * v0.5.3 及以前，触控板 View 的整个矩形（包括圆角外侧的四个小角）都响应，紧挨着按钮只隔一个按钮间距。
     */
    private var padTouchIgnored = false

    private fun cancelPadTouchState() {
        val wasDown = padFingerDown
        val wasCustomSwipeArmed = customSwipeArmed
        padFingerDown = false
        padTouchIgnored = false
        main.removeCallbacks(longPressRunnable)
        swipeVelocityTracker.reset()
        pointerBallistics.reset()
        (cursorView as? CursorArrowView)?.onPadTouchUp()
        if (customSwipeArmed) touchRing?.release()
        customSwipeArmed = false
        if (wasCustomSwipeArmed) {
            scheduleTouchableRegionUpdate()
        }
        if (wasDown && cachedPressFeedback) {
            padArea?.let { pad -> PressFeedback.applyPadRelease(pad) }
        }
    }

    private fun handlePadTouch(e: MotionEvent) {
        if (isPassThroughActive) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                cancelPadTouchState()
                flushCancelledTapsIfIdle()
                flushPendingBoundsRebuild()
            }
            return
        }
        val slop = dp(6).toFloat()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            syncScreenBoundsIfChanged(rebuildNow = false)
            refreshPanelScreenLocation()
            val pad = padArea
            val p = panel
            val panelAtRestScale = p != null && abs(p.scaleX - 1f) < 0.01f && abs(p.scaleY - 1f) < 0.01f
            val localX: Float
            val localY: Float
            val padW: Float
            val padH: Float
            if (padControlRect.w > 0 && padControlRect.h > 0 && panelAtRestScale) {
                // 使用未形变底座坐标判定边缘死区，免疫上一笔松手 200ms 回弹动画期间的局部坐标偏移
                localX = e.rawX - (panelLocationOnScreen[0] + padControlRect.x)
                localY = e.rawY - (panelLocationOnScreen[1] + padControlRect.y)
                padW = padControlRect.w.toFloat()
                padH = padControlRect.h.toFloat()
            } else {
                localX = e.x
                localY = e.y
                padW = pad?.width?.toFloat() ?: 0f
                padH = pad?.height?.toFloat() ?: 0f
            }
            padTouchIgnored = pad != null && padW > 0f && padH > 0f && !insideTouchArea(
                localX, localY, padW, padH, padCornerRadius,
                dp(cachedPadEdgeDeadZoneDp).toFloat(),
            )
        }
        if (padTouchIgnored) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                padTouchIgnored = false
                flushCancelledTapsIfIdle()
                flushPendingBoundsRebuild()
            }
            return
        }
        val panelX = panelLocationOnScreen[0]
        val panelY = panelLocationOnScreen[1]
        val normX: Float
        val normY: Float
        if (padControlRect.w > 0 && padControlRect.h > 0) {
            resolvePadNormalizedCoordsInto(
                padNormOut,
                e.rawX, e.rawY,
                panelX, panelY,
                padControlRect.x, padControlRect.y, padControlRect.w, padControlRect.h,
            )
            normX = padNormOut[0]
            normY = padNormOut[1]
        } else {
            val pad = padArea
            val w = pad?.width?.toFloat()?.coerceAtLeast(1f) ?: 1f
            val h = pad?.height?.toFloat()?.coerceAtLeast(1f) ?: 1f
            normX = ((e.x - w / 2f) / (w / 2f)).coerceIn(-1f, 1f)
            normY = ((e.y - h / 2f) / (h / 2f)).coerceIn(-1f, 1f)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val eventTimeNs = motionEventTimeNs(e)
                padFingerDown = true
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadDown(pad, normX, normY, resources.displayMetrics.density)
                    }
                }
                downRawX = e.rawX; downRawY = e.rawY
                lastRawX = e.rawX; lastRawY = e.rawY
                downTime = eventTimeNs / 1_000_000L
                moved = false; longPressFired = false
                customSwipeArmed = false
                customSwipeStartX = cursorX
                customSwipeStartY = cursorY
                customSwipeEndX = cursorX
                customSwipeEndY = cursorY
                swipeVelocityTracker.reset()
                pointerBallistics.reset()
                edgeHapticTracker.syncInitialPosition(
                    cursorX,
                    cursorY,
                    (screenW - 1).coerceAtLeast(0).toFloat(),
                    (screenH - 1).coerceAtLeast(0).toFloat(),
                )
                (cursorView as? CursorArrowView)?.onPadTouchDown(eventTimeNs, cachedLongPressMs.toLong())
                main.removeCallbacks(longPressRunnable)
                // 拖拽锁定期间触控板只移动光标，不点击、不长按
                if (!dragging) {
                    if (shouldScheduleLongPress(dragging = false)) {
                        main.postDelayed(longPressRunnable, cachedLongPressMs.toLong())
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!padFingerDown) return
                val eventTimeNs = motionEventTimeNs(e)
                val density = resources.displayMetrics.density
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadMove(pad, normX, normY, density)
                    }
                }
                val dx = e.rawX - lastRawX
                val dy = e.rawY - lastRawY
                lastRawX = e.rawX; lastRawY = e.rawY
                if (abs(e.rawX - downRawX) > slop || abs(e.rawY - downRawY) > slop) {
                    if (!moved) {
                        moved = true
                        main.removeCallbacks(longPressRunnable)
                        (cursorView as? CursorArrowView)?.onPadMovedPastSlop()
                    }
                }
                val accel = pointerBallistics.onMove(
                    dxPx = dx,
                    dyPx = dy,
                    eventTimeNs = eventTimeNs,
                    density = density,
                    minMultiplier = cachedPointerAccelMinMul,
                    maxMultiplier = cachedPointerAccelMaxMul,
                    thresholdDpPerSec = cachedPointerAccelThresholdDpPerSec,
                )
                val effectiveGain = if (cachedPointerAccelEnabled) {
                    cachedSensitivity * accel
                } else {
                    cachedSensitivity
                }
                val stepX = dx * effectiveGain
                val stepY = dy * effectiveGain
                val rawNextX = cursorX + stepX
                val rawNextY = cursorY + stepY
                cursorX = clampGestureCoord(rawNextX, screenW)
                cursorY = clampGestureCoord(rawNextY, screenH)
                updateCursor()

                val maxCursorX = (screenW - 1).coerceAtLeast(0).toFloat()
                val maxCursorY = (screenH - 1).coerceAtLeast(0).toFloat()
                val eventTimeMs = eventTimeNs / 1_000_000L
                if (cachedHaptics && cachedEdgeHaptics) {
                    if (edgeHapticTracker.onCursorMoved(cursorX, cursorY, maxCursorX, maxCursorY, density, eventTimeMs) != CURSOR_EDGE_NONE) {
                        hapticEdgeTick()
                    }
                }
                val pushX = computeCursorEdgePushAmount(rawNextX, maxCursorX, stepX, density)
                val pushY = computeCursorEdgePushAmount(rawNextY, maxCursorY, stepY, density)
                val cursorVxDpPerSec = pointerBallistics.smoothedVxDpPerSec * effectiveGain
                (cursorView as? CursorArrowView)?.onCursorMotion(
                    vxDpPerSec = cursorVxDpPerSec,
                    edgePushX = pushX,
                    edgePushY = pushY,
                    eventTimeNs = eventTimeNs,
                )

                if (customSwipeArmed) {
                    customSwipeEndX = cursorX
                    customSwipeEndY = cursorY
                    swipeVelocityTracker.addMovement(eventTimeMs, cursorX, cursorY)
                    if (cachedTouchRingEnabled) {
                        moveTouchRing(cursorX, cursorY)
                    }
                }
                if (dragging) {
                    appendTrailPoint(dragTrail, cursorX, cursorY, dp(4).toFloat())
                }
            }

            MotionEvent.ACTION_UP -> {
                val eventTimeNs = motionEventTimeNs(e)
                val eventTimeMs = eventTimeNs / 1_000_000L
                val wasDown = padFingerDown
                val wasCustomSwipeArmed = customSwipeArmed
                padFingerDown = false
                if (cachedPressFeedback) {
                    padArea?.let { pad ->
                        PressFeedback.applyPadRelease(pad)
                    }
                }
                main.removeCallbacks(longPressRunnable)
                val (vx, vy) = if (customSwipeArmed) {
                    swipeVelocityTracker.computeVelocity(eventTimeMs)
                } else {
                    0f to 0f
                }
                swipeVelocityTracker.reset()
                pointerBallistics.reset()
                (cursorView as? CursorArrowView)?.onPadTouchUp()
                if (!wasDown) {
                    customSwipeArmed = false
                    if (wasCustomSwipeArmed) scheduleTouchableRegionUpdate()
                    flushCancelledTapsIfIdle()
                    flushPendingBoundsRebuild()
                    return
                }
                val distance = kotlin.math.hypot(customSwipeEndX - customSwipeStartX, customSwipeEndY - customSwipeStartY)
                if (dragging) {
                    // 拖拽锁定期间圆环继续跟着光标，等第二次按拖拽锁定再收缩
                    customSwipeArmed = false
                    if (wasCustomSwipeArmed) scheduleTouchableRegionUpdate()
                    flushCancelledTapsIfIdle()
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
                if (wasCustomSwipeArmed && outcome != PadTouchOutcome.CUSTOM_SWIPE) {
                    scheduleTouchableRegionUpdate()
                }
                flushCancelledTapsIfIdle()
                flushPendingBoundsRebuild()
            }

            MotionEvent.ACTION_CANCEL -> {
                motionEventTimeNs(e)
                cancelPadTouchState()
                flushCancelledTapsIfIdle()
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

            val targetX: Float
            val targetY: Float
            if (movingPanel) {
                computePanelDragTargetInto(panelDragTargetOut, moveSmoothedVx, moveSmoothedVy, resources.displayMetrics.density)
                targetX = panelDragTargetOut[0]
                targetY = panelDragTargetOut[1]
            } else {
                targetX = 0f
                targetY = 0f
            }
            val targetGrab = if (movingPanel) 1f else 0f

            stepPanelDragSpringInPlace(
                state = panelSpringState,
                targetX = targetX,
                targetY = targetY,
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
                panelSpringState.isAtRestForTarget(targetX, targetY, targetGrab)
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
        panelSpringState.reset()
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
                val nextX = clampCoordToScreen(movingPanelX + (e.rawX - movingFromX).roundToInt(), panelW, screenW)
                val nextY = clampCoordToScreen(movingPanelY + (e.rawY - movingFromY).roundToInt(), panelH, screenH)
                if (lp.x != nextX || lp.y != nextY) {
                    lp.x = nextX
                    lp.y = nextY
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
                if (movingPanel) {
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
                }
                flushCancelledTapsIfIdle()
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
                resizingCurrentWidthPercent = if (screenW > 0) (width * 100 / screenW).coerceIn(10, 100) else 96
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
                    maybeUpsampleTouchpadBg()
                    panel?.post { keepPanelInBounds() }
                    onPanelResized?.invoke(resizingCurrentWidthDp, resizingCurrentExtraHeightDp)
                }
                flushCancelledTapsIfIdle()
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
            interpolator = OVERSHOOT_125
            addUpdateListener { va ->
                val frac = (va.animatedValue as? Float) ?: va.animatedFraction
                val p = panelParams ?: return@addUpdateListener
                val curX = (startX + (targetX - startX) * frac).roundToInt()
                val curY = (startY + (targetY - startY) * frac).roundToInt()
                val cx = clampCoordToScreen(curX, size, screenW)
                val cy = clampCoordToScreen(curY, size, screenH)
                if (p.x != cx || p.y != cy) {
                    p.x = cx
                    p.y = cy
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

    private fun cancelBallTouchState() {
        main.removeCallbacks(ballLongPressRunnable)
        main.removeCallbacks(ballSingleTapRunnable)
        flushPendingLayoutsNow()
        ballTouchDown = false
        isWaitingForBallDoubleTap = false
        ballDoubleTapArmed = false
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
    }

    /**
     * 关闭悬浮球手势时的极简模式：
     * - 点击悬浮球直接展开触控板（0ms 延迟，无需等待双击超时）；
     * - 拖动悬浮球直接实时移动位置（无需长按触发，无阻尼手势判定与误触发）。
     */
    private fun handleSimpleBallTouch(v: View, e: MotionEvent): Boolean {
        val slop = resolveBallTapSlopPx(cachedScaledTouchSlopPx, dp(10).toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ballTouchDown = true
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
                if (!ballTouchDown) return true
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                if (!ballMoved && kotlin.math.hypot(dx, dy) >= slop) {
                    ballMoved = true
                }
                if (ballMoved) {
                    val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                    val cx = clampCoordToScreen(ballDragStartX + dx.roundToInt(), size, screenW)
                    val cy = clampCoordToScreen(ballDragStartY + dy.roundToInt(), size, screenH)
                    panelParams?.let { lp ->
                        if (lp.x != cx || lp.y != cy) {
                            lp.x = cx
                            lp.y = cy
                            pendingPanelLayout = true
                            scheduleLayoutFlush()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasDown = ballTouchDown
                ballTouchDown = false
                flushPendingLayoutsNow()
                if (wasDown) {
                    if (!ballMoved) {
                        haptic()
                        toggleMinimize()
                    } else {
                        keepBallInBounds()
                    }
                }
                ballMoved = false
                flushCancelledTapsIfIdle()
                flushPendingBoundsRebuild()
            }
            MotionEvent.ACTION_CANCEL -> {
                ballTouchDown = false
                ballMoved = false
                flushPendingLayoutsNow()
                keepBallInBounds()
                flushCancelledTapsIfIdle()
                flushPendingBoundsRebuild()
            }
        }
        return true
    }

    private fun handleBallTouch(v: View, e: MotionEvent): Boolean {
        if (isPassThroughActive) {
            when (e.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ballTouchDown || isDraggingBall || ballLongPressTriggered || isWaitingForBallDoubleTap || ballDoubleTapArmed) {
                        cancelBallTouchState()
                        flushCancelledTapsIfIdle()
                        flushPendingBoundsRebuild()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ballTouchDown) return true
                }
            }
            return false
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            syncScreenBoundsIfChanged(rebuildNow = false)
        }
        if (!prefs.ballGesturesEnabled) {
            return handleSimpleBallTouch(v, e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ballTouchDown = true
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
                ballSwipeTracker.configure(swipeThreshold, cancelThreshold)

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

                // 在第二次 ACTION_DOWN 就锁定双击资格并取消第一次单击超时，避免抬手稍慢或微抖导致双击失效
                val downNow = SystemClock.uptimeMillis()
                ballDoubleTapArmed = isBallSecondTapDownWithinWindow(
                    isWaitingForDoubleTap = isWaitingForBallDoubleTap,
                    lastTapUpMs = lastBallTapTime,
                    secondTapDownMs = downNow,
                    timeoutMs = BALL_DOUBLE_TAP_TIMEOUT_MS,
                    graceMs = BALL_DOUBLE_TAP_GRACE_MS,
                )
                if (isWaitingForBallDoubleTap) {
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                }
                main.removeCallbacks(ballLongPressRunnable)
                val holdDuration = resolveBallLongPressHoldMs(prefs.ballLongPressMs)
                main.postDelayed(ballLongPressRunnable, holdDuration)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!ballTouchDown) return true
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                val dist = kotlin.math.hypot(dx, dy)
                val rawSlop = resolveBallTapSlopPx(cachedScaledTouchSlopPx, dp(10).toFloat())
                val swipeThreshold = ballSwipeTracker.swipeThresholdPx
                val slop = if (swipeThreshold > 0f) minOf(rawSlop, swipeThreshold * 0.8f) else rawSlop

                if (shouldCancelBallLongPress(dist, slop) && !ballMoved) {
                    ballMoved = true
                    main.removeCallbacks(ballLongPressRunnable)
                }

                if (isDraggingBall) {
                    val size = miniBallView?.width ?: dp(prefs.floatingBallSizeDp)
                    val cx = clampCoordToScreen(ballDragStartX + dx.roundToInt(), size, screenW)
                    val cy = clampCoordToScreen(ballDragStartY + dy.roundToInt(), size, screenH)
                    panelParams?.let { lp ->
                        if (lp.x != cx || lp.y != cy) {
                            lp.x = cx
                            lp.y = cy
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
                                computeDragMotionStateInto(
                                    out = ballDeformOut,
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
                                ballDragCurrentRotation = lerpAngleDeg(ballDragCurrentRotation, ballDeformOut[2], rotAlpha)
                                ballBgView?.rotation = ballDragCurrentRotation
                                ballBgView?.scaleX = ballDeformOut[0]
                                ballBgView?.scaleY = ballDeformOut[1]
                                ballIconView?.translationX = ballDeformOut[3]
                                ballIconView?.translationY = ballDeformOut[4]
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
                        computeDampedSwipeOffsetInto(ballOffsetOut, dx, dy, maxOffset)
                        val cx = clampCoordToScreen((ballAnchorX + ballOffsetOut[0]).roundToInt(), size, screenW)
                        val cy = clampCoordToScreen((ballAnchorY + ballOffsetOut[1]).roundToInt(), size, screenH)
                        panelParams?.let { lp ->
                            if (lp.x != cx || lp.y != cy) {
                                lp.x = cx
                                lp.y = cy
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
                val wasDown = ballTouchDown
                ballTouchDown = false
                flushPendingLayoutsNow()
                if (!wasDown) {
                    ballDoubleTapArmed = false
                    flushCancelledTapsIfIdle()
                    flushPendingBoundsRebuild()
                    return true
                }
                val dx = e.rawX - ballFromX
                val dy = e.rawY - ballFromY
                val dist = kotlin.math.hypot(dx, dy)
                val swipeThreshold = ballSwipeTracker.swipeThresholdPx

                if (isDraggingBall) {
                    isDraggingBall = false
                    isWaitingForBallDoubleTap = false
                    ballDoubleTapArmed = false
                    ballDragLastTimeNs = 0L
                    ballDragHoverSettled = true
                    ballBgView?.animate()?.cancel()
                    ballIconView?.animate()?.cancel()
                    ballBgView?.animate()
                        ?.rotation(0f)
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.setDuration(220)
                        ?.setInterpolator(OVERSHOOT_135)
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
                    isWaitingForBallDoubleTap = false
                    ballDoubleTapArmed = false
                    snapBallBackToAnchor()
                } else if (ballSwipeTracker.isCancelledByReturn) {
                    // 用户拖回中心打断手势：不触发滑动动作，也不触发点击，平滑复位
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                    ballDoubleTapArmed = false
                    snapBallBackToAnchor()
                } else if (ballSwipeTracker.hasArmedSwipe || dist >= swipeThreshold) {
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                    ballDoubleTapArmed = false

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
                        if (act != PadAction.DRAG_LOCK) haptic()
                        performAction(act, withHaptic = false)
                    }
                } else if (
                    shouldTreatBallReleaseAsDoubleTap(
                        doubleTapArmed = ballDoubleTapArmed,
                        hasArmedSwipe = ballSwipeTracker.hasArmedSwipe,
                        isCancelledByReturn = ballSwipeTracker.isCancelledByReturn,
                        distPx = dist,
                        swipeThresholdPx = swipeThreshold,
                    )
                ) {
                    // 第二次点击抬起（已锁定双击且未达到滑动阈值）：立即震动并触发双击动作
                    ballDoubleTapArmed = false
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                    snapBallBackToAnchor()
                    val doubleAction = prefs.ballActionDoubleTap
                    if (doubleAction != PadAction.NONE) {
                        if (doubleAction != PadAction.DRAG_LOCK) haptic()
                        performAction(doubleAction, withHaptic = false)
                    }
                } else if (!ballMoved) {
                    ballDoubleTapArmed = false
                    snapBallBackToAnchor()
                    val doubleAction = prefs.ballActionDoubleTap
                    if (doubleAction == PadAction.NONE) {
                        // 未开启双击功能：单击完全零延迟，立即震动并触发动作
                        val act = prefs.ballActionSingleTap
                        if (act != PadAction.NONE) {
                            if (act != PadAction.DRAG_LOCK) haptic()
                            performAction(act, withHaptic = false)
                        }
                    } else {
                        // 第一次点击抬起：立即震动反馈（0ms 消除感知延迟），启动双击等待窗口
                        haptic()
                        lastBallTapTime = SystemClock.uptimeMillis()
                        isWaitingForBallDoubleTap = true
                        main.removeCallbacks(ballSingleTapRunnable)
                        main.postDelayed(ballSingleTapRunnable, BALL_DOUBLE_TAP_TIMEOUT_MS)
                    }
                } else {
                    // 微小拖动但未达到滑动阈值，不派发点击，平滑复位
                    ballDoubleTapArmed = false
                    main.removeCallbacks(ballSingleTapRunnable)
                    isWaitingForBallDoubleTap = false
                    snapBallBackToAnchor()
                }
                flushCancelledTapsIfIdle()
                flushPendingBoundsRebuild()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelBallTouchState()
                flushCancelledTapsIfIdle()
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
        flushPanelTouchableRegionNow()
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

    private class QueuedGesture(
        val gesture: GestureDescription,
        val checkPoints: FloatArray,
        val startPoints: FloatArray,
        val expectedMs: Long = 500L,
        val keepPassThroughAfterComplete: Boolean = false,
        val isContinuation: Boolean = false,
        val isShortTap: Boolean = false,
        val isCoalescedReplay: Boolean = false,
        val tapX: Float = 0f,
        val tapY: Float = 0f,
        val onComplete: (() -> Unit)? = null,
    )

    private var isDispatching = false
    private var gestureDispatchGen = 0L
    private var passThroughSynced = false
    private var activeGestureItem: QueuedGesture? = null
    private val gestureQueue = ArrayDeque<QueuedGesture>()
    private val cancelledTapTracker = CancelledTapTracker()
    private val coalescedTapCoordsScratch = FloatArray(MAX_COALESCED_TAPS * 2)

    private fun isUserTouchingOverlay(): Boolean =
        padFingerDown || actionButtonDownCount > 0 || ballTouchDown || movingPanel || resizingPanel

    /** 具名可取消的 20ms 延迟恢复任务，杜绝连续穿透点击时前一次恢复覆盖下一次穿透态。 */
    private val delayedRestoreTouchRunnable = Runnable {
        setOverlayTouchable(true)
        flushPendingBoundsRebuild()
    }

    private var pendingPassThroughDispatchRunnable: Runnable? = null
    private var activeGestureWatchdogRunnable: Runnable? = null

    private fun cancelGestureWatchdog() {
        activeGestureWatchdogRunnable?.let { main.removeCallbacks(it) }
        activeGestureWatchdogRunnable = null
    }

    private fun resetGestureDispatchState(clearQueue: Boolean) {
        gestureDispatchGen++
        pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
        pendingPassThroughDispatchRunnable = null
        cancelGestureWatchdog()
        main.removeCallbacks(delayedRestoreTouchRunnable)
        passThroughSynced = false
        activeGestureItem = null
        isDispatching = false
        if (clearQueue) {
            gestureQueue.clear()
            cancelledTapTracker.clear()
        }
        setOverlayTouchable(true)
        if (isFullPanelVisible()) {
            flushPanelTouchableRegionNow()
        }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        if (touchable) {
            passThroughSynced = false
        } else if (!isPassThroughActive) {
            // 切入穿透态瞬间主动取消当前活跃的按钮/触控板/悬浮球长按计时，防止被穿透吞掉抬手事件后延迟误弹 ActionPicker 或误触发长按
            activeButtonCancelCallback?.invoke()
            main.removeCallbacks(longPressRunnable)
            main.removeCallbacks(ballLongPressRunnable)
        }
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
        return isPointInsidePanelBounds(
            px = x,
            py = y,
            screenX = panelLocationOnScreen[0],
            screenY = panelLocationOnScreen[1],
            paramX = lp.x,
            paramY = lp.y,
            width = w,
            height = h,
            marginPx = 2f,
        )
    }

    private fun isAnyPointInsidePanel(points: FloatArray): Boolean {
        if (panel == null || panelParams == null || points.size < 2) return false
        var i = 0
        while (i + 1 < points.size) {
            if (isPointInsidePanel(points[i], points[i + 1])) return true
            i += 2
        }
        return false
    }

    /**
     * 顺序派发注入手势：
     * 1. 仅当光标位于触控板面板范围内时才短暂切换 FLAG_NOT_TOUCHABLE，且在真正派发时按当前面板实时判定，避免对已切换的悬浮球误设穿透；
     * 2. 穿透期间锁定 isPassThroughActive 状态，触控板按钮、把手与触控板自身全面拦截任何新触摸并安全清理残留按压状态，彻底杜绝误触；
     * 3. 光标在面板外部（绝大多数常规操作场景）时，面板始终保持可触摸状态，快速连续点击绝不会击穿面板触发底层内容；
     * 4. 所有手势均受单调代数 gestureDispatchGen 与统一看门狗保护，即使系统丢回调或窗口重建也绝不发生 isDispatching 永久死锁。
     */
    private fun dispatch(
        gesture: GestureDescription,
        checkPoints: FloatArray,
        startPoints: FloatArray,
        expectedMs: Long = 500L,
        keepPassThroughAfterComplete: Boolean = false,
        isShortTap: Boolean = false,
        isCoalescedReplay: Boolean = false,
        tapX: Float = 0f,
        tapY: Float = 0f,
        onComplete: (() -> Unit)? = null,
    ) {
        if (gestureQueue.size >= MAX_GESTURE_QUEUE_SIZE) {
            val dropIdx = gestureQueue.indexOfFirst { !it.isContinuation }
            if (dropIdx >= 0) {
                gestureQueue.removeAt(dropIdx)
            } else {
                gestureQueue.removeFirstOrNull()
            }
        }
        gestureQueue.addLast(
            QueuedGesture(
                gesture = gesture,
                checkPoints = checkPoints,
                startPoints = startPoints,
                expectedMs = expectedMs,
                keepPassThroughAfterComplete = keepPassThroughAfterComplete,
                isContinuation = false,
                isShortTap = isShortTap,
                isCoalescedReplay = isCoalescedReplay,
                tapX = tapX,
                tapY = tapY,
                onComplete = onComplete,
            ),
        )
        drainGestureQueue()
    }

    /** 拖拽续接（continueStroke）必须紧跟在 holdStroke 之后立即派发，绝不可被排队手势插队导致穿透态泄漏或断触。 */
    private fun dispatchFront(
        gesture: GestureDescription,
        checkPoints: FloatArray,
        startPoints: FloatArray,
        expectedMs: Long = 500L,
    ) {
        gestureQueue.addFirst(
            QueuedGesture(
                gesture = gesture,
                checkPoints = checkPoints,
                startPoints = startPoints,
                expectedMs = expectedMs,
                keepPassThroughAfterComplete = false,
                isContinuation = true,
                onComplete = null,
            ),
        )
        drainGestureQueue()
    }

    private fun recordCancelledTapIfNeeded(item: QueuedGesture) {
        if (item.isShortTap && !item.isCoalescedReplay && isUserTouchingOverlay()) {
            cancelledTapTracker.recordCancelledTap(item.tapX, item.tapY, SystemClock.uptimeMillis())
        }
    }

    private fun handleGestureCancelled(item: QueuedGesture) {
        recordCancelledTapIfNeeded(item)
        if (!cleanedUp && !isUserTouchingOverlay()) {
            (cursorView as? CursorArrowView)?.triggerBlockedShake()
            hapticReject()
        }
    }

    private fun drainGestureQueue() {
        if (isDispatching) return
        val item = gestureQueue.removeFirstOrNull() ?: run {
            activeGestureItem = null
            scheduleTouchableRegionUpdate()
            flushPendingBoundsRebuild()
            return
        }
        isDispatching = true
        activeGestureItem = item
        val gen = ++gestureDispatchGen

        val api33FullPanel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isFullPanelVisible()
        if (api33FullPanel) {
            flushPanelTouchableRegionNow()
        }
        if (api33FullPanel && areItemStartPointsCoveredByPinhole(item)) {
            if (isPassThroughActive) {
                main.removeCallbacks(delayedRestoreTouchRunnable)
                setOverlayTouchable(true)
            }
            val startInsidePanel = isAnyPointInsidePanel(item.startPoints)
            val settleDelayMs = if (startInsidePanel) {
                resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs, SystemClock.uptimeMillis())
            } else {
                0L
            }

            cancelGestureWatchdog()
            val watchdogTimeout = resolveGestureWatchdogTimeoutMs(
                expectedMs = item.expectedMs,
                needsPassThrough = settleDelayMs > 0L,
                passThroughDelayMs = settleDelayMs,
            )
            val watchdog = Runnable {
                if (gestureDispatchGen != gen) return@Runnable
                activeGestureWatchdogRunnable = null
                pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
                pendingPassThroughDispatchRunnable = null
                main.removeCallbacks(delayedRestoreTouchRunnable)
                activeGestureItem = null
                isDispatching = false
                setOverlayTouchable(true)
                scheduleTouchableRegionUpdate()
                drainGestureQueue()
            }
            activeGestureWatchdogRunnable = watchdog
            main.postDelayed(watchdog, watchdogTimeout)

            val runPinholeDispatch = Runnable {
                if (gestureDispatchGen != gen) return@Runnable
                pendingPassThroughDispatchRunnable = null
                val ok = runCatching {
                    dispatchGesture(item.gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (gestureDispatchGen != gen) return
                            cancelGestureWatchdog()
                            activeGestureItem = null
                            isDispatching = false
                            runCatching { item.onComplete?.invoke() }
                            if (gestureDispatchGen != gen || isDispatching) return
                            if (gestureQueue.isNotEmpty()) {
                                drainGestureQueue()
                            } else {
                                scheduleTouchableRegionUpdate()
                                flushPendingBoundsRebuild()
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (gestureDispatchGen != gen) return
                            cancelGestureWatchdog()
                            activeGestureItem = null
                            isDispatching = false
                            handleGestureCancelled(item)
                            if (gestureQueue.isNotEmpty()) {
                                drainGestureQueue()
                            } else {
                                scheduleTouchableRegionUpdate()
                                flushPendingBoundsRebuild()
                            }
                        }
                    }, main)
                }.getOrDefault(false)
                if (!ok && gestureDispatchGen == gen) {
                    cancelGestureWatchdog()
                    activeGestureItem = null
                    isDispatching = false
                    if (gestureQueue.isNotEmpty()) {
                        drainGestureQueue()
                    } else {
                        scheduleTouchableRegionUpdate()
                        flushPendingBoundsRebuild()
                    }
                }
            }

            if (settleDelayMs <= 0L) {
                runPinholeDispatch.run()
            } else {
                pendingPassThroughDispatchRunnable = runPinholeDispatch
                main.postDelayed(runPinholeDispatch, settleDelayMs)
            }
            return
        }

        val needsPassThroughNow = isAnyPointInsidePanel(item.checkPoints)

        // 统一看门狗保护（覆盖穿透与直发两条路径）：若系统异常丢弃 GestureResultCallback，到期自动解锁队列并恢复面板可触摸
        cancelGestureWatchdog()
        val watchdogTimeout = resolveGestureWatchdogTimeoutMs(
            expectedMs = item.expectedMs,
            needsPassThrough = needsPassThroughNow,
            passThroughDelayMs = OVERLAY_PASS_THROUGH_DELAY_MS,
        )
        val watchdog = Runnable {
            if (gestureDispatchGen != gen) return@Runnable
            activeGestureWatchdogRunnable = null
            pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
            pendingPassThroughDispatchRunnable = null
            main.removeCallbacks(delayedRestoreTouchRunnable)
            activeGestureItem = null
            isDispatching = false
            setOverlayTouchable(true)
            drainGestureQueue()
        }
        activeGestureWatchdogRunnable = watchdog
        main.postDelayed(watchdog, watchdogTimeout)

        if (needsPassThroughNow && panel != null && panelParams != null) {
            // 取消上一笔手势可能尚未执行的 20ms 恢复回调与等待派发任务，杜绝恢复竞态
            main.removeCallbacks(delayedRestoreTouchRunnable)
            pendingPassThroughDispatchRunnable?.let { main.removeCallbacks(it) }
            pendingPassThroughDispatchRunnable = null

            // 仅当上一次 FLAG_NOT_TOUCHABLE 已完成 50ms 窗口同步且仍处于穿透态时才可跳过等待
            val alreadySyncedNonTouchable = passThroughSynced &&
                isPassThroughActive &&
                ((panelParams?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
            setOverlayTouchable(false)

            val runDispatch = Runnable {
                if (gestureDispatchGen != gen) return@Runnable
                pendingPassThroughDispatchRunnable = null
                passThroughSynced = isPassThroughActive
                val ok = runCatching {
                    dispatchGesture(item.gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (gestureDispatchGen != gen) return
                            cancelGestureWatchdog()
                            activeGestureItem = null
                            isDispatching = false
                            runCatching { item.onComplete?.invoke() }
                            if (gestureDispatchGen != gen) return
                            if (gestureQueue.isNotEmpty() && !isDispatching) {
                                drainGestureQueue()
                            }
                            if (gestureDispatchGen != gen || isDispatching) return
                            if (item.keepPassThroughAfterComplete) {
                                // 续接回调未能启动下一笔手势：立即恢复可触摸，杜绝穿透态残留
                                main.removeCallbacks(delayedRestoreTouchRunnable)
                                setOverlayTouchable(true)
                                flushPendingBoundsRebuild()
                            } else {
                                // 延迟 20ms 恢复可触摸，确保注入手势的 UP 事件在底层应用中完全处理完毕
                                main.removeCallbacks(delayedRestoreTouchRunnable)
                                main.postDelayed(delayedRestoreTouchRunnable, 20L)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (gestureDispatchGen != gen) return
                            cancelGestureWatchdog()
                            main.removeCallbacks(delayedRestoreTouchRunnable)
                            setOverlayTouchable(true)
                            activeGestureItem = null
                            isDispatching = false
                            handleGestureCancelled(item)
                            drainGestureQueue()
                        }
                    }, main)
                }.getOrDefault(false)
                if (!ok && gestureDispatchGen == gen) {
                    cancelGestureWatchdog()
                    main.removeCallbacks(delayedRestoreTouchRunnable)
                    setOverlayTouchable(true)
                    activeGestureItem = null
                    isDispatching = false
                    drainGestureQueue()
                }
            }

            if (shouldSkipPassThroughDelay(alreadySyncedNonTouchable)) {
                // 面板已处于同步完成的 FLAG_NOT_TOUCHABLE（例如拖拽 continueStroke 续接或连续穿透点击），立即派发零间隙
                runDispatch.run()
            } else {
                pendingPassThroughDispatchRunnable = runDispatch
                main.postDelayed(runDispatch, OVERLAY_PASS_THROUGH_DELAY_MS)
            }
        } else {
            // 光标在触控板外部，直接派发手势；若上一笔遗留了穿透恢复计时，立即恢复可触摸，
            // 绝不在直发手势期间保持 FLAG_NOT_TOUCHABLE。
            if (isPassThroughActive) {
                main.removeCallbacks(delayedRestoreTouchRunnable)
                setOverlayTouchable(true)
            }
            val ok = runCatching {
                dispatchGesture(item.gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (gestureDispatchGen != gen) return
                        cancelGestureWatchdog()
                        activeGestureItem = null
                        isDispatching = false
                        runCatching { item.onComplete?.invoke() }
                        if (gestureDispatchGen != gen || isDispatching) return
                        drainGestureQueue()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (gestureDispatchGen != gen) return
                        cancelGestureWatchdog()
                        activeGestureItem = null
                        isDispatching = false
                        handleGestureCancelled(item)
                        drainGestureQueue()
                    }
                }, main)
            }.getOrDefault(false)
            if (!ok && gestureDispatchGen == gen) {
                cancelGestureWatchdog()
                activeGestureItem = null
                isDispatching = false
                drainGestureQueue()
            }
        }
    }

    private fun dispatchCoalescedTaps(count: Int) {
        if (count <= 0) return
        val builder = GestureDescription.Builder()
        for (idx in 0 until count) {
            val tx = coalescedTapCoordsScratch[idx * 2]
            val ty = coalescedTapCoordsScratch[idx * 2 + 1]
            val path = Path().apply { moveTo(tx, ty) }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    computeCoalescedTapStartTimeMs(idx),
                    TAP_STROKE_DURATION_MS,
                ),
            )
        }
        val coords = coalescedTapCoordsScratch.copyOf(count * 2)
        dispatch(
            builder.build(),
            checkPoints = coords,
            startPoints = coords,
            expectedMs = computeCoalescedTapTotalDurationMs(count),
            isShortTap = false,
            isCoalescedReplay = true,
        )
    }

    private fun flushCancelledTapsIfIdle() {
        if (isUserTouchingOverlay() || cancelledTapTracker.size <= 0) return
        val count = cancelledTapTracker.drainForCoalescedTapInto(
            coalescedTapCoordsScratch,
            SystemClock.uptimeMillis(),
            includeNewTap = false,
        )
        if (count >= 1) {
            dispatchCoalescedTaps(count)
        }
    }

    private fun tapAt(x: Float, y: Float, ms: Long = TAP_STROKE_DURATION_MS, showFeedbackRing: Boolean = true) {
        val sx = clampGestureCoord(x, screenW)
        val sy = clampGestureCoord(y, screenH)
        if (ms == TAP_STROKE_DURATION_MS) {
            (cursorView as? CursorArrowView)?.triggerClickPeck()
        }
        if (showFeedbackRing) showTouchRing(sx, sy, ms)
        if (ms == TAP_STROKE_DURATION_MS && !isUserTouchingOverlay() && cancelledTapTracker.size > 0) {
            val count = cancelledTapTracker.drainForCoalescedTapInto(
                coalescedTapCoordsScratch,
                SystemClock.uptimeMillis(),
                includeNewTap = true,
                newX = sx,
                newY = sy,
            )
            if (count >= 2) {
                dispatchCoalescedTaps(count)
                return
            }
        }
        val path = Path().apply { moveTo(sx, sy) }
        val point = floatArrayOf(sx, sy)
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            checkPoints = point,
            startPoints = point,
            expectedMs = ms,
            isShortTap = ms == TAP_STROKE_DURATION_MS,
            isCoalescedReplay = false,
            tapX = sx,
            tapY = sy,
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

        val checkPoints = floatArrayOf(
            plan.startX, plan.startY,
            (plan.startX + plan.endX) * 0.5f, (plan.startY + plan.endY) * 0.5f,
            plan.endX, plan.endY,
            plan.targetX, plan.targetY,
        )

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
            checkPoints = checkPoints,
            startPoints = floatArrayOf(plan.startX, plan.startY),
            expectedMs = plan.durationMs,
        )
        hapticRelease()
    }

    private fun swipeBy(dx: Float, dy: Float, ms: Long) {
        val x0 = clampGestureCoord(cursorX, screenW)
        val y0 = clampGestureCoord(cursorY, screenH)
        val x1 = clampGestureCoord(x0 + dx, screenW)
        val y1 = clampGestureCoord(y0 + dy, screenH)
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            checkPoints = floatArrayOf(x0, y0, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, x1, y1),
            startPoints = floatArrayOf(x0, y0),
            expectedMs = ms,
        )
    }

    private fun startDrag() {
        dragging = true
        dragTrail.clear()
        dragTrail += TrailPoint(cursorX, cursorY)
        flushPanelTouchableRegionNow()
        if (cachedTouchRingEnabled) {
            showTouchRing(cursorX, cursorY, 180)
        }
        (cursorView as? CursorArrowView)?.setDragArmed(true, triggerPop = true)
        hapticLatch()
        refreshDragButtons()
    }

    /** 结束拖拽状态（不注入任何手势），圆环在当前位置收缩。 */
    private fun cancelDrag() {
        dragging = false
        dragTrail.clear()
        (cursorView as? CursorArrowView)?.setDragArmed(false)
        scheduleTouchableRegionUpdate()
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
        hapticRelease()
        val start = trail.firstOrNull() ?: return
        // 没移动就再按一次 = 取消
        if (trailLength(trail) < dp(8)) return
        val startX = clampGestureCoord(start.x, screenW)
        val startY = clampGestureCoord(start.y, screenH)
        val dragPoints = FloatArray(trail.size * 2)
        trail.forEachIndexed { idx, pt ->
            dragPoints[idx * 2] = clampGestureCoord(pt.x, screenW)
            dragPoints[idx * 2 + 1] = clampGestureCoord(pt.y, screenH)
        }
        val startPoints = floatArrayOf(startX, startY)
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
                checkPoints = dragPoints,
                startPoints = startPoints,
                expectedMs = hold + moveMs,
            )
            return
        }
        dispatch(
            GestureDescription.Builder().addStroke(holdStroke).build(),
            checkPoints = dragPoints,
            startPoints = startPoints,
            expectedMs = hold,
            keepPassThroughAfterComplete = true,
        ) {
            dispatchFront(
                GestureDescription.Builder().addStroke(moveStroke).build(),
                checkPoints = dragPoints,
                startPoints = startPoints,
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
    private fun ensureTouchRing(): TouchRingView? {
        touchRing?.let { v ->
            touchRingParams?.let { lp ->
                if (touchRingNeedsRaise) {
                    touchRingNeedsRaise = false
                    runCatching { wm.removeViewImmediate(v) }
                    runCatching { wm.addView(v, lp) }
                }
                return v
            }
        }
        val density = resources.displayMetrics.density
        val radius = dp(TOUCH_RING_RADIUS_DP).toFloat()
        val stroke = 2.6f * density
        // 预留充分空间容纳旋转光弧外扩光晕与四散三角粒子火花，确保不发生窗口边缘裁切
        val size = computeSparkWindowSizePx(radius, stroke, density)
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
        return v
    }

    private fun moveTouchRing(x: Float, y: Float, immediate: Boolean = false) {
        val v = ensureTouchRing() ?: return
        val lp = touchRingParams ?: return
        val nx = (x - lp.width / 2f).roundToInt()
        val ny = (y - lp.height / 2f).roundToInt()
        if (lp.x == nx && lp.y == ny) return
        val oldX = lp.x
        val oldY = lp.y
        lp.x = nx
        lp.y = ny
        v.offsetParticles((oldX - nx).toFloat(), (oldY - ny).toFloat())
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
        if (withHaptic && action != PadAction.DRAG_LOCK) {
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
        val sizePx = p.width.toFloat()
        val ox = cursorViewOriginCoord(cursorX, sizePx)
        val oy = cursorViewOriginCoord(cursorY, sizePx)
        if (!immediate && p.x == ox && p.y == oy) return
        p.x = ox
        p.y = oy
        if (immediate) {
            pendingCursorLayout = false
            pendingTouchableRegionUpdate = false
            cursorView?.let { runCatching { wm.updateViewLayout(it, p) } }
            flushPanelTouchableRegionNow()
        } else {
            pendingCursorLayout = true
            pendingTouchableRegionUpdate = true
            scheduleLayoutFlush()
        }
    }

    private fun performHapticConstant(constant: Int, view: View? = null) {
        if (!cachedHaptics) return
        runCatching {
            (view ?: miniBallView ?: padArea ?: panel)?.performHapticFeedback(
                constant,
                @Suppress("DEPRECATION")
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    private fun haptic(view: View? = null) {
        performHapticConstant(android.view.HapticFeedbackConstants.VIRTUAL_KEY, view)
    }

    private fun hapticEdgeTick() {
        performHapticConstant(android.view.HapticFeedbackConstants.CLOCK_TICK, padArea ?: panel)
    }

    private fun hapticLatch(view: View? = null) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.HapticFeedbackConstants.CONFIRM
        } else {
            android.view.HapticFeedbackConstants.LONG_PRESS
        }
        performHapticConstant(constant, view)
    }

    private fun hapticRelease(view: View? = null) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.HapticFeedbackConstants.GESTURE_END
        } else {
            android.view.HapticFeedbackConstants.KEYBOARD_TAP
        }
        performHapticConstant(constant, view)
    }

    private fun hapticReject() {
        if (!cachedHaptics) return
        val nowMs = SystemClock.uptimeMillis()
        if (lastRejectHapticMs >= 0L && nowMs - lastRejectHapticMs < 180L) return
        lastRejectHapticMs = nowMs
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.HapticFeedbackConstants.REJECT
        } else {
            android.view.HapticFeedbackConstants.KEYBOARD_TAP
        }
        performHapticConstant(constant)
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
