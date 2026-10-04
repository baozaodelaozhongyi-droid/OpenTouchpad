# v0.5.8

## 3D 物理按压反馈（3D Tilt & Sink Press Feedback）

**触控板与按钮加入真实的 3D 悬浮机械按压下沉手感：**

- **触控板 3D 倾斜下沉（Touchpad 3D Tilt & Sink）**：
  - 按住左边：左侧下沉（沿 Y 轴 3D 透视倾斜）；
  - 按住右边：右侧下沉；
  - 按住上方/下方：上下对应倾斜下沉；
  - 手指在触控板上滑动时，3D 倾斜角度随手指落点动态流畅跟随；
  - 抬手松开时：带有物理弹性阻尼平滑回弹复原。
- **功能按钮按压质感（Button Press Feedback）**：
  - 面板各功能按钮加入受力向内凹陷与下沉动效；
  - 设置页中的操作按钮与槽位预览按钮同样支持按压反馈。
- **触觉与视觉深度配合（Haptic & Perspective Depth）**：
  - 按下瞬间触发触觉震动反馈；
  - 调优摄像机透视距离（Camera Distance）与容器裁剪属性，保证 3D 纵深立体感自然无裁切。
- **设置开关（Settings Toggle）**：
  - 设置界面的「手感」区域新增「按压反馈」开关（默认开启），可自由开关该物理动效。

## Install

`versionCode 21`, `versionName 0.5.8`, same signing key as before. Installs over previous versions.
