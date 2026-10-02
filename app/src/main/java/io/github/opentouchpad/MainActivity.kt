package io.github.opentouchpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 设置界面。刻意用系统的普通控件 + 大号点击区域：
 * 这个 App 的用户可能很难精准点击，所以宁可界面朴素一点，也别做成小按钮的密集布局。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var content: LinearLayout
    private var darkUi = false

    private val pageColor get() = if (darkUi) 0xFF101114.toInt() else 0xFFFFFFFF.toInt()
    private val surfaceColor get() = if (darkUi) 0xFF1C1D21.toInt() else 0xFFF4F5F7.toInt()
    private val elevatedColor get() = if (darkUi) 0xFF25262B.toInt() else 0xFFFFFFFF.toInt()
    private val textColor get() = if (darkUi) 0xFFF4F4F5.toInt() else 0xFF171717.toInt()
    private val secondaryTextColor get() = if (darkUi) 0xFFB6B7BC.toInt() else 0xFF5F6368.toInt()
    private val borderColor get() = if (darkUi) 0xFF3A3B42.toInt() else 0xFFE1E3E8.toInt()
    private val accentColor get() = if (darkUi) 0xFF8AB4F8.toInt() else 0xFF2563EB.toInt()

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
            setPadding(dp(20), dp(20), dp(20), dp(36))
            setBackgroundColor(pageColor)
        }
        content = col

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = borderedSurface(elevatedColor, borderColor, 18)
            elevation = dp(2).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(textColor)
        })
        hero.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 14f
            setTextColor(secondaryTextColor)
            setPadding(0, dp(8), 0, 0)
        })
        col.addView(hero)

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(textColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = borderedSurface(surfaceColor, borderColor, 14)
        }
        col.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(14), 0, dp(4))
        })

        col.addView(bigButton(getString(R.string.open_a11y_settings), onClick = { openAccessibilitySettings() }))
        col.addView(bigButton(getString(R.string.btn_setup), onClick = { showDialog(getString(R.string.steps_title), getString(R.string.steps_body)) }))
        col.addView(bigButton(getString(R.string.btn_tutorial), onClick = { showDialog(getString(R.string.tutorial_title), getString(R.string.tutorial_body)) }))

        // ── 外观 ──
        col.addView(section(getString(R.string.sec_appearance)))
        col.addView(themeModeRow())

        // ── 触控板 ──
        col.addView(section(getString(R.string.sec_pad)))
        val maxPanelWidth = pixelsToDp(resources.displayMetrics.widthPixels, resources.displayMetrics.density)
            .coerceIn(PANEL_MIN_WIDTH_DP, CONTROL_MAX_SIZE_DP)
        val currentPanelWidth = (prefs.panelWidthDp.takeIf { it > 0 }
            ?: (maxPanelWidth * prefs.padWidthPercent / 100)).coerceIn(PANEL_MIN_WIDTH_DP, maxPanelWidth)
        val maxPanelHeight = pixelsToDp(resources.displayMetrics.heightPixels, resources.displayMetrics.density)
            .coerceIn(CONTROL_MIN_SIZE_DP, CONTROL_MAX_SIZE_DP)
        val currentPanelHeight = prefs.controlHeightDp.coerceIn(CONTROL_MIN_SIZE_DP, maxPanelHeight)
        col.addView(slider(getString(R.string.set_pad_height), CONTROL_MIN_SIZE_DP, maxPanelHeight, currentPanelHeight, onChange = { prefs.controlHeightDp = it; reload() }))
        col.addView(slider(getString(R.string.set_pad_width), PANEL_MIN_WIDTH_DP, maxPanelWidth, currentPanelWidth, onChange = { prefs.panelWidthDp = it; reload() }))
        col.addView(slider(getString(R.string.set_opacity), 20, 100, prefs.opacityPercent, onChange = { prefs.opacityPercent = it; reload() }))
        col.addView(bigButton(getString(R.string.btn_toggle_panel)) {
            TouchpadService.instance?.toggleMinimize() ?: toast(getString(R.string.status_off))
        })
        col.addView(bigButton(getString(R.string.btn_reset_position)) {
            prefs.padX = -1
            prefs.padY = -1
            prefs.ballX = -1
            prefs.ballY = -1
            reload()
        })

        // ── 悬浮球 ──
        col.addView(section(getString(R.string.sec_ball)))
        col.addView(slider(getString(R.string.set_ball_size), FLOATING_BALL_MIN_DP, FLOATING_BALL_MAX_DP, prefs.floatingBallSizeDp, onChange = { prefs.floatingBallSizeDp = it; reload() }))
        col.addView(slider(getString(R.string.set_ball_opacity), 20, 100, prefs.floatingBallOpacityPercent, onChange = { prefs.floatingBallOpacityPercent = it; reload() }))
        col.addView(switchRow(getString(R.string.switch_hide_ball), prefs.hideFloatingBall, onChange = { prefs.hideFloatingBall = it; reload() }))
        col.addView(label(getString(R.string.hide_ball_hint)))

        // ── 光标 ──
        col.addView(section(getString(R.string.sec_cursor)))
        col.addView(slider(getString(R.string.set_cursor_size), CURSOR_MIN_DP, CURSOR_MAX_DP, prefs.cursorSizeDp, onChange = { prefs.cursorSizeDp = it; reload() }))
        col.addView(slider(getString(R.string.set_cursor_opacity), 10, 100, prefs.cursorOpacityPercent, onChange = { prefs.cursorOpacityPercent = it; reload() }))
        col.addView(label(getString(R.string.set_cursor_color)))
        col.addView(colorRow())

        // ── 手感 ──
        col.addView(section(getString(R.string.sec_feel)))
        col.addView(slider(getString(R.string.set_sensitivity), 5, 40, (prefs.sensitivity * 10).roundToInt()) { prefs.sensitivity = it / 10f })
        col.addView(slider(getString(R.string.set_long_press), 200, 1500, prefs.longPressMs, onChange = { prefs.longPressMs = it }))
        col.addView(slider(getString(R.string.set_dwell), 0, 2000, prefs.dwellMs, getString(R.string.dwell_off), onChange = { prefs.dwellMs = it }))
        col.addView(slider(getString(R.string.set_scroll_distance), 60, 500, prefs.scrollDistanceDp, onChange = { prefs.scrollDistanceDp = it }))
        col.addView(label(getString(R.string.custom_swipe_hint)))
        col.addView(switchRow(getString(R.string.switch_haptics), prefs.haptics, onChange = { prefs.haptics = it }))
        col.addView(switchRow(getString(R.string.switch_autohide_landscape), prefs.autoHideLandscape, onChange = { prefs.autoHideLandscape = it; reload() }))
        col.addView(switchRow(getString(R.string.switch_minimize_keyboard), prefs.minimizeOnKeyboard, onChange = { prefs.minimizeOnKeyboard = it }))

        // ── 按钮 ──
        col.addView(section(getString(R.string.sec_buttons)))
        col.addView(label(getString(R.string.btn_edit_buttons)))
        col.addView(buttonSlotList())
        col.addView(slider(getString(R.string.set_button_radius), 0, 40, prefs.buttonRadiusDp, onChange = { prefs.buttonRadiusDp = it; reload() }))
        col.addView(slider(getString(R.string.set_button_spacing), 0, BUTTON_SPACING_MAX_DP, prefs.buttonSpacingDp, onChange = { prefs.buttonSpacingDp = it; reload() }))
        col.addView(slider(getString(R.string.set_button_text_size), 10, 34, prefs.buttonTextSizeSp, onChange = { prefs.buttonTextSizeSp = it; reload() }))

        // ── 关于 ──
        col.addView(section(getString(R.string.sec_about)))
        col.addView(bigButton(getString(R.string.btn_github)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)))) }
        })
        col.addView(bigButton(getString(R.string.btn_reset_all)) {
            prefs.resetAll()
            toast(getString(R.string.reset_done))
            reload()
            recreate()
        })

        return ScrollView(this).apply {
            setBackgroundColor(pageColor)
            isFillViewport = true
            addView(col)
        }
    }

    // ───────────────────────── 小控件 ─────────────────────────

    private fun section(title: String): View = TextView(this).apply {
        text = title
        textSize = 18f
        setTextColor(textColor)
        setPadding(dp(2), dp(28), 0, dp(10))
    }

    private fun label(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(secondaryTextColor)
        setPadding(dp(2), dp(10), 0, dp(2))
    }

    private fun bigButton(text: String, onClick: () -> Unit): View = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        minHeight = dp(54)
        setTextColor(textColor)
        background = borderedSurface(surfaceColor, borderColor, 14)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(6), 0, dp(2))
        }
    }

    private fun roundedSurface(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun borderedSurface(color: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        roundedSurface(color, radiusDp).apply { setStroke(dp(1), strokeColor) }

    private fun themeModeRow(): View {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        ).forEach { (mode, labelRes) ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(labelRes)
                textSize = 15f
                setTextColor(textColor)
                minHeight = dp(48)
                isChecked = prefs.themeMode == mode
                buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
                setOnClickListener {
                    if (prefs.themeMode != mode) {
                        prefs.themeMode = mode
                        reload()
                        recreate()
                    }
                }
            }
            group.addView(button, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return group
    }

    private fun slider(
        title: String,
        min: Int,
        max: Int,
        value: Int,
        offLabel: String? = null,
        onChange: (Int) -> Unit,
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(secondaryTextColor)
            setPadding(dp(2), dp(12), 0, 0)
        }
        fun render(v: Int) {
            tv.text = if (v <= 0 && offLabel != null) "$title: $offLabel" else "$title: $v"
        }
        render(value)
        box.addView(tv)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
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
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        return box
    }

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(textColor)
            isChecked = checked
            minHeight = dp(52)
            setPadding(0, dp(8), 0, dp(8))
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun colorRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colors = listOf(
            0xFFFFFFFF.toInt() to "white",
            0xFF000000.toInt() to "black",
            0xFFFFEB3B.toInt() to "yellow",
            0xFF00E5FF.toInt() to "cyan",
            0xFFFF4081.toInt() to "pink",
            0xFF76FF03.toInt() to "green",
        )
        colors.forEach { (color, _) ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(2), if (prefs.cursorColor == color) 0xFF1A73E8.toInt() else 0x55000000)
                }
                setOnClickListener {
                    prefs.cursorColor = color
                    reload()
                    recreate()
                }
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
        }
        return row
    }

    private fun buttonSlotList(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val slots = prefs.buttons.toMutableList()
        while (slots.size < 12) slots.add(PadAction.NONE)
        slots.forEachIndexed { index, action ->
            val row = TextView(this).apply {
                text = "${index + 1}.  ${action.icon}  ${getString(action.labelRes)}"
                textSize = 16f
                setTextColor(textColor)
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener { pickAction(index, action) }
            }
            box.addView(row)
        }
        return box
    }

    private fun pickAction(index: Int, current: PadAction) {
        val labels = PadAction.ALL.map { "${it.icon}  ${getString(it.labelRes)}" }.toTypedArray()
        val dialogTheme = if (darkUi) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
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
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
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
        statusView.text = if (isServiceEnabled()) getString(R.string.status_on) else getString(R.string.status_off)
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

    private fun reload() {
        TouchpadService.instance?.reload()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun pixelsToDp(v: Int, density: Float): Int =
        if (density > 0f) (v / density).roundToInt() else v
}
