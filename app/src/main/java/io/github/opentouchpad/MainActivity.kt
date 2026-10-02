package io.github.opentouchpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private var darkUi = false

    // 页面是浅灰底 + 白色卡片（深色模式：近黑底 + 深灰卡片）
    private val pageColor get() = if (darkUi) 0xFF0E0F12.toInt() else 0xFFF2F3F7.toInt()
    private val surfaceColor get() = if (darkUi) 0xFF1A1B20.toInt() else 0xFFFFFFFF.toInt()
    private val elevatedColor get() = if (darkUi) 0xFF2A2B31.toInt() else 0xFFFFFFFF.toInt()
    private val trackColor get() = if (darkUi) 0xFF26272D.toInt() else 0xFFEDEEF2.toInt()
    private val trackStrongColor get() = if (darkUi) 0xFF3A3B42.toInt() else 0xFFD5D8DF.toInt()
    private val textColor get() = if (darkUi) 0xFFF4F4F5.toInt() else 0xFF15171A.toInt()
    private val secondaryTextColor get() = if (darkUi) 0xFFA4A6AD.toInt() else 0xFF6B7078.toInt()
    private val tertiaryTextColor get() = if (darkUi) 0xFF6E7078.toInt() else 0xFFA3A7AE.toInt()
    private val borderColor get() = if (darkUi) 0xFF2C2D33.toInt() else 0xFFE6E8EC.toInt()
    private val accentColor get() = if (darkUi) 0xFF8AB4F8.toInt() else 0xFF2563EB.toInt()
    private val accentSoftColor get() = if (darkUi) 0x338AB4F8 else 0x1A2563EB
    private val accentSoftStrongColor get() = if (darkUi) 0x808AB4F8.toInt() else 0x662563EB
    private val rippleColor get() = if (darkUi) 0x22FFFFFF else 0x14000000
    private val dangerColor get() = if (darkUi) 0xFFF28B82.toInt() else 0xFFD93025.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        darkUi = prefs.themeMode.resolvesToDark(systemIsDark())
        setTheme(if (darkUi) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        window.decorView.systemUiVisibility = if (darkUi) 0 else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ───────────────────────── 界面骨架 ─────────────────────────

    private fun buildUi(): View {
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
            actionRow(getString(R.string.btn_toggle_panel)) {
                TouchpadService.instance?.toggleMinimize() ?: toast(getString(R.string.status_off))
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
            buttonSlotMap(),
            slider(getString(R.string.set_button_spacing), 0, BUTTON_SPACING_MAX_DP, prefs.buttonSpacingDp, unit = "dp", onChange = { prefs.buttonSpacingDp = it; reload() }),
            slider(getString(R.string.set_button_text_size), 10, 34, prefs.buttonTextSizeSp, unit = "sp", onChange = { prefs.buttonTextSizeSp = it; reload() }),
        ))

        // ── 悬浮球 ──
        col.addView(sectionHeader(getString(R.string.sec_ball)))
        col.addView(card(
            slider(getString(R.string.set_ball_size), FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP, prefs.floatingBallSizeDp, unit = "dp", onChange = { prefs.floatingBallSizeDp = it; reload() }),
            colorRow(getString(R.string.set_ball_color), BALL_COLORS, { prefs.floatingBallColor }, { prefs.floatingBallColor = it }),
            slider(getString(R.string.set_ball_opacity), 20, 100, prefs.floatingBallOpacityPercent, unit = "%", onChange = { prefs.floatingBallOpacityPercent = it; reload() }),
            switchRow(getString(R.string.switch_hide_ball), prefs.hideFloatingBall, hint = getString(R.string.hide_ball_hint), onChange = { prefs.hideFloatingBall = it; reload() }),
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
            slider(getString(R.string.set_long_press), 200, 1500, prefs.longPressMs, unit = "ms", onChange = { prefs.longPressMs = it }),
            slider(getString(R.string.set_dwell), 0, 2000, prefs.dwellMs, unit = "ms", offLabel = getString(R.string.dwell_off), onChange = { prefs.dwellMs = it }),
            slider(getString(R.string.set_scroll_distance), 60, 500, prefs.scrollDistanceDp, unit = "dp", hint = getString(R.string.custom_swipe_hint), onChange = { prefs.scrollDistanceDp = it }),
            switchRow(getString(R.string.switch_haptics), prefs.haptics, onChange = { prefs.haptics = it }),
            switchRow(getString(R.string.switch_autohide_landscape), prefs.autoHideLandscape, onChange = { prefs.autoHideLandscape = it; reload() }),
            switchRow(getString(R.string.switch_minimize_keyboard), prefs.minimizeOnKeyboard, onChange = { prefs.minimizeOnKeyboard = it }),
        ))

        // ── 关于 ──
        col.addView(sectionHeader(getString(R.string.sec_about)))
        col.addView(card(
            actionRow(getString(R.string.btn_setup)) { showDialog(getString(R.string.steps_title), getString(R.string.steps_body)) },
            actionRow(getString(R.string.btn_tutorial)) { showDialog(getString(R.string.tutorial_title), getString(R.string.tutorial_body)) },
            actionRow(getString(R.string.btn_github)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)))) }
            },
            actionRow(getString(R.string.btn_reset_all), destructive = true) {
                prefs.resetAll()
                toast(getString(R.string.reset_done))
                reload()
                recreate()
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
            setBackgroundColor(pageColor)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    // ───────────────────────── 头部 ─────────────────────────

    private fun hero(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (darkUi) intArrayOf(0xFF1E3A8A.toInt(), 0xFF312E81.toInt())
                else intArrayOf(0xFF2563EB.toInt(), 0xFF4F46E5.toInt()),
            ).apply { cornerRadius = dp(24).toFloat() }
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 13f
            setTextColor(0xD9FFFFFF.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(14))
        })
        statusView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            compoundDrawablePadding = dp(8)
        }
        box.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val primary = TextView(this).apply {
            text = getString(R.string.open_a11y_settings)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (darkUi) 0xFF1E3A8A.toInt() else 0xFF1D4ED8.toInt())
            background = ripple(roundedSurface(Color.WHITE, 14), 0x332563EB, 14)
            isClickable = true
            setOnClickListener { openAccessibilitySettings() }
        }
        box.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(16)
        })
        return box
    }

    // ───────────────────────── 小控件 ─────────────────────────

    private fun sectionHeader(title: String): View = TextView(this).apply {
        text = title
        textSize = 13f
        letterSpacing = 0.04f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(accentColor)
        setPadding(dp(8), dp(26), 0, dp(8))
    }

    /** 一组设置放在同一张圆角卡片里，组内用细分隔线隔开。 */
    private fun card(vararg rows: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = borderedSurface(surfaceColor, borderColor, 20)
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
        textSize = 12.5f
        setTextColor(secondaryTextColor)
        setLineSpacing(dp(2).toFloat(), 1f)
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

    private fun roundedSurface(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun borderedSurface(color: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        roundedSurface(color, radiusDp).apply { setStroke(maxOf(1, dp(1) / 2), strokeColor) }

    private fun ripple(content: Drawable?, color: Int, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), content, roundedSurface(Color.WHITE, radiusDp))

    private fun pill(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(accentColor)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = roundedSurface(accentSoftColor, 999)
    }

    /** 外观：三段式分段选择器。 */
    private fun themeModeRow(): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedSurface(trackColor, 14)
        }
        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        ).forEach { (mode, labelRes) ->
            val selected = prefs.themeMode == mode
            track.addView(TextView(this).apply {
                text = getString(labelRes)
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                setTextColor(if (selected) textColor else secondaryTextColor)
                background = if (selected) {
                    roundedSurface(elevatedColor, 11).apply { setStroke(maxOf(1, dp(1) / 2), borderColor) }
                } else {
                    ripple(null, rippleColor, 11)
                }
                if (selected) elevation = dp(1).toFloat()
                isClickable = true
                contentDescription = getString(labelRes)
                setOnClickListener {
                    if (prefs.themeMode != mode) {
                        prefs.themeMode = mode
                        reload()
                        recreate()
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        return FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(track)
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

    private fun switchRow(title: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit): View {
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
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(title))
            if (hint != null) addView(hintText(hint))
        }
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
     * 标题 + 一排颜色色块。[palette] 里的 0 代表「跟随主题默认色」，显示为蓝色渐变色块。
     * 选中的色块外圈加强调色描边并显示对勾；色块多时可以横向滑动。
     */
    private fun colorRow(title: String, palette: List<Int>, get: () -> Int, set: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        palette.forEach { color ->
            val selected = get() == color
            val fill = if (color == 0) 0xFF2563EB.toInt() else color
            val swatch = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                text = if (selected) "✓" else ""
                setTextColor(if (Color.luminance(fill) > 0.55f) 0xFF111827.toInt() else Color.WHITE)
                val inner = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (color == 0) {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(0xFF2563EB.toInt(), 0xFF8AB4F8.toInt())
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
                    recreate()
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

    /**
     * 按钮槽位编辑：画一张和真实面板同布局的缩略图（6 列 × 5 行），
     * 中间是触控板，左列顶部是移动键、右列底部是缩放键，点任意按钮改动作。
     */
    private fun buttonSlotMap(): View {
        val slots = prefs.buttons.toMutableList()
        while (slots.size < BUTTON_SLOT_COUNT) slots.add(PadAction.NONE)
        val positions = resources.getStringArray(R.array.slot_positions)
        // 槽位 → (列, 行)，和 computeControlLayout 的顺序一致
        val cells = (1..4).map { it to 0 } + (1..4).map { it to 4 } +
            listOf(0 to 2, 0 to 3, 5 to 1, 5 to 2) +
            listOf(0 to 0, 5 to 0, 0 to 4, 5 to 4)
        val gap = dp(6)
        val available = resources.displayMetrics.widthPixels - dp(16) * 2 - dp(16) * 2
        val cell = ((available - 5 * gap) / 6).coerceAtMost(dp(52))
        val step = cell + gap
        val map = FrameLayout(this)
        fun place(v: View, c: Int, r: Int, w: Int = cell, h: Int = cell) {
            map.addView(v, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = c * step
                topMargin = r * step
            })
        }
        place(View(this).apply {
            background = borderedSurface(trackColor, borderColor, 16)
        }, 1, 1, 4 * cell + 3 * gap, 3 * cell + 2 * gap)
        fun grip(label: String, c: Int, r: Int, desc: String) = place(TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(accentColor)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentSoftColor) }
            contentDescription = desc
        }, c, r)
        grip("✥", 0, 1, getString(R.string.handle_move))
        grip("⤡", 5, 3, getString(R.string.handle_resize))
        slots.take(BUTTON_SLOT_COUNT).forEachIndexed { index, action ->
            val (c, r) = cells[index]
            val empty = action == PadAction.NONE
            place(TextView(this).apply {
                text = if (empty) "+" else action.icon
                textSize = if (empty) 16f else 15f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (empty) tertiaryTextColor else textColor)
                val face = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (empty) Color.TRANSPARENT else elevatedColor)
                    setStroke(maxOf(1, dp(1) / 2), if (empty) tertiaryTextColor else borderColor)
                    if (empty) setStroke(maxOf(1, dp(1) / 2), tertiaryTextColor, dp(3).toFloat(), dp(3).toFloat())
                }
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), face,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                if (!empty) elevation = dp(1).toFloat()
                isClickable = true
                contentDescription = "${positions.getOrElse(index) { "${index + 1}" }}: ${getString(action.labelRes)}"
                setOnClickListener { pickAction(index, action) }
            }, c, r)
        }
        // 中间触控板上写提示
        place(TextView(this).apply {
            text = getString(R.string.btn_edit_buttons)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(secondaryTextColor)
            setPadding(dp(10), 0, dp(10), 0)
        }, 1, 1, 4 * cell + 3 * gap, 3 * cell + 2 * gap)
        return FrameLayout(this).apply {
            setPadding(0, dp(16), 0, dp(12))
            addView(map, FrameLayout.LayoutParams(6 * cell + 5 * gap, 5 * cell + 4 * gap, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun pickAction(index: Int, current: PadAction) {
        val labels = PadAction.ALL.map { "${it.icon}  ${getString(it.labelRes)}" }.toTypedArray()
        val dialogTheme = if (darkUi) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(R.string.pick_action)
            .setSingleChoiceItems(labels, PadAction.ALL.indexOf(current)) { dialog, which ->
                val list = prefs.buttons.toMutableList()
                while (list.size <= index) list.add(PadAction.NONE)
                list[index] = PadAction.ALL[which]
                prefs.buttons = list
                reload()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDialog(title: String, body: String) {
        val dialogTheme = if (darkUi) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
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
        statusView.setTextColor(Color.WHITE)
        statusView.background = roundedSurface(if (on) 0x4022C55E else 0x40F59E0B, 999)
        statusView.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt())
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
        val CURSOR_COLORS = listOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFEB3B.toInt(),
            0xFF00E5FF.toInt(), 0xFFFF4081.toInt(), 0xFF76FF03.toInt(),
        )
        /** 0 = 跟随主题的默认蓝色。 */
        val BALL_COLORS = listOf(
            0, 0xFFFFFFFF.toInt(), 0xFF374151.toInt(), 0xFF111827.toInt(), 0xFF7C3AED.toInt(),
            0xFFEC4899.toInt(), 0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF10B981.toInt(), 0xFF06B6D4.toInt(),
        )
    }
}
