package io.github.opentouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.widget.FrameLayout

/**
 * 支持整板方向形变的悬浮触控板根容器：
 * 拖动触控板移动时，通过硬件加速 Canvas 3×3 齐次投影矩阵将触控板本体与四周所有按键
 * 作为一个整体施加跟随移动方向的果冻拉伸、惯性摆角与 3D 透视倾斜，静止时零开销直通绘制。
 */
internal class DeformablePanelLayout(context: Context) : FrameLayout(context) {

    private val deformMatrix = Matrix()
    private var deformActive = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    /** 应用由 [computePanelDragMatrix] 产出的 3×3 矩阵；非激活时自动复原恒等绘制。 */
    fun setDeformationMatrix(values: FloatArray, active: Boolean) {
        if (!active) {
            clearDeformation()
            return
        }
        deformMatrix.setValues(values)
        deformActive = true
        invalidate()
    }

    /** 立即清除形变并恢复 1:1 原始绘制。 */
    fun clearDeformation() {
        if (!deformActive) return
        deformActive = false
        deformMatrix.reset()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (deformActive) {
            val saveCount = canvas.save()
            canvas.concat(deformMatrix)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
