# OpenTouchpad v0.5.94

### 现代手机多档位动态刷新率（LTPO / VRR 1–240Hz）专项优化 ⚡

针对现代 LTPO 动态刷新率手机（1Hz–120Hz / 144Hz / 165Hz / 240Hz）与高触控采样率设备做了专项降耗与手感一致性优化，同时完整保留所有视觉特效与原生高刷跟手体验：

- **彻底消除 LTPO 换档引起的后台唤醒**：
  - 移除无法按事件过滤的常驻 `DisplayListener`，避免屏幕在阅读、刷流或看视频期间每次 `120↔90↔60↔30↔10↔1Hz` 自动换档时唤醒服务主线程；
  - 改用 `onConfigurationChanged`（附 120ms 静默延迟兜底校验应对部分系统 `maximumWindowMetrics` 滞后 1 帧）+ 亮屏广播 `ACTION_SCREEN_ON` + 全部 5 个触摸入口的非破坏性惰性边界同步（`ACTION_DOWN` 仅更新尺寸不毁窗，手势结束 `ACTION_UP/CANCEL` 再安全重建）。
- **高刷屏液态玻璃流光着色器独立节流 + 尾沿补刷**：
  - 仅对液态玻璃高开销的 4-Shader 环境流光层做 `13ms` 独立节流（60Hz 屏抗抖稳跑 60Hz、120Hz 稳 60Hz、144Hz 稳 72Hz），光标位移、3D 物理倾角及 Material You 模式继续跑满原生最高刷新率；
  - 增加尾沿补刷（trailing-edge flush）与动画终帧收敛，并钳位 `OvershootInterpolator` 回弹值，防止急停高光错位；移动把手拖动流光同步节流并在停顿/停止时即时收敛。
- **跨刷新率与高采样率物理手感一致化**：
  - 整板拖动形变速度采样放宽至 `3.5ms–55ms`（覆盖 20Hz–240Hz 全档位）并采用自适应低刷静默衰减窗口；
  - 悬浮球长按拖拽改用硬件事件时间戳（Android 14+ `eventTimeNanos`）+ 首包播种 + `<1.5ms` 粘包位移累积 + 时间常数指数平滑（EMA），彻底修复高采样率下的速度低估与长按首帧跳变。

## Install

`versionCode 107`, `versionName 0.5.94`, same signing key as before. Installs over previous versions.
