package io.github.opentouchpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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
            setBackgroundColor(0xFFF6F7FB.toInt())
        }
        content = col

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = roundedSurface(0xFF172554.toInt(), 24)
            elevation = dp(4).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(0xFFF8FAFC.toInt())
        })
        hero.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 14f
            setTextColor(0xFFDCE7FF.toInt())
            setPadding(0, dp(8), 0, 0)
        })
        col.addView(hero)

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF3730A3.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedSurface(0xFFE0E7FF.toInt(), 16)
        }
        col.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(14), 0, dp(4))
        })

        col.addView(bigButton(getString(R.string.open_a11y_settings), onClick = { openAccessibilitySettings() }))
        col.addView(bigButton(getString(R.string.btn_setup), onClick = { showDialog(getString(R.string.steps_title), getString(R.string.steps_body)) }))
        col.addView(bigButton(getString(R.string.btn_tutorial), onClick = { showDialog(getString(R.string.tutorial_title), getString(R.string.tutorial_body)) }))

        // ── 触控板 ──
        col.addView(section(getString(R.string.sec_pad)))
        col.addView(slider(getString(R.string.set_pad_height), 80, 900, prefs.padHeightDp, onChange = { prefs.padHeightDp = it; reload() }))
        col.addView(slider(getString(R.string.set_pad_width), 280, 1200, prefs.panelWidthDp.takeIf { it > 0 } ?: 720, onChange = { prefs.panelWidthDp = it; reload() }))
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
        col.addView(slider(getString(R.string.set_ball_size), 40, 140, prefs.floatingBallSizeDp, onChange = { prefs.floatingBallSizeDp = it; reload() }))
        col.addView(slider(getString(R.string.set_ball_opacity), 20, 100, prefs.floatingBallOpacityPercent, onChange = { prefs.floatingBallOpacityPercent = it; reload() }))

        // ── 光标 ──
        col.addView(section(getString(R.string.sec_cursor)))
        col.addView(slider(getString(R.string.set_cursor_size), 16, 160, prefs.cursorSizeDp, onChange = { prefs.cursorSizeDp = it; reload() }))
        col.addView(label(getString(R.string.set_cursor_color)))
        col.addView(colorRow())

        // ── 手感 ──
        col.addView(section(getString(R.string.sec_feel)))
        col.addView(slider(getString(R.string.set_sensitivity), 5, 40, (prefs.sensitivity * 10).roundToInt()) { prefs.sensitivity = it / 10f })
        col.addView(slider(getString(R.string.set_long_press), 200, 1500, prefs.longPressMs, onChange = { prefs.longPressMs = it }))
        col.addView(slider(getString(R.string.set_dwell), 0, 2000, prefs.dwellMs, getString(R.string.dwell_off), onChange = { prefs.dwellMs = it }))
        col.addView(slider(getString(R.string.set_scroll_distance), 60, 500, prefs.scrollDistanceDp, onChange = { prefs.scrollDistanceDp = it }))
        col.addView(slider(getString(R.string.set_swipe_distance), 150, 900, prefs.swipeDistanceDp, onChange = { prefs.swipeDistanceDp = it }))
        col.addView(switchRow(getString(R.string.switch_haptics), prefs.haptics, onChange = { prefs.haptics = it }))
        col.addView(switchRow(getString(R.string.switch_autohide_landscape), prefs.autoHideLandscape, onChange = { prefs.autoHideLandscape = it; reload() }))
        col.addView(switchRow(getString(R.string.switch_minimize_keyboard), prefs.minimizeOnKeyboard, onChange = { prefs.minimizeOnKeyboard = it }))

        // ── 按钮 ──
        col.addView(section(getString(R.string.sec_buttons)))
        col.addView(label(getString(R.string.btn_edit_buttons)))
        col.addView(buttonSlotList())
        col.addView(slider(getString(R.string.set_button_radius), 0, 40, prefs.buttonRadiusDp, onChange = { prefs.buttonRadiusDp = it; reload() }))
        col.addView(slider(getString(R.string.set_button_spacing), 0, 24, prefs.buttonSpacingDp, onChange = { prefs.buttonSpacingDp = it; reload() }))
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
            setBackgroundColor(0xFFF6F7FB.toInt())
            isFillViewport = true
            addView(col)
        }
    }

    // ───────────────────────── 小控件 ─────────────────────────

    private fun section(title: String): View = TextView(this).apply {
        text = title
        textSize = 18f
        setTextColor(0xFF111827.toInt())
        setPadding(dp(2), dp(28), 0, dp(10))
    }

    private fun label(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF334155.toInt())
        setPadding(dp(2), dp(10), 0, dp(2))
    }

    private fun bigButton(text: String, onClick: () -> Unit): View = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        minHeight = dp(54)
        setTextColor(0xFF1E3A8A.toInt())
        background = roundedSurface(0xFFE7EEFF.toInt(), 14)
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
            setTextColor(0xFF334155.toInt())
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
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF2563EB.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF2563EB.toInt())
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
            isChecked = checked
            minHeight = dp(52)
            setPadding(0, dp(8), 0, dp(8))
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
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener { pickAction(index, action) }
            }
            box.addView(row)
        }
        return box
    }

    private fun pickAction(index: Int, current: PadAction) {
        val labels = PadAction.ALL.map { "${it.icon}  ${getString(it.labelRes)}" }.toTypedArray()
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ───────────────────────── 杂项 ─────────────────────────

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
}
