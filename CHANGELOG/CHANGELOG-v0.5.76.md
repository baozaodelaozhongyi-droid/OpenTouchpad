# OpenTouchpad v0.5.76

### 规范级自适应圆角图标 (Adaptive Icon) 与 Android 13+ Monet 动态取色适配

- **Android 规范级自适应图标适配 (Adaptive Icon / API 26+)**：
  - 新增 `mipmap-anydpi-v26/ic_launcher.xml` 与 `mipmap-anydpi-v26/ic_launcher_round.xml`；
  - 遵循 Android 官方 108dp 视口与 72dp 安全区（Safe Zone）规范，背景层与前景矢量层完美解耦；
  - 完美适配各家手机厂商与第三方桌面（Google Pixel、小米 HyperOS、三星 One UI、OPPO ColorOS、vivo OriginOS、Nova 等）的任意图标裁切形态（圆形、圆角矩形、方圆、水滴型），彻底告别双层白底与生硬裁切；
- **Android 13+ (API 33+) Monet 动态取色 / 主题图标全面适配 (Themed Icon)**：
  - 引入专属 `ic_launcher_monochrome.xml` 单色矢量适配图层；
  - 当在系统「壁纸与样式」中开启「主题图标 / 动态取色」时，OpenTouchpad 图标将自动吸取系统壁纸调色盘，呈现高度和谐统一的 Material You 动态主题视觉效果；
- **超清纯矢量图层 (VectorDrawable)**：
  - 前景图层与单色图层均采用原生 Android VectorDrawable 矢量绘制，在任何超高分辨率屏幕与折叠屏上缩放均保持极高清晰度。
