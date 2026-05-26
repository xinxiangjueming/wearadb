# wear-adb

无线 ADB 调试工具，基于 Jetpack Compose 构建。

## 功能

- **无线连接** — 手动输入 IP:端口 / NSD 自动发现 / Android 11+ 配对码配对
- **Shell 终端** — 交互式命令执行
- **设备信息** — 型号/系统/电量/屏幕/内存
- **应用管理** — 列表/搜索/卸载/清除数据/禁用/启用
- **文件管理** — 目录浏览/查看/删除/Push/Pull
- **高级操作** — 截屏/重启/音量/WiFi/蓝牙/导航键/媒体控制

## 技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Hilt |
| ADB 协议 | 纯 Kotlin 实现（无系统二进制依赖） |
| 持久化 | DataStore Preferences |
| 网络发现 | Android NSD (mDNS) |

---

## 文件说明

### 🔧 Gradle 构建

```
build.gradle.kts                 根构建脚本，声明插件版本（Kotlin, Hilt, Compose）
settings.gradle.kts              项目设置，镜像源配置（腾讯+阿里云），模块声明
gradle.properties                Gradle JVM 参数和 AndroidX 配置
gradle/wrapper/
  gradle-wrapper.properties      Gradle 发行版地址（腾讯镜像）
app/
  build.gradle.kts               应用构建脚本，依赖声明（Compose, Hilt, DataStore, Coroutines）
  proguard-rules.pro             R8 混淆规则
```

### 📦 应用入口

```
app/src/main/
  AndroidManifest.xml            权限声明（INTERNET, WIFI, NSD），Activity 注册，configChanges 配置
  res/values/
    strings.xml                  应用名称
    colors.xml                   颜色资源（圆角适配用）
    themes.xml                   系统主题（NoActionBar 基础主题）
  res/drawable/
    ic_launcher_foreground.xml   启动图标前景（WiFi 波 + 终端符号）
  res/mipmap-hdpi/
    ic_launcher.xml              自适应图标配置
```

### 🧠 ADB 协议层 (`adb/`)

```
adb/AdbMessage.kt
  ADB 协议二进制消息格式。
  定义 24 字节头结构（command, arg0, arg1, data_length, data_crc32, magic）。
  命令常量：CNXN / AUTH / OPEN / OKAY / WRTE / CLSE。
  负责消息的序列化（toBytes）和反序列化（fromBytes）。

adb/AdbClient.kt
  ADB 协议客户端核心。
  管理 TCP Socket 连接、读写循环、认证流程。
  提供 openShell() 打开 shell 流，runCommand() 执行单条命令并返回输出。
  处理 CNXN → AUTH → OPEN → WRTE → OKAY → CLSE 完整协议握手。
  连接状态通过 StateFlow 暴露（DISCONNECTED / CONNECTING / AUTHENTICATING / CONNECTED / ERROR）。

adb/AdbKeyManager.kt
  RSA 密钥对管理。
  生成 2048 位 RSA 密钥对用于 ADB 认证。
  提供 formatAdbPublicKey() 生成 ADB 格式的公钥字符串。
  提供 signToken() 对 AUTH TOKEN 进行 SHA1withRSA 签名。
  密钥缓存在内存中，进程重启后重新生成。

adb/AdbOutputParser.kt
  ADB shell 输出解析器。
  parseDeviceInfo() — 解析 getprop / dumpsys battery / wm size 等输出为 DeviceInfo 对象。
  parsePackageList() — 解析 pm list packages -f 输出为 AppEntry 列表。
  parseFileListing() — 解析 ls -la 输出为 FileEntry 列表。
  parseDiskUsage() — 解析 df -h 输出。

adb/NsdDiscoveryManager.kt
  网络服务发现（NSD/mDNS）。
  扫描局域网中的 _adb-tls-connect._tcp（可连接设备）和 _adb-tls-pairing._tcp（需配对设备）。
  通过 StateFlow 实时暴露发现的设备列表和扫描状态。
  封装 NsdManager 的 discoverServices / resolveService 流程。

adb/PairingManager.kt
  Android 11+ 无线调试配对协议。
  连接设备配对端口，接收设备 RSA 公钥。
  用设备公钥加密配对码，发送本机公钥 + 加密配对码。
  返回 PairingResult（成功/失败 + 设备连接信息）。

adb/AdbFileSync.kt
  ADB SYNC 文件传输协议。
  push() — 通过 SEND → DATA（64KB 分块）→ DONE 流程推送文件到设备。
  pull() — 通过 RECV → DATA 接收流程从设备拉取文件。
  处理 SYNC_DATA / SYNC_DONE / SYNC_OKAY / SYNC_FAIL 响应。

adb/AdvancedOps.kt
  高级 ADB shell 操作封装。
  电源操作：reboot / reboot recovery / reboot bootloader / shutdown。
  显示操作：screenshot（screencap -p）/ screenOn / screenOff。
  输入操作：tap / swipe / keyEvent / text。
  连接操作：enableWifi / disableWifi / enableBluetooth / disableBluetooth。
  音量操作：volumeUp / volumeDown / volumeMute。
  内置 KeyCodes 常量（HOME=3, BACK=4, POWER=26 等）。
```

