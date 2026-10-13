package io.github.opentouchpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 设置界面：分组卡片 + 大号点击区域（每行至少 54dp 高）。
 * 这个 App 的用户可能很难精准点击，所以整行都可点，开关点文字也能切换。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var baseScroller: ScrollView
    private lateinit var rootContainer: FrameLayout
    class SliderController(val setValue: (Int) -> Unit)
    private class CollapsibleCardController(
        val id: String,
        val setExpanded: (Boolean) -> Unit,
        val isExpanded: () -> Boolean,
    )


    private var showPanelSwitch: Switch? = null
    private var baseStatusView: TextView? = null
    private var baseShowPanelSwitch: Switch? = null
    private var widthSliderController: SliderController? = null
    private var heightSliderController: SliderController? = null
    private var baseWidthSliderController: SliderController? = null
    private var baseHeightSliderController: SliderController? = null
    private var darkUi = false
    private var baseThemeDark = false
    private var currentReveal: RevealLayout? = null
    private var currentRevealDark = false
    private var lastInsetsTop = 0
    private var lastInsetsBottom = 0
    private var activeAnimator: ValueAnimator? = null
    private var isSyncingScroll = false
    private var settingsBgImageView: ImageView? = null
    private var settingsBgScrimView: View? = null
    private var settingsBgBitmap: android.graphics.Bitmap? = null
    private var contentHost: FrameLayout? = null

    private val currentTheme: SettingsTheme
        get() = resolveSettingsTheme(this, darkUi, prefs.dynamicColor, prefs.uiAccentColor)

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
    private val hasCustomSettingsBg get() = CustomBackgroundManager.hasSettingsBg(this)
    private val cardBgColor
        get() = if (hasCustomSettingsBg) {
            Color.argb(230, Color.red(surfaceColor), Color.green(surfaceColor), Color.blue(surfaceColor))
        } else {
            surfaceColor
        }

    /** 标题用衬线体（内置 Noto Serif SC Medium 子集），正文/控件用系统无衬线体。 */
    private val serif: Typeface by lazy {
        runCatching { resources.getFont(R.font.serif_medium) }.getOrDefault(Typeface.SERIF)
    }
    private val sansMedium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        applyExcludeFromRecents(prefs.excludeFromRecents)
        baseThemeDark = prefs.themeMode.resolvesToDark(systemIsDark())
        darkUi = baseThemeDark
        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        updateSystemBars()

        baseScroller = buildUi()
        scroller = baseScroller
        baseStatusView = statusView
        baseShowPanelSwitch = showPanelSwitch
        baseWidthSliderController = widthSliderController
        baseHeightSliderController = heightSliderController
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
            // Android 15 起强制全面屏：内容会画到状态栏下面，这里按系统栏高度补内边距。
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    top = bars.top; bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                lastInsetsTop = top
                lastInsetsBottom = bottom
                host.let { h ->
                    for (i in 0 until h.childCount) {
                        val c = h.getChildAt(i)
                        val s = when (c) {
                            is ScrollView -> c
                            is RevealLayout -> c.scroller
                            else -> null
                        }
                        s?.setPadding(0, top, 0, bottom)
                    }
                }
                insets
            }
        }
        setContentView(rootContainer)

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
        val oldAnim = activeAnimator
        activeAnimator = null
        oldAnim?.cancel()
        settingsBgBitmap?.recycle()
        settingsBgBitmap = null
    }

    private fun applySettingsBackground() {
        val bgFile = CustomBackgroundManager.getSettingsBgFile(this)
        if (bgFile.exists() && bgFile.length() > 0) {
            if (settingsBgBitmap == null) {
                val dm = resources.displayMetrics
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1080)
                settingsBgBitmap = CustomBackgroundManager.loadBackgroundBitmap(bgFile, maxDim)
            }
            val bmp = settingsBgBitmap
            if (bmp != null) {
                settingsBgImageView?.setImageBitmap(bmp)
                settingsBgImageView?.visibility = View.VISIBLE
                val scrimAlpha = (255 * prefs.settingsBgScrimPercent / 100).coerceIn(0, 255)
                val scrimColor = Color.argb(scrimAlpha, Color.red(pageColor), Color.green(pageColor), Color.blue(pageColor))
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
                    Thread {
                        val success = CustomBackgroundManager.saveBackgroundFromUri(
                            appContext, uri, CustomBackgroundManager.getTouchpadBgFile(appContext), maxDim = 1200
                        )
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                if (success) {
                                    toast(getString(R.string.bg_image_applied))
                                    TouchpadService.instance?.reloadTouchpadBackground()
                                    recreateKeepingScroll()
                                } else {
                                    toast(getString(R.string.bg_image_failed))
                                }
                            }
                        }
                    }.start()
                }
                REQUEST_CODE_PICK_SETTINGS_BG -> {
                    val dm = resources.displayMetrics
                    val maxDim = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1080)
                    Thread {
                        val success = CustomBackgroundManager.saveBackgroundFromUri(
                            appContext, uri, CustomBackgroundManager.getSettingsBgFile(appContext), maxDim = maxDim
                        )
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                if (success) {
                                    toast(getString(R.string.bg_image_applied))
                                    settingsBgBitmap = null
                                    applySettingsBackground()
                                    recreateKeepingScroll()
                                } else {
                                    toast(getString(R.string.bg_image_failed))
                                }
                            }
                        }
                    }.start()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TouchpadService.onPanelResized = { w, h ->
            runOnUiThread {
                updateResizedSliders(w, h)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        TouchpadService.onPanelResized = null
    }

    override fun onResume() {
        super.onResume()
        applyExcludeFromRecents(prefs.excludeFromRecents)
        updateStatus()
        // 用户可能在面板上点了收起/展开，回到设置页时同步开关（不触发回调）
        showPanelSwitch?.let { sw ->
            val shown = !prefs.minimized
            if (sw.isChecked != shown) {
                sw.tag = SYNCING
                sw.isChecked = shown
                sw.tag = null
            }
        }
        val maxPanelWidth = pixelsToDp(resources.displayMetrics.widthPixels, resources.displayMetrics.density)
            .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
        val w = if (prefs.panelWidthDp > 0) {
            prefs.panelWidthDp
        } else {
            maxPanelWidth * prefs.padWidthPercent / 100
        }
        updateResizedSliders(w, prefs.extraHeightDp)
    }

    private fun updateResizedSliders(widthDp: Int, extraHeightDp: Int) {
        widthSliderController?.setValue(widthDp)
        heightSliderController?.setValue(extraHeightDp)
        if (baseWidthSliderController !== widthSliderController) {
            baseWidthSliderController?.setValue(widthDp)
        }
        if (baseHeightSliderController !== heightSliderController) {
            baseHeightSliderController?.setValue(extraHeightDp)
        }
    }

    // ───────────────────────── 界面骨架 ─────────────────────────

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
        }
        content = col

        col.addView(hero())

        val uiCardControllers = mutableListOf<CollapsibleCardController>()
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
            vararg rows: View,
        ) {
            col.addView(collapsibleCard(
                cardId = cardId,
                title = title,
                iconResId = iconResId,
                summary = summary,
                defaultExpanded = defaultExpanded,
                onToggle = { updateExpandAllText() },
                controllers = uiCardControllers,
                rows = rows,
            ))
        }

        // ── 外观 ──
        val appearanceSummary = buildString {
            append(when (prefs.themeMode) {
                ThemeMode.SYSTEM -> getString(R.string.theme_system)
                ThemeMode.LIGHT -> getString(R.string.theme_light)
                ThemeMode.DARK -> getString(R.string.theme_dark)
            })
            if (prefs.dynamicColor) {
                append(" · ")
                append(getString(R.string.label_dynamic_color))
            }
        }
        val hasSettingsBg = CustomBackgroundManager.hasSettingsBg(this)
        val appearanceRows = mutableListOf<View>(
            themeModeRow(),
            switchRow(
                getString(R.string.switch_dynamic_color),
                prefs.dynamicColor,
                hint = getString(R.string.switch_dynamic_color_hint),
                onChange = {
                    prefs.dynamicColor = it
                    TouchpadService.instance?.let { svc ->
                        svc.reload()
                        svc.updateTouchRingAppearance()
                    }
                    recreateKeepingScroll()
                },
            ),
            colorRow(
                getString(R.string.set_ui_accent_color),
                UI_ACCENT_COLORS,
                { prefs.uiAccentColor },
                { prefs.uiAccentColor = it },
                onServiceUpdate = {
                    TouchpadService.instance?.let { svc ->
                        svc.reload()
                        svc.updateTouchRingAppearance()
                    }
                },
                allowCustomHex = true,
            ),
            customBackgroundRow(
                title = getString(R.string.set_settings_bg),
                hint = getString(R.string.set_settings_bg_hint),
                hasCustomBg = hasSettingsBg,
                onPick = {
                    launchImagePicker(REQUEST_CODE_PICK_SETTINGS_BG)
                },
                onClear = {
                    CustomBackgroundManager.clearBackground(CustomBackgroundManager.getSettingsBgFile(this))
                    settingsBgBitmap?.recycle()
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
                        val scrimAlpha = (255 * it / 100).coerceIn(0, 255)
                        val scrimColor = Color.argb(scrimAlpha, Color.red(pageColor), Color.green(pageColor), Color.blue(pageColor))
                        settingsBgScrimView?.setBackgroundColor(scrimColor)
                    },
                )
            )
        }
        addCollapsible(
            "appearance",
            getString(R.string.sec_appearance),
            R.drawable.ic_lu_layers_2,
            appearanceSummary,
            defaultExpanded = true,
            *appearanceRows.toTypedArray(),
        )

        // ── 触控板 ──
        val maxPanelWidth = pixelsToDp(resources.displayMetrics.widthPixels, resources.displayMetrics.density)
            .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
        val currentPanelWidth = (prefs.panelWidthDp.takeIf { it > 0 }
            ?: (maxPanelWidth * prefs.padWidthPercent / 100)).coerceIn(PANEL_MIN_WIDTH_DP, maxPanelWidth)
        val padSummary = "${currentPanelWidth}dp · ${prefs.opacityPercent}%"
        val hasTouchpadBg = CustomBackgroundManager.hasTouchpadBg(this)
        val padRows = mutableListOf(
            slider(
                getString(R.string.set_pad_width),
                PANEL_MIN_WIDTH_DP,
                maxPanelWidth,
                currentPanelWidth,
                unit = "dp",
                bind = { widthSliderController = it },
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
                bind = { heightSliderController = it },
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
            ),
            customBackgroundRow(
                title = getString(R.string.set_touchpad_bg),
                hint = getString(R.string.set_touchpad_bg_hint),
                hasCustomBg = hasTouchpadBg,
                onPick = {
                    launchImagePicker(REQUEST_CODE_PICK_TOUCHPAD_BG)
                },
                onClear = {
                    CustomBackgroundManager.clearBackground(CustomBackgroundManager.getTouchpadBgFile(this))
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
            switchRow(getString(R.string.switch_show_panel), !prefs.minimized, hint = getString(R.string.switch_show_panel_hint),
                bind = { showPanelSwitch = it }) { show ->
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
        addCollapsible(
            "pad",
            getString(R.string.sec_pad),
            R.drawable.ic_lu_touchpad,
            padSummary,
            defaultExpanded = true,
            *padRows.toTypedArray(),
        )

        // ── 按钮 ──
        val buttonRows = mutableListOf<View>(
            buttonLayoutModeRow(),
            buttonSlotMap(),
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
                onChange = {
                    prefs.longPressButtonToCustomize = it
                    recreateKeepingScroll()
                },
            ),
        )
        if (prefs.longPressButtonToCustomize) {
            buttonRows.add(
                slider(
                    getString(R.string.set_button_customize_hold),
                    BUTTON_CUSTOMIZE_HOLD_MIN_MS,
                    BUTTON_CUSTOMIZE_HOLD_MAX_MS,
                    prefs.buttonCustomizeHoldMs,
                    unit = "ms",
                    hint = getString(R.string.button_customize_hold_hint),
                    onChange = { prefs.buttonCustomizeHoldMs = it },
                )
            )
        }
        val buttonsSummary = when (prefs.buttonCount) {
            4 -> getString(R.string.btn_mode_4)
            8 -> getString(R.string.btn_mode_8)
            12 -> getString(R.string.btn_mode_12)
            18 -> getString(R.string.btn_mode_18)
            else -> getString(R.string.btn_mode_16)
        }
        addCollapsible(
            "buttons",
            getString(R.string.sec_buttons),
            R.drawable.ic_lu_mouse_pointer_click,
            buttonsSummary,
            defaultExpanded = true,
            *buttonRows.toTypedArray(),
        )

        // ── 悬浮球 ──
        val ballSummary = if (prefs.hideFloatingBall) {
            getString(R.string.label_off)
        } else {
            "${prefs.floatingBallSizeDp}dp · ${prefs.floatingBallOpacityPercent}%"
        }
        addCollapsible(
            "ball",
            getString(R.string.sec_ball),
            R.drawable.ic_lu_circle_dot,
            ballSummary,
            defaultExpanded = false,
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
            switchRow(getString(R.string.switch_hide_ball), prefs.hideFloatingBall, hint = getString(R.string.hide_ball_hint), onChange = { prefs.hideFloatingBall = it; reload() }),
        )

        // ── 悬浮球手势 ──
        val ballGestureRows = mutableListOf<View>()
        ballGestureRows.add(
            switchRow(
                getString(R.string.switch_ball_gestures),
                prefs.ballGesturesEnabled,
                hint = getString(R.string.switch_ball_gestures_hint),
                onChange = {
                    prefs.ballGesturesEnabled = it
                    reload()
                    recreateKeepingScroll()
                }
            )
        )
        if (prefs.ballGesturesEnabled) {
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_single_tap), prefs.ballActionSingleTap, PadAction.BALL_ACTIONS) { prefs.ballActionSingleTap = it })
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_double_tap), prefs.ballActionDoubleTap, PadAction.BALL_ACTIONS) { prefs.ballActionDoubleTap = it })
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_long_press), prefs.ballActionLongPress, PadAction.BALL_LONG_PRESS_ACTIONS) { prefs.ballActionLongPress = it })
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_swipe_up), prefs.ballActionSwipeUp, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeUp = it })
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_swipe_down), prefs.ballActionSwipeDown, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeDown = it })
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_swipe_left), prefs.ballActionSwipeLeft, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeLeft = it })
            ballGestureRows.add(actionPickerRow(getString(R.string.gesture_swipe_right), prefs.ballActionSwipeRight, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeRight = it })
            ballGestureRows.add(actionRow(getString(R.string.btn_reset_ball_gestures)) {
                prefs.resetBallActions()
                reload()
                recreateKeepingScroll()
            })
        }
        val ballGesturesSummary = if (prefs.ballGesturesEnabled) getString(R.string.label_on) else getString(R.string.label_off)
        addCollapsible(
            "ball_gestures",
            getString(R.string.sec_ball_gestures),
            R.drawable.ic_lu_hand,
            ballGesturesSummary,
            defaultExpanded = false,
            *ballGestureRows.toTypedArray(),
        )

        // ── 光标 ──
        val cursorRows = mutableListOf<View>(
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
            ),
            switchRow(
                getString(R.string.switch_touch_ring),
                prefs.touchRingEnabled,
                hint = getString(R.string.switch_touch_ring_hint),
                onChange = {
                    prefs.touchRingEnabled = it
                    TouchpadService.instance?.updateTouchRingAppearance(preview = it)
                    recreateKeepingScroll()
                },
            ),
        )
        if (prefs.touchRingEnabled) {
            cursorRows.add(
                colorRow(
                    getString(R.string.set_touch_ring_color),
                    RING_COLORS,
                    { prefs.touchRingColor },
                    { prefs.touchRingColor = it },
                    onServiceUpdate = { TouchpadService.instance?.updateTouchRingAppearance(preview = true) },
                    allowCustomHex = true,
                )
            )
        }
        val cursorSummary = "${prefs.cursorSizeDp}dp · ${prefs.cursorOpacityPercent}%"
        addCollapsible(
            "cursor",
            getString(R.string.sec_cursor),
            R.drawable.ic_lu_pointer,
            cursorSummary,
            defaultExpanded = false,
            *cursorRows.toTypedArray(),
        )

        // ── 手感 ──
        val feelSummary = String.format(java.util.Locale.US, "%.1f×", prefs.sensitivity)
        addCollapsible(
            "feel",
            getString(R.string.sec_feel),
            R.drawable.ic_lu_move,
            feelSummary,
            defaultExpanded = false,
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

        // ── 关于 ──
        val aboutSummary = "v${appVersion()}".takeIf { it != "v" } ?: ""
        addCollapsible(
            "about",
            getString(R.string.sec_about),
            R.drawable.ic_lu_settings,
            aboutSummary,
            defaultExpanded = false,
            actionRow(getString(R.string.btn_setup)) { showDialog(getString(R.string.steps_title), getString(R.string.steps_body)) },
            actionRow(getString(R.string.btn_github)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)))) }
            },
            actionRow(getString(R.string.btn_reset_all), destructive = true) {
                prefs.resetAll()
                CustomBackgroundManager.clearBackground(CustomBackgroundManager.getTouchpadBgFile(this))
                CustomBackgroundManager.clearBackground(CustomBackgroundManager.getSettingsBgFile(this))
                TouchpadService.instance?.reloadTouchpadBackground()
                settingsBgBitmap?.recycle()
                settingsBgBitmap = null
                expandedCardIds.clear()
                hasInitializedCardDefaults = false
                applyExcludeFromRecents(false)
                toast(getString(R.string.reset_done))
                reload()
                recreateKeepingScroll()
            },
        )

        if (!hasInitializedCardDefaults) {
            hasInitializedCardDefaults = true
        }
        updateExpandAllText()

        col.addView(TextView(this).apply {
            text = "OpenTouchpad ${appVersion()}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (hasCustomSettingsBg && !darkUi) secondaryTextColor else tertiaryTextColor)
            if (hasCustomSettingsBg) {
                setShadowLayer(
                    dp(4).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    if (darkUi) 0x80000000.toInt() else 0x99FFFFFF.toInt()
                )
            }
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            scroller = this
            // 固定 id：系统重建（换主题/换颜色后的 recreate）时会自动保存并恢复滚动位置
            id = R.id.settings_scroll
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(col)
        }
    }

    // ───────────────────────── 头部 ─────────────────────────

    private fun hero(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 34f
            typeface = serif
            letterSpacing = -0.01f
            setTextColor(textColor)
            if (hasCustomSettingsBg) {
                // 环境温润漫反射光晕：浅色模式下采用温润柔和半透白晕，深色模式下采用柔和深色阴影
                // 零遮挡背景插画与人物立绘，同时将字形边缘与复杂暗色/高对比背景利落剥离
                setShadowLayer(
                    dp(8).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    if (darkUi) 0xB3000000.toInt() else 0xD9FFFFFF.toInt()
                )
            }
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 15f
            setTextColor(if (hasCustomSettingsBg && !darkUi) textColor else secondaryTextColor)
            setLineSpacing(0f, 1.5f)
            setPadding(0, dp(10), 0, dp(18))
            if (hasCustomSettingsBg) {
                setShadowLayer(
                    dp(5).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    if (darkUi) 0x99000000.toInt() else 0xB3FFFFFF.toInt()
                )
            }
        })
        statusView = TextView(this).apply {
            textSize = 13f
            typeface = sansMedium
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            compoundDrawablePadding = dp(8)
        }
        box.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val primary = TextView(this).apply {
            text = getString(R.string.open_a11y_settings)
            textSize = 15f
            typeface = sansMedium
            gravity = Gravity.CENTER
            setTextColor(onAccentColor)
            background = ripple(roundedSurface(accentColor, 12), 0x33FAF9F5, 12)
            isClickable = true
            PressFeedback.attach(this, isEnabled = { prefs.pressFeedback })
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

        val label = TextView(this).apply {
            text = getString(R.string.categories_title)
            textSize = 14f
            typeface = sansMedium
            setTextColor(if (hasCustomSettingsBg && !darkUi) textColor else secondaryTextColor)
            if (hasCustomSettingsBg) {
                setShadowLayer(
                    dp(4).toFloat(),
                    0f,
                    dp(1).toFloat(),
                    if (darkUi) 0x80000000.toInt() else 0xB3FFFFFF.toInt()
                )
            }
        }
        bar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btn = TextView(this).apply {
            textSize = 13f
            typeface = sansMedium
            setTextColor(accentColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = ripple(roundedSurface(trackColor, 999), rippleColor, 999)
            isClickable = true
            val allExpanded = controllers.isNotEmpty() && controllers.all { it.isExpanded() }
            text = getString(if (allExpanded) R.string.collapse_all else R.string.expand_all)
            setOnClickListener {
                haptic(it)
                val isAllExpanded = controllers.isNotEmpty() && controllers.all { c -> c.isExpanded() }
                val targetExpand = !isAllExpanded
                controllers.forEach { c -> c.setExpanded(targetExpand) }
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
        vararg rows: View,
    ): View {
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
                text = summary
                textSize = 12f
                typeface = sansMedium
                setTextColor(secondaryTextColor)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundedSurface(trackColor, 999)
            }
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

        // 内容容器
        val contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            setPadding(0, dp(4), 0, dp(6))
        }

        rows.forEachIndexed { i, row ->
            if (i > 0) {
                contentBox.addView(View(this).apply { setBackgroundColor(borderColor) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                        marginStart = dp(18); marginEnd = dp(18)
                    })
            }
            contentBox.addView(row)
        }
        box.addView(contentBox)

        val controller = CollapsibleCardController(
            id = cardId,
            setExpanded = { expand ->
                if (expand) {
                    expandedCardIds.add(cardId)
                    contentBox.visibility = View.VISIBLE
                    headerDivider.visibility = View.VISIBLE
                    chevron.animate().rotation(0f).setDuration(180).start()
                } else {
                    expandedCardIds.remove(cardId)
                    contentBox.visibility = View.GONE
                    headerDivider.visibility = View.GONE
                    chevron.animate().rotation(-90f).setDuration(180).start()
                }
                header.contentDescription = "$title, ${if (expand) "已展开" else "已折叠"}"
            },
            isExpanded = { contentBox.visibility == View.VISIBLE }
        )
        controllers?.add(controller)

        header.setOnClickListener {
            haptic(it)
            val willExpand = contentBox.visibility != View.VISIBLE
            controller.setExpanded(willExpand)
            onToggle?.invoke()
        }

        return box
    }

    private fun sectionHeader(title: String): View = TextView(this).apply {
        text = title
        textSize = 20f
        typeface = serif
        setTextColor(textColor)
        setPadding(dp(8), dp(32), 0, dp(12))
    }

    /** 一组设置放在同一张圆角卡片里，组内用细分隔线隔开。 */
    private fun card(vararg rows: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedSurface(cardBgColor, 24)
            clipToOutline = true
            setPadding(0, dp(4), 0, dp(6))
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(borderColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                    marginStart = dp(18); marginEnd = dp(18)
                })
            box.addView(row)
        }
        return box
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

    private fun actionRow(title: String, destructive: Boolean = false, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(6), dp(12), dp(6))
            background = ripple(null, rippleColor, 0)
            isClickable = true
            setOnClickListener { onClick() }
            addView(titleText(title).apply {
                if (destructive) setTextColor(dangerColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = ripple(null, rippleColor, 0)
            isClickable = true
            setOnClickListener {
                ActionPicker.build(
                    this@MainActivity,
                    darkUi,
                    currentAction,
                    actions = availableActions,
                    noneLabelRes = R.string.act_none_gesture,
                    accentColor = accentColor,
                ) { picked ->
                    onPicked(picked)
                    reload()
                    recreateKeepingScroll()
                }.show()
            }
            addView(titleText(gestureTitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val chip = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(12), dp(4))
                background = roundedSurface(trackColor, 999).apply {
                    setStroke(maxOf(1, dp(1)), borderColor)
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(currentAction.iconRes)
                    imageTintList = ColorStateList.valueOf(accentColor)
                    setPadding(0, 0, dp(6), 0)
                }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(TextView(this@MainActivity).apply {
                    text = if (currentAction == PadAction.NONE) {
                        getString(R.string.act_none_gesture)
                    } else {
                        getString(currentAction.labelRes)
                    }
                    textSize = 13f
                    typeface = sansMedium
                    setTextColor(textColor)
                })
            }
            addView(chip)
        }

    private fun roundedSurface(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun borderedSurface(color: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        roundedSurface(color, radiusDp).apply { setStroke(maxOf(1, dp(1)), strokeColor) }

    private fun ripple(content: Drawable?, color: Int, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), content, roundedSurface(Color.WHITE, radiusDp))

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
                    if (prefs.haptics) {
                        runCatching {
                            performHapticFeedback(
                                HapticFeedbackConstants.VIRTUAL_KEY,
                                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                            )
                        }
                    }
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
        val theme = resolveSettingsTheme(this, isDark, prefs.dynamicColor, prefs.uiAccentColor)
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
            progressTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = ColorStateList.valueOf(trackColor)
            thumbTintList = ColorStateList.valueOf(accentColor)
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
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, if (darkUi) 0xFFB6B7BC.toInt() else 0xFFFFFFFF.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentSoftStrongColor, trackStrongColor),
            )
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
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
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
                COLOR_DYNAMIC -> resolveDynamicAccentColor(this, darkUi)
                0 -> if (prefs.dynamicColor) resolveDynamicAccentColor(this, darkUi) else (if (darkUi) 0xFFD97757.toInt() else 0xFFC96442.toInt())
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
                    set(color)
                    if (onServiceUpdate != null) {
                        onServiceUpdate()
                    } else {
                        reload()
                    }
                    recreateKeepingScroll()
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
                    showCustomColorDialog(currentColor) { picked ->
                        set(picked)
                        if (onServiceUpdate != null) {
                            onServiceUpdate()
                        } else {
                            reload()
                        }
                        recreateKeepingScroll()
                    }
                }
            }
            row.addView(customSwatch, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
            addView(titleText(title).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
    }

    private fun showCustomColorDialog(
        currentColor: Int,
        onColorSelected: (Int) -> Unit,
    ) {
        val initialHex = if (currentColor != 0 && currentColor != COLOR_DYNAMIC) {
            String.format(java.util.Locale.US, "#%06X", currentColor and 0x00FFFFFF)
        } else {
            String.format(java.util.Locale.US, "#%06X", accentColor and 0x00FFFFFF)
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
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(accentColor)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(accentColor)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val text = edit.text.toString().trim()
            val hex = if (!text.startsWith("#")) "#$text" else text
            val parsed = runCatching { Color.parseColor(hex) }.getOrNull()
            if (parsed != null) {
                onColorSelected(parsed)
                dialog.dismiss()
            } else {
                toast(getString(R.string.custom_color_invalid))
            }
        }
    }

    private fun buttonLayoutModeRow(): View {
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
        modes.forEach { (mode, labelRes) ->
            val selected = prefs.buttonCount == mode.count
            track.addView(TextView(this).apply {
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
                    if (prefs.buttonCount != mode.count) {
                        prefs.buttonCount = mode.count
                        reload()
                        recreateKeepingScroll()
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        col.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), 0)
        })

        val descRes = when (prefs.buttonCount) {
            4 -> R.string.btn_mode_4_desc
            8 -> R.string.btn_mode_8_desc
            12 -> R.string.btn_mode_12_desc
            18 -> R.string.btn_mode_18_desc
            else -> R.string.btn_mode_16_desc
        }
        col.addView(hintText(getString(descRes)).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
        })

        return col
    }

    /**
     * 按钮槽位编辑：画一张和真实面板同布局的自适应缩略图，
     * 中间是触控板；根据所选模式（4/8/12/16/18 键）呈现对应布局与形状。
     */
    private fun buttonSlotMap(): View {
        val count = prefs.buttonCount
        val slots = prefs.buttons.take(count).toMutableList()
        while (slots.size < count) slots.add(PadAction.NONE)

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

        slotRects.forEachIndexed { index, rect ->
            val action = slots.getOrElse(index) { PadAction.NONE }
            val empty = action == PadAction.NONE
            val grip = PadAction.isGrip(action)
            val radius = minOf(rect.w, rect.h) / 2f
            val standardIconPx = (cell * 0.54f).roundToInt()
            val maxIconPx = (minOf(rect.w, rect.h) - dp(4)).coerceAtLeast(dp(8))
            val iconPx = minOf(standardIconPx, maxIconPx).coerceAtLeast(dp(8))
            val padH = ((rect.w - iconPx) / 2).coerceAtLeast(0)
            val padV = ((rect.h - iconPx) / 2).coerceAtLeast(0)

            place(ImageView(this).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(when {
                    empty -> tertiaryTextColor
                    grip -> accentColor
                    else -> textColor
                })
                setPadding(padH, padV, padH, padV)
                val face = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    when {
                        empty -> {
                            setColor(Color.TRANSPARENT)
                            setStroke(maxOf(1, dp(1) / 2), tertiaryTextColor, dp(3).toFloat(), dp(3).toFloat())
                        }
                        grip -> {
                            setColor(accentSoftColor)
                            setStroke(maxOf(1, dp(1)), accentSoftStrongColor)
                        }
                        else -> {
                            setColor(elevatedColor)
                            setStroke(maxOf(1, dp(1) / 2), borderColor)
                        }
                    }
                }
                val mask = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), face, mask)
                if (!empty && !grip) elevation = dp(1).toFloat()
                isClickable = true
                PressFeedback.attach(this, maxTiltX = 6f, maxTiltY = 6f, pressScale = 0.92f, sinkDp = 1.5f, isEnabled = { prefs.pressFeedback })
                contentDescription = "${slotPositionDescription(index, count, this@MainActivity)}: ${getString(action.labelRes)}"
                setOnClickListener { pickAction(index, action) }
            }, rect.x, rect.y, rect.w, rect.h)
        }

        val totalW = 6 * cell + 5 * gap
        return FrameLayout(this).apply {
            setPadding(0, dp(16), 0, dp(12))
            addView(map, FrameLayout.LayoutParams(totalW, totalH, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun pickAction(index: Int, current: PadAction) {
        ActionPicker.build(this, darkUi, current, actions = PadAction.TOUCHPAD_ACTIONS, accentColor = accentColor) { picked ->
            val list = replaceSlot(prefs.buttons, index, picked, prefs.buttonCount)
            if (list == null) {
                toast(getString(R.string.need_move_key))
                return@build
            }
            prefs.buttons = list
            reload()
            recreateKeepingScroll()
        }.show()
    }

    private fun showDialog(title: String, body: String) {
        val dialogTheme = if (darkUi) R.style.WarmDialog_Dark else R.style.WarmDialog
        val dialog = AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(accentColor)
        }
        dialog.show()
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private fun systemIsDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast(getString(R.string.cannot_open_settings)) }
    }

    private fun updateStatus() {
        val on = isServiceEnabled()
        statusView.text = getString(if (on) R.string.status_on else R.string.status_off)
        statusView.setTextColor(if (darkUi) 0xFFE8E6DC.toInt() else 0xFF4D4C48.toInt())
        statusView.background = roundedSurface(trackColor, 999).apply { setStroke(maxOf(1, dp(1)), borderColor) }
        statusView.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF7A9A5B.toInt() else accentColor)
            setSize(dp(8), dp(8))
        }, null, null, null)
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

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
    private fun updateSystemBars() {
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (darkUi) 0 else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            if (darkUi) {
                controller?.setSystemBarsAppearance(0, flags)
            } else {
                controller?.setSystemBarsAppearance(flags, flags)
            }
        }
    }

    /**
     * 以 (cx, cy) 为圆心进行圆形遮罩裁剪的容器，实现无缝可打断、可交互的扩散动效。
     */
    private class RevealLayout(context: android.content.Context, val scroller: ScrollView) : FrameLayout(context) {
        init {
            addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
            if (radius >= maxRadius && maxRadius > 0f) {
                super.dispatchDraw(canvas)
                return
            }
            val save = canvas.save()
            path.reset()
            path.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.clipPath(path)
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
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                reveal.radius = va.animatedValue as Float
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
                            rootContainer.removeView(reveal)
                            currentReveal = null
                            baseStatusView?.let { statusView = it }
                            baseShowPanelSwitch?.let { showPanelSwitch = it }
                            baseWidthSliderController?.let { widthSliderController = it }
                            baseHeightSliderController?.let { heightSliderController = it }
                            scroller = baseScroller
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
                // 目标主题与底层主题外观一致（例如深色系统下在跟随系统与深色模式间切换），仅按钮高亮变化，无需动画
                return
            }
            ThemeTransitionAction.EXPAND_REVEAL -> {
                // 目标主题与当前正在扩散的主题一致，确保向外平滑展开至全屏
                val reveal = currentReveal ?: return
                startRevealAnimation(reveal, fromRadius = reveal.radius, toRadius = reveal.maxRadius, toBase = false)
            }
            ThemeTransitionAction.REVERSE_TO_BASE -> {
                // 目标主题回到了底层主题，当前扩散波平滑收缩回圆心并清理，杜绝残留卡死
                val reveal = currentReveal ?: return
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

                // 临时切换 darkUi 以构建新主题视图
                darkUi = targetDark
                val newScroller = buildUi().apply {
                    setPadding(0, lastInsetsTop, 0, lastInsetsBottom)
                }
                darkUi = baseThemeDark // 恢复底层 baseThemeDark

                attachScrollSync(newScroller)
                updateStatus()

                val newReveal = RevealLayout(this, newScroller).apply {
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
                        newScroller.scrollTo(0, baseScroller.scrollY)
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

        val host = contentHost ?: rootContainer
        host.removeAllViews()
        winnerReveal.removeView(winnerScroller)
        host.addView(
            winnerScroller,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        winnerScroller.scrollTo(0, currentScroll)
        baseScroller = winnerScroller
        baseThemeDark = newDark
        scroller = winnerScroller
        darkUi = newDark
        currentReveal = null
        baseStatusView = statusView
        baseShowPanelSwitch = showPanelSwitch
        baseWidthSliderController = widthSliderController
        baseHeightSliderController = heightSliderController

        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        rootContainer.setBackgroundColor(pageColor)
        applySettingsBackground()
        updateSystemBars()
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
        const val REQUEST_CODE_PICK_TOUCHPAD_BG = 1001
        const val REQUEST_CODE_PICK_SETTINGS_BG = 1002
        val SYNCING = Any()
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
