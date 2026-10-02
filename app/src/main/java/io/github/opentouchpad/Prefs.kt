package io.github.opentouchpad

import android.content.Context

/** 全部设置都存在本地 SharedPreferences，不联网、不上传、无遥测。 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("opentouchpad", Context.MODE_PRIVATE)

    init {
        migrateCompactLayout()
    }

    /**
     * v0.4.3：整体缩小。已经手动调过尺寸的用户，宽高各缩到 80%，只执行一次。
     * 之后用户再调的尺寸原样保留。
     */
    private fun migrateCompactLayout() {
        if (sp.getBoolean("compactLayoutV043", false)) return
        val e = sp.edit().putBoolean("compactLayoutV043", true)
        sp.getInt("panelWidthDp", 0).takeIf { it > 0 }?.let {
            e.putInt("panelWidthDp", (it * 0.8f).toInt().coerceAtLeast(PANEL_MIN_WIDTH_DP))
        }
        sp.getInt("controlHeightDp", 0).takeIf { it > 0 }?.let {
            e.putInt("controlHeightDp", (it * 0.8f).toInt().coerceAtLeast(CONTROL_MIN_SIZE_DP))
        }
        e.apply()
    }

    var themeMode: ThemeMode
        get() = if (sp.contains("themeMode")) {
            ThemeMode.fromId(sp.getString("themeMode", null))
        } else {
            ThemeMode.LIGHT
        }
        set(v) = sp.edit().putString("themeMode", v.id).apply()

    // ── 光标 ──
    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 1.4f)
        set(v) = sp.edit().putFloat("sensitivity", v).apply()

    var cursorSizeDp: Int
        get() = sp.getInt("cursorSize", 36)
        set(v) = sp.edit().putInt("cursorSize", v).apply()

    var cursorOpacityPercent: Int
        get() = sp.getInt("cursorOpacity", 100)
        set(v) = sp.edit().putInt("cursorOpacity", v).apply()

    var cursorColor: Int
        get() = sp.getInt("cursorColor", 0xFFFFFFFF.toInt())
        set(v) = sp.edit().putInt("cursorColor", v).apply()

    // ── 触控板 ──
    /** Legacy pad height retained for preference compatibility. */
    var padHeightDp: Int
        get() = sp.getInt("padHeight", 240)
        set(v) = sp.edit().putInt("padHeight", v).apply()

    var padWidthPercent: Int
        get() = sp.getInt("padWidthPercent", 96)
        set(v) = sp.edit().putInt("padWidthPercent", v).apply()

    /** Absolute panel width in dp. 0 keeps the legacy percentage-based width. */
    var panelWidthDp: Int
        get() = sp.getInt("panelWidthDp", DEFAULT_PANEL_WIDTH_DP)
        set(v) = sp.edit().putInt("panelWidthDp", v).apply()

    /** Overall overlay height in dp. 0 keeps the legacy pad-height preference. */
    var controlHeightDp: Int
        get() = sp.getInt("controlHeightDp", 0).takeIf { it > 0 }
            ?: if (sp.contains("padHeight")) {
                (padHeightDp + 168).coerceIn(CONTROL_MIN_SIZE_DP, CONTROL_MAX_SIZE_DP)
            } else {
                DEFAULT_CONTROL_HEIGHT_DP
            }
        set(v) = sp.edit().putInt("controlHeightDp", v).apply()

    var opacityPercent: Int
        get() = sp.getInt("opacity", 75)
        set(v) = sp.edit().putInt("opacity", v).apply()

    var floatingBallSizeDp: Int
        get() = sp.getInt("floatingBallSize", 64)
        set(v) = sp.edit().putInt("floatingBallSize", v).apply()

    /** 收起时完全不显示悬浮球；从设置页「显示／隐藏触控板」展开。 */
    var hideFloatingBall: Boolean
        get() = sp.getBoolean("hideFloatingBall", false)
        set(v) = sp.edit().putBoolean("hideFloatingBall", v).apply()

    /** 悬浮球颜色；0 表示跟随主题的默认蓝色。 */
    var floatingBallColor: Int
        get() = sp.getInt("floatingBallColor", 0)
        set(v) = sp.edit().putInt("floatingBallColor", v).apply()

    var floatingBallOpacityPercent: Int
        get() = sp.getInt("floatingBallOpacity", 90)
        set(v) = sp.edit().putInt("floatingBallOpacity", v).apply()

    /** 面板左上角坐标；-1 表示"自动贴在底部居中"。 */
    var padX: Int
        get() = sp.getInt("padX", -1)
        set(v) = sp.edit().putInt("padX", v).apply()

    var padY: Int
        get() = sp.getInt("padY", -1)
        set(v) = sp.edit().putInt("padY", v).apply()

    /** Floating ball top-left coordinates; -1 means the default right-center position. */
    var ballX: Int
        get() = sp.getInt("ballX", -1)
        set(v) = sp.edit().putInt("ballX", v).apply()

    var ballY: Int
        get() = sp.getInt("ballY", -1)
        set(v) = sp.edit().putInt("ballY", v).apply()

    var minimized: Boolean
        get() = sp.getBoolean("minimized", false)
        set(v) = sp.edit().putBoolean("minimized", v).apply()

    var autoHideLandscape: Boolean
        get() = sp.getBoolean("autoHideLandscape", true)
        set(v) = sp.edit().putBoolean("autoHideLandscape", v).apply()

    var minimizeOnKeyboard: Boolean
        get() = sp.getBoolean("minimizeOnKeyboard", true)
        set(v) = sp.edit().putBoolean("minimizeOnKeyboard", v).apply()

    // ── 按钮 ──
    var buttonRadiusDp: Int
        get() = sp.getInt("buttonRadius", 14)
        set(v) = sp.edit().putInt("buttonRadius", v).apply()

    var buttonSpacingDp: Int
        get() = sp.getInt("buttonSpacing", 6)
        set(v) = sp.edit().putInt("buttonSpacing", v).apply()

    var buttonTextSizeSp: Int
        get() = sp.getInt("buttonTextSize", 16)
        set(v) = sp.edit().putInt("buttonTextSize", v).apply()

    /** 按钮槽位，逗号分隔的 PadAction.id；默认 16 个（含四角）。 */
    var buttons: List<PadAction>
        get() = decodeButtonSlots(sp.getString("buttons", null))
        set(v) = sp.edit().putString("buttons", v.joinToString(",") { it.id }).apply()

    // ── 手感 ──
    var longPressMs: Int
        get() = sp.getInt("longPressMs", 600)
        set(v) = sp.edit().putInt("longPressMs", v).apply()

    /** 0 = 关闭停留点击；否则为"手指静止这么久就自动点一下"的毫秒数。 */
    var dwellMs: Int
        get() = sp.getInt("dwellMs", 0)
        set(v) = sp.edit().putInt("dwellMs", v).apply()

    var scrollDistanceDp: Int
        get() = sp.getInt("scrollDistance", 180)
        set(v) = sp.edit().putInt("scrollDistance", v).apply()

    var swipeDistanceDp: Int
        get() = sp.getInt("swipeDistance", 420)
        set(v) = sp.edit().putInt("swipeDistance", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    fun resetAll() = sp.edit().clear().apply()
}
