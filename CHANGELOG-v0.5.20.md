# v0.5.20

## 悬浮球手势阻尼滑动动效与弹性回弹（Floating Ball Swipe Gesture Damped Slide & Spring Snap-back）

- **新增悬浮球手势跟手滑动动效**：
  - 在悬浮球上进行上下左右方向滑动时，悬浮球根据手指滑动方向与距离呈现平滑自然的弹性阻尼滑动动效；
  - 阻尼曲线采用指数平滑衰减，直观展现手势滑动方向与拉伸反馈，同时防止悬浮球被过度拉离；
- **自然灵动的弹簧过冲回弹（Overshoot Spring Snap-back）**：
  - 手指抬起或手势触发完成后，悬浮球通过过冲弹簧插值器（`OvershootInterpolator`）利落回弹至原始锚点，伴随自然微颤回位；
  - 严格保持悬浮球的固定配置坐标（`prefs.ballX/ballY`），滑动动效完全不改变悬浮球的驻留位置；
- **长按拖动与手势动效无缝兼容**：
  - 手势滑动仅产生临时弹性动效并在松手时弹回原位；原地静止长按触发震动后进入拖拽模式，松手即在新位置驻留。

## Install

`versionCode 33`, `versionName 0.5.20`, same signing key as before. Installs over previous versions.
