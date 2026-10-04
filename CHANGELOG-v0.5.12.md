# v0.5.12

## 修复光标点击误触触控板自身按钮（Fix Cursor Triggering Touchpad Buttons）

- **彻底杜绝光标在触控板/按钮区域点击时误触触控板自身按钮**：
  - 穿透机制增加活跃状态标记锁（`isPassThroughActive`），在穿透手势派发期间，触控板外圈所有动作按钮、移动缩放把手及触控板自身全面拦截并忽略任何点击、长按与触摸反馈；
  - 优化穿透窗口不可触摸标志（`FLAG_NOT_TOUCHABLE`）生效安全延时（50ms），确保系统 InputDispatcher 稳固同步窗口属性，使手势精准注入并由底层应用接收；
  - 动态计算兜底超时保护，彻底消除长按与拖拽手势中途面板提前恢复可触摸导致的误触；
  - 手势注入完成后延时平滑恢复可触摸，消除残余事件误触发。

## Install

`versionCode 25`, `versionName 0.5.12`, same signing key as before. Installs over previous versions.
