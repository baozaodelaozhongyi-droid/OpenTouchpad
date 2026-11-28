# OpenTouchpad v0.5.66

### 主设置页升级 Material 3 / Flowmix 容器卡片风格与前置矢量图标

- **Material 3 Expressive 容器卡片 (M3 Surface Container)**：
  - 卡片圆角全面升级为 **24dp**（M3 标准超大圆角）；
  - 移除原有的 1dp 高亮线框描边，改为纯正的无边框实体容器色阶设计，卡片间距拉大到 14dp，界面更温润柔和、通透自然。
- **卡片头部主题色前置矢量图标 (Header Vector Icons)**：
  - 8 大核心功能卡片头部全面增设前置彩色矢量图标：
    - 「外观与主题」：图层叠加 (`ic_lu_layers_2`)
    - 「触控板」：专属触控板 (`ic_lu_touchpad`)
    - 「按钮」：鼠标指针点击 (`ic_lu_mouse_pointer_click`)
    - 「悬浮球」：立体圆环圆点 (`ic_lu_circle_dot`)
    - 「悬浮球手势」：手势交互 (`ic_lu_hand`)
    - 「光标」：光标指针 (`ic_lu_pointer`)
    - 「手感与控制」：移动方向控制 (`ic_lu_move`)
    - 「关于与无障碍」：设置服务 (`ic_lu_settings`)
  - 前置图标全面联动动态强调色（`accentColor`），在深色背景下呈现流光点缀。
- **标题排版与状态摘要胶囊精细化调整**：
  - 标题字型切换为现代无衬线中粗体（`sans-serif-medium`），与 Material 3 及 Flowmix 整体视觉保持高度统一；
  - 状态摘要胶囊（Status Chip）去除多余描边，回归深色药丸原生质感；
  - 标题点击水波纹区域适配 24dp 圆角边缘，点击反馈更加舒适。
