# v0.5.37

## 拖拽缩放实时联动滑动条与全滑动条零闪烁平滑更新（Real-Time Resize Slider Sync & Zero-Flicker Live Updates）

- **缩放按键拖拽与设置界面滑动条双向实时联动**：
  - 在触控板悬浮窗上直接拖动「缩放」按键时，通过 `onPanelResized` 回调实时通知设置界面；
  - 设置界面中的「触控板控制区宽度」和「触控板加高」滑动条及数值指示胶囊实时跟随手指拖拽同步更新；
  - 引入 `SYNCING` 标记防回环机制，避免设置页滑动条程序化赋值反向触发重复更新。
- **彻底消除所有修改悬浮球与触控板滑动条时的闪烁问题（零闪烁即时更新）**：
  - 将所有连续调节类滑动条（控制区宽度、触控板加高、面板透明度、按键间距、悬浮球大小、悬浮球透明度、光标大小、光标透明度）从原先的整窗销毁重建（`reload()` / `removeView + addView`）重构为就地平滑更新；
  - 提供 `updatePanelGeometry()`、`updatePanelAppearance()`、`updateBallAppearance()`、`updateCursorAppearance()` 等精准更新方法，仅通过 `WindowManager.updateViewLayout` 与动态刷新视图背景完成渲染，窗口表面永不销毁，拖动滑动条时丝滑顺畅、彻底杜绝一闪一闪的现象。

## Install

`versionCode 50`, `versionName 0.5.37`, same signing key as before. Installs over previous versions.