### 💾 数据层 (`data/`)

```
data/model/SavedDevice.kt
  已保存设备数据类。包含 host, port, name, lastConnected, isFavorite。
  address 属性返回 "host:port" 格式。

data/model/DeviceInfo.kt
  设备信息数据类。包含型号/品牌/Android版本/电量/屏幕/内存等全部字段。

data/model/AppEntry.kt
  应用条目数据类。包含 packageName, versionName, versionCode, isSystem, isEnabled。

data/model/FileEntry.kt
  文件条目数据类。包含 name, path, isDirectory, size, permissions, lastModified。

data/repository/DeviceRepository.kt
  设备数据持久化。基于 DataStore Preferences。
  保存/读取/删除已连接设备列表（JSON 序列化）。
  保存/读取上次连接的 IP 和端口。
  收藏/取消收藏设备。

data/repository/AdbRepository.kt
  ADB 操作仓库。整合 AdbClient + PairingManager + NsdDiscoveryManager + AdbFileSync + AdvancedOps。
  是 ViewModel 唯一的数据源。
  连接成功后自动保存设备记录 + 保存 IP + 关闭蓝牙。
  暴露所有 ADB 操作的 suspend 函数。
```

### 💉 依赖注入 (`di/`)

```
di/AppModule.kt
  Hilt 模块。提供 DeviceRepository 和 AdbRepository 的单例注入。
```

### 🎨 主题系统 (`ui/theme/`)

```
ui/theme/Color.kt
  颜色常量。定义灰色色阶（Gray50~Gray950）、强调色（Accent 绿色）、状态色（Error/Warning/Info）。
  分 Light/Dark 两组语义化颜色变量。

ui/theme/Type.kt
  排版系统。基于 Material 3 Typography，终端区域使用 FontFamily.Monospace。
  定义 displayLarge ~ labelSmall 全部文字样式。

ui/theme/Theme.kt
  主题核心文件。
  WearAdbColors 数据类 — 集中管理全部颜色 token（背景/文字/按钮/状态/边框/图标等 35+ 颜色字段）。
  WearAdbShape 数据类 — 集中管理圆角等形状参数。
  LightColors / DarkColors — 两套完整的颜色方案。
  WearAdbTheme object — 通过 CompositionLocal 提供 colors 和 shape。
  MaterialTheme 映射 — WearAdbColors 自动转换为 MaterialColorScheme。
  系统栏处理 — 透明状态栏/导航栏、displayCutout 避让、深浅色图标自动切换。

ui/theme/ScreenCorners.kt
  屏幕圆角适配。
  小米/Redmi 设备：读取系统 rounded_corner_radius_top / rounded_corner_radius_bottom 资源。
  其他设备：默认 28dp。
  结果缓存，避免重复查询。通过 screenCornerRadius() Composable 提供动态值。
```

### 🧩 通用组件 (`ui/components/`)

