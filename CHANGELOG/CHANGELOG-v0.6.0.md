# OpenTouchpad v0.6.0

### 光标穿孔点击穿透 & 快速连点合并 🎯

- **光标穿孔（Cursor Pinhole）直通点击**：
  - 触控屏悬浮窗在光标位置开一个 3px 半径的穿孔（Android 13+ `setTouchableRegion`），落在光标上的触摸直接穿透到下层 App —— 不用再「先移开面板再点」；
  - 穿孔矩形用预分配的整数数组批量计算与比较，位置没变就跳过提交，避免每帧重建 `Region`；
  - 面板边缘带安全判定：手指在面板外、或完全落在穿孔覆盖范围内才走直通，避免误触穿透；
  - 提交穿孔后保留 32ms 沉降延迟再注入手势，规避窗口区域同步时序造成的漏点。
- **快速连点合并（Rapid Tap Coalescing）**：
  - 连续快速点击（最多 3 次）合并为一次多击手势注入：单次按下 22ms、间隔 45ms（≥ 系统双击最小间隔 40ms），系统可正确识别双击 / 三击；
  - 新增固定容量 FIFO 的「已取消点击」记录（300ms 过期），面板被打断后能快速恢复，热路径零堆分配。
- **单元测试**：`TouchpadMathTest` 新增 252 行，覆盖穿孔矩形计算、覆盖判定、沉降延迟、连点时间线等纯逻辑。

## Install

`versionCode 113`, `versionName 0.6.0`, same signing key as before. Installs over previous versions.
