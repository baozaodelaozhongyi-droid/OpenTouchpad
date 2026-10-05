# v0.5.24

## 消除悬浮球单击返回的震动延迟与触发延迟（Eliminate Single-Tap Back Vibration and Trigger Delays）

- **松手即刻震动（0ms 触觉零延迟反馈）**：
  - 修复此前单击震动被滞后放入双击等待超时回调中（需等待近 300ms 才会震动）的问题；
  - 现改为在手指抬手（`ACTION_UP`）的毫秒瞬间立即派发震动确认，点击触感干脆利落、即按即有；
- **第二次按下即时打断单击（Cancel on 2nd Tap ACTION_DOWN）**：
  - 在用户进行第二次快速点击按下（`ACTION_DOWN`）的瞬间，立即取消挂起的单击超时任务，双击动作与单击动作彻底互不干扰；
- **优化双击判定等待时长（190ms 极速触发）**：
  - 判定窗口从原本冗长的 280ms 压缩至轻快舒适的 190ms，单次点击触发提速近 100ms；
- **未配置双击动作时完全 0ms 瞬发**：
  - 若将悬浮球「双击」动作设置为「无」，单击（如返回）动作无需等待任何超时判定，松手 0ms 零延迟绝对瞬发！

## Install

`versionCode 37`, `versionName 0.5.24`, same signing key as before. Installs over previous versions.
