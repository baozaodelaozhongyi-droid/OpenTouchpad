# v0.5.13

## 修复点击双重震动问题（Fix Duplicate Vibration Feedback）

- **彻底消除触控板与按钮点击时的两道震动**：
  - **触控板消除按下冗余震动**：手指刚接触触控板时（`ACTION_DOWN`）仅执行 3D 悬浮物理倾斜下沉，不触发震动；震动反馈统一在手指抬起触发点击（`tapOrDouble`）时精准发出一次；
  - **外圈按钮统一震动时机**：移除 `PressFeedback` 按下时的重复震动，统一由按钮动作执行（`performAction`）时产生单次清脆确认震动；
  - **屏蔽系统层多余震动干扰**：禁用视图层的自动触觉反馈（`isHapticFeedbackEnabled = false`），通过 `FLAG_IGNORE_VIEW_SETTING` 保证应用专属的震动严格单次触发，不受部分机型系统全局点击震动的二次叠加干扰。

## Install

`versionCode 26`, `versionName 0.5.13`, same signing key as before. Installs over previous versions.
