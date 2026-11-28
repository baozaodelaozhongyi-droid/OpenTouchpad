# v0.5.39

## 点击反馈光圈松手即收缩（Touch Feedback Ring Collapses on Release）

- **光圈反馈彻底改为松手即收缩**：
  - 点击与长按反馈圆环的完全展开静止停留时间（`TAP_HOLD_MS`）设为 `0 ms`；
  - 触控板长按时，手指一旦抬起松手（`ACTION_UP`），圆环立即向内收缩淡出，不再在松手后强制停留数百毫秒；
  - 普通点击反馈在展开完成后无缝衔接立即向内收缩淡出，零停留延迟，视觉响应更加干脆利落。

## Install

`versionCode 52`, `versionName 0.5.39`, same signing key as before. Installs over previous versions.