```
ui/components/Components.kt
  可复用 UI 组件库。
  WearCard — 圆角卡片容器（背景色 + 边框 + 可点击）。
  WearInput — 圆角输入框（等宽字体、placeholder、光标颜色跟随主题）。
  WearButton — 圆角按钮（三种变体：Primary / Secondary / Danger）。
  StatusDot — 状态指示点（绿色=活跃，灰色=非活跃）。
  SectionHeader — 分组标题。
  所有组件的颜色和圆角均从 WearAdbTheme 读取。
```

### 📱 屏幕 (`ui/screens/`)

```
ui/screens/HomeScreen.kt
  主页。
  连接区：IP 输入 + 端口输入 + 连接/断开按钮 + 状态指示。
  未连接时显示「发现设备」和「配对」快捷入口。
  已连接时显示 6 宫格工具入口（Shell / 设备 / 应用 / 文件 / 高级）。
  历史设备列表（收藏 / 删除 / 点击重连）。
  自动回填上次连接的 IP 和端口。

ui/screens/ShellScreen.kt
  交互式终端。
  等宽字体终端输出区域，支持自动滚动。
  底部命令输入栏 + 发送按钮。
  命令以绿色 $ 前缀显示，输出为默认文字色。

ui/screens/DeviceInfoScreen.kt
  设备信息展示。
  分组显示：基本信息 / 系统 / 屏幕 / 电池 / 内存。
  电池电量带图标和颜色指示（绿/黄/红）。
  支持手动刷新。

ui/screens/AppsScreen.kt
  应用管理。
  顶部搜索栏 + 三态筛选芯片（全部/系统/第三方）。
  应用卡片：包名 + 版本 + 系统/第三方标识点。
  点击展开操作按钮：停止 / 清除数据 / 禁用(启用) / 卸载。
  Snackbar 反馈操作结果。

ui/screens/FilesScreen.kt
  文件管理。
  路径栏显示当前路径 + 上级导航按钮。
  快捷路径芯片：/sdcard /data /system /tmp。
  文件列表：文件夹/文件图标 + 大小 + 权限 + 修改时间。
  点击文件展开操作：查看内容 / 删除。
  文件内容弹窗展示（等宽字体，最大 10000 字符）。

ui/screens/DiscoveryScreen.kt
  设备发现。
  NSD 扫描状态指示（扫描中/已停止）。
  分两组显示：可连接设备 / 需配对设备。
  一键连接或跳转配对页。
  空状态提示「确保设备已开启无线调试」。

ui/screens/PairingScreen.kt
  设备配对。
  操作说明卡片（3 步指引）。
  输入框：IP + 配对端口 + 6 位配对码。
  配对状态：Idle / Pairing / Success / Error。
  成功后自动返回，失败显示错误信息 + 重试按钮。

ui/screens/AdvancedOpsScreen.kt
  高级操作。
  分组按钮网格：
    显示 — 截屏 / 亮屏 / 息屏
    音量 — 增大 / 减小 / 静音
    连接 — 开/关 WiFi、开/关蓝牙
    导航 — Home / 返回 / 电源
    媒体 — 上一曲 / 播放暂停 / 下一曲
    电源 — 重启设备（正常/Recovery/Bootloader/关机选择弹窗）
  截屏弹窗显示结果和文件大小。
```

### 📐 导航 (`ui/navigation/`)

```
ui/navigation/NavGraph.kt
  Compose Navigation 路由图。
  8 个路由：HOME / SHELL / DEVICE_INFO / APPS / FILES / DISCOVERY / PAIRING / ADVANCED。
  管理页面跳转和返回栈。
```

### 🚀 入口

```
ui/AppViewModel.kt
  全局 ViewModel。Hilt 注入 AdbRepository。
  管理所有页面的状态：连接 / Shell / 设备信息 / 应用 / 文件 / 发现 / 配对 / 截屏。
  提供所有操作方法供 UI 层调用。
  页面切换时自动保存/恢复状态。

MainActivity.kt
  应用入口 Activity。
  启用 Edge-to-Edge 沉浸模式。
  设置 Compose 内容：WearAdbTheme + AppNavGraph。

WearAdbApp.kt
  Application 类。Hilt 入口点（@HiltAndroidApp）。
```

---

## 构建

用 Android Studio 打开项目根目录，Sync Gradle 后直接 Run。

最低 SDK：26 (Android 8.0)
目标 SDK：35 (Android 15)
