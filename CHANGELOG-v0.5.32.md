# v0.5.32

## 新增悬浮球手势动作总开关（Floating Ball Gesture Actions Toggle）

- **新增「开启手势动作」开关（Master Toggle for Floating Ball Gestures）**：
  - 在设置页「悬浮球手势动作」卡片顶部新增功能开关；
  - **开启时（默认）**：保留完整的单击、双击、长按以及上下左右四向滑动手势动作与阻尼果冻形变动画；
  - **关闭时（极简模式）**：
    - 点击悬浮球以 0ms 零延迟直接展开触控板（无需等待双击超时）；
    - 按住悬浮球直接实时拖动位置（无需长按判定，无手势判定与误触问题）；
    - 设置页自动折叠手势动作配置列表，界面清爽简约；
- **配置持久化记忆**：
  - 开关状态持久化保存在本地设置中，重启无障碍服务后依然保持。

## Install

`versionCode 45`, `versionName 0.5.32`, same signing key as before. Installs over previous versions.
