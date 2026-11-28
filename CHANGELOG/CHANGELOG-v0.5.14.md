# v0.5.14

## 外观主题切换颜色扩散动效（Theme Switch Circular Reveal Animation）

- **以当前选项为起点的全屏颜色扩散动效**：
  - 点击“跟随系统”、“白色模式”或“黑色模式”切换外观时，不再黑屏/闪烁重建 Activity；
  - 自动获取当前被点击选项按钮在屏幕上的精确物理中心点坐标作为扩散源点；
  - 启动丝滑硬件加速的全屏圆形揭示动效（Circular Reveal），新主题的颜色如涟漪水波般从被点击的选项向外扩散铺满整个屏幕；
  - 动效进行中自动拦截重复误触，平滑同步保持原页面滚动位置，并在动画完成瞬间优雅无缝过渡系统状态栏与导航栏配色。

## Install

`versionCode 27`, `versionName 0.5.14`, same signing key as before. Installs over previous versions.
