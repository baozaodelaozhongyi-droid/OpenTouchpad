package io.github.opentouchpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 设置界面：分组卡片 + 大号点击区域（每行至少 54dp 高）。
 * 这个 App 的用户可能很难精准点击，所以整行都可点，开关点文字也能切换。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroller: ScrollView
    private lateinit var baseScroller: ScrollView
    private lateinit var rootContainer: FrameLayout

    class SliderController(val setValue: (Int) -> Unit)

    private class CollapsibleCardController(
        val id: String,
        private val handler: (expand: Boolean, animate: Boolean, startDelay: Long) -> Unit,
        val isExpanded: () -> Boolean,
        private val cancelHandler: () -> Unit,
    ) {
        fun setExpanded(expand: Boolean, animate: Boolean = true, startDelay: Long = 0L) {
            handler(expand, animate, startDelay)
        }

        fun cancelAnimation() {
            cancelHandler()
        }
    }

    /** 单次 buildUi() 作用域内共享的主题 ColorStateList 集合，避免每个开关/滑块/按钮重复分配。 */
    private class UiColorStateLists(theme: SettingsTheme, isDark: Boolean) {
        val accent: ColorStateList = ColorStateList.valueOf(theme.accentColor)
        val track: ColorStateList = ColorStateList.valueOf(theme.trackColor)
        val ripple: ColorStateList = ColorStateList.valueOf(theme.rippleColor)
        val text: ColorStateList = ColorStateList.valueOf(theme.textColor)
        val tertiaryText: ColorStateList = ColorStateList.valueOf(theme.tertiaryTextColor)
        val switchThumb: ColorStateList = ColorStateList(
            SWITCH_STATES,
            intArrayOf(theme.accentColor, if (isDark) 0xFFB6B7BC.toInt() else 0xFFFFFFFF.toInt()),
        )
        val switchTrack: ColorStateList = ColorStateList(
            SWITCH_STATES,
            intArrayOf(theme.accentSoftStrongColor, theme.trackStrongColor),
        )
    }

    /** 每个 ScrollView 视图树独占的绑定上下文，确保主题扩散双树与惰性卡片展开期间绝不串色或丢引用。 */
    private class UiBindings(
        val isDark: Boolean,
        val colors: UiColorStateLists,
    ) {
        lateinit var content: LinearLayout
        lateinit var statusView: TextView
        var showPanelSwitch: Switch? = null
        var keepAliveSwitch: Switch? = null
        var batteryStatusTextView: TextView? = null
        var widthSliderController: SliderController? = null
        var heightSliderController: SliderController? = null
        val cardControllers = mutableListOf<CollapsibleCardController>()

        fun cancelAllCardAnimations() {
            cardControllers.forEach { it.cancelAnimation() }
        }
    }

    private lateinit var baseBindings: UiBindings
    private var revealBindings: UiBindings? = null
    private var activeBindings: UiBindings? = null
    private var pendingKeepAliveToggle: ((Boolean) -> Unit)? = null
    private var activeDialog: AlertDialog? = null
    private var darkUi = false
    private var baseThemeDark = false
    private var currentReveal: RevealLayout? = null
    private var currentRevealDark = false
    private var appliedStatusBarDark: Boolean? = null
    private var appliedNavBarDark: Boolean? = null
    private var lastInsetsLeft = 0
    private var lastInsetsTop = 0
    private var lastInsetsRight = 0
    private var lastInsetsBottom = 0
    private var activeAnimator: ValueAnimator? = null
    private var isSyncingScroll = false
    private var isActivityStarted = false
    private var settingsBgImageView: ImageView? = null
    private var settingsBgScrimView: View? = null
    private var settingsBgBitmap: android.graphics.Bitmap? = null
    private var contentHost: FrameLayout? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var bgIoExecutor: ThreadPoolExecutor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRecreateRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            recreateKeepingScroll()
        }
    }

    private fun getBgIoExecutor(): ThreadPoolExecutor {
        return bgIoExecutor ?: ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }.also { bgIoExecutor = it }
    }

    private fun allActiveBindings(): List<UiBindings> {
        val list = ArrayList<UiBindings>(2)
        if (::baseBindings.isInitialized) list.add(baseBindings)
        revealBindings?.let { if (it !== baseBindings) list.add(it) }
        return list
    }

    private inline fun <T> withUiContext(bindings: UiBindings, block: () -> T): T {
        val prevDark = darkUi
        val prevBindings = activeBindings
        darkUi = bindings.isDark
        activeBindings = bindings
        return try {
            block()
        } finally {
            darkUi = prevDark
            activeBindings = prevBindings
        }
    }

    private fun currentColorStateLists(): UiColorStateLists =
        activeBindings?.colors ?: UiColorStateLists(currentTheme, darkUi)

    private fun showTrackedDialog(dialog: AlertDialog) {
        dismissActiveDialog()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        if (!isFinishing && !isDestroyed) {
            dialog.show()
        } else {
            activeDialog = null
        }
    }

    private fun dismissActiveDialog() {
        activeDialog?.let { runCatching { if (it.isShowing) it.dismiss() } }
        activeDialog = null
    }

    private var cachedLightTheme: SettingsTheme? = null
    private var cachedDarkTheme: SettingsTheme? = null
    private var cachedThemeDynamic = false
    private var cachedThemeAccent = 0
    private var cachedDynamicAccentLight: Int? = null
    private var cachedDynamicAccentDark: Int? = null
    private var cachedHasCustomSettingsBg: Boolean? = null

    private fun invalidateThemeCache() {
        cachedLightTheme = null
        cachedDarkTheme = null
        cachedDynamicAccentLight = null
        cachedDynamicAccentDark = null
    }

    private fun getThemeFor(isDark: Boolean): SettingsTheme {
        val dyn = prefs.dynamicColor
        val accent = prefs.uiAccentColor
        if (cachedThemeDynamic != dyn || cachedThemeAccent != accent) {
            cachedLightTheme = null
            cachedDarkTheme = null
            cachedThemeDynamic = dyn
            cachedThemeAccent = accent
        }
        return if (isDark) {
            cachedDarkTheme ?: resolveSettingsTheme(this, true, dyn, accent).also { cachedDarkTheme = it }
        } else {
            cachedLightTheme ?: resolveSettingsTheme(this, false, dyn, accent).also { cachedLightTheme = it }
        }
    }

    private fun getDynamicAccent(isDark: Boolean): Int =
        if (isDark) {
            cachedDynamicAccentDark ?: resolveDynamicAccentColor(this, true).also { cachedDynamicAccentDark = it }
        } else {
            cachedDynamicAccentLight ?: resolveDynamicAccentColor(this, false).also { cachedDynamicAccentLight = it }
        }

    private val currentTheme: SettingsTheme
        get() = getThemeFor(darkUi)

    private val pageColor get() = currentTheme.pageColor
    private val surfaceColor get() = currentTheme.surfaceColor
    private val elevatedColor get() = currentTheme.elevatedColor
    private val trackColor get() = currentTheme.trackColor
    private val trackStrongColor get() = currentTheme.trackStrongColor
    private val textColor get() = currentTheme.textColor
    private val secondaryTextColor get() = currentTheme.secondaryTextColor
    private val tertiaryTextColor get() = currentTheme.tertiaryTextColor
    private val borderColor get() = currentTheme.borderColor
    private val accentColor get() = currentTheme.accentColor
    private val onAccentColor get() = currentTheme.onAccentColor
    private val accentSoftColor get() = currentTheme.accentSoftColor
    private val accentSoftStrongColor get() = currentTheme.accentSoftStrongColor
    private val rippleColor get() = currentTheme.rippleColor
    private val dangerColor get() = currentTheme.dangerColor
    private val hasCustomSettingsBg: Boolean
        get() = cachedHasCustomSettingsBg ?: CustomBackgroundManager.hasSettingsBg(this).also {
            cachedHasCustomSettingsBg = it
        }
    private val cardBgColor
        get() = if (hasCustomSettingsBg) {
            Color.argb(230, Color.red(surfaceColor), Color.green(surfaceColor), Color.blue(surfaceColor))
        } else {
            surfaceColor
        }

    /** 标题用衬线体（内置 Noto Serif SC Medium 子集，全局缓存），正文/控件用系统无衬线体。 */
    private val serif: Typeface
        get() = cachedSerif ?: runCatching { resources.getFont(R.font.serif_medium) }
            .getOrDefault(Typeface.SERIF)
            .also { cachedSerif = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        applyExcludeFromRecents(prefs.excludeFromRecents)
        baseThemeDark = prefs.themeMode.resolvesToDark(systemIsDark())
        darkUi = baseThemeDark
        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)

        val (initialScroller, initialBindings) = buildUi(baseThemeDark)
        baseScroller = initialScroller
        baseBindings = initialBindings
        scroller = baseScroller
        attachScrollSync(baseScroller)
        val host = FrameLayout(this).apply {
            addView(baseScroller, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        contentHost = host

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(pageColor)

            val bgIv = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            settingsBgImageView = bgIv
            addView(bgIv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val scrimView = View(this@MainActivity).apply {
                visibility = View.GONE
            }
            settingsBgScrimView = scrimView
            addView(scrimView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            addView(host, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            applySettingsBackground()
            // Android 15 起强制全面屏：内容会画到状态栏/刘海区下面，这里按系统栏与挖孔安全区四边补内边距。
            setOnApplyWindowInsetsListener { _, insets ->
                val left: Int
                val top: Int
                val right: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    left = bars.left
                    top = bars.top
                    right = bars.right
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    val sysLeft = insets.systemWindowInsetLeft
                    @Suppress("DEPRECATION")
                    val sysTop = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    val sysRight = insets.systemWindowInsetRight
                    @Suppress("DEPRECATION")
                    val sysBottom = insets.systemWindowInsetBottom
                    if (Build.VERSION.SDK_INT >= 28) {
                        val cutout = insets.displayCutout
                        left = maxOf(sysLeft, cutout?.safeInsetLeft ?: 0)
                        top = maxOf(sysTop, cutout?.safeInsetTop ?: 0)
                        right = maxOf(sysRight, cutout?.safeInsetRight ?: 0)
                        bottom = maxOf(sysBottom, cutout?.safeInsetBottom ?: 0)
                    } else {
                        left = sysLeft
                        top = sysTop
                        right = sysRight
                        bottom = sysBottom
                    }
                }
                lastInsetsLeft = left
                lastInsetsTop = top
                lastInsetsRight = right
                lastInsetsBottom = bottom
                host.let { h ->
                    for (i in 0 until h.childCount) {
                        val c = h.getChildAt(i)
                        val s = when (c) {
                            is ScrollView -> c
                            is RevealLayout -> c.scroller
                            else -> null
                        }
                        s?.let { applyScrollInsets(it, left, top, right, bottom) }
                    }
                }
                insets
            }
        }
        setContentView(rootContainer)
        updateSystemBars()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback { moveTaskToBack(true) }
            backInvokedCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                cb
            )
        }

        val savedScroll = savedInstanceState?.getInt(KEY_SCROLL_Y, 0) ?: 0
        if (savedScroll > 0) {
            // 等内容测量完再滚动，否则 ScrollView 还没有可滚动高度
            scroller.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroller.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    scroller.scrollTo(0, savedScroll)
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let {
                runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            }
            backInvokedCallback = null
        }
        mainHandler.removeCallbacks(pendingRecreateRunnable)
        dismissActiveDialog()
        pendingKeepAliveToggle = null
        allActiveBindings().forEach { it.cancelAllCardAnimations() }
        revealBindings = null
        if (::rootContainer.isInitialized) {
            rootContainer.setOnApplyWindowInsetsListener(null)
        }
        // 使用优雅 shutdown() 让正在落盘的后台写图任务安全完成并自动回收线程，不中断 IO
        bgIoExecutor?.shutdown()
        bgIoExecutor = null
        val oldAnim = activeAnimator
        activeAnimator = null
        oldAnim?.cancel()
        currentReveal?.release()
        currentReveal = null
        settingsBgImageView?.setImageDrawable(null)
        settingsBgBitmap?.recycle()
        settingsBgBitmap = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 退到后台保活，不销毁 Activity，让多任务列表中的卡片保持运行状态，以便系统允许加锁
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            prefs.keepAliveNotification = granted
            TouchpadService.instance?.updateKeepAliveNotification()
            allActiveBindings().mapNotNull { it.keepAliveSwitch }.forEach { sw ->
                sw.tag = SYNCING
                sw.isChecked = granted
                sw.tag = null
            }
            pendingKeepAliveToggle?.invoke(granted)
            pendingKeepAliveToggle = null
            if (!granted) {
                toast(getString(R.string.notification_permission_denied))
            }
        }
    }

    private fun applySettingsBackground() {
        val bgFile = CustomBackgroundManager.getSettingsBgFile(this)
        if (bgFile.exists() && bgFile.length() > 0) {
            if (settingsBgBitmap == null || settingsBgBitmap?.isRecycled == true) {
                val dm = resources.displayMetrics
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1080)
                settingsBgBitmap = CustomBackgroundManager.loadBackgroundBitmap(bgFile, maxDim)
            }
            val bmp = settingsBgBitmap
            if (bmp != null) {
                settingsBgImageView?.setImageBitmap(bmp)
                settingsBgImageView?.visibility = View.VISIBLE
                val scrimColor = computeSettingsBgScrimColor(pageColor, prefs.settingsBgScrimPercent)
                settingsBgScrimView?.setBackgroundColor(scrimColor)
                settingsBgScrimView?.visibility = View.VISIBLE
                return
            }
        }
        settingsBgImageView?.setImageDrawable(null)
        settingsBgImageView?.visibility = View.GONE
        settingsBgScrimView?.visibility = View.GONE
    }

    private fun launchImagePicker(requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"
                }
                startActivityForResult(intent, requestCode)
                return
            } catch (_: Exception) {
                // 部分定制系统可能未启用 Photo Picker，回退到系统 SAF 文件选择器
            }
        }
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            toast(getString(R.string.bg_image_failed))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return
            val appContext = applicationContext
            when (requestCode) {
                REQUEST_CODE_PICK_TOUCHPAD_BG -> {
                    getBgIoExecutor().execute {
                        val success = CustomBackgroundManager.saveBackgroundFromUri(
                            appContext, uri, CustomBackgroundManager.getTouchpadBgSaveFile(appContext), maxDim = 1200
                        )
                        mainHandler.post {
                            if (success) {
                                TouchpadService.instance?.reloadTouchpadBackground()
                            }
                            if (!isFinishing && !isDestroyed) {
                                if (success) {
                                    toast(getString(R.string.bg_image_applied))
                                    recreateKeepingScroll()
                                } else {
                                    toast(getString(R.string.bg_image_failed))
                                }
                            }
                        }
                    }
                }
                REQUEST_CODE_PICK_SETTINGS_BG -> {
                    val dm = resources.displayMetrics
                    val maxDim = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1080)
                    getBgIoExecutor().execute {
                        val success = CustomBackgroundManager.saveBackgroundFromUri(
                            appContext, uri, CustomBackgroundManager.getSettingsBgSaveFile(appContext), maxDim = maxDim
                        )
                        mainHandler.post {
                            if (!isFinishing && !isDestroyed) {
                                if (success) {
                                    toast(getString(R.string.bg_image_applied))
                                    // 不在此处提前 recycle 或解码新图：recreate() 会同步先走旧实例 onDestroy() 释放旧图，
                                    // 再走新实例 onCreate() 仅解码一次新图，消除双全屏 Bitmap 峰值与 1 帧纯色闪屏。
                                    cachedHasCustomSettingsBg = true
                                    recreateKeepingScroll()
                                } else {
                                    toast(getString(R.string.bg_image_failed))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        if (hasCustomSettingsBg && (settingsBgBitmap == null || settingsBgBitmap?.isRecycled == true)) {
            applySettingsBackground()
        }
        TouchpadService.onPanelResized = { w, h ->
            runOnUiThread {
                updateResizedSliders(w, h)
            }
        }
    }

    override fun onStop() {
        isActivityStarted = false
        super.onStop()
        TouchpadService.onPanelResized = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 设置页退到后台保活（moveTaskToBack）且完全不可见时，释放全屏壁纸 Bitmap（10–36 MB）与主题缓存，
        // 切回前台时 onStart() 自动无感恢复，保留多任务卡片预览与后台加锁能力。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !isActivityStarted) {
            if (activeAnimator != null) {
                // 先同步完结进行中的主题扩散动画，避免仅 cancel() 导致半屏 RevealLayout 卡死
                activeAnimator?.end()
            }
            currentReveal?.release()
            settingsBgImageView?.setImageDrawable(null)
            settingsBgImageView?.visibility = View.GONE
            settingsBgScrimView?.visibility = View.GONE
            settingsBgBitmap?.recycle()
            settingsBgBitmap = null
            invalidateThemeCache()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onResume() {
        super.onResume()
        invalidateThemeCache()
        applyExcludeFromRecents(prefs.excludeFromRecents)
        val bindingsList = allActiveBindings()
        bindingsList.forEach { b ->
            updateStatus(b.statusView, b.isDark)
        }
        // 用户可能在面板上点了收起/展开，回到设置页时同步开关（不触发回调）
        val shown = !prefs.minimized
        bindingsList.mapNotNull { it.showPanelSwitch }.forEach { sw ->
            if (sw.isChecked != shown) {
                sw.tag = SYNCING
                sw.isChecked = shown
                sw.tag = null
            }
        }
        // 若用户在系统设置中撤销了通知权限，回到设置页时自动同步关闭开关状态
        if (prefs.keepAliveNotification && !hasNotificationPermission()) {
            prefs.keepAliveNotification = false
            TouchpadService.instance?.updateKeepAliveNotification()
        }
        bindingsList.mapNotNull { it.keepAliveSwitch }.forEach { sw ->
            if (sw.isChecked != prefs.keepAliveNotification) {
                sw.tag = SYNCING
                sw.isChecked = prefs.keepAliveNotification
                sw.tag = null
            }
        }
        val batteryLabel = if (isIgnoringBatteryOptimizations()) {
            getString(R.string.btn_battery_whitelisted)
        } else {
            getString(R.string.btn_request_battery_whitelist)
        }
        bindingsList.mapNotNull { it.batteryStatusTextView }.forEach { tv ->
            tv.text = batteryLabel
        }
        val maxPanelWidth = pixelsToDp(fullScreenWidthPx(), resources.displayMetrics.density)
            .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
        val w = if (prefs.panelWidthDp > 0) {
            prefs.panelWidthDp
        } else {
            maxPanelWidth * prefs.padWidthPercent / 100
        }
        updateResizedSliders(w, prefs.extraHeightDp)
    }

    private fun updateResizedSliders(widthDp: Int, extraHeightDp: Int) {
        allActiveBindings().forEach { b ->
            b.widthSliderController?.setValue(widthDp)
            b.heightSliderController?.setValue(extraHeightDp)
        }
    }

    // ───────────────────────── 界面骨架 ─────────────────────────

    private fun buttonModeLabel(count: Int): String = when (count) {
        4 -> getString(R.string.btn_mode_4)
        8 -> getString(R.string.btn_mode_8)
        12 -> getString(R.string.btn_mode_12)
        18 -> getString(R.string.btn_mode_18)
        else -> getString(R.string.btn_mode_16)
    }

    private fun buildUi(isDark: Boolean): Pair<ScrollView, UiBindings> {
        val bindings = UiBindings(isDark, UiColorStateLists(getThemeFor(isDark), isDark))
        val scrollView = withUiContext(bindings) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(40))
            }
            bindings.content = col

            col.addView(hero(bindings))

            val uiCardControllers = bindings.cardControllers
            var uiExpandAllBtn: TextView? = null

            fun updateExpandAllText() {
                val allExpanded = uiCardControllers.isNotEmpty() && uiCardControllers.all { it.isExpanded() }
                uiExpandAllBtn?.text = getString(if (allExpanded) R.string.collapse_all else R.string.expand_all)
            }

            col.addView(categoriesBar(uiCardControllers) { uiExpandAllBtn = it })

            fun addCollapsible(
                cardId: String,
                title: String,
                iconResId: Int? = null,
                summary: String? = null,
                defaultExpanded: Boolean = false,
                bindSummaryChip: ((TextView) -> Unit)? = null,
                rowsBuilder: () -> List<View>,
            ) {
                col.addView(collapsibleCard(
                    cardId = cardId,
                    title = title,
                    iconResId = iconResId,
                    summary = summary,
                    defaultExpanded = defaultExpanded,
                    onToggle = { updateExpandAllText() },
                    controllers = uiCardControllers,
                    bindSummaryChip = bindSummaryChip,
                    rowsBuilder = rowsBuilder,
                ))
            }

            // ── 外观 ──
            val appearanceSummary = buildAppearanceSummary()
            addCollapsible(
                cardId = "appearance",
                title = getString(R.string.sec_appearance),
                iconResId = R.drawable.ic_lu_layers_2,
                summary = appearanceSummary,
                defaultExpanded = true,
            ) {
                val hasSettingsBg = hasCustomSettingsBg
                val appearanceRows = mutableListOf<View>(
                    themeModeRow(),
                    switchRow(
                        getString(R.string.switch_dynamic_color),
                        prefs.dynamicColor,
                        hint = getString(R.string.switch_dynamic_color_hint),
                        onChange = {
                            prefs.dynamicColor = it
                            invalidateThemeCache()
                            TouchpadService.instance?.let { svc ->
                                svc.reload()
                                svc.updateTouchRingAppearance()
                            }
                            mainHandler.removeCallbacks(pendingRecreateRunnable)
                            mainHandler.postDelayed(pendingRecreateRunnable, 280L)
                        },
                    ),
                    colorRow(
                        getString(R.string.set_ui_accent_color),
                        UI_ACCENT_COLORS,
                        { prefs.uiAccentColor },
                        { prefs.uiAccentColor = it; invalidateThemeCache() },
                        onServiceUpdate = {
                            TouchpadService.instance?.let { svc ->
                                svc.reload()
                                svc.updateTouchRingAppearance()
                            }
                        },
                        allowCustomHex = true,
                        recreateOnChange = true,
                    ),
                    customBackgroundRow(
                        title = getString(R.string.set_settings_bg),
                        hint = getString(R.string.set_settings_bg_hint),
                        hasCustomBg = hasSettingsBg,
                        onPick = {
                            launchImagePicker(REQUEST_CODE_PICK_SETTINGS_BG)
                        },
                        onClear = {
                            CustomBackgroundManager.clearSettingsBg(this)
                            cachedHasCustomSettingsBg = false
                            currentReveal?.release()
                            settingsBgImageView?.setImageDrawable(null)
                            settingsBgBitmap = null
                            toast(getString(R.string.bg_image_cleared))
                            recreateKeepingScroll()
                        },
                    ),
                )
                if (hasSettingsBg) {
                    appearanceRows.add(
                        slider(
                            getString(R.string.set_settings_bg_scrim),
                            10,
                            95,
                            prefs.settingsBgScrimPercent,
                            unit = "%",
                            hint = getString(R.string.set_settings_bg_scrim_hint),
                            onChange = {
                                prefs.settingsBgScrimPercent = it
                                settingsBgScrimView?.setBackgroundColor(computeSettingsBgScrimColor(pageColor, it))
                            },
                        )
                    )
                }
                appearanceRows
            }

            // ── 触控板 ──
            val initMaxPanelWidth = pixelsToDp(fullScreenWidthPx(), resources.displayMetrics.density)
                .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
            val initPanelWidth = (prefs.panelWidthDp.takeIf { it > 0 }
                ?: (initMaxPanelWidth * prefs.padWidthPercent / 100)).coerceIn(PANEL_MIN_WIDTH_DP, initMaxPanelWidth)
            val padSummary = "${initPanelWidth}dp · ${prefs.opacityPercent}%"
            addCollapsible(
                cardId = "pad",
                title = getString(R.string.sec_pad),
                iconResId = R.drawable.ic_lu_touchpad,
                summary = padSummary,
                defaultExpanded = true,
            ) {
                val maxPanelWidth = pixelsToDp(fullScreenWidthPx(), resources.displayMetrics.density)
                    .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
                val currentPanelWidth = (prefs.panelWidthDp.takeIf { it > 0 }
                    ?: (maxPanelWidth * prefs.padWidthPercent / 100)).coerceIn(PANEL_MIN_WIDTH_DP, maxPanelWidth)
                val hasTouchpadBg = CustomBackgroundManager.hasTouchpadBg(this)
                val padRows = mutableListOf(
                    slider(
                        getString(R.string.set_pad_width),
                        PANEL_MIN_WIDTH_DP,
                        maxPanelWidth,
                        currentPanelWidth,
                        unit = "dp",
                        bind = { bindings.widthSliderController = it },
                        onChange = {
                            prefs.panelWidthDp = it
                            TouchpadService.instance?.updatePanelGeometry()
                        },
                    ),
                    slider(
                        getString(R.string.set_pad_height),
                        0,
                        CONTROL_EXTRA_HEIGHT_MAX_DP,
                        prefs.extraHeightDp.coerceIn(0, CONTROL_EXTRA_HEIGHT_MAX_DP),
                        unit = "dp",
                        hint = getString(R.string.pad_height_hint),
                        bind = { bindings.heightSliderController = it },
                        onChange = {
                            prefs.extraHeightDp = it
                            TouchpadService.instance?.updatePanelGeometry()
                        },
                    ),
                    colorRow(
                        getString(R.string.set_touchpad_color),
                        TOUCHPAD_COLORS,
                        { prefs.touchpadColor },
                        { prefs.touchpadColor = it },
                        onServiceUpdate = { TouchpadService.instance?.updatePanelAppearance() },
                        allowCustomHex = true,
                        recreateOnChange = false,
                    ),
                    customBackgroundRow(
                        title = getString(R.string.set_touchpad_bg),
                        hint = getString(R.string.set_touchpad_bg_hint),
                        hasCustomBg = hasTouchpadBg,
                        onPick = {
                            launchImagePicker(REQUEST_CODE_PICK_TOUCHPAD_BG)
                        },
                        onClear = {
                            CustomBackgroundManager.clearTouchpadBg(this)
                            TouchpadService.instance?.reloadTouchpadBackground()
                            toast(getString(R.string.bg_image_cleared))
                            recreateKeepingScroll()
                        },
                    ),
                )
                if (hasTouchpadBg) {
                    padRows.add(
                        slider(
                            getString(R.string.set_touchpad_bg_focus_y),
                            0,
                            100,
                            prefs.touchpadBgFocusYPercent,
                            unit = "%",
                            hint = getString(R.string.set_touchpad_bg_focus_y_hint),
                            onChange = {
                                prefs.touchpadBgFocusYPercent = it
                                TouchpadService.instance?.updatePanelAppearance()
                            },
                        )
                    )
                    padRows.add(
                        slider(
                            getString(R.string.set_touchpad_bg_focus_x),
                            0,
                            100,
                            prefs.touchpadBgFocusXPercent,
                            unit = "%",
                            hint = getString(R.string.set_touchpad_bg_focus_x_hint),
                            onChange = {
                                prefs.touchpadBgFocusXPercent = it
                                TouchpadService.instance?.updatePanelAppearance()
                            },
                        )
                    )
                }
                padRows.add(
                    slider(
                        getString(R.string.set_opacity),
                        20,
                        100,
                        prefs.opacityPercent,
                        unit = "%",
                        onChange = {
                            prefs.opacityPercent = it
                            TouchpadService.instance?.updatePanelAppearance()
                        },
                    )
                )
                padRows.add(
                    switchRow(
                        getString(R.string.switch_show_panel),
                        !prefs.minimized,
                        hint = getString(R.string.switch_show_panel_hint),
                        bind = { bindings.showPanelSwitch = it },
                    ) { show ->
                        val svc = TouchpadService.instance
                        if (svc == null) {
                            toast(getString(R.string.status_off))
                        } else if (show == prefs.minimized) {
                            svc.toggleMinimize()
                        }
                    }
                )
                padRows.add(
                    actionRow(getString(R.string.btn_reset_position)) {
                        prefs.resetPositions()
                        reload()
                    }
                )
                padRows
            }

            // ── 按钮 ──
            var buttonsSummaryChip: TextView? = null
            addCollapsible(
                cardId = "buttons",
                title = getString(R.string.sec_buttons),
                iconResId = R.drawable.ic_lu_mouse_pointer_click,
                summary = buttonModeLabel(prefs.buttonCount),
                defaultExpanded = true,
                bindSummaryChip = { buttonsSummaryChip = it },
            ) {
                val slotMapHost = FrameLayout(this)
                fun refreshSlotMap() {
                    withUiContext(bindings) {
                        slotMapHost.removeAllViews()
                        slotMapHost.addView(buttonSlotMap(bindings))
                    }
                }
                refreshSlotMap()

                var holdSlider: View? = null
                listOf(
                    buttonLayoutModeRow(bindings) { newCount ->
                        buttonsSummaryChip?.text = buttonModeLabel(newCount)
                        refreshSlotMap()
                    },
                    slotMapHost,
                    FrameLayout(this).apply {
                        setPadding(dp(16), 0, dp(16), dp(10))
                        addView(hintText(getString(R.string.drag_lock_hint)))
                    },
                    slider(
                        getString(R.string.set_button_spacing),
                        0,
                        BUTTON_SPACING_MAX_DP,
                        prefs.buttonSpacingDp,
                        unit = "dp",
                        onChange = {
                            prefs.buttonSpacingDp = it
                            TouchpadService.instance?.updatePanelGeometry()
                        },
                    ),
                    switchRow(
                        getString(R.string.switch_long_press_customize),
                        prefs.longPressButtonToCustomize,
                        hint = getString(R.string.switch_long_press_customize_hint),
                        onChange = { enabled ->
                            prefs.longPressButtonToCustomize = enabled
                            holdSlider?.let { setCardRowVisibility(it, enabled) }
                        },
                    ),
                    slider(
                        getString(R.string.set_button_customize_hold),
                        BUTTON_CUSTOMIZE_HOLD_MIN_MS,
                        BUTTON_CUSTOMIZE_HOLD_MAX_MS,
                        prefs.buttonCustomizeHoldMs,
                        unit = "ms",
                        hint = getString(R.string.button_customize_hold_hint),
                        onChange = { prefs.buttonCustomizeHoldMs = it },
                    ).also { holdSlider = it }.apply {
                        visibility = if (prefs.longPressButtonToCustomize) View.VISIBLE else View.GONE
                    },
                )
            }

            // ── 悬浮球 ──
            val ballSummary = if (prefs.hideFloatingBall) {
                getString(R.string.label_off)
            } else {
                "${prefs.floatingBallSizeDp}dp · ${prefs.floatingBallOpacityPercent}%"
            }
            addCollapsible(
                cardId = "ball",
                title = getString(R.string.sec_ball),
                iconResId = R.drawable.ic_lu_circle_dot,
                summary = ballSummary,
                defaultExpanded = false,
            ) {
                listOf(
                    slider(
                        getString(R.string.set_ball_size),
                        FLOATING_BALL_MIN_DP,
                        FLOATING_BALL_MAX_DP,
                        prefs.floatingBallSizeDp,
                        unit = "dp",
                        onChange = {
                            prefs.floatingBallSizeDp = it
                            TouchpadService.instance?.updateBallAppearance()
                        },
                    ),
                    colorRow(
                        getString(R.string.set_ball_color),
                        BALL_COLORS,
                        { prefs.floatingBallColor },
                        { prefs.floatingBallColor = it },
                        onServiceUpdate = { TouchpadService.instance?.updateBallAppearance() },
                        allowCustomHex = true,
                        recreateOnChange = false,
                    ),
                    slider(
                        getString(R.string.set_ball_opacity),
                        20,
                        100,
                        prefs.floatingBallOpacityPercent,
                        unit = "%",
                        onChange = {
                            prefs.floatingBallOpacityPercent = it
                            TouchpadService.instance?.updateBallAppearance()
                        },
                    ),
                    switchRow(
                        getString(R.string.switch_hide_ball),
                        prefs.hideFloatingBall,
                        hint = getString(R.string.hide_ball_hint),
                        onChange = { prefs.hideFloatingBall = it; reload() },
                    ),
                )
            }

            // ── 悬浮球手势 ──
            var ballGesturesSummaryChip: TextView? = null
            val ballGesturesSummary = if (prefs.ballGesturesEnabled) getString(R.string.label_on) else getString(R.string.label_off)
            addCollapsible(
                cardId = "ball_gestures",
                title = getString(R.string.sec_ball_gestures),
                iconResId = R.drawable.ic_lu_hand,
                summary = ballGesturesSummary,
                defaultExpanded = false,
                bindSummaryChip = { ballGesturesSummaryChip = it },
            ) {
                val detailBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                fun rebuildBallGestureDetails() {
                    withUiContext(bindings) {
                        detailBox.removeAllViews()
                        if (!prefs.ballGesturesEnabled) {
                            detailBox.visibility = View.GONE
                            return@withUiContext
                        }
                        val rows = listOf(
                            actionPickerRow(getString(R.string.gesture_single_tap), prefs.ballActionSingleTap, PadAction.BALL_ACTIONS) { prefs.ballActionSingleTap = it },
                            actionPickerRow(getString(R.string.gesture_double_tap), prefs.ballActionDoubleTap, PadAction.BALL_ACTIONS) { prefs.ballActionDoubleTap = it },
                            actionPickerRow(getString(R.string.gesture_long_press), prefs.ballActionLongPress, PadAction.BALL_LONG_PRESS_ACTIONS) { prefs.ballActionLongPress = it },
                            slider(
                                getString(R.string.set_ball_hold),
                                200,
                                1200,
                                prefs.ballLongPressMs,
                                unit = "ms",
                                hint = getString(R.string.ball_hold_hint),
                                onChange = { prefs.ballLongPressMs = it },
                            ),
                            actionPickerRow(getString(R.string.gesture_swipe_up), prefs.ballActionSwipeUp, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeUp = it },
                            actionPickerRow(getString(R.string.gesture_swipe_down), prefs.ballActionSwipeDown, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeDown = it },
                            actionPickerRow(getString(R.string.gesture_swipe_left), prefs.ballActionSwipeLeft, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeLeft = it },
                            actionPickerRow(getString(R.string.gesture_swipe_right), prefs.ballActionSwipeRight, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeRight = it },
                            actionRow(getString(R.string.btn_reset_ball_gestures)) {
                                prefs.resetBallActions()
                                reload()
                                rebuildBallGestureDetails()
                            },
                        )
                        populateCardContent(detailBox, rows, borderColor)
                        detailBox.visibility = View.VISIBLE
                    }
                }
                rebuildBallGestureDetails()

                listOf(
                    switchRow(
                        getString(R.string.switch_ball_gestures),
                        prefs.ballGesturesEnabled,
                        hint = getString(R.string.switch_ball_gestures_hint),
                        onChange = { enabled ->
                            prefs.ballGesturesEnabled = enabled
                            reload()
                            ballGesturesSummaryChip?.text = getString(if (enabled) R.string.label_on else R.string.label_off)
                            rebuildBallGestureDetails()
                            setCardRowVisibility(detailBox, enabled)
                        },
                    ),
                    detailBox,
                )
            }

            // ── 光标 ──
            val cursorSummary = "${prefs.cursorSizeDp}dp · ${prefs.cursorOpacityPercent}%"
            addCollapsible(
                cardId = "cursor",
                title = getString(R.string.sec_cursor),
                iconResId = R.drawable.ic_lu_pointer,
                summary = cursorSummary,
                defaultExpanded = false,
            ) {
                var ringColorRow: View? = null
                listOf(
                    slider(
                        getString(R.string.set_cursor_size),
                        CURSOR_MIN_DP,
                        CURSOR_MAX_DP,
                        prefs.cursorSizeDp,
                        unit = "dp",
                        onChange = {
                            prefs.cursorSizeDp = it
                            TouchpadService.instance?.updateCursorAppearance()
                        },
                    ),
                    slider(
                        getString(R.string.set_cursor_opacity),
                        10,
                        100,
                        prefs.cursorOpacityPercent,
                        unit = "%",
                        onChange = {
                            prefs.cursorOpacityPercent = it
                            TouchpadService.instance?.updateCursorAppearance()
                        },
                    ),
                    colorRow(
                        getString(R.string.set_cursor_color),
                        CURSOR_COLORS,
                        { prefs.cursorColor },
                        { prefs.cursorColor = it },
                        onServiceUpdate = { TouchpadService.instance?.updateCursorAppearance() },
                        allowCustomHex = true,
                        recreateOnChange = false,
                    ),
                    switchRow(
                        getString(R.string.switch_touch_ring),
                        prefs.touchRingEnabled,
                        hint = getString(R.string.switch_touch_ring_hint),
                        onChange = { enabled ->
                            prefs.touchRingEnabled = enabled
                            TouchpadService.instance?.updateTouchRingAppearance(preview = enabled)
                            ringColorRow?.let { setCardRowVisibility(it, enabled) }
                        },
                    ),
                    colorRow(
                        getString(R.string.set_touch_ring_color),
                        RING_COLORS,
                        { prefs.touchRingColor },
                        { prefs.touchRingColor = it },
                        onServiceUpdate = { TouchpadService.instance?.updateTouchRingAppearance(preview = true) },
                        allowCustomHex = true,
                        recreateOnChange = false,
                    ).also { ringColorRow = it }.apply {
                        visibility = if (prefs.touchRingEnabled) View.VISIBLE else View.GONE
                    },
                )
            }

            // ── 手感 ──
            val feelSummary = String.format(java.util.Locale.US, "%.1f×", prefs.sensitivity)
            addCollapsible(
                cardId = "feel",
                title = getString(R.string.sec_feel),
                iconResId = R.drawable.ic_lu_move,
                summary = feelSummary,
                defaultExpanded = false,
            ) {
                listOf(
                    slider(getString(R.string.set_sensitivity), 5, 40, (prefs.sensitivity * 10).roundToInt(), format = { String.format(java.util.Locale.US, "%.1f×", it / 10f) }) { prefs.sensitivity = it / 10f },
                    slider(getString(R.string.set_pad_dead_zone), 0, PAD_EDGE_DEAD_ZONE_MAX_DP, prefs.padEdgeDeadZoneDp, unit = "dp",
                        offLabel = getString(R.string.label_off), hint = getString(R.string.pad_dead_zone_hint),
                        onChange = { prefs.padEdgeDeadZoneDp = it }),
                    slider(getString(R.string.set_long_press), 200, 1500, prefs.longPressMs, unit = "ms", hint = getString(R.string.custom_swipe_hint), onChange = { prefs.longPressMs = it }),
                    slider(getString(R.string.set_cursor_hold), CURSOR_HOLD_MIN_MS, CURSOR_HOLD_MAX_MS, prefs.cursorHoldMs, unit = "ms",
                        hint = getString(R.string.cursor_hold_hint), onChange = { prefs.cursorHoldMs = it }),
                    slider(getString(R.string.set_scroll_distance), 60, 500, prefs.scrollDistanceDp, unit = "dp", hint = getString(R.string.scroll_distance_hint), onChange = { prefs.scrollDistanceDp = it }),
                    switchRow(getString(R.string.switch_press_feedback), prefs.pressFeedback, onChange = { prefs.pressFeedback = it; reload() }),
                    switchRow(getString(R.string.switch_haptics), prefs.haptics, onChange = { prefs.haptics = it }),
                    switchRow(getString(R.string.switch_autohide_landscape), prefs.autoHideLandscape, onChange = { prefs.autoHideLandscape = it; reload() }),
                    switchRow(getString(R.string.switch_minimize_keyboard), prefs.minimizeOnKeyboard, onChange = {
                        prefs.minimizeOnKeyboard = it
                        TouchpadService.instance?.syncKeyboardMinimize()
                    }),
                    switchRow(getString(R.string.switch_exclude_from_recents), prefs.excludeFromRecents, hint = getString(R.string.switch_exclude_from_recents_hint), onChange = {
                        prefs.excludeFromRecents = it
                        applyExcludeFromRecents(it)
                    }),
                )
            }

            // ── 后台保活与防杀加锁 ──
            val isBattIgnored = isIgnoringBatteryOptimizations()
            val keepAliveSummary = buildString {
                if (prefs.keepAliveNotification) append(getString(R.string.summary_notif_enabled))
                if (isBattIgnored) {
                    if (isNotEmpty()) append(" · ")
                    append(getString(R.string.summary_battery_whitelisted))
                }
                if (isEmpty()) append(getString(R.string.summary_keep_alive_suggested))
            }
            addCollapsible(
                cardId = "keep_alive",
                title = getString(R.string.sec_keep_alive),
                iconResId = R.drawable.ic_lu_lock,
                summary = keepAliveSummary,
                defaultExpanded = false,
            ) {
                listOf(
                    FrameLayout(this).apply {
                        setPadding(dp(16), dp(4), dp(16), dp(10))
                        addView(hintText(getString(R.string.keep_alive_explain_hint)))
                    },
                    switchRow(
                        getString(R.string.switch_keep_alive_notification),
                        prefs.keepAliveNotification,
                        hint = getString(R.string.switch_keep_alive_notification_hint),
                        bind = { bindings.keepAliveSwitch = it },
                        onChange = { enable ->
                            checkAndEnableKeepAliveNotification(enable) {
                                TouchpadService.instance?.updateKeepAliveNotification()
                            }
                        }
                    ),
                    actionRow(
                        if (isIgnoringBatteryOptimizations()) getString(R.string.btn_battery_whitelisted) else getString(R.string.btn_request_battery_whitelist),
                        bindTitle = { bindings.batteryStatusTextView = it }
                    ) {
                        requestIgnoreBatteryOptimization()
                    },
                    actionRow(getString(R.string.btn_open_app_details)) {
                        openAppDetailsSettings()
                    },
                    actionRow(getString(R.string.btn_vendor_keep_alive_guide)) {
                        showDialog(
                            getString(R.string.vendor_guide_title),
                            getString(R.string.vendor_guide_body)
                        )
                    },
                )
            }

            // ── 关于 ──
            val aboutSummary = "v${appVersion()}".takeIf { it != "v" } ?: ""
            addCollapsible(
                cardId = "about",
                title = getString(R.string.sec_about),
                iconResId = R.drawable.ic_lu_settings,
                summary = aboutSummary,
                defaultExpanded = false,
            ) {
                listOf(
                    actionRow(getString(R.string.btn_setup)) { showDialog(getString(R.string.steps_title), getString(R.string.steps_body)) },
                    actionRow(getString(R.string.btn_github)) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)))) }
                    },
                    actionRow(getString(R.string.btn_reset_all), destructive = true) {
                        prefs.resetAll()
                        invalidateThemeCache()
                        CustomBackgroundManager.clearTouchpadBg(this)
                        CustomBackgroundManager.clearSettingsBg(this)
                        cachedHasCustomSettingsBg = false
                        TouchpadService.instance?.reloadTouchpadBackground()
                        currentReveal?.release()
                        settingsBgImageView?.setImageDrawable(null)
                        settingsBgBitmap = null
                        expandedCardIds.clear()
                        hasInitializedCardDefaults = false
                        applyExcludeFromRecents(false)
                        toast(getString(R.string.reset_done))
                        reload()
                        recreateKeepingScroll()
                    },
                )
            }

            if (!hasInitializedCardDefaults) {
                hasInitializedCardDefaults = true
            }
            updateExpandAllText()

            col.addView(TextView(this).apply {
                text = footerVersionText()
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (hasCustomSettingsBg || darkUi) 0x99FFFFFF.toInt() else tertiaryTextColor)
                if (hasCustomSettingsBg) {
                    setShadowLayer(
                        dp(3).toFloat(),
                        0f,
                        dp(1).toFloat(),
                        0x80000000.toInt()
                    )
                }
                setPadding(0, dp(20), 0, 0)
            })

            SettingsScrollView(this).apply {
                // 固定 id：系统重建（换主题/换颜色后的 recreate）时会自动保存并恢复滚动位置
                id = R.id.settings_scroll
                addView(col)
            }
        }
        return scrollView to bindings
    }

    /**
     * 设置页专用滚动容器：
     * - 顶部按系统状态栏高度留白并启用 clipToPadding = true，防止滚动后文字与时间/信号图标重叠；
     * - 仅在顶部滚动后叠加柔和的 16dp 渐隐边缘，底部保持通透以适配全面屏手势导航栏。
     */
    private inner class SettingsScrollView(context: Context) : ScrollView(context) {
        init {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
        }

        override fun getBottomFadingEdgeStrength(): Float = 0f
    }

    private fun applyScrollInsets(scrollView: ScrollView, left: Int, top: Int, right: Int, bottom: Int) {
        scrollView.setPadding(left, top, right, 0)
        scrollView.getChildAt(0)?.setPadding(dp(16), dp(16), dp(16), dp(40) + bottom)
    }

    // ───────────────────────── 头部 ─────────────────────────

    private fun hero(bindings: UiBindings): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }
        val isHeaderDark = darkUi || hasCustomSettingsBg
        val headerTitleColor = if (isHeaderDark) 0xFFFFFFFF.toInt() else textColor
        val headerDescColor = if (isHeaderDark) 0xD9FFFFFF.toInt() else secondaryTextColor

        box.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 34f
            typeface = serif
            letterSpacing = -0.01f
            setTextColor(headerTitleColor)
            if (hasCustomSettingsBg) {
                // 暗色模式风格：纯白大号衬线标题辅以细腻暗色阴影，与壁纸自然融为一体且边缘清晰
                setShadowLayer(
                    dp(6).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    0x99000000.toInt()
                )
            }
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 15f
            setTextColor(headerDescColor)
            setLineSpacing(0f, 1.5f)
            setPadding(0, dp(10), 0, dp(18))
            if (hasCustomSettingsBg) {
                setShadowLayer(
                    dp(4).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    0x80000000.toInt()
                )
            }
        })
        val status = TextView(this).apply {
            textSize = 13f
            typeface = sansMedium
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            compoundDrawablePadding = dp(8)
        }
        bindings.statusView = status
        box.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val primary = TextView(this).apply {
            text = getString(R.string.open_a11y_settings)
            textSize = 15f
            typeface = sansMedium
            gravity = Gravity.CENTER
            setTextColor(onAccentColor)
            background = ripple(roundedSurface(accentColor, 12), 0x33FAF9F5, 12)
            isClickable = true
            setOnClickListener { openAccessibilitySettings() }
        }
        box.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(18)
        })
        return box
    }

    // ───────────────────────── 小控件 ─────────────────────────

    private fun haptic(v: View) {
        if (prefs.haptics) {
            runCatching {
                v.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                )
            }
        }
    }

    private fun categoriesBar(
        controllers: List<CollapsibleCardController>,
        bindBtn: ((TextView) -> Unit)? = null,
    ): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(22), dp(4), dp(4))
        }

        val isHeaderDark = darkUi || hasCustomSettingsBg
        val labelColor = if (isHeaderDark) 0xD9FFFFFF.toInt() else secondaryTextColor

        val label = TextView(this).apply {
            text = getString(R.string.categories_title)
            textSize = 14f
            typeface = sansMedium
            setTextColor(labelColor)
            if (hasCustomSettingsBg) {
                setShadowLayer(
                    dp(4).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    0x80000000.toInt()
                )
            }
        }
        bar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btn = TextView(this).apply {
            textSize = 13f
            typeface = sansMedium
            setTextColor(accentColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val btnBg = if (hasCustomSettingsBg || darkUi) {
                roundedSurface(0x33000000.toInt(), 999).apply {
                    setStroke(maxOf(1, dp(1)), 0x33FFFFFF.toInt())
                }
            } else {
                roundedSurface(trackColor, 999)
            }
            background = ripple(btnBg, rippleColor, 999)
            isClickable = true
            val allExpanded = controllers.isNotEmpty() && controllers.all { it.isExpanded() }
            text = getString(if (allExpanded) R.string.collapse_all else R.string.expand_all)
            setOnClickListener {
                haptic(it)
                val isAllExpanded = controllers.isNotEmpty() && controllers.all { c -> c.isExpanded() }
                val targetExpand = !isAllExpanded
                controllers.forEachIndexed { index, c ->
                    c.setExpanded(targetExpand, animate = true, startDelay = (index * 20L).coerceAtMost(160L))
                }
                text = getString(if (targetExpand) R.string.collapse_all else R.string.expand_all)
            }
        }
        bindBtn?.invoke(btn)
        bar.addView(btn)
        return bar
    }

    /** 具有 Flowmix / Material 3 风格的分组折叠卡片：包含头部彩色矢量图标、大号触控标题、实时状态摘要胶囊与平滑旋转指示箭头 */
    private fun collapsibleCard(
        cardId: String,
        title: String,
        iconResId: Int? = null,
        summary: String? = null,
        defaultExpanded: Boolean = false,
        onToggle: (() -> Unit)? = null,
        controllers: MutableList<CollapsibleCardController>? = null,
        bindSummaryChip: ((TextView) -> Unit)? = null,
        rowsBuilder: () -> List<View>,
    ): View {
        val cardBindings = activeBindings!!
        if (!hasInitializedCardDefaults && defaultExpanded) {
            expandedCardIds.add(cardId)
        }
        val isExpanded = expandedCardIds.contains(cardId)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Material 3 容器风格：24dp 大圆角，依赖色阶呈现层次，移除原有生硬描边
            background = roundedSurface(cardBgColor, 24)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        }

        // 标题栏：整行可点击（>= 56dp 高度），提供大号无障碍触控目标与水波纹反馈
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(18), dp(12), dp(16), dp(12))
            background = ripple(null, rippleColor, 24)
            isClickable = true
            contentDescription = "$title, ${if (isExpanded) "已展开" else "已折叠"}"
        }

        // 头部主题色前置矢量图标（Material 3 / Flowmix 核心特征）
        if (iconResId != null && iconResId != 0) {
            val iconIv = ImageView(this).apply {
                setImageResource(iconResId)
                setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val iconLp = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(12)
            }
            header.addView(iconIv, iconLp)
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 17f
            typeface = sansMedium
            setTextColor(textColor)
        }
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 实时状态摘要胶囊 (Status Chip)
        if (!summary.isNullOrBlank()) {
            val chip = TextView(this).apply {
                if (cardId == "appearance") {
                    tag = APPEARANCE_SUMMARY_CHIP_TAG
                }
                text = summary
                textSize = 12f
                typeface = sansMedium
                setTextColor(secondaryTextColor)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundedSurface(trackColor, 999)
            }
            bindSummaryChip?.invoke(chip)
            val chipLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
            header.addView(chip, chipLp)
        }

        // 旋转折叠箭头 (Chevron)
        val chevron = TextView(this).apply {
            text = "⌄"
            textSize = 20f
            typeface = sansMedium
            setTextColor(tertiaryTextColor)
            rotation = if (isExpanded) 0f else -90f
        }
        header.addView(chevron)

        box.addView(header)

        // 标题与内容的分割线
        val headerDivider = View(this).apply {
            setBackgroundColor(borderColor)
            visibility = if (isExpanded) View.VISIBLE else View.GONE
        }
        box.addView(headerDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
            marginStart = dp(18); marginEnd = dp(18)
        })

        // 内容容器（默认折叠的卡片惰性构建子视图，仅在首次展开时实例化，降低初始 View 树内存）
        val contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            setPadding(0, dp(4), 0, dp(6))
            clipChildren = true
            clipToPadding = true
        }

        var isInflated = false
        fun ensureInflated() {
            if (isInflated) return
            isInflated = true
            withUiContext(cardBindings) {
                populateCardContent(contentBox, rowsBuilder(), borderColor)
            }
        }
        if (isExpanded) {
            ensureInflated()
        }
        box.addView(contentBox)

        var currentAnimator: ValueAnimator? = null

        val controller = CollapsibleCardController(
            id = cardId,
            handler = { expand, animate, startDelay ->
                // 如果当前状态与目标状态一致且没有动画在运行，直接返回
                if (currentAnimator == null && expand == expandedCardIds.contains(cardId)) {
                    return@CollapsibleCardController
                }

                currentAnimator?.cancel()
                currentAnimator = null

                if (expand) {
                    expandedCardIds.add(cardId)
                    ensureInflated()
                } else {
                    expandedCardIds.remove(cardId)
                }
                header.contentDescription = "$title, ${if (expand) "已展开" else "已折叠"}"

                val targetRotation = if (expand) 0f else -90f
                if (!animate) {
                    chevron.rotation = targetRotation
                    headerDivider.visibility = if (expand) View.VISIBLE else View.GONE
                    headerDivider.alpha = 1f
                    contentBox.visibility = if (expand) View.VISIBLE else View.GONE
                    contentBox.alpha = 1f
                    contentBox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    return@CollapsibleCardController
                }

                chevron.animate()
                    .rotation(targetRotation)
                    .setDuration(if (expand) 260 else 220)
                    .setInterpolator(CARD_INTERPOLATOR)
                    .setStartDelay(startDelay)
                    .start()

                val availableWidth = if (box.width > 0) {
                    box.width
                } else if (cardBindings.content.width > 0) {
                    (cardBindings.content.width - dp(32)).coerceAtLeast(dp(200))
                } else {
                    (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(200))
                }

                if (expand) {
                    headerDivider.visibility = View.VISIBLE
                    contentBox.visibility = View.VISIBLE

                    val startHeight = if (contentBox.height > 0) contentBox.height else 0
                    val startAlpha = if (contentBox.alpha in 0f..1f) contentBox.alpha else 0f

                    // 临时将高度设置为 WRAP_CONTENT 进行测量，获得无拘束状态下的完整内容高度
                    contentBox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    contentBox.measure(widthSpec, heightSpec)
                    val targetHeight = contentBox.measuredHeight

                    // 恢复起始高度与透明度
                    contentBox.layoutParams.height = startHeight
                    contentBox.alpha = startAlpha
                    headerDivider.alpha = startAlpha

                    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 260
                        interpolator = CARD_INTERPOLATOR
                        this.startDelay = startDelay
                        addUpdateListener { va ->
                            val frac = va.animatedValue as Float
                            val h = (startHeight + (targetHeight - startHeight) * frac).roundToInt()
                            contentBox.layoutParams.height = h
                            val currentAlpha = startAlpha + (1f - startAlpha) * frac
                            contentBox.alpha = currentAlpha
                            headerDivider.alpha = currentAlpha
                            contentBox.requestLayout()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            var wasCancelled = false
                            override fun onAnimationCancel(animation: Animator) {
                                wasCancelled = true
                            }
                            override fun onAnimationEnd(animation: Animator) {
                                if (!wasCancelled) {
                                    contentBox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    contentBox.alpha = 1f
                                    headerDivider.alpha = 1f
                                    currentAnimator = null
                                    contentBox.requestLayout()
                                }
                            }
                        })
                    }
                    currentAnimator = anim
                    anim.start()
                } else {
                    // 折叠
                    val startHeight = if (contentBox.height > 0) contentBox.height else contentBox.measuredHeight
                    val startAlpha = if (contentBox.alpha in 0f..1f) contentBox.alpha else 1f

                    if (startHeight <= 0) {
                        contentBox.visibility = View.GONE
                        headerDivider.visibility = View.GONE
                        contentBox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        return@CollapsibleCardController
                    }

                    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 220
                        interpolator = CARD_INTERPOLATOR
                        this.startDelay = startDelay
                        addUpdateListener { va ->
                            val frac = va.animatedValue as Float
                            val h = (startHeight * (1f - frac)).roundToInt()
                            contentBox.layoutParams.height = h
                            val currentAlpha = startAlpha * (1f - frac)
                            contentBox.alpha = currentAlpha
                            headerDivider.alpha = currentAlpha
                            contentBox.requestLayout()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            var wasCancelled = false
                            override fun onAnimationCancel(animation: Animator) {
                                wasCancelled = true
                            }
                            override fun onAnimationEnd(animation: Animator) {
                                if (!wasCancelled) {
                                    contentBox.visibility = View.GONE
                                    headerDivider.visibility = View.GONE
                                    contentBox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    contentBox.alpha = 1f
                                    headerDivider.alpha = 1f
                                    currentAnimator = null
                                }
                            }
                        })
                    }
                    currentAnimator = anim
                    anim.start()
                }
            },
            isExpanded = { expandedCardIds.contains(cardId) },
            cancelHandler = {
                currentAnimator?.cancel()
                currentAnimator = null
                chevron.animate().cancel()
            },
        )
        controllers?.add(controller)

        header.setOnClickListener {
            haptic(it)
            val willExpand = !controller.isExpanded()
            controller.setExpanded(willExpand)
            onToggle?.invoke()
        }

        return box
    }

    private fun populateCardContent(container: LinearLayout, rows: List<View>, dividerColor: Int) {
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                val div = View(this).apply {
                    tag = ROW_DIVIDER_TAG
                    setBackgroundColor(dividerColor)
                    visibility = row.visibility
                }
                container.addView(
                    div,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                        marginStart = dp(18)
                        marginEnd = dp(18)
                    },
                )
            }
            container.addView(row)
        }
    }

    /** 更新卡片内行的可见性并同步其前置分隔线，避免为每行注册 OnLayoutChangeListener。 */
    private fun setCardRowVisibility(row: View, visible: Boolean) {
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        row.visibility = targetVisibility
        val parent = row.parent as? LinearLayout ?: return
        val idx = parent.indexOfChild(row)
        if (idx > 0) {
            val prev = parent.getChildAt(idx - 1)
            if (prev.tag === ROW_DIVIDER_TAG) {
                prev.visibility = targetVisibility
            }
        }
    }

    private fun titleText(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 15f
        setTextColor(textColor)
    }

    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(tertiaryTextColor)
        setLineSpacing(0f, 1.45f)
        setPadding(0, dp(4), 0, 0)
    }

    private fun customBackgroundRow(
        title: String,
        hint: String? = null,
        hasCustomBg: Boolean,
        onPick: () -> Unit,
        onClear: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(titleText(title))
        if (!hint.isNullOrBlank()) {
            textCol.addView(hintText(hint))
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(12)
        })

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (hasCustomBg) {
            val clearBtn = TextView(this).apply {
                text = getString(R.string.btn_clear_image)
                textSize = 13f
                typeface = sansMedium
                setTextColor(dangerColor)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = ripple(roundedSurface(trackColor, 999), rippleColor, 999)
                isClickable = true
                setOnClickListener {
                    haptic(it)
                    onClear()
                }
            }
            btnContainer.addView(clearBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }

        val pickBtn = TextView(this).apply {
            text = getString(R.string.btn_pick_image)
            textSize = 13f
            typeface = sansMedium
            setTextColor(onAccentColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = ripple(roundedSurface(accentColor, 999), 0x33FAF9F5, 999)
            isClickable = true
            setOnClickListener {
                haptic(it)
                onPick()
            }
        }
        btnContainer.addView(pickBtn)

        row.addView(btnContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    private fun actionRow(
        title: String,
        destructive: Boolean = false,
        bindTitle: ((TextView) -> Unit)? = null,
        onClick: () -> Unit
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(6), dp(12), dp(6))
            background = ripple(null, rippleColor, 0)
            isClickable = true
            setOnClickListener { onClick() }
            val tv = titleText(title).apply {
                if (destructive) setTextColor(dangerColor)
            }
            bindTitle?.invoke(tv)
            addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 22f
                setTextColor(tertiaryTextColor)
            })
        }

    private fun actionPickerRow(
        gestureTitle: String,
        currentAction: PadAction,
        availableActions: List<PadAction> = PadAction.BALL_ACTIONS,
        onPicked: (PadAction) -> Unit
    ): View {
        val bindings = activeBindings!!
        var selectedAction = currentAction

        val iconView = ImageView(this).apply {
            setImageResource(selectedAction.iconRes)
            imageTintList = bindings.colors.accent
            setPadding(0, 0, dp(6), 0)
        }
        val labelView = TextView(this).apply {
            text = if (selectedAction == PadAction.NONE) {
                getString(R.string.act_none_gesture)
            } else {
                getString(selectedAction.labelRes)
            }
            textSize = 13f
            typeface = sansMedium
            setTextColor(textColor)
        }

        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(12), dp(4))
            background = roundedSurface(trackColor, 999).apply {
                setStroke(maxOf(1, dp(1)), borderColor)
            }
            addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(labelView)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = ripple(null, rippleColor, 0)
            isClickable = true
            setOnClickListener {
                val theme = getThemeFor(bindings.isDark)
                showTrackedDialog(
                    ActionPicker.build(
                        this@MainActivity,
                        bindings.isDark,
                        selectedAction,
                        actions = availableActions,
                        noneLabelRes = R.string.act_none_gesture,
                        accentColor = theme.accentColor,
                    ) { picked ->
                        selectedAction = picked
                        iconView.setImageResource(picked.iconRes)
                        labelView.text = if (picked == PadAction.NONE) {
                            getString(R.string.act_none_gesture)
                        } else {
                            getString(picked.labelRes)
                        }
                        onPicked(picked)
                        reload()
                    }
                )
            }
            addView(titleText(gestureTitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip)
        }
    }

    private fun roundedSurface(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun borderedSurface(color: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        roundedSurface(color, radiusDp).apply { setStroke(maxOf(1, dp(1)), strokeColor) }

    private fun ripple(content: Drawable?, color: Int, radiusDp: Int): Drawable {
        val csl = if (color == rippleColor) {
            currentColorStateLists().ripple
        } else {
            ColorStateList.valueOf(color)
        }
        return RippleDrawable(csl, content, roundedSurface(Color.WHITE, radiusDp))
    }

    private fun pill(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = sansMedium
        setTextColor(if (darkUi) 0xFFE8E6DC.toInt() else 0xFF4D4C48.toInt())
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = roundedSurface(trackColor, 8)
    }

    /** 外观：三段式分段选择器。 */
    private fun themeModeRow(): View {
        val track = LinearLayout(this).apply {
            tag = THEME_SELECTOR_TRACK_TAG
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedSurface(trackColor, 12)
        }
        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        ).forEach { (mode, labelRes) ->
            val selected = prefs.themeMode == mode
            track.addView(TextView(this).apply {
                tag = mode
                text = getString(labelRes)
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = if (selected) sansMedium else Typeface.DEFAULT
                setTextColor(if (selected) textColor else secondaryTextColor)
                background = if (selected) {
                    roundedSurface(elevatedColor, 9).apply { setStroke(maxOf(1, dp(1)), borderColor) }
                } else {
                    ripple(null, rippleColor, 9)
                }
                if (selected) elevation = dp(1).toFloat()
                isClickable = true
                contentDescription = getString(labelRes)
                setOnClickListener {
                    haptic(this)
                    switchThemeWithReveal(mode, this)
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        return FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(track)
        }
    }

    private fun updateThemeSelector(track: LinearLayout, selectedMode: ThemeMode, isDark: Boolean) {
        val theme = getThemeFor(isDark)
        val elevated = theme.elevatedColor
        val border = theme.borderColor
        val text = theme.textColor
        val secondaryText = theme.secondaryTextColor
        val ripple = theme.rippleColor

        for (i in 0 until track.childCount) {
            val tv = track.getChildAt(i) as? TextView ?: continue
            val mode = tv.tag as? ThemeMode ?: continue
            val selected = mode == selectedMode
            tv.typeface = if (selected) sansMedium else Typeface.DEFAULT
            tv.setTextColor(if (selected) text else secondaryText)
            tv.background = if (selected) {
                roundedSurface(elevated, 9).apply { setStroke(maxOf(1, dp(1)), border) }
            } else {
                ripple(null, ripple, 9)
            }
            tv.elevation = if (selected) dp(1).toFloat() else 0f
        }
    }

    private fun slider(
        title: String,
        min: Int,
        max: Int,
        value: Int,
        unit: String = "",
        offLabel: String? = null,
        hint: String? = null,
        format: ((Int) -> String)? = null,
        bind: ((SliderController) -> Unit)? = null,
        onChange: (Int) -> Unit,
    ): View {
        val colors = currentColorStateLists()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(titleText(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = pill("")
        head.addView(valueView)
        fun render(v: Int) {
            valueView.text = when {
                v <= 0 && offLabel != null -> offLabel
                format != null -> format(v)
                unit.isEmpty() -> "$v"
                else -> "$v $unit"
            }
        }
        render(value)
        box.addView(head)
        if (hint != null) box.addView(hintText(hint))
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = colors.accent
            progressBackgroundTintList = colors.track
            thumbTintList = colors.accent
            progressTintMode = PorterDuff.Mode.SRC_IN
            thumbTintMode = PorterDuff.Mode.SRC_IN
            splitTrack = false
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (seekBar?.tag === SYNCING) return
                    val v = progress + min
                    render(v)
                    onChange(v)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val controller = SliderController { newValue ->
            val clamped = newValue.coerceIn(min, max)
            sb.tag = SYNCING
            sb.progress = clamped - min
            sb.tag = null
            render(clamped)
        }
        bind?.invoke(controller)
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        return box
    }

    private fun switchRow(
        title: String,
        checked: Boolean,
        hint: String? = null,
        bind: ((Switch) -> Unit)? = null,
        onChange: (Boolean) -> Unit,
    ): View {
        val colors = currentColorStateLists()
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = colors.switchThumb
            trackTintList = colors.switchTrack
            contentDescription = title
            setOnCheckedChangeListener { v, value -> if (v.tag !== SYNCING) onChange(value) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(title))
            if (hint != null) addView(hintText(hint))
        }
        bind?.invoke(sw)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = ripple(null, rippleColor, 0)
            isClickable = true
            setOnClickListener { sw.toggle() }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(sw)
        }
    }

    /**
     * 标题 + 一排颜色色块。[palette] 里的 0 代表「跟随主题默认色」，显示为陶土色渐变色块。
     * 选中的色块外圈加强调色描边并显示对勾；色块多时可以横向滑动。
     */
    private fun colorRow(
        title: String,
        palette: List<Int>,
        get: () -> Int,
        set: (Int) -> Unit,
        onServiceUpdate: (() -> Unit)? = null,
        allowCustomHex: Boolean = false,
        recreateOnChange: Boolean = false,
    ): View {
        val bindings = activeBindings!!
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val hsv = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

        fun applyColorChange(picked: Int, rebuildInPlace: () -> Unit) {
            set(picked)
            if (onServiceUpdate != null) {
                onServiceUpdate()
            } else {
                reload()
            }
            if (recreateOnChange) {
                recreateKeepingScroll()
            } else {
                rebuildInPlace()
            }
        }

        fun rebuildSwatches() {
            val prevScrollX = hsv.scrollX
            withUiContext(bindings) {
                row.removeAllViews()
                val currentColor = get()
                val allColors = if (allowCustomHex && currentColor != 0 && currentColor != COLOR_DYNAMIC && currentColor !in palette) {
                    palette + currentColor
                } else {
                    palette
                }
                allColors.forEach { color ->
                    val selected = currentColor == color
                    val isDyn = color == COLOR_DYNAMIC
                    val fill = when (color) {
                        COLOR_DYNAMIC -> getDynamicAccent(darkUi)
                        0 -> if (prefs.dynamicColor) getDynamicAccent(darkUi) else (if (darkUi) 0xFFD97757.toInt() else 0xFFC96442.toInt())
                        else -> color
                    }
                    val swatch = TextView(this).apply {
                        gravity = Gravity.CENTER
                        textSize = 15f
                        text = when {
                            selected -> "✓"
                            isDyn -> "✦"
                            else -> ""
                        }
                        setTextColor(if (Color.luminance(fill) > 0.55f) 0xFF111827.toInt() else Color.WHITE)
                        val inner = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            if (isDyn) {
                                orientation = GradientDrawable.Orientation.TL_BR
                                colors = intArrayOf(lightenColor(fill, 0.14f), fill, darkenColor(fill, 0.10f))
                            } else if (color == 0) {
                                orientation = GradientDrawable.Orientation.TL_BR
                                colors = intArrayOf(fill, fill)
                            } else {
                                setColor(color)
                            }
                            setStroke(maxOf(1, dp(1) / 2), 0x33000000)
                        }
                        val ring = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(2), if (selected) accentColor else Color.TRANSPARENT)
                        }
                        background = LayerDrawable(arrayOf(ring, inner)).apply { setLayerInset(1, dp(4), dp(4), dp(4), dp(4)) }
                        contentDescription = when (color) {
                            0 -> getString(R.string.color_default)
                            COLOR_DYNAMIC -> getString(R.string.color_dynamic)
                            else -> String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)
                        }
                        isClickable = true
                        setOnClickListener {
                            applyColorChange(color) { rebuildSwatches() }
                        }
                    }
                    row.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    })
                }
                if (allowCustomHex) {
                    val customSwatch = TextView(this).apply {
                        gravity = Gravity.CENTER
                        textSize = 18f
                        text = "+"
                        setTextColor(textColor)
                        val inner = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(trackStrongColor)
                            setStroke(maxOf(1, dp(1) / 2), 0x33000000)
                        }
                        val ring = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(2), Color.TRANSPARENT)
                        }
                        background = LayerDrawable(arrayOf(ring, inner)).apply { setLayerInset(1, dp(4), dp(4), dp(4), dp(4)) }
                        contentDescription = getString(R.string.custom_color_title)
                        isClickable = true
                        setOnClickListener {
                            withUiContext(bindings) {
                                showCustomColorDialog(get()) { picked ->
                                    applyColorChange(picked) { rebuildSwatches() }
                                }
                            }
                        }
                    }
                    row.addView(customSwatch, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    })
                }
            }
            if (prevScrollX > 0) {
                hsv.post { hsv.scrollTo(prevScrollX, 0) }
            }
        }
        rebuildSwatches()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
            addView(titleText(title).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
            addView(hsv)
        }
    }

    private fun showCustomColorDialog(
        currentColor: Int,
        onColorSelected: (Int) -> Unit,
    ) {
        val dialogAccent = accentColor
        val initialHex = if (currentColor != 0 && currentColor != COLOR_DYNAMIC) {
            String.format(java.util.Locale.US, "#%06X", currentColor and 0x00FFFFFF)
        } else {
            String.format(java.util.Locale.US, "#%06X", dialogAccent and 0x00FFFFFF)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = TextView(this).apply {
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val parsed = runCatching { Color.parseColor(initialHex) }.getOrDefault(0xFF2A2A28.toInt())
                setColor(parsed)
                setStroke(dp(1), 0x33000000)
            }
        }
        val edit = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(initialHex)
            setSingleLine()
            setTextColor(textColor)
            setHintTextColor(tertiaryTextColor)
            hint = "#RRGGBB"
        }
        row.addView(preview)
        row.addView(edit)
        layout.addView(row)
        layout.addView(hintText(getString(R.string.custom_color_hint)).apply {
            setPadding(0, dp(8), 0, 0)
        })

        val dialogTheme = if (darkUi) R.style.WarmDialog_Dark else R.style.WarmDialog
        val dialog = AlertDialog.Builder(this, dialogTheme)
            .setTitle(getString(R.string.custom_color_title))
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString()?.trim().orEmpty()
                val hex = if (!text.startsWith("#")) "#$text" else text
                val parsed = runCatching { Color.parseColor(hex) }.getOrNull()
                if (parsed != null) {
                    (preview.background as? GradientDrawable)?.setColor(parsed)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(dialogAccent)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(dialogAccent)
        }
        showTrackedDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val text = edit.text.toString().trim()
            val hex = if (!text.startsWith("#")) "#$text" else text
            val parsed = runCatching { Color.parseColor(hex) }.getOrNull()
            if (parsed != null) {
                dialog.dismiss()
                onColorSelected(parsed)
            } else {
                toast(getString(R.string.custom_color_invalid))
            }
        }
    }

    private fun buttonLayoutModeRow(
        bindings: UiBindings,
        onModeChanged: (Int) -> Unit,
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        col.addView(titleText(getString(R.string.set_button_layout_mode)).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
        })

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedSurface(trackColor, 12)
        }
        val modes = listOf(
            ButtonLayoutMode.FOUR to R.string.btn_mode_4,
            ButtonLayoutMode.EIGHT to R.string.btn_mode_8,
            ButtonLayoutMode.TWELVE to R.string.btn_mode_12,
            ButtonLayoutMode.SIXTEEN to R.string.btn_mode_16,
            ButtonLayoutMode.EIGHTEEN to R.string.btn_mode_18,
        )

        fun descResFor(count: Int): Int = when (count) {
            4 -> R.string.btn_mode_4_desc
            8 -> R.string.btn_mode_8_desc
            12 -> R.string.btn_mode_12_desc
            18 -> R.string.btn_mode_18_desc
            else -> R.string.btn_mode_16_desc
        }

        val modeViews = ArrayList<Pair<ButtonLayoutMode, TextView>>(modes.size)
        val descView = hintText(getString(descResFor(prefs.buttonCount))).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
        }

        fun renderSelection(selectedCount: Int) {
            withUiContext(bindings) {
                modeViews.forEach { (mode, tv) ->
                    val selected = selectedCount == mode.count
                    tv.typeface = if (selected) sansMedium else Typeface.DEFAULT
                    tv.setTextColor(if (selected) textColor else secondaryTextColor)
                    tv.background = if (selected) {
                        roundedSurface(elevatedColor, 9).apply { setStroke(maxOf(1, dp(1)), borderColor) }
                    } else {
                        ripple(null, rippleColor, 9)
                    }
                    tv.elevation = if (selected) dp(1).toFloat() else 0f
                }
                descView.text = getString(descResFor(selectedCount))
            }
        }

        modes.forEach { (mode, labelRes) ->
            val tv = TextView(this).apply {
                text = getString(labelRes)
                textSize = 14f
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = getString(labelRes)
                setOnClickListener {
                    if (prefs.buttonCount != mode.count) {
                        prefs.buttonCount = mode.count
                        renderSelection(mode.count)
                        reload()
                        onModeChanged(mode.count)
                    }
                }
            }
            modeViews.add(mode to tv)
            track.addView(tv, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        renderSelection(prefs.buttonCount)

        col.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), 0)
        })
        col.addView(descView)

        return col
    }

    /**
     * 按钮槽位编辑：画一张和真实面板同布局的自适应缩略图，
     * 中间是触控板；根据所选模式（4/8/12/16/18 键）呈现对应布局与形状。
     */
    private fun buttonSlotMap(bindings: UiBindings): View {
        val count = prefs.buttonCount
        val slots = prefs.buttons.take(count).toMutableList()
        while (slots.size < count) slots.add(PadAction.NONE)
        val pressFeedbackEnabled = { prefs.pressFeedback }

        val gap = dp(6)
        val available = resources.displayMetrics.widthPixels - dp(16) * 4
        val cell = ((available - 5 * gap) / 6).coerceAtMost(dp(50))
        val span = 4 * cell + 3 * gap
        val map = FrameLayout(this)

        fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
            map.addView(v, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = x
                topMargin = y
            })
        }

        val padX = cell + gap
        val padY = cell + gap
        val minPadH = controlMinPadHeight(cell, gap, count)
        val padH = minPadH
        val totalH = 2 * cell + 2 * gap + padH

        // 中间触控板底衬与提示
        place(View(this).apply {
            background = borderedSurface(trackColor, borderColor, 16)
        }, padX, padY, span, padH)

        place(TextView(this).apply {
            text = getString(R.string.btn_edit_buttons)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(secondaryTextColor)
            setPadding(dp(10), 0, dp(10), 0)
        }, padX, padY, span, padH)

        val slotRects = if (count == 18) {
            val step = cell + gap
            BUTTON_SLOT_CELLS.map { (c, r) ->
                ControlRect(c * step, r * step, cell, cell)
            }
        } else {
            val edgeCount = count / 4
            val bottomY = padY + padH + gap
            val rightX = padX + span + gap
            val rects = mutableListOf<ControlRect>()
            // Top
            for (i in 0 until edgeCount) {
                val (off, size) = edgeItemOffsetAndSize(i, span, edgeCount, gap)
                rects.add(ControlRect(padX + off, 0, size, cell))
            }
            // Bottom
            for (i in 0 until edgeCount) {
                val (off, size) = edgeItemOffsetAndSize(i, span, edgeCount, gap)
                rects.add(ControlRect(padX + off, bottomY, size, cell))
            }
            // Left
            for (i in 0 until edgeCount) {
                val (off, size) = edgeItemOffsetAndSize(i, padH, edgeCount, gap)
                rects.add(ControlRect(0, padY + off, cell, size))
            }
            // Right
            for (i in 0 until edgeCount) {
                val (off, size) = edgeItemOffsetAndSize(i, padH, edgeCount, gap)
                rects.add(ControlRect(rightX, padY + off, cell, size))
            }
            rects
        }

        fun styleSlotView(iv: ImageView, face: GradientDrawable, index: Int, action: PadAction) {
            withUiContext(bindings) {
                val empty = action == PadAction.NONE
                val grip = PadAction.isGrip(action)
                iv.setImageResource(action.iconRes)
                iv.imageTintList = when {
                    empty -> bindings.colors.tertiaryText
                    grip -> bindings.colors.accent
                    else -> bindings.colors.text
                }
                when {
                    empty -> {
                        face.setColor(Color.TRANSPARENT)
                        face.setStroke(maxOf(1, dp(1) / 2), tertiaryTextColor, dp(3).toFloat(), dp(3).toFloat())
                    }
                    grip -> {
                        face.setColor(accentSoftColor)
                        face.setStroke(maxOf(1, dp(1)), accentSoftStrongColor)
                    }
                    else -> {
                        face.setColor(elevatedColor)
                        face.setStroke(maxOf(1, dp(1) / 2), borderColor)
                    }
                }
                iv.elevation = if (!empty && !grip) dp(1).toFloat() else 0f
                iv.contentDescription = "${slotPositionDescription(index, count, this@MainActivity)}: ${getString(action.labelRes)}"
                iv.invalidate()
            }
        }

        slotRects.forEachIndexed { index, rect ->
            val radius = minOf(rect.w, rect.h) / 2f
            val standardIconPx = (cell * 0.54f).roundToInt()
            val maxIconPx = (minOf(rect.w, rect.h) - dp(4)).coerceAtLeast(dp(8))
            val iconPx = minOf(standardIconPx, maxIconPx).coerceAtLeast(dp(8))
            val padHoriz = ((rect.w - iconPx) / 2).coerceAtLeast(0)
            val padVert = ((rect.h - iconPx) / 2).coerceAtLeast(0)

            val face = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.WHITE)
            }
            val iv = ImageView(this).apply {
                setPadding(padHoriz, padVert, padHoriz, padVert)
                background = RippleDrawable(bindings.colors.ripple, face, mask)
                isClickable = true
                PressFeedback.attach(this, maxTiltX = 6f, maxTiltY = 6f, pressScale = 0.92f, sinkDp = 1.5f, isEnabled = pressFeedbackEnabled)
                setOnClickListener {
                    pickAction(bindings, index, slots[index]) { picked ->
                        slots[index] = picked
                        styleSlotView(this, face, index, picked)
                    }
                }
            }
            styleSlotView(iv, face, index, slots.getOrElse(index) { PadAction.NONE })
            place(iv, rect.x, rect.y, rect.w, rect.h)
        }

        val totalW = 6 * cell + 5 * gap
        return FrameLayout(this).apply {
            setPadding(0, dp(16), 0, dp(12))
            addView(map, FrameLayout.LayoutParams(totalW, totalH, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun pickAction(
        bindings: UiBindings,
        index: Int,
        current: PadAction,
        onUpdated: (PadAction) -> Unit,
    ) {
        val theme = getThemeFor(bindings.isDark)
        showTrackedDialog(
            ActionPicker.build(
                this,
                bindings.isDark,
                current,
                actions = PadAction.TOUCHPAD_ACTIONS,
                accentColor = theme.accentColor,
            ) { picked ->
                val list = replaceSlot(prefs.buttons, index, picked, prefs.buttonCount)
                if (list == null) {
                    toast(getString(R.string.need_move_key))
                    return@build
                }
                prefs.buttons = list
                reload()
                onUpdated(picked)
            }
        )
    }

    private fun showDialog(title: String, body: String) {
        val dialogTheme = if (darkUi) R.style.WarmDialog_Dark else R.style.WarmDialog
        val dialogAccent = accentColor
        val dialog = AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(dialogAccent)
        }
        showTrackedDialog(dialog)
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private fun systemIsDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast(getString(R.string.cannot_open_settings)) }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) {
            toast(getString(R.string.battery_already_whitelisted))
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                toast(getString(R.string.cannot_open_settings))
            }
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.cannot_open_settings))
        }
    }

    private fun hasNotificationPermission(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && !nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun checkAndEnableKeepAliveNotification(enable: Boolean, onComplete: (Boolean) -> Unit) {
        if (!enable) {
            prefs.keepAliveNotification = false
            TouchpadService.instance?.updateKeepAliveNotification()
            onComplete(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingKeepAliveToggle = onComplete
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
        } else if (!hasNotificationPermission()) {
            prefs.keepAliveNotification = false
            allActiveBindings().mapNotNull { it.keepAliveSwitch }.forEach { sw ->
                sw.tag = SYNCING
                sw.isChecked = false
                sw.tag = null
            }
            toast(getString(R.string.notification_permission_denied))
            onComplete(false)
        } else {
            prefs.keepAliveNotification = true
            TouchpadService.instance?.updateKeepAliveNotification()
            onComplete(true)
        }
    }

    private fun updateStatus(targetView: TextView, isDark: Boolean = darkUi) {
        val on = isServiceEnabled()
        val theme = getThemeFor(isDark)
        targetView.text = getString(if (on) R.string.status_on else R.string.status_off)
        if (hasCustomSettingsBg || isDark) {
            targetView.setTextColor(0xFFE8E6DC.toInt())
            targetView.background = roundedSurface(0x33000000.toInt(), 999).apply {
                setStroke(maxOf(1, dp(1)), 0x33FFFFFF.toInt())
            }
        } else {
            targetView.setTextColor(0xFF4D4C48.toInt())
            targetView.background = roundedSurface(theme.trackColor, 999).apply {
                setStroke(maxOf(1, dp(1)), theme.borderColor)
            }
        }
        targetView.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF7A9A5B.toInt() else theme.accentColor)
            setSize(dp(8), dp(8))
        }, null, null, null)
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun footerVersionText(): String {
        val ver = appVersion()
        val name = getString(R.string.app_name)
        return if (ver.isNotEmpty()) "$name $ver" else name
    }

    private fun fullScreenWidthPx(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }

    private fun isServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return flat.split(':').any {
            it == "$packageName/.TouchpadService" ||
                it == "$packageName/$packageName.TouchpadService"
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(
        statusDark: Boolean = darkUi,
        navDark: Boolean = darkUi,
        force: Boolean = true,
    ) {
        if (isFinishing || isDestroyed) return
        val currentWindow = window ?: return
        if (!force && appliedStatusBarDark == statusDark && appliedNavBarDark == navDark) return
        appliedStatusBarDark = statusDark
        appliedNavBarDark = navDark

        val statusTheme = getThemeFor(statusDark)
        val navTheme = if (navDark == statusDark) {
            statusTheme
        } else {
            getThemeFor(navDark)
        }
        val lightStatus = !statusDark && !hasCustomSettingsBg
        val lightNav = !navDark

        val decorView = currentWindow.decorView
        currentWindow.statusBarColor = statusTheme.pageColor
        currentWindow.navigationBarColor = navTheme.pageColor

        if (Build.VERSION.SDK_INT >= 30) {
            val controller = decorView.windowInsetsController
                ?: runCatching { currentWindow.insetsController }.getOrNull()
            if (controller != null) {
                val statusFlag = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                val navFlag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(
                    (if (lightStatus) statusFlag else 0) or (if (lightNav) navFlag else 0),
                    statusFlag or navFlag
                )
                return
            }
        }
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = (if (lightStatus) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or
            (if (lightNav) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    private fun buildAppearanceSummary(mode: ThemeMode = prefs.themeMode): String = buildString {
        append(when (mode) {
            ThemeMode.SYSTEM -> getString(R.string.theme_system)
            ThemeMode.LIGHT -> getString(R.string.theme_light)
            ThemeMode.DARK -> getString(R.string.theme_dark)
        })
        if (prefs.dynamicColor) {
            append(" · ")
            append(getString(R.string.label_dynamic_color))
        }
    }

    private fun updateAppearanceSummaryChip(targetScroller: ScrollView?, mode: ThemeMode) {
        targetScroller?.findViewWithTag<TextView>(APPEARANCE_SUMMARY_CHIP_TAG)?.text =
            buildAppearanceSummary(mode)
    }

    /**
     * 以 (cx, cy) 为圆心进行圆形遮罩裁剪的容器，实现无缝可打断、可交互的扩散动效。
     * 在同一圆形裁剪区域内先绘制目标主题底色（及可选的自定义壁纸与目标主题遮罩），
     * 再绘制新主题滚动内容，确保卡片、页面背景与顶部状态栏区域沿圆波同步切换且无重影闪烁。
     */
    private class RevealLayout(
        context: android.content.Context,
        val scroller: ScrollView,
        backdropColor: Int,
        bgBitmap: android.graphics.Bitmap?,
        scrimColor: Int,
    ) : FrameLayout(context) {
        private val backdropPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            color = backdropColor
        }
        private var bgImageView: ImageView? = null

        init {
            if (bgBitmap != null && !bgBitmap.isRecycled) {
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bgBitmap)
                }
                bgImageView = iv
                addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

                val scrim = View(context).apply {
                    setBackgroundColor(scrimColor)
                }
                addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun release() {
            bgImageView?.setImageDrawable(null)
            bgImageView = null
        }

        var cx = 0f
        var cy = 0f
        var radius = 0f
            set(value) {
                field = value
                invalidate()
            }
        var maxRadius = 0f

        private val path = Path()

        override fun dispatchDraw(canvas: Canvas) {
            if (radius <= 0f) return
            val fullReveal = radius >= maxRadius && maxRadius > 0f
            val save = canvas.save()
            if (!fullReveal) {
                path.reset()
                path.addCircle(cx, cy, radius, Path.Direction.CW)
                canvas.clipPath(path)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (radius <= 0f) return false
            if (radius < maxRadius && maxRadius > 0f) {
                val dx = ev.x - cx
                val dy = ev.y - cy
                if ((dx * dx + dy * dy) > (radius * radius)) {
                    return false
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun attachScrollSync(scrollView: ScrollView) {
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (isSyncingScroll) return@setOnScrollChangeListener
            isSyncingScroll = true
            val other = if (scrollView === baseScroller) currentReveal?.scroller else baseScroller
            other?.scrollTo(0, scrollY)
            isSyncingScroll = false
        }
    }

    private fun startRevealAnimation(
        reveal: RevealLayout,
        fromRadius: Float,
        toRadius: Float,
        toBase: Boolean,
    ) {
        val oldAnim = activeAnimator
        activeAnimator = null
        oldAnim?.cancel()

        val animDuration = computeRevealDuration(fromRadius, toRadius, reveal.maxRadius)

        val anim = ValueAnimator.ofFloat(fromRadius, toRadius).apply {
            duration = animDuration
            interpolator = REVEAL_INTERPOLATOR
            addUpdateListener { va ->
                val r = va.animatedValue as Float
                reveal.radius = r
                val hostW = rootContainer.width.toFloat()
                val hostH = rootContainer.height.toFloat()
                if (hostW > 0f && hostH > 0f) {
                    val statusY = (lastInsetsTop * 0.5f).coerceAtLeast(0f)
                    val navY = (hostH - lastInsetsBottom * 0.5f).coerceAtLeast(0f)
                    val statusDark = resolveRevealSystemBarDark(
                        baseDark = baseThemeDark,
                        revealDark = currentRevealDark,
                        radius = r,
                        cx = reveal.cx,
                        cy = reveal.cy,
                        targetX = hostW * 0.5f,
                        targetY = statusY,
                    )
                    val navDark = resolveRevealSystemBarDark(
                        baseDark = baseThemeDark,
                        revealDark = currentRevealDark,
                        radius = r,
                        cx = reveal.cx,
                        cy = reveal.cy,
                        targetX = hostW * 0.5f,
                        targetY = navY,
                    )
                    updateSystemBars(statusDark = statusDark, navDark = navDark, force = false)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var isCanceled = false

                override fun onAnimationCancel(animation: Animator) {
                    isCanceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (isCanceled) return
                    if (activeAnimator === animation) {
                        activeAnimator = null
                        if (toBase) {
                            reveal.release()
                            (reveal.parent as? ViewGroup)?.removeView(reveal)
                            currentReveal = null
                            revealBindings?.cancelAllCardAnimations()
                            revealBindings = null
                            scroller = baseScroller
                            darkUi = baseThemeDark
                            updateSystemBars(statusDark = baseThemeDark, navDark = baseThemeDark, force = true)
                        } else {
                            finishThemeTransition(reveal, currentRevealDark)
                        }
                    }
                }
            })
        }
        activeAnimator = anim
        anim.start()
    }

    /**
     * 外观主题改变时，以点击的选项为圆心向外执行无缝可打断、可逆向的全屏颜色扩散动效。
     * 当在两个或三个主题间快速连续点击时，通过单一 RevealLayout 的正向/反向半径平滑插值，
     * 杜绝多动画竞争与残留圆圈卡住问题，并保证在动效进行中始终保持屏幕全功能可交互。
     */
    private fun switchThemeWithReveal(mode: ThemeMode, originView: View) {
        val targetDark = mode.resolvesToDark(systemIsDark())
        prefs.themeMode = mode
        TouchpadService.instance?.let { svc ->
            svc.updatePanelAppearance()
            svc.updateBallAppearance()
            svc.updateTouchRingAppearance()
        }

        // 无论动效如何，立即更新两个层级上的分段选择器高亮状态
        baseScroller.findViewWithTag<LinearLayout>(THEME_SELECTOR_TRACK_TAG)?.let {
            updateThemeSelector(it, mode, baseThemeDark)
        }
        currentReveal?.scroller?.findViewWithTag<LinearLayout>(THEME_SELECTOR_TRACK_TAG)?.let {
            updateThemeSelector(it, mode, currentRevealDark)
        }

        val w = rootContainer.width
        val h = rootContainer.height
        if (w <= 0 || h <= 0) {
            darkUi = targetDark
            baseThemeDark = targetDark
            reload()
            recreateKeepingScroll()
            return
        }

        val action = resolveThemeTransitionAction(
            baseDark = baseThemeDark,
            currentRevealDark = currentReveal?.let { currentRevealDark },
            targetDark = targetDark,
        )

        when (action) {
            ThemeTransitionAction.NO_OP -> {
                // 目标主题与底层主题外观一致（例如深色系统下在跟随系统与深色模式间切换），仅更新选中态与摘要标签
                updateAppearanceSummaryChip(baseScroller, mode)
                return
            }
            ThemeTransitionAction.EXPAND_REVEAL -> {
                // 目标主题与当前正在扩散的主题一致，确保向外平滑展开至全屏并同步摘要标签
                val reveal = currentReveal ?: return
                updateAppearanceSummaryChip(reveal.scroller, mode)
                startRevealAnimation(reveal, fromRadius = reveal.radius, toRadius = reveal.maxRadius, toBase = false)
            }
            ThemeTransitionAction.REVERSE_TO_BASE -> {
                // 目标主题回到了底层主题，当前扩散波平滑收缩回圆心并清理，杜绝残留卡死
                val reveal = currentReveal ?: return
                updateAppearanceSummaryChip(baseScroller, mode)
                startRevealAnimation(reveal, fromRadius = reveal.radius, toRadius = 0f, toBase = true)
            }
            ThemeTransitionAction.START_REVEAL -> {
                // 当前没有扩散波，启动新的圆形扩散动画
                val originLoc = IntArray(2)
                originView.getLocationInWindow(originLoc)
                val containerLoc = IntArray(2)
                rootContainer.getLocationInWindow(containerLoc)
                val cx = if (originView.width > 0) {
                    (originLoc[0] - containerLoc[0]) + originView.width / 2f
                } else {
                    w / 2f
                }
                val cy = if (originView.height > 0) {
                    (originLoc[1] - containerLoc[1]) + originView.height / 2f
                } else {
                    h / 2f
                }

                val dx = maxOf(cx, w - cx).toDouble()
                val dy = maxOf(cy, h - cy).toDouble()
                val maxRadius = kotlin.math.hypot(dx, dy).toFloat() + dp(4).toFloat()

                // 在独立 UiBindings 上下文中构建目标主题视图与状态胶囊，不污染底层 baseThemeDark
                val targetTheme = getThemeFor(targetDark)
                revealBindings?.cancelAllCardAnimations()
                val (newScroller, newBindings) = buildUi(targetDark)
                applyScrollInsets(newScroller, lastInsetsLeft, lastInsetsTop, lastInsetsRight, lastInsetsBottom)
                updateStatus(newBindings.statusView, targetDark)
                revealBindings = newBindings

                attachScrollSync(newScroller)

                val revealBgBitmap = settingsBgBitmap?.takeIf { hasCustomSettingsBg && !it.isRecycled }
                val revealScrimColor = if (revealBgBitmap != null) {
                    computeSettingsBgScrimColor(targetTheme.pageColor, prefs.settingsBgScrimPercent)
                } else {
                    0
                }
                val newReveal = RevealLayout(
                    context = this,
                    scroller = newScroller,
                    backdropColor = targetTheme.pageColor,
                    bgBitmap = revealBgBitmap,
                    scrimColor = revealScrimColor,
                ).apply {
                    this.cx = cx
                    this.cy = cy
                    this.radius = 0f
                    this.maxRadius = maxRadius
                }
                currentReveal = newReveal
                currentRevealDark = targetDark

                val host = contentHost ?: rootContainer
                host.addView(
                    newReveal,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                )

                newScroller.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        newScroller.viewTreeObserver.removeOnPreDrawListener(this)
                        isSyncingScroll = true
                        newScroller.scrollTo(0, baseScroller.scrollY)
                        isSyncingScroll = false
                        return true
                    }
                })

                startRevealAnimation(newReveal, fromRadius = 0f, toRadius = maxRadius, toBase = false)
            }
        }
    }

    private fun finishThemeTransition(winnerReveal: RevealLayout, newDark: Boolean) {
        activeAnimator = null
        val winnerScroller = winnerReveal.scroller
        val currentScroll = winnerScroller.scrollY

        winnerReveal.release()
        baseBindings.cancelAllCardAnimations()
        val host = contentHost ?: rootContainer
        host.removeAllViews()
        winnerReveal.removeView(winnerScroller)
        host.addView(
            winnerScroller,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        isSyncingScroll = true
        winnerScroller.scrollTo(0, currentScroll)
        isSyncingScroll = false
        baseScroller = winnerScroller
        baseThemeDark = newDark
        scroller = winnerScroller
        darkUi = newDark
        currentReveal = null
        revealBindings?.let { baseBindings = it }
        revealBindings = null

        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        rootContainer.setBackgroundColor(pageColor)
        applySettingsBackground()
        updateSystemBars(statusDark = newDark, navDark = newDark, force = true)
    }

    /**
     * 换主题、换颜色、换按钮动作后需要重建界面。recreate() 会把页面滚回顶部，
     * 这里先记下滚动位置，重建后再滚回去。
     */
    private fun recreateKeepingScroll() {
        pendingScrollY = scroller.scrollY
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL_Y, if (pendingScrollY >= 0) pendingScrollY else scroller.scrollY)
    }

    private var pendingScrollY = -1

    private fun reload() {
        TouchpadService.instance?.reload()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun pixelsToDp(v: Int, density: Float): Int =
        if (density > 0f) (v / density).roundToInt() else v

    private fun applyExcludeFromRecents(exclude: Boolean) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        am.appTasks?.forEach { task ->
            runCatching { task.setExcludeFromRecents(exclude) }
        }
    }

    private companion object {
        const val KEY_SCROLL_Y = "settings_scroll_y"
        const val THEME_SELECTOR_TRACK_TAG = "theme_selector_track"
        const val APPEARANCE_SUMMARY_CHIP_TAG = "appearance_summary_chip"
        const val REQUEST_CODE_PICK_TOUCHPAD_BG = 1001
        const val REQUEST_CODE_PICK_SETTINGS_BG = 1002
        const val REQUEST_CODE_POST_NOTIFICATIONS = 1003
        val SYNCING = Any()
        val ROW_DIVIDER_TAG = Any()
        val CARD_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
        val REVEAL_INTERPOLATOR = AccelerateDecelerateInterpolator()
        val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        var cachedSerif: Typeface? = null
        val SWITCH_STATES = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val expandedCardIds = mutableSetOf<String>()
        var hasInitializedCardDefaults = false

        /** 和设置页同一套暖色：暖黑（默认）、象牙白、陶土、珊瑚、沙色、橄榄绿、雾蓝。 */
        val CURSOR_COLORS = listOf(
            0xFF141413.toInt(), 0xFFFAF9F5.toInt(), 0xFFC96442.toInt(), 0xFFD97757.toInt(),
            0xFFD4A27F.toInt(), 0xFF7A9A5B.toInt(), 0xFF6A9BCC.toInt(),
        )
        /** 0 = 跟随主题默认色；COLOR_DYNAMIC = 跟随壁纸动态取色。 */
        val BALL_COLORS = listOf(
            0, COLOR_DYNAMIC, 0xFFFAF9F5.toInt(), 0xFFE8E6DC.toInt(), 0xFF87867F.toInt(), 0xFF30302E.toInt(), 0xFF141413.toInt(),
            0xFFD97757.toInt(), 0xFFB53333.toInt(), 0xFFD4A27F.toInt(), 0xFF7A9A5B.toInt(), 0xFF6A9BCC.toInt(), 0xFF8E7CC3.toInt(),
        )
        /** 0 = 跟随主题默认色；COLOR_DYNAMIC = 跟随壁纸动态取色。 */
        val RING_COLORS = listOf(
            0, COLOR_DYNAMIC, 0xFFFAF9F5.toInt(), 0xFFE8E6DC.toInt(), 0xFF87867F.toInt(), 0xFF30302E.toInt(), 0xFF141413.toInt(),
            0xFFD97757.toInt(), 0xFFB53333.toInt(), 0xFFD4A27F.toInt(), 0xFF7A9A5B.toInt(), 0xFF6A9BCC.toInt(), 0xFF8E7CC3.toInt(),
        )
        /** 0 = 跟随主题默认色；COLOR_DYNAMIC = 跟随壁纸动态取色。 */
        val TOUCHPAD_COLORS = listOf(
            0, COLOR_DYNAMIC, 0xFF141413.toInt(), 0xFF2A2A28.toInt(), 0xFF30302E.toInt(), 0xFF87867F.toInt(),
            0xFFE8E6DC.toInt(), 0xFFFAF9F5.toInt(), 0xFFD97757.toInt(), 0xFFB53333.toInt(),
            0xFFD4A27F.toInt(), 0xFF7A9A5B.toInt(), 0xFF6A9BCC.toInt(), 0xFF8E7CC3.toInt(),
            0xFF1E293B.toInt(), 0xFF1C3829.toInt(),
        )
        /** 0 = 跟随主题默认色；COLOR_DYNAMIC = 跟随壁纸动态取色。 */
        val UI_ACCENT_COLORS = listOf(
            0, COLOR_DYNAMIC, 0xFFD97757.toInt(), 0xFFC96442.toInt(), 0xFFD4A27F.toInt(), 0xFFD97706.toInt(),
            0xFF7A9A5B.toInt(), 0xFF10B981.toInt(), 0xFF6A9BCC.toInt(), 0xFF3B82F6.toInt(),
            0xFF8E7CC3.toInt(), 0xFFE5826F.toInt(), 0xFFB53333.toInt(), 0xFF30302E.toInt(),
            0xFF141413.toInt(), 0xFFFAF9F5.toInt(),
        )
    }
}
