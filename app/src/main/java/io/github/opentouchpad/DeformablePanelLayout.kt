package io.github.opentouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 支持整板方向形变的悬浮触控板根容器：
 * 拖动触控板移动时，通过硬件加速 Canvas 3×3 齐次投影矩阵将触控板本体与四周所有按键
 * 作为一个整体施加跟随移动方向的果冻拉伸、惯性摆角与 3D 透视倾斜；
 * 形变期间自动为静态按键子视图启用临时硬件纹理层以减少每帧矢量/贝塞尔路径重光栅化，
 * 静止时自动释放图层并零开销直通绘制。
 */
internal class DeformablePanelLayout(context: Context) : FrameLayout(context) {

    private val deformMatrix = Matrix()
    private val appliedValues = FloatArray(9)
    private var deformActive = false
    private var hardwareLayersApplied = false

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** 应用由 [computePanelDragMatrix] 产出的 3×3 矩阵；非激活时自动复原恒等绘制。 */
    fun setDeformationMatrix(values: FloatArray, active: Boolean, activeHandle: View? = null) {
        if (!active) {
            clearDeformation()
            return
        }
        if (deformActive && isNearlyEqual(appliedValues, values)) {
            return
        }
        System.arraycopy(values, 0, appliedValues, 0, 9)
        deformMatrix.setValues(values)
        if (!deformActive) {
            deformActive = true
            enableStaticChildHardwareLayers(activeHandle)
        }
        invalidate()
    }

    /** 立即清除形变并恢复 1:1 原始绘制，同时释放临时硬件层显存。 */
    fun clearDeformation() {
        if (!deformActive && !hardwareLayersApplied) return
        deformActive = false
        deformMatrix.reset()
        releaseChildHardwareLayers()
        invalidate()
    }

    private fun enableStaticChildHardwareLayers(activeHandle: View?) {
        if (hardwareLayersApplied) return
        hardwareLayersApplied = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // 触控板本体和正在更新高光的移动把手不强制缓存，其余静态按键缓存为硬件纹理
            if (child is TouchpadSurfaceView || child === activeHandle) continue
            if (child.layerType != LAYER_TYPE_HARDWARE) {
                child.setLayerType(LAYER_TYPE_HARDWARE, null)
            }
        }
    }

    private fun releaseChildHardwareLayers() {
        if (!hardwareLayersApplied) return
        hardwareLayersApplied = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.layerType == LAYER_TYPE_HARDWARE) {
                child.setLayerType(LAYER_TYPE_NONE, null)
            }
        }
    }

    override fun onDetachedFromWindow() {
        releaseChildHardwareLayers()
        super.onDetachedFromWindow()
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

    private fun isNearlyEqual(a: FloatArray, b: FloatArray): Boolean {
        for (i in 0 until 9) {
            if (abs(a[i] - b[i]) > 1e-4f) return false
        }
        return true
    }
}
