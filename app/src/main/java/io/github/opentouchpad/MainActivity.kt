package io.github.opentouchpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private var showPanelSwitch: Switch? = null
    private var baseStatusView: TextView? = null
    private var baseShowPanelSwitch: Switch? = null
    private var darkUi = false
    private var baseThemeDark = false
    private var currentReveal: RevealLayout? = null
    private var currentRevealDark = false
    private var lastInsetsTop = 0
    private var lastInsetsBottom = 0
    private var activeAnimator: ValueAnimator? = null
    private var isSyncingScroll = false

    // 暖色调配色（参考 Claude 官网）：羊皮纸底色 + 象牙白卡片 + 陶土橙强调色；所有灰色都带暖黄底调。
    private val pageColor get() = if (darkUi) 0xFF1F1E1D.toInt() else 0xFFF5F4ED.toInt()
    private val surfaceColor get() = if (darkUi) 0xFF262624.toInt() else 0xFFFAF9F5.toInt()
    private val elevatedColor get() = if (darkUi) 0xFF30302E.toInt() else 0xFFFFFFFF.toInt()
    private val trackColor get() = if (darkUi) 0xFF30302E.toInt() else 0xFFF0EEE6.toInt()
    private val trackStrongColor get() = if (darkUi) 0xFF4D4C48.toInt() else 0xFFD1CFC5.toInt()
    private val textColor get() = if (darkUi) 0xFFFAF9F5.toInt() else 0xFF141413.toInt()
    private val secondaryTextColor get() = if (darkUi) 0xFFB0AEA5.toInt() else 0xFF5E5D59.toInt()
    private val tertiaryTextColor get() = if (darkUi) 0xFF87867F.toInt() else 0xFF87867F.toInt()
    private val borderColor get() = if (darkUi) 0xFF3D3D3A.toInt() else 0xFFE8E6DC.toInt()
    private val accentColor get() = if (darkUi) 0xFFD97757.toInt() else 0xFFC96442.toInt()
    private val onAccentColor get() = 0xFFFAF9F5.toInt()
    private val accentSoftColor get() = if (darkUi) 0x33D97757 else 0x1FC96442
    private val accentSoftStrongColor get() = if (darkUi) 0x80D97757.toInt() else 0x66C96442
    private val rippleColor get() = if (darkUi) 0x1FFAF9F5 else 0x14141413
    private val dangerColor get() = if (darkUi) 0xFFE5826F.toInt() else 0xFFB53333.toInt()

    /** 标题用衬线体（内置 Noto Serif SC Medium 子集），正文/控件用系统无衬线体。 */
    private val serif: Typeface by lazy {
        runCatching { resources.getFont(R.font.serif_medium) }.getOrDefault(Typeface.SERIF)
    }
    private val sansMedium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        baseThemeDark = prefs.themeMode.resolvesToDark(systemIsDark())
        darkUi = baseThemeDark
        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        updateSystemBars()

        baseScroller = buildUi()
        scroller = baseScroller
        baseStatusView = statusView
        baseShowPanelSwitch = showPanelSwitch
        attachScrollSync(baseScroller)
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(pageColor)
            addView(baseScroller, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
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
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    val s = when (c) {
                        is ScrollView -> c
                        is RevealLayout -> c.scroller
                        else -> null
                    }
                    s?.setPadding(0, top, 0, bottom)
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
    }

    override fun onResume() {
        super.onResume()
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
    }

    // ───────────────────────── 界面骨架 ─────────────────────────

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
            setBackgroundColor(pageColor)
        }
        content = col

        col.addView(hero())

        // ── 外观 ──
        col.addView(sectionHeader(getString(R.string.sec_appearance)))
        col.addView(card(themeModeRow()))

        // ── 触控板 ──
        val maxPanelWidth = pixelsToDp(resources.displayMetrics.widthPixels, resources.displayMetrics.density)
            .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
        val currentPanelWidth = (prefs.panelWidthDp.takeIf { it > 0 }
            ?: (maxPanelWidth * prefs.padWidthPercent / 100)).coerceIn(PANEL_MIN_WIDTH_DP, maxPanelWidth)
        col.addView(sectionHeader(getString(R.string.sec_pad)))
        col.addView(card(
            slider(getString(R.string.set_pad_width), PANEL_MIN_WIDTH_DP, maxPanelWidth, currentPanelWidth, unit = "dp", onChange = { prefs.panelWidthDp = it; reload() }),
            slider(getString(R.string.set_pad_height), 0, CONTROL_EXTRA_HEIGHT_MAX_DP, prefs.extraHeightDp.coerceIn(0, CONTROL_EXTRA_HEIGHT_MAX_DP), unit = "dp",
                hint = getString(R.string.pad_height_hint), onChange = { prefs.extraHeightDp = it; reload() }),
            slider(getString(R.string.set_opacity), 20, 100, prefs.opacityPercent, unit = "%", onChange = { prefs.opacityPercent = it; reload() }),
            switchRow(getString(R.string.switch_show_panel), !prefs.minimized, hint = getString(R.string.switch_show_panel_hint),
                bind = { showPanelSwitch = it }) { show ->
                val svc = TouchpadService.instance
                if (svc == null) {
                    toast(getString(R.string.status_off))
                } else if (show == prefs.minimized) {
                    svc.toggleMinimize()
                }
            },
            actionRow(getString(R.string.btn_reset_position)) {
                prefs.padX = -1
                prefs.padY = -1
                prefs.ballX = -1
                prefs.ballY = -1
                reload()
            },
        ))

        // ── 按钮 ──
        col.addView(sectionHeader(getString(R.string.sec_buttons)))
        col.addView(card(
            buttonLayoutModeRow(),
            buttonSlotMap(),
            FrameLayout(this).apply {
                setPadding(dp(16), 0, dp(16), dp(10))
                addView(hintText(getString(R.string.drag_lock_hint)))
            },
            slider(getString(R.string.set_button_spacing), 0, BUTTON_SPACING_MAX_DP, prefs.buttonSpacingDp, unit = "dp", onChange = { prefs.buttonSpacingDp = it; reload() }),
            switchRow(getString(R.string.switch_long_press_customize), prefs.longPressButtonToCustomize, hint = getString(R.string.switch_long_press_customize_hint), onChange = { prefs.longPressButtonToCustomize = it; reload() }),
        ))

        // ── 悬浮球 ──
        col.addView(sectionHeader(getString(R.string.sec_ball)))
        col.addView(card(
            slider(getString(R.string.set_ball_size), FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP, prefs.floatingBallSizeDp, unit = "dp", onChange = { prefs.floatingBallSizeDp = it; reload() }),
            colorRow(getString(R.string.set_ball_color), BALL_COLORS, { prefs.floatingBallColor }, { prefs.floatingBallColor = it }),
            slider(getString(R.string.set_ball_opacity), 20, 100, prefs.floatingBallOpacityPercent, unit = "%", onChange = { prefs.floatingBallOpacityPercent = it; reload() }),
            switchRow(getString(R.string.switch_hide_ball), prefs.hideFloatingBall, hint = getString(R.string.hide_ball_hint), onChange = { prefs.hideFloatingBall = it; reload() }),
        ))
        col.addView(sectionHeader(getString(R.string.sec_ball_gestures)))
        col.addView(card(
            actionPickerRow(getString(R.string.gesture_single_tap), prefs.ballActionSingleTap, PadAction.BALL_ACTIONS) { prefs.ballActionSingleTap = it },
            actionPickerRow(getString(R.string.gesture_double_tap), prefs.ballActionDoubleTap, PadAction.BALL_ACTIONS) { prefs.ballActionDoubleTap = it },
            actionPickerRow(getString(R.string.gesture_long_press), prefs.ballActionLongPress, PadAction.BALL_LONG_PRESS_ACTIONS) { prefs.ballActionLongPress = it },
            actionPickerRow(getString(R.string.gesture_swipe_up), prefs.ballActionSwipeUp, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeUp = it },
            actionPickerRow(getString(R.string.gesture_swipe_down), prefs.ballActionSwipeDown, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeDown = it },
            actionPickerRow(getString(R.string.gesture_swipe_left), prefs.ballActionSwipeLeft, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeLeft = it },
            actionPickerRow(getString(R.string.gesture_swipe_right), prefs.ballActionSwipeRight, PadAction.BALL_ACTIONS) { prefs.ballActionSwipeRight = it },
            actionRow(getString(R.string.btn_reset_ball_gestures)) {
                prefs.resetBallActions()
                reload()
                recreateKeepingScroll()
            },
        ))

        // ── 光标 ──
        col.addView(sectionHeader(getString(R.string.sec_cursor)))
        col.addView(card(
            slider(getString(R.string.set_cursor_size), CURSOR_MIN_DP, CURSOR_MAX_DP, prefs.cursorSizeDp, unit = "dp", onChange = { prefs.cursorSizeDp = it; reload() }),
            slider(getString(R.string.set_cursor_opacity), 10, 100, prefs.cursorOpacityPercent, unit = "%", onChange = { prefs.cursorOpacityPercent = it; reload() }),
            colorRow(getString(R.string.set_cursor_color), CURSOR_COLORS, { prefs.cursorColor }, { prefs.cursorColor = it }),
        ))

        // ── 手感 ──
        col.addView(sectionHeader(getString(R.string.sec_feel)))
        col.addView(card(
            slider(getString(R.string.set_sensitivity), 5, 40, (prefs.sensitivity * 10).roundToInt(), format = { String.format(java.util.Locale.US, "%.1f×", it / 10f) }) { prefs.sensitivity = it / 10f },
            slider(getString(R.string.set_pad_dead_zone), 0, PAD_EDGE_DEAD_ZONE_MAX_DP, prefs.padEdgeDeadZoneDp, unit = "dp",
                offLabel = getString(R.string.dwell_off), hint = getString(R.string.pad_dead_zone_hint),
                onChange = { prefs.padEdgeDeadZoneDp = it }),
            slider(getString(R.string.set_long_press), 200, 1500, prefs.longPressMs, unit = "ms", onChange = { prefs.longPressMs = it }),
            slider(getString(R.string.set_cursor_hold), CURSOR_HOLD_MIN_MS, CURSOR_HOLD_MAX_MS, prefs.cursorHoldMs, unit = "ms",
                hint = getString(R.string.cursor_hold_hint), onChange = { prefs.cursorHoldMs = it }),
            slider(getString(R.string.set_dwell), 0, 2000, prefs.dwellMs, unit = "ms", offLabel = getString(R.string.dwell_off), onChange = { prefs.dwellMs = it }),
            slider(getString(R.string.set_scroll_distance), 60, 500, prefs.scrollDistanceDp, unit = "dp", hint = getString(R.string.custom_swipe_hint), onChange = { prefs.scrollDistanceDp = it }),
            switchRow(getString(R.string.switch_press_feedback), prefs.pressFeedback, onChange = { prefs.pressFeedback = it; reload() }),
            switchRow(getString(R.string.switch_haptics), prefs.haptics, onChange = { prefs.haptics = it }),
            switchRow(getString(R.string.switch_autohide_landscape), prefs.autoHideLandscape, onChange = { prefs.autoHideLandscape = it; reload() }),
            switchRow(getString(R.string.switch_minimize_keyboard), prefs.minimizeOnKeyboard, onChange = {
                prefs.minimizeOnKeyboard = it
                TouchpadService.instance?.syncKeyboardMinimize()
            }),
        ))

        // ── 关于 ──
        col.addView(sectionHeader(getString(R.string.sec_about)))
        col.addView(card(
            actionRow(getString(R.string.btn_setup)) { showDialog(getString(R.string.steps_title), getString(R.string.steps_body)) },
            actionRow(getString(R.string.btn_github)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)))) }
            },
            actionRow(getString(R.string.btn_reset_all), destructive = true) {
                prefs.resetAll()
                toast(getString(R.string.reset_done))
                reload()
                recreateKeepingScroll()
            },
        ))

        col.addView(TextView(this).apply {
            text = "OpenTouchpad ${appVersion()}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(tertiaryTextColor)
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            scroller = this
            // 固定 id：系统重建（换主题/换颜色后的 recreate）时会自动保存并恢复滚动位置
            id = R.id.settings_scroll
            setBackgroundColor(pageColor)
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
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 15f
            setTextColor(secondaryTextColor)
            setLineSpacing(0f, 1.5f)
            setPadding(0, dp(10), 0, dp(18))
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
            background = borderedSurface(surfaceColor, borderColor, 16)
            clipToOutline = true
            setPadding(0, dp(4), 0, dp(4))
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(borderColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                    marginStart = dp(16); marginEnd = dp(16)
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
        val elevated = if (isDark) 0xFF30302E.toInt() else 0xFFFFFFFF.toInt()
        val border = if (isDark) 0xFF3D3D3A.toInt() else 0xFFE8E6DC.toInt()
        val text = if (isDark) 0xFFFAF9F5.toInt() else 0xFF141413.toInt()
        val secondaryText = if (isDark) 0xFFB0AEA5.toInt() else 0xFF5E5D59.toInt()
        val ripple = if (isDark) 0x1FFAF9F5 else 0x14141413

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
            splitTrack = false
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    render(v)
                    onChange(v)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
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
    private fun colorRow(title: String, palette: List<Int>, get: () -> Int, set: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        palette.forEach { color ->
            val selected = get() == color
            val fill = if (color == 0) 0xFFC96442.toInt() else color
            val swatch = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                text = if (selected) "✓" else ""
                setTextColor(if (Color.luminance(fill) > 0.55f) 0xFF111827.toInt() else Color.WHITE)
                val inner = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (color == 0) {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(0xFFC96442.toInt(), 0xFFD97757.toInt())
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
                contentDescription = if (color == 0) getString(R.string.color_default) else String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)
                isClickable = true
                setOnClickListener {
                    set(color)
                    reload()
                    recreateKeepingScroll()
                }
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
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
        val (padH, totalH) = if (count == 18) {
            (3 * cell + 2 * gap) to (5 * cell + 4 * gap)
        } else {
            span to (2 * cell + span + 2 * gap)
        }

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
            val bottomY = padY + span + gap
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
                val (off, size) = edgeItemOffsetAndSize(i, span, edgeCount, gap)
                rects.add(ControlRect(0, padY + off, cell, size))
            }
            // Right
            for (i in 0 until edgeCount) {
                val (off, size) = edgeItemOffsetAndSize(i, span, edgeCount, gap)
                rects.add(ControlRect(rightX, padY + off, cell, size))
            }
            rects
        }

        slotRects.forEachIndexed { index, rect ->
            val action = slots.getOrElse(index) { PadAction.NONE }
            val empty = action == PadAction.NONE
            val grip = PadAction.isGrip(action)
            val radius = minOf(rect.w, rect.h) / 2f
            val iconPx = (minOf(rect.w, rect.h) * 0.54f).roundToInt().coerceAtLeast(dp(8))
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
        ActionPicker.build(this, darkUi, current, actions = PadAction.TOUCHPAD_ACTIONS) { picked ->
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
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

                rootContainer.addView(
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

        rootContainer.removeAllViews()
        winnerReveal.removeView(winnerScroller)
        rootContainer.addView(
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

        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        rootContainer.setBackgroundColor(pageColor)
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

    private companion object {
        const val KEY_SCROLL_Y = "settings_scroll_y"
        const val THEME_SELECTOR_TRACK_TAG = "theme_selector_track"
        val SYNCING = Any()

        /** 和设置页同一套暖色：暖黑（默认）、象牙白、陶土、珊瑚、沙色、橄榄绿、雾蓝。 */
        val CURSOR_COLORS = listOf(
            0xFF141413.toInt(), 0xFFFAF9F5.toInt(), 0xFFC96442.toInt(), 0xFFD97757.toInt(),
            0xFFD4A27F.toInt(), 0xFF7A9A5B.toInt(), 0xFF6A9BCC.toInt(),
        )
        /** 0 = 跟随主题的默认陶土色。 */
        val BALL_COLORS = listOf(
            0, 0xFFFAF9F5.toInt(), 0xFFE8E6DC.toInt(), 0xFF87867F.toInt(), 0xFF30302E.toInt(), 0xFF141413.toInt(),
            0xFFD97757.toInt(), 0xFFB53333.toInt(), 0xFFD4A27F.toInt(), 0xFF7A9A5B.toInt(), 0xFF6A9BCC.toInt(), 0xFF8E7CC3.toInt(),
        )
    }
}
