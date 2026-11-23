package io.github.opentouchpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义背景图片管理：支持触控板面板与主设置界面选取本地图片作为背景。
 * - 纯本地离线处理，零网络请求，零危险权限；
 * - 优先采用 Android 9+ (API 28+) ImageDecoder，原生支持 HEIC/HEIF/WebP/PNG/JPEG 及自动 EXIF 矫正；
 * - 兼容低版本与单次流读取降级方案（含系统原生 ExifInterface 旋转矫正），杜绝 ContentProvider 二次开流异常；
 * - 自动进行安全下采样（Downsampling），防止大图导致 OOM；
 * - 采用高效率 WebP（质量 90）持久化存储在应用私有文件目录，同时无缝兼容读取旧版 PNG 文件。
 */
object CustomBackgroundManager {
    private const val TAG = "CustomBgManager"
    private const val TOUCHPAD_BG_WEBP = "touchpad_custom_bg.webp"
    private const val SETTINGS_BG_WEBP = "settings_custom_bg.webp"
    private const val TOUCHPAD_BG_LEGACY_PNG = "touchpad_custom_bg.png"
    private const val SETTINGS_BG_LEGACY_PNG = "settings_custom_bg.png"

    private fun resolveExistingOrDefault(dir: File, primary: String, legacy: String): File {
        val webp = File(dir, primary)
        if (webp.exists() && webp.length() > 0L) return webp
        val png = File(dir, legacy)
        if (png.exists() && png.length() > 0L) return png
        return webp
    }

    fun getTouchpadBgFile(context: Context): File =
        resolveExistingOrDefault(context.filesDir, TOUCHPAD_BG_WEBP, TOUCHPAD_BG_LEGACY_PNG)

    fun getSettingsBgFile(context: Context): File =
        resolveExistingOrDefault(context.filesDir, SETTINGS_BG_WEBP, SETTINGS_BG_LEGACY_PNG)

    fun getTouchpadBgSaveFile(context: Context): File =
        File(context.filesDir, TOUCHPAD_BG_WEBP)

    fun getSettingsBgSaveFile(context: Context): File =
        File(context.filesDir, SETTINGS_BG_WEBP)

    fun hasTouchpadBg(context: Context): Boolean =
        getTouchpadBgFile(context).let { it.exists() && it.length() > 0 }

    fun hasSettingsBg(context: Context): Boolean =
        getSettingsBgFile(context).let { it.exists() && it.length() > 0 }

    fun clearTouchpadBg(context: Context): Boolean {
        val a = clearBackground(File(context.filesDir, TOUCHPAD_BG_WEBP))
        val b = clearBackground(File(context.filesDir, TOUCHPAD_BG_LEGACY_PNG))
        return a && b
    }

    fun clearSettingsBg(context: Context): Boolean {
        val a = clearBackground(File(context.filesDir, SETTINGS_BG_WEBP))
        val b = clearBackground(File(context.filesDir, SETTINGS_BG_LEGACY_PNG))
        return a && b
    }

