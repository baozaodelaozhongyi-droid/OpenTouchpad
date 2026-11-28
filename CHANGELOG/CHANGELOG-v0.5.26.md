# v0.5.26

## 优化悬浮球手势菜单选项：精简触控板光标操作，聚焦全局系统动作（Optimize Floating Ball Gesture Action Menu）

- **净化过滤触控板专属光标手势（Filter Out Touchpad-Specific Cursor Actions）**：
  - 根因分析：此前悬浮球手势选择弹窗与触控板实体按钮公用同一个全量动作列表，导致打开悬浮球菜单时，排在最前列的全是「左键单击」、「光标处长按」、「拖拽锁定」、「向上滚动」、「长距离上滑」等 11 项仅在触控板光标激活时有效的专属操作，导致菜单冗长繁杂、不易查找且容易误选；
  - 精准分类：将动作集合细分为「悬浮球专用手势动作（`BALL_ACTIONS` / `BALL_LONG_PRESS_ACTIONS`）」与「触控板专用动作（`TOUCHPAD_ACTIONS`）」；
- **悬浮球手势专属精简菜单（Tailored Floating Ball Action Set）**：
  - 单击、双击与四向滑动弹窗仅呈现对悬浮球真实有效的全局与系统操作：返回、桌面（主屏）、最近任务、展开/收起触控板、下拉通知栏、截图、电源菜单、弹出输入法、音量 +/−、打开设置、无动作；
  - 长按手势专属置顶「拖动悬浮球（`MOVE_BALL`）」，保证按住拖拽移动悬浮球的直观配置；
- **优化空动作文案提示（Intuitive "No Action" Label）**：
  - 悬浮球手势中的 `NONE` 状态由原先适用于按钮网格的「空槽位」优化显示为「无动作」，更加符合手势配置语境。

## Install

`versionCode 39`, `versionName 0.5.26`, same signing key as before. Installs over previous versions.
