package io.github.opentouchpad

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.drawable.RippleDrawable
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/**
 * 物理按压反馈（基于 Choreographer 的连续二阶质量-弹簧-阻尼驱动）：
 * - 按下时施加柔和连续的向下初速度动量脉冲（位置严禁瞬移），即使 30–50ms 快速轻点抬手，
 *   已有动量也会自然推着面板/按键走完一段圆润软垫下压弧线，再以近临界阻尼丝滑回弹归零；
 * - 按住拖动时平滑更新目标倾斜角，全程连续跟手，零动画取消/重启/65ms 跳变；
 * - 每个受控 View 持有独立弹簧状态，收敛静止或离窗时自动停帧，保持 0 后台唤醒。
 */
object PressFeedback {

    private class ViewSpringController(private val view: View) : View.OnAttachStateChangeListener {
        val spring = MutablePressSpringState()
        var targetPress: Float = 0f
        var targetTiltX: Float = 0f
        var targetTiltY: Float = 0f
        var maxTiltX: Float = 5.5f
        var maxTiltY: Float = 7.5f
        var pressScale: Float = 0.93f
        var sinkDp: Float = 1.8f
        var density: Float = view.resources.displayMetrics.density
        var isPad: Boolean = false
        var buttonLastInside: Boolean = true
        var frameLoopActive: Boolean = false
        var lastFrameNanos: Long = 0L

        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!frameLoopActive) return
                if (!canAnimate()) {
                    cancelAndReset()
                    return
                }
                val dtSec = if (lastFrameNanos == 0L) {
                    1f / 60f
                } else {
                    ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.040f)
                }
                lastFrameNanos = frameTimeNanos

                stepPressSpringInPlace(
                    state = spring,
                    targetPress = targetPress,
                    targetTiltX = targetTiltX,
                    targetTiltY = targetTiltY,
                    dtSec = dtSec,
                )
                applyVisuals()

                if (spring.isSettled(targetPress, targetTiltX, targetTiltY)) {
                    spring.snapTo(targetPress, targetTiltX, targetTiltY)
                    applyVisuals()
                    frameLoopActive = false
                    lastFrameNanos = 0L
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        init {
            view.addOnAttachStateChangeListener(this)
        }

        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) {
            cancelAndReset()
        }

        fun canAnimate(): Boolean =
            ValueAnimator.areAnimatorsEnabled() && view.isAttachedToWindow

        fun configure(
            isPad: Boolean,
            maxTiltX: Float,
            maxTiltY: Float,
            pressScale: Float,
            sinkDp: Float,
            densityOverride: Float = view.resources.displayMetrics.density,
        ) {
            this.isPad = isPad
            this.maxTiltX = maxTiltX
            this.maxTiltY = maxTiltY
            this.pressScale = pressScale
            this.sinkDp = sinkDp
            this.density = densityOverride
            val targetCameraDist = 1400f * densityOverride
            if (view.cameraDistance != targetCameraDist) {
                view.cameraDistance = targetCameraDist
            }
            if (view.isHapticFeedbackEnabled) {
                view.isHapticFeedbackEnabled = false
            }
        }

        fun ensureRunning() {
            if (!canAnimate()) {
                cancelAndReset()
                return
            }
            if (!frameLoopActive) {
                frameLoopActive = true
                lastFrameNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        fun ensureRunningIfNeeded() {
            if (!canAnimate()) {
                cancelAndReset()
                return
            }
            if (!frameLoopActive && !spring.isSettled(targetPress, targetTiltX, targetTiltY)) {
                frameLoopActive = true
                lastFrameNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        fun applyVisuals() {
            val p = spring.press.coerceIn(-0.04f, 1.08f)
            val s = 1f + (pressScale - 1f) * p
            val sinkPx = sinkDp * density * p
            if (view.scaleX != s) view.scaleX = s
            if (view.scaleY != s) view.scaleY = s
            if (view.translationY != sinkPx) view.translationY = sinkPx
            val rx = -spring.tiltY * maxTiltX
            val ry = spring.tiltX * maxTiltY
            if (kotlin.math.abs(view.rotationX - rx) > 0.01f || rx == 0f) view.rotationX = rx
            if (kotlin.math.abs(view.rotationY - ry) > 0.01f || ry == 0f) view.rotationY = ry
            if (isPad) {
                (view as? TouchpadSurfaceView)?.setTilt(
                    spring.tiltX,
                    spring.tiltY,
                    spring.press.coerceIn(0f, 1f),
                )
            } else {
                updateBubbleTilt(view, spring.tiltX, spring.tiltY)
            }
        }

        fun cancelAndReset() {
            if (frameLoopActive) {
                frameLoopActive = false
                runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            }
            lastFrameNanos = 0L
            buttonLastInside = true
            targetPress = 0f
            targetTiltX = 0f
            targetTiltY = 0f
            val hadTransform = !spring.isIdentity ||
                view.scaleX != 1f ||
                view.scaleY != 1f ||
                view.translationY != 0f ||
                view.rotationX != 0f ||
                view.rotationY != 0f
            spring.reset()
            if (hadTransform) {
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationY = 0f
                view.rotationX = 0f
                view.rotationY = 0f
                if (isPad) {
                    (view as? TouchpadSurfaceView)?.setTilt(0f, 0f, 0f)
                } else {
                    updateBubbleTilt(view, 0f, 0f)
                }
            }
        }
    }

    private fun getOrCreateController(view: View): ViewSpringController {
        val existing = view.getTag(R.id.tag_press_spring) as? ViewSpringController
        if (existing != null) return existing
        return ViewSpringController(view).also {
            view.setTag(R.id.tag_press_spring, it)
        }
    }

    private fun updateBubbleTilt(view: View, normX: Float, normY: Float) {
        when (val bg = view.background) {
            is BubbleDrawable -> bg.setInteractiveTilt(normX, normY)
            is RippleDrawable -> {
                for (i in 0 until bg.numberOfLayers) {
                    (bg.getDrawable(i) as? BubbleDrawable)?.setInteractiveTilt(normX, normY)
                }
            }
        }
    }

    fun cancel(view: View) {
        val controller = view.getTag(R.id.tag_press_spring) as? ViewSpringController
        if (controller != null) {
            controller.cancelAndReset()
            return
        }
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        view.rotationX = 0f
        view.rotationY = 0f
        (view as? TouchpadSurfaceView)?.setTilt(0f, 0f, 0f)
        updateBubbleTilt(view, 0f, 0f)
    }

    /**
     * 为按钮等普通 View 挂载 3D 倾斜下沉按压反馈。
     * 返回 false 保持 View 原有的 onTouchEvent 继续触发点击（OnClickListener/performClick）和长按（OnLongClickListener）。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        view: View,
        maxTiltX: Float = 5.5f,
        maxTiltY: Float = 7.5f,
        pressScale: Float = 0.93f,
        sinkDp: Float = 1.8f,
        isEnabled: () -> Boolean = { true },
        onHaptic: (() -> Unit)? = null,
    ) {
        val density = view.resources.displayMetrics.density
        view.cameraDistance = 1400f * density
        view.isHapticFeedbackEnabled = false

        view.setOnTouchListener { v, event ->
            if (!isEnabled()) {
                if (v.getTag(R.id.tag_press_spring) != null) {
                    cancel(v)
                }
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
        maxTiltX: Float = 5.5f,
        maxTiltY: Float = 7.5f,
        pressScale: Float = 0.93f,
        sinkDp: Float = 1.8f,
        onHaptic: (() -> Unit)? = null,
    ) {
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        val clampedNormX = ((event.x - w / 2f) / (w / 2f)).coerceIn(-1f, 1f)
        val clampedNormY = ((event.y - h / 2f) / (h / 2f)).coerceIn(-1f, 1f)
        val controller = getOrCreateController(v)
        controller.configure(
            isPad = false,
            maxTiltX = maxTiltX,
            maxTiltY = maxTiltY,
            pressScale = pressScale,
            sinkDp = sinkDp,
        )
        controller.buttonLastInside = true
        controller.targetPress = 1f
        controller.targetTiltX = clampedNormX
        controller.targetTiltY = clampedNormY
        controller.spring.applyDownImpulse()
        controller.ensureRunning()
        onHaptic?.invoke()
    }

    fun applyButtonMove(
        v: View,
        event: MotionEvent,
        maxTiltX: Float = 5.5f,
        maxTiltY: Float = 7.5f,
        pressScale: Float = 0.93f,
        sinkDp: Float = 1.8f,
    ) {
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        val inside = event.x in -w * 0.25f..w * 1.25f && event.y in -h * 0.25f..h * 1.25f
        val controller = getOrCreateController(v)
        controller.configure(
            isPad = false,
            maxTiltX = maxTiltX,
            maxTiltY = maxTiltY,
            pressScale = pressScale,
            sinkDp = sinkDp,
        )
        if (inside) {
            val clampedNormX = ((event.x - w / 2f) / (w / 2f)).coerceIn(-1f, 1f)
            val clampedNormY = ((event.y - h / 2f) / (h / 2f)).coerceIn(-1f, 1f)
            if (!controller.buttonLastInside) {
                controller.buttonLastInside = true
                controller.spring.applyDownImpulse()
            }
            controller.targetPress = 1f
            controller.targetTiltX = clampedNormX
            controller.targetTiltY = clampedNormY
            controller.ensureRunningIfNeeded()
        } else if (controller.buttonLastInside) {
            controller.buttonLastInside = false
            controller.targetPress = 0f
            controller.targetTiltX = 0f
            controller.targetTiltY = 0f
            controller.ensureRunningIfNeeded()
        }
    }

    fun applyButtonRelease(v: View) {
        val controller = v.getTag(R.id.tag_press_spring) as? ViewSpringController ?: return
        controller.buttonLastInside = true
        controller.targetPress = 0f
        controller.targetTiltX = 0f
        controller.targetTiltY = 0f
        controller.ensureRunningIfNeeded()
    }

    /**
     * 触控板按下反馈：
     * 基于归一化按压坐标 [normX], [normY]（-1f..1f），赋予柔和下压初速度动量并平滑倾斜。
     */
    fun applyPadDown(
        pad: View,
        normX: Float,
        normY: Float,
        density: Float,
        maxTiltX: Float = 4.0f,
        maxTiltY: Float = 5.0f,
        pressScale: Float = 0.985f,
        sinkDp: Float = 2.2f,
    ) {
        val clampedNormX = normX.coerceIn(-1f, 1f)
        val clampedNormY = normY.coerceIn(-1f, 1f)
        val controller = getOrCreateController(pad)
        controller.configure(
            isPad = true,
            maxTiltX = maxTiltX,
            maxTiltY = maxTiltY,
            pressScale = pressScale,
            sinkDp = sinkDp,
            densityOverride = density,
        )
        controller.targetPress = 1f
        controller.targetTiltX = clampedNormX
        controller.targetTiltY = clampedNormY
        controller.spring.applyDownImpulse()
        controller.ensureRunning()
    }

    /**
     * 触控板手指移动：
     * 平滑更新目标倾斜角，连续弹簧跟手，无任何动画取消或跳变。
     */
    fun applyPadMove(
        pad: View,
        normX: Float,
        normY: Float,
        density: Float,
        maxTiltX: Float = 4.0f,
        maxTiltY: Float = 5.0f,
        pressScale: Float = 0.985f,
        sinkDp: Float = 2.2f,
    ) {
        val clampedNormX = normX.coerceIn(-1f, 1f)
        val clampedNormY = normY.coerceIn(-1f, 1f)
        val controller = getOrCreateController(pad)
        controller.configure(
            isPad = true,
            maxTiltX = maxTiltX,
            maxTiltY = maxTiltY,
            pressScale = pressScale,
            sinkDp = sinkDp,
            densityOverride = density,
        )
        controller.targetPress = 1f
        controller.targetTiltX = clampedNormX
        controller.targetTiltY = clampedNormY
        controller.ensureRunningIfNeeded()
    }

    /**
     * 触控板松开：近临界阻尼丝滑回弹恢复平整。
     */
    fun applyPadRelease(pad: View) {
        val controller = pad.getTag(R.id.tag_press_spring) as? ViewSpringController ?: return
        controller.targetPress = 0f
        controller.targetTiltX = 0f
        controller.targetTiltY = 0f
        controller.ensureRunningIfNeeded()
    }
}
