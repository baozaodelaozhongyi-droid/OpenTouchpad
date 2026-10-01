# OpenTouchpad

**A touchpad, an on-screen cursor and large action buttons for people who cannot reach the whole screen.**
**给无法够到整块屏幕的人：触控板 + 屏幕光标 + 大按钮。**

[English](#english) · [中文](#中文)

---

## English

### What it is

A single-purpose Android accessibility app. It puts a touchpad panel and a cursor on your screen,
so the whole device can be operated from one small area — ideal for large phones and for users with
limited hand mobility. No internet, no ads, no analytics, no accounts.

### Why this exists

An accessibility service injects its gestures through `AccessibilityService.dispatchGesture()`.
On Android 16 that stopped working on the most important screens — permission dialogs, Developer
options, Google Play account pages — for any service that has **not** declared itself as a legitimate
accessibility tool:

> "any app requesting accessibility permission that hasn't explicitly declared itself as a legitimate
> accessibility tool (`isAccessibilityTool=true`) is denied access to that view"

Android 16 introduced `accessibilityDataSensitive` and wired it into the older
`setFilterTouchesWhenObscured` protection, so those screens reject foreign gestures.

This app declares `android:isAccessibilityTool="true"` in
[`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml) — as a
genuine, ad-free, offline assistive tool — so it keeps working exactly where it is needed.
Reference: [Android Developers blog](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)

### Features (v0.3)

- Desktop-style arrow cursor with adjustable size and selectable colour
- Floating touchpad panel — drag the **move** handle to place it anywhere, drag **resize** to change the overall width and height, and minimise it to a draggable floating ball
- Floating ball size and opacity are adjustable; tap the ball to restore the touchpad
- Modernised settings UI with clearer sections, larger controls and accessible contrast
- Single tap / double tap / **long press** / **drag & drop with a lock button** (real "hold", not a fake long press)
- **Dwell click** (auto click after the finger rests for N ms) — for very limited dexterity
- Scroll in 4 directions + long swipes in 4 directions
- **12 configurable buttons**, each bound to any action — long-press a button to re-bind it:
  left click · long press · drag lock · 4 scroll directions · 4 long swipes ·
  notification panel · power menu · volume ± · screenshot · keyboard (focus input field) ·
  back · home · recents · settings · hide
- Auto-hide in landscape, auto-minimise when the keyboard opens
- Panel opacity, button corner radius, spacing and icon size — all adjustable
- Haptic feedback
- Chinese + English UI

### Install

1. Download the APK from the latest GitHub Actions artifact / release.
2. Install it. On Android 13+ sideloaded apps need
   **Settings → Apps → OpenTouchpad → ⋮ → Allow restricted settings** before the service can be enabled.
3. **Settings → Accessibility → OpenTouchpad touchpad** → turn on.

### Build

```bash
gradle --no-daemon assembleDebug      # needs JDK 17 + Android SDK 35
```

CI (GitHub Actions) builds an APK on every push — see `.github/workflows/build.yml`.

### Roadmap

- Focus-stepping mode (select UI elements directly, for screens that hide third-party overlays)
- Hardware switch / Bluetooth keyboard users
- Layout presets, per-app tweaks
- F-Droid release

### License

GPL-3.0. Built together with a motor-impaired user, for real daily use.

---

## 中文

### 这是什么

一个只做一件事的安卓无障碍应用：在屏幕上放一块触控板和一个光标，于是**整台手机都能用一小块区域来操作** ——
为大手机会够不到屏幕的人、手部活动受限的人设计。不联网、无广告、无统计、无账号。

### 为什么要做它

无障碍服务靠 `dispatchGesture()` 注入手势。到了 Android 16，权限弹窗、开发者选项、Google Play 账号页
这些界面开始拒绝"没有声明自己是合法无障碍工具"的服务：

> 任何申请了无障碍权限、但**没有**显式声明自己是合法无障碍工具（`isAccessibilityTool=true`）的应用，
> 都会被拒绝访问这些视图。

Android 16 引入的 `accessibilityDataSensitive` 与旧的 `setFilterTouchesWhenObscured` 打通后，
这些界面就会丢弃外来手势 —— 于是"手指能点、触控板点不了"。

本应用在 [`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml)
里声明了 `android:isAccessibilityTool="true"`（它确实是离线、无广告的无障碍工具），所以在最需要的界面上依然可用。
参考：[Android 开发者博客](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)

### 功能（v0.3）

- 电脑风格箭头光标，可调大小并可选择颜色
- 悬浮触控板 —— 拖「移动」把手可放到屏幕任何位置，拖「调整大小」可同时改变整体宽高，收起后变成可拖动悬浮球
- 悬浮球大小与透明度可调，点击悬浮球即可恢复触控板
- 设置界面采用现代化靛蓝视觉、清晰分区、更大的操作控件和更高对比度
- 单击 / 双击 / **长按** / **拖拽（带锁定按钮，真正的"按住不放"）**
- **停留点击**（手指静止 N 毫秒自动点击）—— 适合手部动作非常有限的人
- 四方向滚动 + 四方向长滑动
- **12 个可自定义按钮**（长按面板上的按钮即可改绑）：
  左键 · 长按 · 拖拽锁定 · 四方向滚动 · 四方向长滑 ·
  通知栏 · 电源菜单 · 音量± · 截图 · 键盘（聚焦输入框）·
  返回 · 主屏 · 最近任务 · 设置 · 收起
- 横屏自动隐藏，键盘弹出时自动最小化
- 透明度、按钮圆角、间距、图标大小全部可调
- 震动反馈
- 中英双语界面

### 安装

1. 从 GitHub Actions 产物或 Release 下载 APK。
2. 安装。Android 13+ 侧载应用需要先
   **设置 → 应用 → OpenTouchpad → ⋮ → 允许受限设置**，否则无障碍服务开不了。
3. **设置 → 无障碍 → OpenTouchpad 触控板** → 打开。

### 编译

```bash
gradle --no-daemon assembleDebug      # 需要 JDK 17 + Android SDK 35
```

每次 push 由 GitHub Actions 自动出包，见 `.github/workflows/build.yml`。

### 路线图

- 焦点步进模式（直接选中界面元素，应对"敏感界面不显示第三方叠加层"的情况）
- 硬件开关 / 蓝牙键盘支持
- 布局预设、按应用微调
- F-Droid 上架

### 许可证

GPL-3.0。与一位运动障碍用户一起开发，目标是每天真正能用。
