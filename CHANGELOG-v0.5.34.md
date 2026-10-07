# v0.5.34

## 触控屏拉高后侧边按钮联动拉伸（Dynamic Side Button Stretching with Height）

- **触控板拉高侧边按钮纵向贴合拉伸**：
  - 无论在自选键位模式（4 / 8 / 12 / 16 键）还是经典 18 键模式下，当通过拖动把手拉高触控板高度时，触控板左右两侧的边缘按键均会与触控板高度联动纵向等比拉伸；
  - 侧边按键顶部和底部分别严格对齐触控板的上下边缘，按键之间保留标准间距，不再悬浮居中或留出上下空隙，视觉与触控区域更加一体化；
- **设置页预览与单元测试同步完善**：
  - 设置页面键位预览卡片同步采用动态拉伸算法，预览与实际面板渲染保持完全一致；
  - 补充并更新单元测试 `extraHeightStretchesSideButtonsWithTouchpad` 与 `extraHeightStretchesSideButtonsInEighteenMode`，全面覆盖各按键数量下的联动拉伸逻辑与无重叠校验。

## Install

`versionCode 47`, `versionName 0.5.34`, same signing key as before. Installs over previous versions.
