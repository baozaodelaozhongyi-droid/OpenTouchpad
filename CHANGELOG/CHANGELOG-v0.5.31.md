# v0.5.31

## 新增 18 键位经典模式支持（Restored Classic 18-Key Layout Mode）

- **新增「18 键」经典环形按键选项（Classic 18-Key Ring Layout）**：
  - 在自选键位模式中，除了 4 / 8 / 12 / 16 键位模式外，恢复并完整支持最开始的经典 **18 键位** 选项；
  - 经典 18 键位采用 6 列 × 5 行网格布局，包含四角按键（左上、右上、左下、右下）以及专用的移动键（左列上方）与缩放键（右列下方）；
- **动态几何与网格高度适配（Adaptive Geometry & Heights）**：
  - 当选择 18 键位时，触控板面板最小高度自适应使用经典 5 单元高度（`5 * b + 4 * gap`）；额外高度拉伸时侧边按键保持平滑居中；
  - 4 / 8 / 12 / 16 键位继续保持自适应胶囊与紧凑圆形的四边布局；
- **全模式设置预览与槽位编辑（Preview & Customization）**：
  - 设置页缩略图针对 18 键位 1:1 动态还原 6 列 × 5 行布局及四角按键，点击任意槽位均可单独配置动作；
  - 完善无障碍辅助文本与按键保护机制；
- **配置持久化与向下兼容（Persistence & Migration）**：
  - 18 键位配置支持独立存储；老版本已保存的 12/16/18 键位历史配置自动平滑迁移。

## Install

`versionCode 44`, `versionName 0.5.31`, same signing key as before. Installs over previous versions.
