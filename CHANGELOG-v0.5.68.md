# OpenTouchpad v0.5.68

### 自定义背景图片全格式兼容与加载稳定性增强

- **原生支持 HEIC / HEIF / AVIF 现代相册格式**：
  - 接入 Android 9+ (API 28+) 原生 `ImageDecoder` 引擎，彻底解决小米/红米 (HyperOS)、vivo (OriginOS)、OPPO (ColorOS) 等手机相机默认拍摄的 `.heic` 格式照片在旧版 `BitmapFactory` 下无法解析导致“加载图片失败”的致命问题；
  - 自动读取并矫正相机拍摄照片的 EXIF 旋转角度（竖屏/横屏拍摄方向）。
- **杜绝二次打开流引发的权限与读取异常**：
  - 采用流安全单次捕获与本地沙箱缓存降级机制，规避不同厂商相册临时 Uri 在重复执行 `openInputStream` 时发生流中断或失效的问题。
- **适配系统级 Photo Picker 与异步多线程解码**：
  - 在 Android 13+ (API 33+) 优先拉起系统原生照片选择器（`MediaStore.ACTION_PICK_IMAGES`），并向下兼容 SAF 存储访问框架；
  - 兼容单选时通过 `ClipData` 传递图片 Uri 的定制机型；
  - 图片采样与压缩处理全面移至后台线程，彻底杜绝主线程大图解码引起的 UI 卡顿。
