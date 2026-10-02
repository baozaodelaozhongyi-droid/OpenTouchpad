package io.github.opentouchpad

import android.app.AlertDialog
import android.content.Context
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
 * 动作选择对话框：每行是「线条图标 + 名称」，当前动作用陶土色标出。
 * 设置页和面板长按共用，保证两处样式一致。
 */
internal object ActionPicker {
    fun build(context: Context, dark: Boolean, current: PadAction, onPick: (PadAction) -> Unit): AlertDialog {
        val themed = android.view.ContextThemeWrapper(context, if (dark) R.style.WarmDialog_Dark else R.style.WarmDialog)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val text = if (dark) 0xFFFAF9F5.toInt() else 0xFF141413.toInt()
        val accent = if (dark) 0xFFD97757.toInt() else 0xFFC96442.toInt()
        val chip = if (dark) 0xFF30302E.toInt() else 0xFFF0EEE6.toInt()
        val actions = PadAction.ALL
        val adapter = object : BaseAdapter() {
            override fun getCount() = actions.size
            override fun getItem(position: Int) = actions[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val action = actions[position]
                val selected = action == current
                val icon = ImageView(themed).apply {
                    setImageResource(action.iconRes)
                    imageTintList = ColorStateList.valueOf(if (selected) accent else text)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (selected) (accent and 0x00FFFFFF) or 0x26000000 else chip)
                    }
                }
                val label = TextView(themed).apply {
                    this.text = context.getString(action.labelRes)
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
                    contentDescription = context.getString(action.labelRes)
                }
            }
        }
        return AlertDialog.Builder(themed)
            .setTitle(R.string.pick_action)
            .setAdapter(adapter) { d, which ->
                onPick(actions[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
