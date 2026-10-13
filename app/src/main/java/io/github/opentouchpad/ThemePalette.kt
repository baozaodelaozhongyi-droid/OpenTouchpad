package io.github.opentouchpad

import android.app.WallpaperManager
import android.content.Context
import android.os.Build

/**
 * 设置页面与应用主题色板配置。
 * 支持经典 Claude 风格暖色（羊皮纸 + 象牙白）与 Android 12+ (API 31+) Material You Monet 系统动态壁纸取色。
 */
data class SettingsTheme(
    val pageColor: Int,
    val surfaceColor: Int,
    val elevatedColor: Int,
    val trackColor: Int,
    val trackStrongColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val tertiaryTextColor: Int,
    val borderColor: Int,
    val accentColor: Int,
    val onAccentColor: Int,
    val accentSoftColor: Int,
    val accentSoftStrongColor: Int,
    val rippleColor: Int,
    val dangerColor: Int,
)

internal fun rgbToHsl(rgb: Int): FloatArray {
    val r = ((rgb ushr 16) and 0xFF) / 255f
    val g = ((rgb ushr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    var h = 0f
    var s = 0f
    val l = (max + min) / 2f

    if (delta > 0.0001f) {
        s = if (l <= 0.5f) delta / (max + min) else delta / (2f - max - min)
        h = when (max) {
            r -> ((g - b) / delta + (if (g < b) 6f else 0f)) * 60f
            g -> ((b - r) / delta + 2f) * 60f
            else -> ((r - g) / delta + 4f) * 60f
        }
    }
    return floatArrayOf(h, s, l)
}

internal fun hslToRgb(h: Float, s: Float, l: Float): Int {
    fun hue2rgb(p: Float, q: Float, t: Float): Float {
        var tc = t
        if (tc < 0f) tc += 1f
        if (tc > 1f) tc -= 1f
        return when {
            tc < 1f / 6f -> p + (q - p) * 6f * tc
            tc < 1f / 2f -> q
            tc < 2f / 3f -> p + (q - p) * (2f / 3f - tc) * 6f
            else -> p
        }
    }

    val r: Float
    val g: Float
    val b: Float

    if (s <= 0.0001f) {
        r = l
        g = l
        b = l
    } else {
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hNorm = (h % 360f + 360f) % 360f / 360f
        r = hue2rgb(p, q, hNorm + 1f / 3f)
        g = hue2rgb(p, q, hNorm)
        b = hue2rgb(p, q, hNorm - 1f / 3f)
    }

    val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
    val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
    val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}

/**
 * 从系统提取 Material You (Monet) 壁纸动态强调色。
 * 1. Android 12+ (API 31+): 优先提取系统 Monet token:
 *    - 深色模式使用 system_accent1_200 (兼顾柔和与暗底高可见度)
 *    - 浅色模式使用 system_accent1_600 (具备充足色彩饱和度与阅读对比度)
 * 2. Android 8.1+ (API 27+): 通过 WallpaperManager 提取主壁纸色并合成动态强调色。
 * 3. 降级回退至经典暖陶土强调色。
 */
fun resolveDynamicAccentColor(context: Context, isDark: Boolean): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val resId = if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        val c = runCatching { context.getColor(resId) }.getOrNull()
        if (c != null && c != 0) return c
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val wpColors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        val primary = wpColors?.primaryColor?.toArgb()
        if (primary != null && primary != 0) {
            val hsl = rgbToHsl(primary)
            return hslToRgb(hsl[0], hsl[1].coerceIn(0.40f, 0.70f), if (isDark) 0.75f else 0.48f)
        }
    }
    return if (isDark) 0xFFD97757.toInt() else 0xFFC96442.toInt()
}

