# Wear ADB

无线 ADB + 有线 USB ADB + Fastboot 调试工具，基于 Jetpack Compose 构建。

## 功能

- **无线 ADB** — 手动输入 IP:端口 / NSD 自动发现 / Android 11+ 配对码配对
- **有线 USB ADB** — 通过 USB 直连设备，无需网络，支持 USB 权限自动申请
- **Shell 终端** — 交互式命令执行 + Shizuku/Scene/黑阈一键激活
- **设备信息** — 型号/系统/电量/屏幕/内存/存储
- **应用管理** — 列表/搜索/卸载/清除数据/禁用/启用
- **文件管理** — 目录浏览/查看/删除/Push/Pull
- **高级操作** — 重启(Recovery/Bootloader/关机)/截屏/音量/WiFi/蓝牙/导航键/媒体控制
- **Fastboot 模式** — USB 有线连接 Bootloader 设备，支持刷入/擦除/重启/解锁

## 技术架构

```
┌─────────────────────────────────────────────────┐
│                   UI (Compose)                   │
├──────────────────┬──────────────────────────────┤
│  无线 ADB 通道    │       有线 USB ADB 通道       │
│  AdbRepository   │   UsbAdbRepository           │
│  (libadb-android)│   (自实现 ADB over USB Host)  │
├──────────────────┴──────────────────────────────┤
│              Fastboot (自实现 USB Host)           │
└─────────────────────────────────────────────────┘
```

