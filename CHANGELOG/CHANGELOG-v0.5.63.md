# OpenTouchpad v0.5.63

### 修复底层窗口模糊标志导致整个屏幕模糊的严重问题

- **移除系统级窗口 `FLAG_BLUR_BEHIND`**：
  - Android 12+ 的 `FLAG_BLUR_BEHIND` 是为系统对话框与通知中心设计的窗口级效果，应用在无障碍悬浮窗上会导致 SurfaceFlinger 对屏幕底层所有应用与桌面画面施加全局高斯模糊。
  - 彻底移除 `FLAG_BLUR_BEHIND` 与 `blurBehindRadius`，彻底恢复屏幕全局画面的清晰锐利。
  - 保留纯 Canvas 光学折射渐变基底、毛玻璃漫反射水洗层与镜面高光外描边，在保证底层屏幕完全清晰可见的前提下呈现通透的液态玻璃质感。
