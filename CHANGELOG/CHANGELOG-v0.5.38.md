# v0.5.38

## 修复悬浮球长按移动松手时产生多余振动问题（Fix Unwanted Haptic on Floating Ball Release）

- **消除悬浮球移动松手后的二次振动**：
  - 移除了悬浮球长按移动松手时（`ACTION_UP`）误调用的 `ballHapticCancel()`；
  - 保留长按起手时的振动提示（告知用户已进入移动状态），移动到新位置松手就位后干净利落、不再发出多余的振动；
  - 悬浮球划动手势拉回中心取消的震动反馈保持不受影响。

## Install

`versionCode 51`, `versionName 0.5.38`, same signing key as before. Installs over previous versions.
