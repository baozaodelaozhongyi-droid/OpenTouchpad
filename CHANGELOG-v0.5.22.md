# v0.5.22

## 悬浮球手势支持拖回中心触发震动并安全打断手势（Abort Gesture with Haptic Feedback on Returning to Center）

- **拖回中心即时打断与震动反馈**：
  - 用户在滑动过程中（如误向右滑）如果改变主意，只需将手指拖回中心判定圈（<= 8~10dp），悬浮球会立即触发即时震动反馈（Android 11+ 优先使用 `HapticFeedbackConstants.REJECT` 拒绝反馈），明确提示手势已被打断；
  - 手指在中心松开后安全复位，既不会触发滑动手势动作，也绝不会误触发单次点击/双击动作，彻底避免误操作；
- **具备迟滞区间（Hysteresis）与重触发支持**：
  - 设置外圈触发阈值与内圈打断阈值之间的迟滞死区，防止边缘抖动引发误打断；
  - 若打断后未抬手并重新向外滑出阈值，手势会自动重新激活（Re-arm），支持丝滑变向操作与随时反悔；
- **严格隔离点击判定与滑动打断**：
  - 区分原地点击与位移操作，拖动回中或微小位移均不会误执行单次点击（如返回操作），保证手势意图精准执行。

## Install

`versionCode 35`, `versionName 0.5.22`, same signing key as before. Installs over previous versions.
