package io.github.opentouchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义背景图片管理：支持触控板面板与主设置界面选取本地图片作为背景。
 * - 纯本地离线处理，零网络请求，零危险权限；
 * - 优先采用 Android 9+ (API 28+) ImageDecoder，原生支持 HEIC/HEIF/WebP/PNG/JPEG 及自动 EXIF 矫正；
 * - 兼容低版本与单次流读取降级方案，杜绝 ContentProvider 二次开流异常；
 * - 自动进行安全下采样（Downsampling），防止大图导致 OOM；
 * - 持久化存储在应用私有文件目录。
 */
object CustomBackgroundManager {
    private const val TAG = "CustomBgManager"
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
        var decodedBitmap: Bitmap? = null
        try {
            val cr = context.contentResolver

            // 1. Android 9+ (API 28+) 优先使用系统原生 ImageDecoder：
            // - 原生支持 HEIC / HEIF、WebP、PNG、JPEG、AVIF 等全格式容器；
            // - 原生自动根据 EXIF 元数据矫正竖拍/横拍旋转角度；
            // - 单次流解析，直接在解码阶段按硬件比例安全缩放，杜绝 OOM；
            // - 规避某些机型 ContentProvider 临时 Uri 无法重复 openInputStream 的问题。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = ImageDecoder.createSource(cr, uri)
                    decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val srcW = info.size.width
                        val srcH = info.size.height
                        val maxSrc = maxOf(srcW, srcH)
                        if (maxSrc > maxDim && maxSrc > 0) {
                            val scale = maxDim.toFloat() / maxSrc
                            val targetW = (srcW * scale).toInt().coerceAtLeast(1)
                            val targetH = (srcH * scale).toInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetW, targetH)
                        }
                        // 强制指定为软件位图，否则压缩为 PNG 时会报错（硬件位图不支持 compress）
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "ImageDecoder failed, falling back to cached stream decode: ${e.message}")
                    decodedBitmap = null
                }
            }

            // 2. 降级方案（API < 28 或 ImageDecoder 解码失败时）：
            // 先将输入流单次拷贝到应用内部沙箱临时缓存文件中，再通过本地文件路径安全探测尺寸和解码，
            // 彻底解决 ContentProvider 二次 openInputStream 导致流中断或权限丢失的问题。
            if (decodedBitmap == null) {
                val tempCacheFile = File(context.cacheDir, "bg_decode_temp_${System.currentTimeMillis()}")
                try {
                    cr.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempCacheFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: return false

                    if (!tempCacheFile.exists() || tempCacheFile.length() == 0L) return false

                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tempCacheFile.absolutePath, boundsOptions)
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
                    decodedBitmap = BitmapFactory.decodeFile(tempCacheFile.absolutePath, decodeOptions)
                } finally {
                    tempCacheFile.delete()
                }
            }

            val bmp = decodedBitmap ?: return false

            val tmpFile = File(context.filesDir, "${targetFile.name}.tmp")
            FileOutputStream(tmpFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            bmp.recycle()

            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save background from uri: $uri", e)
            decodedBitmap?.recycle()
            return false
        }
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
