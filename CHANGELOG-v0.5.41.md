# v0.5.41

## 悬浮球操作时保持用户设定透明度（Floating Ball Maintains Configured Opacity on Touch）

- **修复低透明度悬浮球在操作时突然变亮、满透明度的问题**：
  - 彻底移除触摸按压（ACTION_DOWN）、长按移动（MOVE_BALL）以及复位回弹期间将悬浮球透明度强制拉满到 100%（`alpha = 1.0`）的动画逻辑；
  - 无论在静止待机、点击按压、手势滑动、长按拖拽还是松手回弹，悬浮球始终严格保持用户在设置中设定的半透明度（20%–100%），彻底解决操作悬浮球时刺眼闪亮、忽明忽暗的问题。

## Install

`versionCode 54`, `versionName 0.5.41`, same signing key as before. Installs over previous versions.
