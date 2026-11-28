# v0.5.25

## 消除双重震动 Bug：单击返回与回中打断恢复单次干脆震动反馈（Fix Double-Vibration on Tap and Cancel）

- **修复单击返回出现两道震动的问题（Single-Tap Double Vibration Resolved）**：
  - 根因分析：此前 `performAction` 在动作执行完毕后无条件调用了通用 `haptic()`，而悬浮球触摸处理在抬手（`ACTION_UP`）时已先行提供了即时触觉反馈，导致抬手与动作派发阶段连续震动两次；
  - 优化处理：为 `performAction` 引入 `withHaptic: Boolean = true` 控制参数，在悬浮球已先行震动的路径（单击、双击、滑动及长按）中显式关闭重复震动，严格保证一次操作对应单次干脆清晰的触觉反馈。
- **修复拖回中心打断出现两道震动的问题（Center Abort Double Vibration Resolved）**：
  - 根因分析：回中打断此前在 Android 11+（API 30+）使用了系统的 `HapticFeedbackConstants.REJECT` 效果，而 Android 系统与底层震动马达 HAL 将 REJECT 默认定义为「哒哒两下」的错误/拒绝双脉冲波形，从而产生了多重震动体验；
  - 优化处理：将打断反馈统一切换为标准单次微振反馈 `HapticFeedbackConstants.KEYBOARD_TAP`，手势回中打断时只触发一次轻盈、清脆的单脉冲震动。

## Install

`versionCode 38`, `versionName 0.5.25`, same signing key as before. Installs over previous versions.
