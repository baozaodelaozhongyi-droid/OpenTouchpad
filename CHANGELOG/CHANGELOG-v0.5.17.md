# v0.5.17

## 修复快速切换主题时扩散波卡住残留（Fix Theme Transition Diffusion Wave Freezing During Rapid Taps）

- **单层可逆扩散波架构，杜绝波纹卡住残留**：
  - 修复在“跟随系统 / 白色模式 / 黑色模式”之间快速来回点击时，上一个未完成的扩散波被意外中断并永久卡在屏幕上的问题（如白色圆圈斑块残留）；
  - 引入单一过渡层可逆插值模型（Reversible Reveal Transition）：
    - 当用户在动画进行中快速反向切换时，扩散波会从当前半径丝滑收缩回圆心并自动清理，无需等待动画重头开始或重复堆叠视图；
    - 同一外观分支间快速切换时平滑续接或就地更新按钮状态（如跟随系统与深色模式外观一致时零延迟就地高亮切换），杜绝多动画竞争与竞态条件；
  - 同步启动 ValueAnimator 并通过标志位严格过滤取消事件，彻底消除由异步 `onPreDraw` 延迟导致的悬空未托管动画；
  - 动效进行中全屏触摸与滚动交互保持丝滑同步，触控板面板依然保持专业的独立暗色解耦风格。

## Install

`versionCode 30`, `versionName 0.5.17`, same signing key as before. Installs over previous versions.
