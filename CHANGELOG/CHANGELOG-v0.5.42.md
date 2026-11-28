# v0.5.42

## 长按按钮自定义动作支持自定义时长（Customizable Hold Duration for Button Reassignment）

- **长按自定义动作判定时长自定义（可设 300ms–3000ms，最长 3 秒）**：
  - 设置页「按钮」卡片中的「长按按钮自定义动作」开关下方新增「长按自定义判定时长」滑动条；
  - 默认值由原来的系统硬编码 ~400ms 调整为 1000ms（1 秒），支持自由滑动调节至最长 3000ms（3 秒）；
  - 彻底解决平时快速点击或手指轻微停顿时容易意外触发换键弹窗（ActionPicker）的问题，仅在用户特意长按至所设时长时才触发更换动作；
  - 滑动条调节时零闪烁无感热更新，无需重启悬浮窗服务。

## Install

`versionCode 55`, `versionName 0.5.42`, same signing key as before. Installs over previous versions.