无线 ADB 使用 [libadb-android](https://github.com/MuntashirAkon/libadb-android) 库，有线 USB ADB 和 Fastboot 均为自实现协议栈。

## 有线 USB ADB 实现

有线 ADB 通过 Android USB Host API 直接与设备的 ADB 接口通信，无需 root，无需 adb 二进制文件。

### 协议流程

```
手机 (Host)                      手表/设备 (Device)
    │                                  │
    │──── CNXN (version=maxPayload) ──→│
    │←─── AUTH (TOKEN, 20 bytes) ─────│
    │──── AUTH (SIGNATURE, RSA) ──────→│
    │←─── AUTH (TOKEN) ───────────────│  (签名被拒)
    │──── AUTH (PUBLIC_KEY) ──────────→│
    │←── 用户在设备上确认授权 ──────────│
    │←─── CNXN (system identity) ─────│
    │                                  │
    │  连接建立，可以打开 shell/sync 流  │
    │                                  │
    │──── OPEN (localId, "shell:cmd") →│
    │←─── OKAY (remoteId, localId) ───│
    │←─── WRTE (data chunks) ─────────│  (命令输出)
    │──── OKAY (ACK) ─────────────────→│
    │←─── CLSE ───────────────────────│  (命令结束)
```

### 核心类

| 类 | 职责 |
|---|------|
| `UsbAdbManager` | USB 设备枚举、权限申请、连接生命周期 |
| `UsbAdbConnection` | ADB 协议握手 (CNXN/AUTH)、流多路复用 (OPEN/OKAY/WRTE/CLSE) |
| `UsbAdbTransport` | USB 传输层，UsbRequest 异步读 + bulkTransfer 写 |
| `UsbAdbStream` | 单个 ADB 流，LinkedBlockingQueue 收集 WRTE 数据 |
| `UsbAdbProtocol` | ADB 消息编解码 (24 字节 header + payload) |

### USB 设备识别

ADB 接口通过 USB interface descriptor 识别：

```kotlin
interfaceClass    = 0xFF  // Vendor-specific
interfaceSubclass = 0x42  // ADB
interfaceProtocol = 0x01  // ADB
```

Fastboot 使用相同的 class/subclass，但 protocol 为 `0x03`。

## 踩坑记录

### 1. ADB 协议中 arg0/arg1 的方向问题

**坑**: ADB 消息的 `arg0`/`arg1` 在不同方向含义不同。

- **Host → Device** (OPEN): `arg0` = host localId, `arg1` = 0
- **Device → Host** (OKAY/WRTE/CLSE): `arg0` = device localId, `arg1` = host localId

如果把 Device 发来的 OKAY 的 `arg0` 当作 host localId 去查找流，会找不到（因为 `arg0` 是设备端的 ID）。正确做法是用 `arg1` 查找 host 端的流。

参考实现：[cgutman/AdbLib](https://github.com/cgutman/AdbLib) 中 `AdbConnection.java` 的 `handleMessage` 方法。

### 2. UsbRequest 的缓冲区位置不可靠

**坑**: `UsbRequest.queue(ByteBuffer, int)` 完成后，`ByteBuffer.position()` 不一定正确更新。

Android 文档说会更新，但部分设备/系统版本行为不一致。稳妥做法是结合 `request.getActual()` 和 `buf.position()` 交叉验证。目前实测 `buf.position()` 在多数设备上可用。

### 3. USB 读写必须分开传 header 和 payload

**坑**: ADB 消息的 header (24 字节) 和 payload 必须分两次 `bulkTransfer` 发送，合并成一个 buffer 发送会导致设备端解析失败。

参考 [ADB-SafeScan](https://github.com/nickelchan/ADB-SafeScan) 的注释：
> "writing header+payload as a single buffer produces different results from writing them separately"

### 4. 流关闭时的 sentinel 机制

**坑**: Shell 命令执行完毕后设备发 CLSE，但调用方可能还在 `readAll()` 中阻塞等待数据。

解决方案：`onClosed()` 向 `LinkedBlockingQueue` 中放入一个空的 `ByteArray(0)` 作为 sentinel，`readAll()` 检测到空数组即退出循环。

### 5. USB 端点需要先清空残留数据

**坑**: 连接建立前 IN 端点可能有残留数据，不清空会导致协议解析错乱。

连接流程中需要：
1. `CLEAR_HALT` IN/OUT 端点
2. 循环 `bulkTransfer` 读取 IN 端点直到返回 <= 0 字节
3. 然后再开始 ADB 握手

### 6. 认证流程：签名 → 公钥

**坑**: ADB 认证先尝试 RSA 签名，如果设备不认识这个 key（返回 AUTH 而非 CNXN），需要再发送公钥，然后等用户在设备上点击"允许"。

整个过程需要串行等待，不能并发。超时时间建议 15 秒（用户需要时间在设备上确认）。

### 7. ViewModel 生命周期与单例资源管理

**坑**: `@Singleton` 的 Repository 不应在 ViewModel 的 `onCleared()` 中调用 `destroy()`。Compose Navigation 中 ViewModel 可能在页面切换时被清理，但 USB 连接应该由 Application 生命周期管理。

错误做法：
```kotlin
override fun onCleared() {
    usbAdbRepository.destroy()  // 会断开 USB 连接!
}
```

正确做法：`onCleared()` 只清理 ViewModel 自身的资源（如无线 ADB 的 stream），单例的生命周期交给 Application 或显式的用户操作（如点击"断开"按钮）。

## 开源参考

本项目参考了以下开源实现，感谢这些项目的作者：

| 项目 | 语言 | 用途 | 链接 |
|------|------|------|------|
| **libadb-android** | Java/Kotlin | 无线 ADB 连接/TLS/配对 | [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android) |
| **AdbLib** | Java | ADB 协议参考 (流多路复用、WRTE 处理) | [cgutman/AdbLib](https://github.com/cgutman/AdbLib) |
| **ya-webadb** | TypeScript | ADB 协议参考 (WebUSB 实现) | [yume-chan/ya-webadb](https://github.com/yume-chan/ya-webadb) |
| **ADB-SafeScan** | - | USB 读写技巧 (header/payload 分离发送) | [nickelchan/ADB-SafeScan](https://github.com/nickelchan/ADB-SafeScan) |

### ADB 协议规范

- 官方协议文档：`platform/system/core/adb/protocol.txt` (AOSP 源码)
- 消息格式：24 字节 header (command, arg0, arg1, dataLength, checksum, magic) + 变长 payload
- 命令：CNXN / AUTH / OPEN / OKAY / WRTE / CLSE / SYNC

### 关键依赖

| 库 | 版本 | 用途 |
|---|------|------|
| libadb-android | 3.1.1 | 无线 ADB (TLS/配对/Shell/Sync) |
| Conscrypt | 2.5.3 | TLS (libadb-android 需要 exportKeyingMaterial) |
| BouncyCastle | 1.78 | RSA 密钥对生成 + X.509 自签名证书 (有线 ADB 认证) |
| Hilt | 2.51.1 | 依赖注入 |
| Compose BOM | - | UI 框架 |
| kotlinx-serialization | 1.7.3 | JSON 序列化 |
| DataStore | 1.1.1 | 持久化偏好设置 |

## Fastboot 功能

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

## 环境要求

- **最低 SDK：** 26 (Android 8.0)
- **目标 SDK：** 35 (Android 15)
- **JDK：** 17
- **构建工具：** Android Studio + Gradle (Kotlin DSL)

## 项目结构

```
com.wearadb/
├── adb/                        ADB 协议层
│   ├── UsbAdbProtocol.kt          ADB 消息编解码 (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE)
│   ├── UsbAdbTransport.kt         USB 传输层 (UsbRequest 异步读 + bulkTransfer 写)
│   ├── UsbAdbConnection.kt        ADB 协议连接 (握手 + 流多路复用 + 读取线程)
│   ├── UsbAdbStream.kt            ADB 流 (LinkedBlockingQueue + sentinel 关闭)
│   ├── UsbAdbManager.kt           USB 设备枚举 + 权限 + 连接生命周期
│   ├── UsbAdbRepository.kt        有线 ADB 仓库 (设备信息/Shell/包管理)
│   ├── AdbOutputParser.kt         Shell 输出解析 (设备信息/包列表/文件列表/存储)
│   ├── AdvancedOps.kt             高级操作封装 (按键码常量)
│   ├── WearAdbConnectionManager.kt  无线 ADB 连接管理器
│   └── MemoryReceiver.kt          内存信息接收器
├── fastboot/                   Fastboot 协议层
│   ├── FastbootManager.kt         USB 设备枚举 + fastboot 协议通信
│   └── FastbootRepository.kt      Fastboot 仓库层
├── data/
│   ├── model/                  数据模型
│   └── repository/             数据仓库
├── di/
│   └── AppModule.kt            Hilt 依赖注入
├── ui/
│   ├── components/             通用 UI 组件
│   ├── navigation/NavGraph.kt  导航 (10 个路由)
│   ├── screens/                页面
│   ├── theme/                  主题系统
│   ├── AppViewModel.kt         全局 ViewModel
│   └── utils/                  工具函数
├── WearAdbApp.kt               Application 入口
└── MainActivity.kt             Activity
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
| `android.hardware.usb.host` | USB 设备访问 (有线 ADB + Fastboot) |

## 适配说明

- **小米/Redmi 设备** — 自动读取系统圆角资源，适配异形屏
- **折叠屏/大屏** — `resizeableActivity` + `WindowSizeHelper` 自适应布局
- **Edge-to-Edge** — 沉浸式状态栏/导航栏，自动避让刘海
- **有线 ADB** — 通过 USB Host API 直接通信，无需 root，无需 adb 二进制文件
- **Fastboot** — 通过 USB Host API 直接与 Bootloader 设备通信，无需 root
