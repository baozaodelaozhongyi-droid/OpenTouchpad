package io.github.opentouchpad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        col.addView(Button(this).apply {
            text = getString(R.string.open_a11y_settings)
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { toast(getString(R.string.cannot_open_settings)) }
            }
        })

        slider(col, getString(R.string.set_sensitivity), 5, 40, (prefs.sensitivity * 10).roundToInt()) {
            prefs.sensitivity = it / 10f
            TouchpadService.instance?.reload()
        }
        slider(col, getString(R.string.set_pad_height), 120, 480, prefs.padHeightDp) {
            prefs.padHeightDp = it
            TouchpadService.instance?.reload()
        }
        slider(col, getString(R.string.set_cursor_size), 12, 96, prefs.cursorSizeDp) {
            prefs.cursorSizeDp = it
            TouchpadService.instance?.reload()
        }
        slider(col, getString(R.string.set_long_press), 300, 1500, prefs.longPressMs) {
            prefs.longPressMs = it
        }
        slider(col, getString(R.string.set_dwell), 0, 2000, prefs.dwellMs) {
            prefs.dwellMs = it
        }

        col.addView(Switch(this).apply {
            text = getString(R.string.set_haptics)
            isChecked = prefs.haptics
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.haptics = checked }
        })

        col.addView(Button(this).apply {
            text = getString(R.string.toggle_panel)
            setOnClickListener {
                val svc = TouchpadService.instance
                if (svc == null) {
                    toast(getString(R.string.enable_service_first))
                } else {
                    svc.togglePanel()
                }
            }
        })

        col.addView(Button(this).apply {
            text = getString(R.string.reset_defaults)
            setOnClickListener {
                prefs.reset()
                TouchpadService.instance?.reload()
                recreate()
                toast(getString(R.string.done))
            }
        })

        col.addView(TextView(this).apply {
            text = getString(R.string.privacy_note)
            textSize = 12f
            setPadding(0, dp(24), 0, 0)
        })

        return ScrollView(this).apply { addView(col) }
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit
    ) {
        val tv = TextView(this).apply {
            text = "$label: $value"
            textSize = 14f
            setPadding(0, dp(14), 0, 0)
        }
        parent.addView(tv)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    tv.text = "$label: $v"
                    onChange(v)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        parent.addView(sb)
    }

    private fun updateStatus() {
        status.text = if (isServiceEnabled()) {
            getString(R.string.status_on)
        } else {
            getString(R.string.status_off)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.split(':').any { it.startsWith(packageName) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