internal fun resolveSettingsTheme(
    context: Context,
    isDark: Boolean,
    isDynamic: Boolean,
    customAccentColor: Int = 0,
): SettingsTheme {
    val dynamicAccent = resolveDynamicAccentColor(context, isDark)
    if (!isDynamic) {
        return resolveWarmSettingsTheme(isDark, customAccentColor, dynamicAccent)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = runCatching {
            val page = context.getColor(if (isDark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50)
            val surface = context.getColor(if (isDark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_10)
            val elevated = context.getColor(if (isDark) android.R.color.system_neutral1_700 else android.R.color.system_neutral1_0)
            val track = context.getColor(if (isDark) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_100)
            val trackStrong = context.getColor(if (isDark) android.R.color.system_neutral2_600 else android.R.color.system_neutral2_200)
            val text = context.getColor(if (isDark) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900)
            val secondaryText = context.getColor(if (isDark) android.R.color.system_neutral2_300 else android.R.color.system_neutral2_700)
            val tertiaryText = context.getColor(if (isDark) android.R.color.system_neutral2_400 else android.R.color.system_neutral2_500)
            val border = context.getColor(if (isDark) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_200)

            val accent = when {
                customAccentColor == COLOR_DYNAMIC -> dynamicAccent
                customAccentColor != 0 -> if ((customAccentColor ushr 24) == 0) (customAccentColor or 0xFF000000.toInt()) else customAccentColor
                else -> dynamicAccent
            }

            val onAccent = if (colorLuminance(accent) > 0.55f) 0xFF141413.toInt() else 0xFFFAF9F5.toInt()
            val (accentSoft, accentSoftStrong) = resolveUiAccentSoftColors(accent, isDark)
            val ripple = if (isDark) 0x1FFAF9F5 else 0x14141413
            val danger = if (isDark) 0xFFE5826F.toInt() else 0xFFB53333.toInt()

            SettingsTheme(
                pageColor = page,
                surfaceColor = surface,
                elevatedColor = elevated,
                trackColor = track,
                trackStrongColor = trackStrong,
                textColor = text,
                secondaryTextColor = secondaryText,
                tertiaryTextColor = tertiaryText,
                borderColor = border,
                accentColor = accent,
                onAccentColor = onAccent,
                accentSoftColor = accentSoft,
                accentSoftStrongColor = accentSoftStrong,
                rippleColor = ripple,
                dangerColor = danger,
            )
        }.getOrNull()
        if (dynamic != null) return dynamic
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val wpColors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        val primary = wpColors?.primaryColor?.toArgb()
        if (primary != null && primary != 0) {
            return synthesizeMaterialYouTheme(primary, isDark, customAccentColor, dynamicAccent)
        }
    }

    return resolveWarmSettingsTheme(isDark, customAccentColor, dynamicAccent)
}

internal fun synthesizeMaterialYouTheme(
    wallpaperPrimary: Int,
    isDark: Boolean,
    customAccentColor: Int,
    fallbackDynamicAccent: Int? = null,
): SettingsTheme {
    val hsl = rgbToHsl(wallpaperPrimary)
    val hue = hsl[0]

    val neutralSat = 0.08f
    val page = hslToRgb(hue, neutralSat, if (isDark) 0.11f else 0.95f)
    val surface = hslToRgb(hue, neutralSat, if (isDark) 0.15f else 0.98f)
    val elevated = hslToRgb(hue, neutralSat, if (isDark) 0.19f else 1.0f)
    val track = hslToRgb(hue, neutralSat * 1.5f, if (isDark) 0.22f else 0.92f)
    val trackStrong = hslToRgb(hue, neutralSat * 2f, if (isDark) 0.30f else 0.83f)
    val text = if (isDark) hslToRgb(hue, 0.04f, 0.96f) else hslToRgb(hue, 0.10f, 0.10f)
    val secondaryText = if (isDark) hslToRgb(hue, 0.08f, 0.72f) else hslToRgb(hue, 0.12f, 0.38f)
    val tertiaryText = if (isDark) hslToRgb(hue, 0.08f, 0.55f) else hslToRgb(hue, 0.10f, 0.52f)
    val border = hslToRgb(hue, neutralSat * 1.8f, if (isDark) 0.25f else 0.89f)

    val dynamicAccent = fallbackDynamicAccent
        ?: hslToRgb(hue, hsl[1].coerceIn(0.40f, 0.70f), if (isDark) 0.75f else 0.48f)
    val accent = when {
        customAccentColor == COLOR_DYNAMIC -> dynamicAccent
        customAccentColor != 0 -> if ((customAccentColor ushr 24) == 0) (customAccentColor or 0xFF000000.toInt()) else customAccentColor
        else -> dynamicAccent
    }

    val onAccent = if (colorLuminance(accent) > 0.55f) 0xFF141413.toInt() else 0xFFFAF9F5.toInt()
    val (accentSoft, accentSoftStrong) = resolveUiAccentSoftColors(accent, isDark)
    val ripple = if (isDark) 0x1FFAF9F5 else 0x14141413
    val danger = if (isDark) 0xFFE5826F.toInt() else 0xFFB53333.toInt()

    return SettingsTheme(
        pageColor = page,
        surfaceColor = surface,
        elevatedColor = elevated,
        trackColor = track,
        trackStrongColor = trackStrong,
        textColor = text,
        secondaryTextColor = secondaryText,
        tertiaryTextColor = tertiaryText,
        borderColor = border,
        accentColor = accent,
        onAccentColor = onAccent,
        accentSoftColor = accentSoft,
        accentSoftStrongColor = accentSoftStrong,
        rippleColor = ripple,
        dangerColor = danger,
    )
}

internal fun resolveWarmSettingsTheme(
    isDark: Boolean,
    customAccentColor: Int,
    dynamicAccentColor: Int? = null,
): SettingsTheme {
    val page = if (isDark) 0xFF1F1E1D.toInt() else 0xFFF5F4ED.toInt()
    val surface = if (isDark) 0xFF262624.toInt() else 0xFFFAF9F5.toInt()
    val elevated = if (isDark) 0xFF30302E.toInt() else 0xFFFFFFFF.toInt()
    val track = if (isDark) 0xFF30302E.toInt() else 0xFFF0EEE6.toInt()
    val trackStrong = if (isDark) 0xFF4D4C48.toInt() else 0xFFD1CFC5.toInt()
    val text = if (isDark) 0xFFFAF9F5.toInt() else 0xFF141413.toInt()
    val secondaryText = if (isDark) 0xFFB0AEA5.toInt() else 0xFF5E5D59.toInt()
    val tertiaryText = 0xFF87867F.toInt()
    val border = if (isDark) 0xFF3D3D3A.toInt() else 0xFFE8E6DC.toInt()
    val accent = when {
        customAccentColor == COLOR_DYNAMIC -> dynamicAccentColor ?: resolveUiAccentColor(COLOR_DYNAMIC, isDark)
        customAccentColor != 0 -> if ((customAccentColor ushr 24) == 0) (customAccentColor or 0xFF000000.toInt()) else customAccentColor
        else -> resolveUiAccentColor(0, isDark)
    }
    val onAccent = resolveOnAccentColor(accent)
    val (accentSoft, accentSoftStrong) = resolveUiAccentSoftColors(accent, isDark)
    val ripple = if (isDark) 0x1FFAF9F5 else 0x14141413
    val danger = if (isDark) 0xFFE5826F.toInt() else 0xFFB53333.toInt()

    return SettingsTheme(
        pageColor = page,
        surfaceColor = surface,
        elevatedColor = elevated,
        trackColor = track,
        trackStrongColor = trackStrong,
        textColor = text,
        secondaryTextColor = secondaryText,
        tertiaryTextColor = tertiaryText,
        borderColor = border,
        accentColor = accent,
        onAccentColor = onAccent,
        accentSoftColor = accentSoft,
        accentSoftStrongColor = accentSoftStrong,
        rippleColor = ripple,
        dangerColor = danger,
    )
}
