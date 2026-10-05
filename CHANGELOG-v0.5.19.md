# v0.5.19

## 悬浮球手势防位移锁定与长按拖拽判定优化（Floating Ball Gesture Anchor & Safe Hold Detection）

- **修复上下左右滑动时悬浮球随手势位移的问题**：
  - 手指在悬浮球上滑动触发方向手势时，严格锚定悬浮球窗口位置，手势滑动期间绝不跟随手指位移；
  - 抬起手指时精准执行配置的方向手势动作（返回桌面、通知栏、最近任务等）；
- **优化长按拖拽判定机制**：
  - 引入静止状态门禁（`ballMoved` 标记与安全防误触时长 `maxOf(longPressMs, 450ms)`）；
  - 发生任何位移滑动（`dist > 6dp`）即刻且永久取消长按判定，彻底杜绝快速滑动时因短长按阈值被误判为拖拽的问题；
  - 仅在手指原地按住静止并触发震动（及放大视觉反馈）后，才允许拖动悬浮球更新屏幕坐标。

## Install

`versionCode 32`, `versionName 0.5.19`, same signing key as before. Installs over previous versions.
