package io.github.opentouchpad

import android.content.Context

/** 全部设置都存在本地 SharedPreferences，不联网、不上传、无遥测。 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("opentouchpad", Context.MODE_PRIVATE)

    init {
        migrateCompactLayout()
        migrateUniformGrid()
        migrateWarmColors()
        migrateDarkCursor()
    }

    /** v0.5.3：光标默认改成暖黑实心箭头 + 白色描边。还在用旧默认（象牙白）的用户一起换掉，只执行一次。 */
    private fun migrateDarkCursor() {
        if (sp.getBoolean("darkCursorV053", false)) return
        val e = sp.edit().putBoolean("darkCursorV053", true)
        val c = sp.getInt("cursorColor", 0xFFFAF9F5.toInt())
        if (c == 0xFFFAF9F5.toInt() || c == 0xFFFFFFFF.toInt()) e.putInt("cursorColor", 0xFF141413.toInt())
        e.apply()
    }

    /** v0.5.1：光标颜色换成暖色系，旧的高饱和色映射到最接近的新颜色。只执行一次。 */
    private fun migrateWarmColors() {
        if (sp.getBoolean("warmColorsV051", false)) return
        val map = mapOf(
            0xFFFFFFFF.toInt() to 0xFFFAF9F5.toInt(),
            0xFF000000.toInt() to 0xFF141413.toInt(),
            0xFFFFEB3B.toInt() to 0xFFD4A27F.toInt(),
            0xFF00E5FF.toInt() to 0xFF6A9BCC.toInt(),
            0xFFFF4081.toInt() to 0xFFD97757.toInt(),
            0xFF76FF03.toInt() to 0xFF7A9A5B.toInt(),
        )
        val e = sp.edit().putBoolean("warmColorsV051", true)
        if (sp.contains("cursorColor")) map[sp.getInt("cursorColor", 0)]?.let { e.putInt("cursorColor", it) }
        e.apply()
    }

    /**
     * v0.4.4：改成等间距网格。高度改为「网格最小高度 + 额外高度」，额外高度归零；
     * 宽度再缩到 85%，按钮间距最多 3dp。只执行一次。
     */
    private fun migrateUniformGrid() {
        if (sp.getBoolean("uniformGridV044", false)) return
        val e = sp.edit().putBoolean("uniformGridV044", true).putInt("extraHeightDp", 0)
        sp.getInt("panelWidthDp", 0).takeIf { it > 0 }?.let {
            e.putInt("panelWidthDp", (it * 0.85f).toInt().coerceIn(PANEL_MIN_WIDTH_DP, DEFAULT_PANEL_WIDTH_DP))
        }
        if (sp.getInt("buttonSpacing", DEFAULT_BUTTON_SPACING_DP) > DEFAULT_BUTTON_SPACING_DP) {
            e.putInt("buttonSpacing", DEFAULT_BUTTON_SPACING_DP)
        }
        e.apply()
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
        get() = sp.getInt("cursorColor", 0xFF141413.toInt())
        set(v) = sp.edit().putInt("cursorColor", v).apply()

    /** 点击光圈视觉反馈开关。默认开启。 */
    var touchRingEnabled: Boolean
        get() = sp.getBoolean("touchRingEnabled", true)
        set(v) = sp.edit().putBoolean("touchRingEnabled", v).apply()

    /** 点击光圈颜色；0 表示跟随主题默认陶土色。 */
    var touchRingColor: Int
        get() = sp.getInt("touchRingColor", 0)
        set(v) = sp.edit().putInt("touchRingColor", v).apply()

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
                248
            }
        set(v) = sp.edit().putInt("controlHeightDp", v).apply()

    /** 面板高度超出最紧凑网格的部分（dp），只加高触控板。0 = 最紧凑。 */
    var extraHeightDp: Int
        get() = sp.getInt("extraHeightDp", 0)
        set(v) = sp.edit().putInt("extraHeightDp", v).apply()

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

    /** 悬浮球颜色；0 表示跟随主题的默认陶土色。 */
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

    /** 当前的收起是不是「键盘弹出自动收起」造成的（键盘收起时据此自动还原；存下来，服务重启后也有效）。 */
    var minimizedByKeyboard: Boolean
        get() = sp.getBoolean("minimizedByKeyboard", false)
        set(v) = sp.edit().putBoolean("minimizedByKeyboard", v).apply()

    // ── 悬浮球手势动作 ──
    /** 是否开启悬浮球手势动作。默认开启。关闭后点击直接展开触控板，拖动直接移动悬浮球。 */
    var ballGesturesEnabled: Boolean
        get() = sp.getBoolean("ballGesturesEnabled", true)
        set(v) = sp.edit().putBoolean("ballGesturesEnabled", v).apply()

    var ballActionSingleTap: PadAction
        get() = PadAction.fromId(sp.getString("ballActionSingleTap", null)) ?: PadAction.BACK
        set(v) = sp.edit().putString("ballActionSingleTap", v.id).apply()

    var ballActionDoubleTap: PadAction
        get() = PadAction.fromId(sp.getString("ballActionDoubleTap", null)) ?: PadAction.MINIMIZE
        set(v) = sp.edit().putString("ballActionDoubleTap", v.id).apply()

    var ballActionLongPress: PadAction
        get() = PadAction.fromId(sp.getString("ballActionLongPress", null)) ?: PadAction.MOVE_BALL
        set(v) = sp.edit().putString("ballActionLongPress", v.id).apply()

    var ballActionSwipeUp: PadAction
        get() = PadAction.fromId(sp.getString("ballActionSwipeUp", null)) ?: PadAction.HOME
        set(v) = sp.edit().putString("ballActionSwipeUp", v.id).apply()

    var ballActionSwipeDown: PadAction
        get() = PadAction.fromId(sp.getString("ballActionSwipeDown", null)) ?: PadAction.NOTIFICATIONS
        set(v) = sp.edit().putString("ballActionSwipeDown", v.id).apply()

    var ballActionSwipeLeft: PadAction
        get() = PadAction.fromId(sp.getString("ballActionSwipeLeft", null)) ?: PadAction.RECENTS
        set(v) = sp.edit().putString("ballActionSwipeLeft", v.id).apply()

    var ballActionSwipeRight: PadAction
        get() = PadAction.fromId(sp.getString("ballActionSwipeRight", null)) ?: PadAction.RECENTS
        set(v) = sp.edit().putString("ballActionSwipeRight", v.id).apply()

    fun resetBallActions() {
        sp.edit()
            .remove("ballActionSingleTap")
            .remove("ballActionDoubleTap")
            .remove("ballActionLongPress")
            .remove("ballActionSwipeUp")
            .remove("ballActionSwipeDown")
            .remove("ballActionSwipeLeft")
            .remove("ballActionSwipeRight")
            .apply()
    }


    // ── 按钮 ──
    var buttonRadiusDp: Int
        get() = sp.getInt("buttonRadius", 14)
        set(v) = sp.edit().putInt("buttonRadius", v).apply()

    var buttonSpacingDp: Int
        get() = sp.getInt("buttonSpacing", DEFAULT_BUTTON_SPACING_DP)
        set(v) = sp.edit().putInt("buttonSpacing", v).apply()

    var longPressButtonToCustomize: Boolean
        get() = sp.getBoolean("longPressButtonToCustomize", true)
        set(v) = sp.edit().putBoolean("longPressButtonToCustomize", v).apply()

    /** 自选键位模式：4, 8, 12, 16, 18 键位。默认 16 键。 */
    var buttonCount: Int
        get() = sp.getInt("buttonCount", 16).let {
            if (it in listOf(4, 8, 12, 16, 18)) it else 16
        }
        set(v) {
            val valid = if (v in listOf(4, 8, 12, 16, 18)) v else 16
            sp.edit().putInt("buttonCount", valid).apply()
        }

    var buttonLayoutMode: ButtonLayoutMode
        get() = ButtonLayoutMode.fromCount(buttonCount)
        set(v) { buttonCount = v.count }

    fun buttonsFor(count: Int): List<PadAction> {
        val key = "buttons_$count"
        val raw = if (sp.contains(key)) {
            sp.getString(key, null)
        } else if (sp.contains("buttons")) {
            sp.getString("buttons", null)
        } else {
            null
        }
        return decodeButtonSlots(raw, count)
    }

    fun setButtonsFor(count: Int, list: List<PadAction>) {
        val encoded = list.take(count).joinToString(",") { it.id }
        sp.edit().putString("buttons_$count", encoded).apply()
    }

    /** 当前键位模式下的按键列表。 */
    var buttons: List<PadAction>
        get() = buttonsFor(buttonCount)
        set(v) = setButtonsFor(buttonCount, v)

    // ── 手感 ──
    var longPressMs: Int
        get() = sp.getInt("longPressMs", 600)
        set(v) = sp.edit().putInt("longPressMs", v).apply()

    /** 「光标处长按」按住多久；拖拽锁定也先在起点按住这么久再开始移动。300–3000 ms。 */
    var cursorHoldMs: Int
        get() = sp.getInt("cursorHoldMs", CURSOR_HOLD_DEFAULT_MS).coerceIn(CURSOR_HOLD_MIN_MS, CURSOR_HOLD_MAX_MS)
        set(v) = sp.edit().putInt("cursorHoldMs", v.coerceIn(CURSOR_HOLD_MIN_MS, CURSOR_HOLD_MAX_MS)).apply()

    /** 边缘触发宽度（dp）：从触控板边缘这一圈（含圆角外侧）开始的触摸整段忽略。0 = 关闭。 */
    var padEdgeDeadZoneDp: Int
        get() = sp.getInt("padEdgeDeadZone", PAD_EDGE_DEAD_ZONE_DEFAULT_DP).coerceIn(0, PAD_EDGE_DEAD_ZONE_MAX_DP)
        set(v) = sp.edit().putInt("padEdgeDeadZone", v.coerceIn(0, PAD_EDGE_DEAD_ZONE_MAX_DP)).apply()

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

    var pressFeedback: Boolean
        get() = sp.getBoolean("pressFeedback", true)
        set(v) = sp.edit().putBoolean("pressFeedback", v).apply()

    fun resetAll() = sp.edit().clear().apply()
}
