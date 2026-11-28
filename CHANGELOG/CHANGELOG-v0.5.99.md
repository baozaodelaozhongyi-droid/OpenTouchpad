# OpenTouchpad v0.5.99

### 光标 2× 超采样清晰度优化与密度同步修复 🎯

- **光标 2× 超采样离屏渲染**：
  - 将 `CursorArrowView` 预渲染缓存提升为 2× 超采样离屏位图（极端超大尺寸按 1024px 上限封顶防止显存暴涨，低内存时自动回退至 1×），并在超采样坐标系下完整保留尖端热点、描边、圆角与柔和投影比例；
  - 显式设置 `Bitmap.DENSITY_NONE` 并使用复用目标矩形 `dstRect` 下采样绘制，彻底避免系统更改「显示大小 / 最小宽度 (DPI)」或折叠屏/外接屏切换密度时触发隐式二次缩放发糊；
  - 切换光标尺寸与颜色时不再在 View 附着状态下同步 `recycle()` 旧位图，消除硬件加速下与 RenderThread 的潜在竞态。
- **光标悬浮窗硬件加速与密度/尺寸同步**：
  - 为光标无障碍悬浮窗显式启用 `FLAG_HARDWARE_ACCELERATED`，真正走 GPU 硬件 `drawBitmap` 下采样管线；
  - 在屏幕密度或分辨率变化（`onConfigurationChanged` 与屏幕边界同步）时同步刷新光标窗口像素尺寸，并在设置页实时调整光标大小时同步重算窗口偏移以保持尖端锚点不动。

## Install

`versionCode 112`, `versionName 0.5.99`, same signing key as before. Installs over previous versions.
