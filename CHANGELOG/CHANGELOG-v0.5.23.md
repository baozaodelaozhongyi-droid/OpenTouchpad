# v0.5.23

## 悬浮球手势拖拽果冻形变动效与弹性回弹（Floating Ball Drag Jelly Deformation & Rebound Animation）

- **拖拽拉伸与保体积果冻形变（Squash & Stretch）**：
  - 滑动悬浮球时，球体外层光晕与实色底盘沿位移方向自适应平滑转向，并根据拉拽距离产生弹性拉伸（主轴拉伸最高 1.20x，垂直次轴保体积挤压收缩），使拖拽过程具备鲜活有机的水滴/果冻流体质感；
- **内核图标独立正向与视差浮动（Parallax Depth）**：
  - 内部触控板图标保持正向直立，不会随拖拽角度发生反转颠倒；同时在水滴球体内产生细腻的微跟手视差位移，营造通透立体的双层液体悬浮感；
- **松手果冻过冲弹性回弹（Spring Oscillation Rebound）**：
  - 松手复位或打断手势时，形变与坐标位置通过 `OvershootInterpolator` 同步回弹振荡平滑归位，Q 弹生动；
- **长按拖动惯性形变**：
  - 长按悬浮球移位时，根据手指瞬时移动速度动态叠加流体形变，拖拽移位手感更为顺滑灵动。

## Install

`versionCode 36`, `versionName 0.5.23`, same signing key as before. Installs over previous versions.
