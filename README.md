# Wear ADB

无线 ADB 调试工具 + 有线 Fastboot 刷机工具，基于 Jetpack Compose 构建。

## 功能

- **无线连接** — 手动输入 IP:端口 / NSD 自动发现 / Android 11+ 配对码配对
- **Shell 终端** — 交互式命令执行
- **设备信息** — 型号/系统/电量/屏幕/内存/存储
- **应用管理** — 列表/搜索/卸载/清除数据/禁用/启用
- **文件管理** — 目录浏览/查看/删除/Push/Pull，大文件截断提示
- **高级操作** — 截屏/重启/音量/WiFi/蓝牙/导航键/媒体控制
- **Fastboot 模式** — USB 有线连接 Bootloader 设备，支持刷入/擦除/重启/解锁

## Fastboot 功能详情

| 功能 | 命令 |
|------|------|
| 扫描设备 | 自动检测 USB 上的 fastboot 设备 |
| 刷入分区 | `fastboot flash <partition> <image>` |
| 擦除分区 | `fastboot erase <partition>` |
| 临时启动 | `fastboot boot <image>` |
| 解锁 Bootloader | `fastboot flashing unlock` |
| 锁定 Bootloader | `fastboot flashing lock` |
| 重启到系统 | `fastboot reboot` |
| 重启到 Recovery | `fastboot reboot-recovery` |
| 重启到 Bootloader | `fastboot reboot-bootloader` |
| OEM 命令 | `fastboot oem <command>` |
| 上传数据 | `fastboot stage <data>` |
| 下载数据 | `fastboot fetch` |
| 获取变量 | `fastboot getvar:all` |

## 技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Hilt |
| ADB 协议 | [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1 |
| Fastboot 协议 | Android USB Host API（自实现） |
| 加密 | Conscrypt 2.5.3 + BouncyCastle 1.78 |
| 持久化 | DataStore Preferences |
| 网络发现 | Android NSD (mDNS) |
| 序列化 | Kotlinx Serialization 1.7.3 |

## 环境要求

- **最低 SDK：** 26 (Android 8.0)
- **目标 SDK：** 35 (Android 15)
- **JDK：** 17
- **构建工具：** Android Studio + Gradle (Kotlin DSL)

## 项目结构

```
com.wearadb/
├── data/
│   ├── model/              数据模型
│   │   ├── DeviceInfo.kt       设备信息（型号/电量/内存等）
│   │   ├── AppEntry.kt         应用条目（包名/版本/状态）
│   │   ├── FileEntry.kt        文件条目（名称/路径/权限/大小）
│   │   └── SavedDevice.kt      已保存设备（IP/端口/收藏状态）
│   └── repository/         数据仓库
│       ├── AdbRepository.kt    ADB 操作核心，整合连接/配对/文件传输/高级操作
│       └── DeviceRepository.kt 设备持久化（DataStore）
├── fastboot/               Fastboot 协议层
│   ├── FastbootManager.kt      USB 设备枚举 + fastboot 协议通信
│   └── FastbootRepository.kt   Fastboot 仓库层（suspend 封装）
├── di/
│   └── AppModule.kt        Hilt 依赖注入模块
├── ui/
│   ├── components/         通用 UI 组件
│   │   └── Components.kt       WearCard / WearInput / WearButton / StatusDot
│   ├── navigation/         导航
│   │   └── NavGraph.kt         9 个路由（首页/Shell/设备/应用/文件/发现/配对/高级/Fastboot）
│   ├── screens/            页面
│   │   ├── HomeScreen.kt       主页（连接区 + 工具网格 + 历史设备）
│   │   ├── ShellScreen.kt      交互式终端
│   │   ├── DeviceInfoScreen.kt 设备信息展示
│   │   ├── AppsScreen.kt       应用管理（搜索/筛选/操作）
│   │   ├── FilesScreen.kt      文件管理（浏览/查看/Push/Pull）
│   │   ├── DiscoveryScreen.kt  NSD 设备发现
│   │   ├── PairingScreen.kt    Android 11+ 配对
│   │   ├── AdvancedOpsScreen.kt 高级操作面板
│   │   └── FastbootScreen.kt   Fastboot 模式（刷入/擦除/重启/解锁/传输）
│   ├── theme/              主题系统
│   │   ├── Color.kt            颜色常量（Light/Dark）
│   │   ├── Type.kt             排版系统（含等宽终端字体）
│   │   ├── Theme.kt            WearAdbTheme（35+ 颜色 token + 圆角参数）
│   │   ├── ScreenCorners.kt    屏幕圆角适配（小米/Redmi 专项）
│   │   └── WindowSizeHelper.kt 窗口尺寸辅助
│   ├── AppViewModel.kt     全局 ViewModel（状态管理 + 业务逻辑入口）
│   └── utils/              工具函数
│       └── FormatUtils.kt      文件大小格式化（B/KB/MB/GB/TB）
├── adb/                    ADB 协议辅助
│   ├── AdbOutputParser.kt      Shell 输出解析（设备信息/包列表/文件列表/磁盘用量）
│   ├── AdvancedOps.kt          高级操作封装（电源/截屏/输入/WiFi/蓝牙/音量）
│   ├── WearAdbConnectionManager.kt  ADB 连接管理器
│   └── MemoryReceiver.kt       内存信息接收器
├── WearAdbApp.kt           Application 入口（@HiltAndroidApp）
├── AppEntry.kt             应用入口
└── MainActivity.kt         Activity（Edge-to-Edge + Compose）
```

## 构建

```bash
# Android Studio
# 打开项目根目录 → Sync Gradle → Run

# 命令行
./gradlew assembleDebug
```

输出 APK 位于 `app/build/outputs/apk/debug/`。

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | ADB TCP 连接 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `ACCESS_WIFI_STATE` | WiFi 信息获取 |
| `CHANGE_WIFI_MULTICAST_STATE` | NSD/mDNS 服务发现 |
| `android.hardware.usb.host` | Fastboot USB 设备访问 |

## 适配说明

- **小米/Redmi 设备** — 自动读取系统圆角资源，适配异形屏
- **折叠屏/大屏** — `resizeableActivity` + `WindowSizeHelper` 自适应布局
- **Edge-to-Edge** — 沉浸式状态栏/导航栏，自动避让刘海
- **Fastboot** — 通过 USB Host API 直接与 Bootloader 设备通信，无需 root
