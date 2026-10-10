package io.github.opentouchpad

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 动作选择对话框：每行是「线条图标 + 名称」，当前动作用强调色标出。
 * 设置页和面板长按共用，保证两处样式一致。
 */
internal object ActionPicker {
    fun build(
        context: Context,
        dark: Boolean,
        current: PadAction,
        actions: List<PadAction> = PadAction.ALL,
        titleRes: Int = R.string.pick_action,
        noneLabelRes: Int = R.string.act_none,
        accentColor: Int? = null,
        onPick: (PadAction) -> Unit
    ): AlertDialog {
        val themed = android.view.ContextThemeWrapper(context, if (dark) R.style.WarmDialog_Dark else R.style.WarmDialog)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val text = if (dark) 0xFFFAF9F5.toInt() else 0xFF141413.toInt()
        val prefs = Prefs(context)
        val accent = accentColor?.takeIf { it != 0 } ?: resolveUiAccentColor(prefs.uiAccentColor, dark)
        val (accentSoft, accentSoftStrong) = resolveUiAccentSoftColors(accent, dark)
        val chip = if (dark) 0xFF30302E.toInt() else 0xFFF0EEE6.toInt()
        val effectiveActions = if (current in actions) actions else listOf(current) + actions
        val adapter = object : BaseAdapter() {
            override fun getCount() = effectiveActions.size
            override fun getItem(position: Int) = effectiveActions[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val action = effectiveActions[position]
                val selected = action == current
                val icon = ImageView(themed).apply {
                    setImageResource(action.iconRes)
                    imageTintList = ColorStateList.valueOf(if (selected) accent else text)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (selected) accentSoft else chip)
                        if (selected) {
                            setStroke(dp(1), accentSoftStrong)
                        }
                    }
                }
                val labelText = if (action == PadAction.NONE) {
                    context.getString(noneLabelRes)
                } else {
                    context.getString(action.labelRes)
                }
                val label = TextView(themed).apply {
                    this.text = labelText
                    textSize = 15f
                    setTextColor(if (selected) accent else text)
                }
                return LinearLayout(themed).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(20), dp(6), dp(20), dp(6))
                    addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
                    if (selected) addView(ImageView(themed).apply {
                        setImageResource(R.drawable.ic_lu_check)
                        imageTintList = ColorStateList.valueOf(accent)
                    }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    contentDescription = labelText
                }
            }
        }
        val dialog = AlertDialog.Builder(themed)
            .setTitle(titleRes)
            .setAdapter(adapter) { d, which ->
                onPick(effectiveActions[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            TouchpadService.instance?.setOverlaySuppressed(true)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(accent)
            dialog.listView?.apply {
                clipToPadding = false
                setPadding(0, dp(6), 0, dp(6))
                val idx = effectiveActions.indexOf(current)
                if (idx >= 0) {
                    post {
                        setSelection(idx)
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            TouchpadService.instance?.setOverlaySuppressed(false)
        }
        return dialog
    }
}
