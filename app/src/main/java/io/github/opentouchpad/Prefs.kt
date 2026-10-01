package io.github.opentouchpad

import android.content.Context

/** 全部设置都存在本地 SharedPreferences，不联网、不上传。 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("opentouchpad", Context.MODE_PRIVATE)

    /** 触控板灵敏度：手指移动 1px，光标移动 sensitivity px */
    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 1.6f)
        set(value) { sp.edit().putFloat("sensitivity", value).apply() }

    /** 触控板高度（dp） */
    var padHeightDp: Int
        get() = sp.getInt("padHeightDp", 220)
        set(value) { sp.edit().putInt("padHeightDp", value).apply() }

    /** 光标直径（dp） */
    var cursorSizeDp: Int
        get() = sp.getInt("cursorSizeDp", 36)
        set(value) { sp.edit().putInt("cursorSizeDp", value).apply() }

    /** 长按判定时长（ms） */
    var longPressMs: Int
        get() = sp.getInt("longPressMs", 600)
        set(value) { sp.edit().putInt("longPressMs", value).apply() }

    /** 停留点击（dwell click）：手指静止这么久就自动点击；0 = 关闭 */
    var dwellMs: Int
        get() = sp.getInt("dwellMs", 0)
        set(value) { sp.edit().putInt("dwellMs", value).apply() }

    /** 震动反馈 */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(value) { sp.edit().putBoolean("haptics", value).apply() }

    /** 面板是否显示 */
    var panelVisible: Boolean
        get() = sp.getBoolean("panelVisible", true)
        set(value) { sp.edit().putBoolean("panelVisible", value).apply() }

    /** 双击间隔（ms） */
    var doubleTapMs: Int
        get() = sp.getInt("doubleTapMs", 320)
        set(value) { sp.edit().putInt("doubleTapMs", value).apply() }

    /** 移动多大距离才算"拖拽/滑动"而不是"点击"（dp） */
    var slopDp: Int
        get() = sp.getInt("slopDp", 8)
        set(value) { sp.edit().putInt("slopDp", value).apply() }

    fun reset() {
        sp.edit().clear().apply()
    }
}
