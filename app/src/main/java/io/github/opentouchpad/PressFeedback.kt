package io.github.opentouchpad

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.roundToInt

/**
 * 物理按压反馈：
 * 模拟真实触控板和机械按键的悬浮按压感。
 * - 按住左边：左侧下沉（沿 Y 轴产生 3D 透视倾斜）；
 * - 按住右边：右侧下沉；
 * - 按住上方/下方：上下对应倾斜下沉；
 * - 整体下压：伴随微幅深度位移与缩放，呈现立体凹陷质感；
 * - 抬起松手：带微回弹动效恢复平整，并触发触觉反馈。
 */
object PressFeedback {

    val decelerate = DecelerateInterpolator()
    val overshoot = OvershootInterpolator(1.25f)

    private var padDownTimeMs: Long = 0L

    /**
     * 为按钮等普通 View 挂载 3D 倾斜下沉按压反馈。
     * 返回 false 保持 View 原有的 onTouchEvent 继续触发点击（OnClickListener）和长按（OnLongClickListener）。
     */
    fun attach(
        view: View,
        maxTiltX: Float = 8f,
        maxTiltY: Float = 12f,
        pressScale: Float = 0.93f,
        sinkDp: Float = 2f,
        isEnabled: () -> Boolean = { true },
        onHaptic: (() -> Unit)? = null,
    ) {
        val density = view.resources.displayMetrics.density
        view.cameraDistance = 1400f * density
        view.isHapticFeedbackEnabled = false

        view.setOnTouchListener { v, event ->
            if (!isEnabled()) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> applyButtonDown(v, event, maxTiltX, maxTiltY, pressScale, sinkDp, onHaptic)
                MotionEvent.ACTION_MOVE -> applyButtonMove(v, event, maxTiltX, maxTiltY, pressScale, sinkDp)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> applyButtonRelease(v)
            }
            false
        }
    }

    fun applyButtonDown(
        v: View,
        event: MotionEvent,
        maxTiltX: Float = 8f,
        maxTiltY: Float = 12f,
        pressScale: Float = 0.93f,
        sinkDp: Float = 3f,
        onHaptic: (() -> Unit)? = null,
    ) {
        val density = v.resources.displayMetrics.density
        v.cameraDistance = 1400f * density
        v.isHapticFeedbackEnabled = false
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        val normX = ((event.x - w / 2f) / (w / 2f)).coerceIn(-1.5f, 1.5f)
        val normY = ((event.y - h / 2f) / (h / 2f)).coerceIn(-1.5f, 1.5f)
        val sinkPx = (sinkDp * density).roundToInt().toFloat()
        val clampedNormX = normX.coerceIn(-1f, 1f)
        val clampedNormY = normY.coerceIn(-1f, 1f)
        v.animate().cancel()
        v.animate()
            .rotationX(-clampedNormY * maxTiltX)
            .rotationY(clampedNormX * maxTiltY)
            .scaleX(pressScale)
            .scaleY(pressScale)
            .translationY(sinkPx)
            .setDuration(70)
            .setInterpolator(decelerate)
            .start()
        onHaptic?.invoke()
    }

    fun applyButtonMove(
        v: View,
        event: MotionEvent,
        maxTiltX: Float = 8f,
        maxTiltY: Float = 12f,
        pressScale: Float = 0.93f,
        sinkDp: Float = 3f,
    ) {
        val density = v.resources.displayMetrics.density
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        val normX = ((event.x - w / 2f) / (w / 2f)).coerceIn(-1.5f, 1.5f)
        val normY = ((event.y - h / 2f) / (h / 2f)).coerceIn(-1.5f, 1.5f)
        val sinkPx = (sinkDp * density).roundToInt().toFloat()
        val inside = event.x in -w * 0.25f..w * 1.25f && event.y in -h * 0.25f..h * 1.25f
        if (inside) {
            val clampedNormX = normX.coerceIn(-1f, 1f)
            val clampedNormY = normY.coerceIn(-1f, 1f)
            v.animate()
                .rotationX(-clampedNormY * maxTiltX)
                .rotationY(clampedNormX * maxTiltY)
                .scaleX(pressScale)
                .scaleY(pressScale)
                .translationY(sinkPx)
                .setDuration(35)
                .start()
        } else {
            // 手指拖移出按钮较远区域，平滑复原
            v.animate().cancel()
            v.animate()
                .rotationX(0f)
                .rotationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(160)
                .setInterpolator(decelerate)
                .start()
        }
    }

    fun applyButtonRelease(v: View) {
        v.animate().cancel()
        v.animate()
            .rotationX(0f)
            .rotationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(overshoot)
            .start()
    }

    /**
     * 触控板按下反馈：
     * 基于归一化按压坐标 [normX], [normY]（-1f..1f），下沉并沿落点朝向微幅 3D 倾斜。
     */
    fun applyPadDown(
        pad: View,
        normX: Float,
        normY: Float,
        density: Float,
        maxTiltX: Float = 7.0f,
        maxTiltY: Float = 9.0f,
        pressScale: Float = 0.985f,
        sinkDp: Float = 4.5f,
    ) {
        padDownTimeMs = SystemClock.uptimeMillis()
        val clampedNormX = normX.coerceIn(-1f, 1f)
        val clampedNormY = normY.coerceIn(-1f, 1f)
        val sinkPx = (sinkDp * density).roundToInt().toFloat()

        (pad as? TouchpadSurfaceView)?.animateTilt(clampedNormX, clampedNormY, 1f, 65, decelerate)

        pad.animate().cancel()
        pad.animate()
            .rotationX(-clampedNormY * maxTiltX)
            .rotationY(clampedNormX * maxTiltY)
            .scaleX(pressScale)
            .scaleY(pressScale)
            .translationY(sinkPx)
            .setDuration(65)
            .setInterpolator(decelerate)
            .start()
    }

    /**
     * 触控板手指移动：
     * 保持下沉状态，动态更新 3D 倾斜角度跟随手指位置。
     * 初始 65ms 下沉窗口过后直接以屏幕物理刷新率 1:1 跟随手指，无动画打断与延迟滞后。
     */
    fun applyPadMove(
        pad: View,
        normX: Float,
        normY: Float,
        density: Float,
        maxTiltX: Float = 7.0f,
        maxTiltY: Float = 9.0f,
        pressScale: Float = 0.985f,
        sinkDp: Float = 4.5f,
    ) {
        val clampedNormX = normX.coerceIn(-1f, 1f)
        val clampedNormY = normY.coerceIn(-1f, 1f)
        val sinkPx = (sinkDp * density).roundToInt().toFloat()

        val elapsed = SystemClock.uptimeMillis() - padDownTimeMs
        if (elapsed >= 65L) {
            pad.animate().cancel()
            pad.scaleX = pressScale
            pad.scaleY = pressScale
            pad.translationY = sinkPx
            pad.rotationX = -clampedNormY * maxTiltX
            pad.rotationY = clampedNormX * maxTiltY
            (pad as? TouchpadSurfaceView)?.setTilt(clampedNormX, clampedNormY, 1f)
        } else {
            val remainingMs = (65L - elapsed).coerceAtLeast(10L)
            pad.animate()
                .rotationX(-clampedNormY * maxTiltX)
                .rotationY(clampedNormX * maxTiltY)
                .scaleX(pressScale)
                .scaleY(pressScale)
                .translationY(sinkPx)
                .setDuration(remainingMs)
                .setInterpolator(decelerate)
                .start()
            (pad as? TouchpadSurfaceView)?.setTilt(clampedNormX, clampedNormY, 1f)
        }
    }

    /**
     * 触控板松开：微弹回弹恢复平整。
     */
    fun applyPadRelease(pad: View) {
        padDownTimeMs = 0L
        (pad as? TouchpadSurfaceView)?.animateTilt(0f, 0f, 0f, 200, overshoot)

        pad.animate().cancel()
        pad.animate()
            .rotationX(0f)
            .rotationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(overshoot)
            .start()
    }
}
