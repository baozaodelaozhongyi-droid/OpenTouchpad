# OpenTouchpad v0.5.91

### 修复 Android 11+ 启动闪退问题 🔧

针对 `v0.5.90` 在 Android 11 及以上版本（API 30+）无法打开应用的启动空指针闪退进行了紧急修复：

- **修复启动时序导致的空指针崩溃**：
  - 修复 `MainActivity.onCreate()` 在 `setContentView()` 之前过早调用 `updateSystemBars()`，且未先初始化 `DecorView` 就直接访问 `window.insetsController` 引起的 `NullPointerException`；
  - 将 `updateSystemBars()` 调整至 `setContentView(rootContainer)` 之后调用，确保窗口根视图层级已建立。
- **加固系统栏外观设置逻辑**：
  - 在 `updateSystemBars()` 内增加 `isFinishing / isDestroyed` 与空窗口检查，并在设置系统栏颜色与图标深浅前主动获取 `decorView`；
  - Android 11+ 优先通过 `decorView.windowInsetsController` 获取控制器（失败时安全回退 `currentWindow.insetsController` 与旧版 `systemUiVisibility`），避免部分机型或窗口尚未附着时发生异常。
- **完整保留 `v0.5.90` 视觉修复**：
  - 保留设置页 `SettingsScrollView` 顶部状态栏隔离、渐隐边缘与主题切换圆形扩散动效背景/系统栏同步修复。

## Install

`versionCode 104`, `versionName 0.5.91`, same signing key as before. Installs over previous versions.
