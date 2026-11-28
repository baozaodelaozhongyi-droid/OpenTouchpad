# OpenTouchpad v0.5.95

### 全链路内存占用与内存管理专项优化 🧠

在 100% 完整保留所有现有功能、材质动效（Material You / Apple 液态玻璃、3D 倾角流光、整板拖动果冻形变、触控光圈动态模糊、圆形主题扩散、4/8/12/16/18 键位布局）及 Android 8–16 兼容性的前提下，对后台保活与高频交互场景进行了全链路内存瘦身：

- **自定义壁纸全生命周期管理与按需解码**：
  - 设置页退入后台保活（`TRIM_MEMORY_UI_HIDDEN`）后自动安全释放全屏壁纸 Bitmap 与主题缓存，回到前台时在 `onStart()` 无感恢复，保留多任务卡片预览与后台加锁能力；消除选图时双全屏位图解码峰值与多余重建；
  - 触控板自定义底图改用 Center-Crop 最小覆盖尺寸按需解码（保持全彩 `MEMORY_POLICY_DEFAULT` 与长竖图/宽图 1:1 清晰度、焦点裁剪严格一致），面板放大超阈值时防抖升采样，最小化或横屏隐藏 4 秒后在无活跃退场动画时安全回收；
  - 补充 Android 9+ `ImageDecoder.setTargetSize` 直接降采样、低版本超限缩放兜底与临时文件安全清理；
  - 无障碍服务接入 `onTrimMemory` / `onLowMemory` 与息屏回收，在严格保护展开态面板底图与活跃光圈的前提下清理隐藏态位图缓存、隐藏态光标缓存与空闲光圈窗口（关闭触控光圈开关时立即移除常驻窗口）。
- **渲染管线、Shader 与 Drawable 瘦身**：
  - 按钮水波纹蒙版改用极简 `BubbleMaskDrawable`，替代完整 `BubbleDrawable`；液态玻璃专用画笔与路径延迟初始化并在切回 Material You 时释放，同时复用按键 `ColorStateList` 与静态渐变数组；
  - 触控板 3D 按压倾斜流光改用单一单位 `LinearGradient` 配合保角正交相似矩阵与 `Paint.alpha` 更新，消除 60–120Hz 滑动期间每帧新建 Native `LinearGradient` 的显存与 GC 抖动，并在脱离窗口时释放 `BitmapShader`。
- **高频交互热路径零分配 & 悬浮球原地更新**：
  - `SwipeVelocityTracker` 改用原始类型环形数组（64 起步动态扩容至 512 上限），面板拖动弹簧、悬浮球果冻形变、阻尼位移、归一化按压与光标定位全链路切换为原地写入零分配重载；
  - 悬浮球尺寸、颜色、透明度调节改为原地复用 `GradientDrawable` / `LayerDrawable` / `RippleDrawable` 与 `LayoutParams`，透明度滑块走零分配快速路径。
- **设置页视图树瘦身、原地刷新与线程治理**：
  - 默认折叠的设置卡片改为首次展开时惰性构建，引入 `UiBindings` / `UiColorStateLists` 隔离双树主题与颜色状态表；移除所有卡片行的 `OnLayoutChangeListener`，改为显式联动分隔线；
  - 键位模式切换、按钮槽位编辑、非强调色配色选择、触控光圈与悬浮球手势开关全面改为原地局部刷新，避免反复整页 `recreate()`；
  - 后台图片写入线程池改为按需懒创建并在空闲 30 秒后自动退出；统一管理并追踪活动对话框，销毁时安全释放全部动画与回调引用。

## Install

`versionCode 108`, `versionName 0.5.95`, same signing key as before. Installs over previous versions.
