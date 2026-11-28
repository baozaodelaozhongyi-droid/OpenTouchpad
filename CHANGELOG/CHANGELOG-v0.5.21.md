# v0.5.21

## 修复悬浮球拖拽坐标累加导致乱飞的严重问题（Fix Floating Ball Drag Compounding Position Bug）

- **修复长按拖动悬浮球时坐标指数累加飞跑的问题**：
  - 此前在拖拽模式的 `ACTION_MOVE` 过程中错误地将移动后的新坐标即时回写给起始锚点变量，导致每一帧触摸事件中手指位移（`dx/dy`）在已累加位置上重复加算，悬浮球产生加速失控飞跑；
  - 现修正为基于触摸起始点（`ballDragStartX/Y`）进行严格 1:1 线性位移追踪，拖动跟手精准稳定，彻底消除乱飞失控现象；
- **拖动与回弹锚点生命周期严格同步**：
  - 拖拽仅在手指抬起松手（`ACTION_UP`）并完成屏幕边界安全约束后更新基准锚点（`ballAnchorX/Y`）；
  - 手势弹性滑动动效及弹簧复位（`OvershootInterpolator`）严格基于稳定锚点运作，松手回弹准确无误。

## Install

`versionCode 34`, `versionName 0.5.21`, same signing key as before. Installs over previous versions.
