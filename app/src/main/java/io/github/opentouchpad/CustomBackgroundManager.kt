package io.github.opentouchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义背景图片管理：支持触控板面板与主设置界面选取本地图片作为背景。
 * - 纯本地离线处理，零网络请求，零危险权限；
 * - 自动进行安全下采样（Downsampling），防止大图导致 OOM；
 * - 持久化存储在应用私有文件目录。
 */
object CustomBackgroundManager {
    private const val TOUCHPAD_BG_FILENAME = "touchpad_custom_bg.png"
    private const val SETTINGS_BG_FILENAME = "settings_custom_bg.png"

    fun getTouchpadBgFile(context: Context): File =
        File(context.filesDir, TOUCHPAD_BG_FILENAME)

    fun getSettingsBgFile(context: Context): File =
        File(context.filesDir, SETTINGS_BG_FILENAME)

    fun hasTouchpadBg(context: Context): Boolean =
        getTouchpadBgFile(context).let { it.exists() && it.length() > 0 }

    fun hasSettingsBg(context: Context): Boolean =
        getSettingsBgFile(context).let { it.exists() && it.length() > 0 }

    /**
     * 将从系统图片选择器获取的 Uri 解析、安全下采样后保存到目标私有文件。
     */
    fun saveBackgroundFromUri(context: Context, uri: Uri, targetFile: File, maxDim: Int = 1920): Boolean {
        return runCatching {
            val cr = context.contentResolver
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) } ?: return false

            val srcW = boundsOptions.outWidth
            val srcH = boundsOptions.outHeight
            if (srcW <= 0 || srcH <= 0) return false

            var inSample = 1
            val maxSrc = maxOf(srcW, srcH)
            while (maxSrc / (inSample * 2) >= maxDim) {
                inSample *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return false

            val tmpFile = File(context.filesDir, "${targetFile.name}.tmp")
            FileOutputStream(tmpFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            bitmap.recycle()

            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 从私有文件加载背景 Bitmap，自动进行采样以控制显存。
     */
    fun loadBackgroundBitmap(targetFile: File, maxDim: Int = 1920): Bitmap? {
        if (!targetFile.exists() || targetFile.length() == 0L) return null
        return runCatching {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(targetFile.absolutePath, boundsOptions)
            val maxSrc = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            var inSample = 1
            while (maxSrc / (inSample * 2) >= maxDim) {
                inSample *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(targetFile.absolutePath, decodeOptions)
        }.getOrNull()
    }

    /**
     * 清除自定义背景图片文件。
     */
    fun clearBackground(targetFile: File): Boolean {
        return runCatching {
            if (targetFile.exists()) targetFile.delete() else true
        }.getOrDefault(false)
    }
}