    /**
     * 将从系统图片选择器获取的 Uri 解析、安全下采样后以高效 WebP 保存到目标私有文件。
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
                        // 强制指定为软件位图，否则压缩时硬件位图不支持 compress
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "ImageDecoder failed, falling back to cached stream decode: ${e.message}")
                    decodedBitmap = null
                }
            }

            // 2. 降级方案（API < 28 或 ImageDecoder 解码失败时）：
            // 先将输入流单次拷贝到应用内部沙箱临时缓存文件中，再通过本地文件路径安全探测尺寸、解码并应用 EXIF 旋转。
            if (decodedBitmap == null) {
                val tempCacheFile = File.createTempFile("bg_decode_", ".tmp", context.cacheDir)
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
                    val rawBmp = BitmapFactory.decodeFile(tempCacheFile.absolutePath, decodeOptions)
                    decodedBitmap = if (rawBmp != null) {
                        val oriented = applyExifOrientationIfNeeded(rawBmp, tempCacheFile.absolutePath)
                        downscaleIfExceedsMaxDim(oriented, maxDim)
                    } else {
                        null
                    }
                } finally {
                    tempCacheFile.delete()
                }
            }

            val bmp = decodedBitmap ?: return false
            val parentDir = targetFile.parentFile ?: context.filesDir
            val tmpFile = File.createTempFile("bg_save_", ".tmp", parentDir)
            try {
                val compressed = try {
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    val webpOk = FileOutputStream(tmpFile, false).use { fos ->
                        bmp.compress(format, 90, fos)
                    }
                    if (webpOk) {
                        true
                    } else {
                        // WebP 失败时必须截断重写，防止与先前半截字节拼接成损坏文件
                        FileOutputStream(tmpFile, false).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
                        }
                    }
                } finally {
                    bmp.recycle()
                    decodedBitmap = null
                }
                if (!compressed || !tmpFile.exists() || tmpFile.length() == 0L) {
                    return false
                }

                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                // 写入新版 WebP 成功后，顺带清理同名旧版 PNG，避免旧文件残留
                if (targetFile.name == TOUCHPAD_BG_WEBP) {
                    clearBackground(File(parentDir, TOUCHPAD_BG_LEGACY_PNG))
                } else if (targetFile.name == SETTINGS_BG_WEBP) {
                    clearBackground(File(parentDir, SETTINGS_BG_LEGACY_PNG))
                }
                return true
            } finally {
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save background from uri: $uri", e)
            decodedBitmap?.recycle()
            return false
        }
    }

    private fun downscaleIfExceedsMaxDim(bitmap: Bitmap, maxDim: Int): Bitmap {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val maxSrc = maxOf(srcW, srcH)
        if (maxSrc <= maxDim || maxDim <= 0) return bitmap
        val scale = maxDim.toFloat() / maxSrc
        val targetW = (srcW * scale).toInt().coerceAtLeast(1)
        val targetH = (srcH * scale).toInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }.getOrNull() ?: return bitmap
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    @SuppressLint("ExifInterface")
    private fun applyExifOrientationIfNeeded(bitmap: Bitmap, filePath: String): Bitmap {
        val degrees = runCatching {
            val exif = android.media.ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
            resolveExifRotationDegrees(orientation)
        }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    internal data class DecodedBackground(
        val bitmap: Bitmap,
        val reachedCeiling: Boolean,
    )

    /**
     * 从私有文件加载背景 Bitmap，自动进行精确下采样以控制内存与显存（无缝支持 WebP 与旧版 PNG）。
     * - API 28+ 优先采用 ImageDecoder.setTargetSize 精确按需缩放（保持 ALLOCATOR_SOFTWARE 与无损 ARGB_8888）；
     * - 失败或低版本自动降级到 BitmapFactory 下采样。
     */
    fun loadBackgroundBitmap(targetFile: File, maxDim: Int = 1920): Bitmap? {
        if (!targetFile.exists() || targetFile.length() == 0L) return null
        val safeMaxDim = maxDim.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = ImageDecoder.createSource(targetFile)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val maxSrc = maxOf(srcW, srcH)
                    if (maxSrc > safeMaxDim && srcW > 0 && srcH > 0) {
                        val scale = safeMaxDim.toFloat() / maxSrc
                        val targetW = (srcW * scale).toInt().coerceIn(1, srcW)
                        val targetH = (srcH * scale).toInt().coerceIn(1, srcH)
                        decoder.setTargetSize(targetW, targetH)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return runCatching {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(targetFile.absolutePath, boundsOptions)
            val maxSrc = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            var inSample = 1
            while (maxSrc / (inSample * 2) >= safeMaxDim) {
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
     * 按 Center-Crop 最小覆盖尺寸加载触控板背景，既大幅削减默认小尺寸触控板的位图内存，
     * 又确保竖屏长图/超宽图在任意焦点裁切下保持 >= 1:1 清晰度不发糊。
     */
    internal fun loadBackgroundBitmapForCrop(
        targetFile: File,
        targetW: Int,
        targetH: Int,
        maxLongEdge: Int = 1200,
    ): DecodedBackground? {
        if (!targetFile.exists() || targetFile.length() == 0L) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var reachedCeiling = true
            val decoded = runCatching {
                val source = ImageDecoder.createSource(targetFile)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val plan = computeCenterCropDecodeSize(srcW, srcH, targetW, targetH, maxLongEdge)
                    reachedCeiling = plan.reachedCeiling
                    if (plan.targetWidth in 1 until srcW || plan.targetHeight in 1 until srcH) {
                        decoder.setTargetSize(plan.targetWidth, plan.targetHeight)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
                }
            }.getOrNull()
            if (decoded != null) {
                return DecodedBackground(decoded, reachedCeiling)
            }
        }
        return runCatching {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(targetFile.absolutePath, boundsOptions)
            val srcW = boundsOptions.outWidth
            val srcH = boundsOptions.outHeight
            if (srcW <= 0 || srcH <= 0) return@runCatching null
            val plan = computeCenterCropDecodeSize(srcW, srcH, targetW, targetH, maxLongEdge)
            var inSample = 1
            while (srcW / (inSample * 2) >= plan.targetWidth && srcH / (inSample * 2) >= plan.targetHeight) {
                inSample *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(targetFile.absolutePath, decodeOptions) ?: return@runCatching null
            val bmp = if (raw.width > plan.targetWidth * 1.35f || raw.height > plan.targetHeight * 1.35f) {
                val scaled = runCatching {
                    Bitmap.createScaledBitmap(raw, plan.targetWidth, plan.targetHeight, true)
                }.getOrNull()
                if (scaled != null) {
                    if (scaled !== raw) raw.recycle()
                    scaled
                } else {
                    raw
                }
            } else {
                raw
            }
            DecodedBackground(bmp, plan.reachedCeiling)
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
