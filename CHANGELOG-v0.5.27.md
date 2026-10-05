# v0.5.27

## 全新长按移动动效：速度感应果冻形变、跟手倾斜与视差回弹（Fluid Jelly & Inertia Motion for Floating Ball Dragging）

- **长按浮起弹簧动效（Spring Lift on Long-Press）**：
  - 长按触发进入拖动状态时，悬浮球伴随 `OvershootInterpolator` 物理弹性放大（从 1.0x 饱满弹出至 1.18x），不透明度平滑提升至 100%，内核图标微微浮起，呈现被手指“吸附拾起”的立体悬浮感；
- **速度感应惯性果冻拉伸（Velocity-Sensitive Dynamic Jelly Deformation）**：
  - 基于移动速度矢量计算流体阻尼形变：当快速拖动悬浮球移动时，悬浮球沿运动方向柔韧拉伸（最高达 1.38x），垂直轴保体积压扁，带来极具生命力的水滴/果冻拉伸视觉；
  - 自动平滑旋转（Angular Lerp）：悬浮球主轴根据移动矢量角平滑转动，滑行转弯时如同在屏幕表面流畅游弋；
- **内核图标惯性视差滞后（Parallax Inertia Lag）**：
  - 悬浮球移动时，中心图标向运动相反方向轻微滞后漂浮（最大 5dp 视差），静止悬停时自然平滑归中，带来晶莹液体球包裹核心的深邃物理质感；
- **松手回弹落定与微触觉反馈（Snap Landing & Haptic Tick）**：
  - 松手时悬浮球以带阻尼的弹性超弹曲线（Overshoot Spring）落回原始尺寸，伴随轻脆利落的落定触觉微反馈（`KEYBOARD_TAP`），落定干脆自然。

## Install

`versionCode 40`, `versionName 0.5.27`, same signing key as before. Installs over previous versions.
