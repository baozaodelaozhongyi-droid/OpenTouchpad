# OpenTouchpad v0.5.93

### 全面提升 Android 8–16 兼容性并大幅降低性能开销与发热 ⚡

在 **100% 完整保留全部功能与视觉特效**（Material You / Apple 液态玻璃双模态表面、3D 按压倾斜流光、整板方向果冻形变、触控光圈动态模糊、自定义壁纸、主题圆形扩散动效、悬浮球手势与 4/8/12/16/18 键位布局）的前提下，进行了全链路性能优化与多设备兼容性增强：

- **渲染与触控降耗控温**：
  - 触控板表面移除 `clipPath` 模板缓冲，全面改为硬件 `drawRoundRect` + `BitmapShader` 与按脏状态缓存的渐变着色器，绘制热路径实现零堆对象分配；Material You 模式合并底色与哑光层减少 Overdraw；
  - 箭头光标去除软件图层（`LAYER_TYPE_SOFTWARE`），改为按尺寸/颜色缓存预渲染小位图；点击光圈空闲时切回 `INVISIBLE` 移除多余合成层并内联动态模糊计算；
  - 120–360Hz 触控采样期间将 `WindowManager.updateViewLayout` 合并至每 VSync 最多提交一次并过滤亚像素冗余 IPC；拖动/缩放改为松手时批量落盘，消除高频磁盘写入；
  - 整板拖动形变期间为静态按键临时启用硬件纹理层并在静止时释放，按住把手停顿时自动暂停 `Choreographer` 空转；复用按钮 `BubbleDrawable` 与动作选择器列表行视图；
  - 按需动态开关无障碍 `TYPE_WINDOWS_CHANGED` 订阅（关闭键盘自动最小化时实现零无效跨进程唤醒），并新增息屏监听及时停止所有动画与待执行回调；
  - 设置页缓存动态配色主题与壁纸状态，自定义壁纸改用 WebP（质量 90）存储并无缝兼容旧版 PNG；正式包开启 R8 代码与资源精简。
- **Android 8–16 / 折叠屏 / 挖孔屏 / 定制 ROM 兼容性增强**：
  - 严格将所有光标与注入手势坐标钳制在 `[0, W-1] × [0, H-1]`，修复边缘手势被部分机型静默丢弃的问题；
  - 修复连续穿透点击时的 20ms 恢复竞态，并在已处于穿透态（含拖拽锁定续接手势）时跳过多余 50ms 停顿；
  - 所有悬浮窗统一适配挖孔屏安全区（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` / `SHORT_EDGES`）；Android 11+ 直接信任 `maximumWindowMetrics` 并监听 `DisplayListener`，修复折叠屏展开/近似方形内屏与分屏尺寸适配；
  - 优化输入法可见性判定（过滤空矩形/极窄占位窗）、有界 BFS 节点查找与低版本节点回收、服务解绑与对话框清理，以及设置页四边挖孔留白、Android 13+ 预测性返回与通知权限撤销同步。

## Install

`versionCode 106`, `versionName 0.5.93`, same signing key as before. Installs over previous versions.
