# Wear ADB

运行在 Android 手机/平板上的 ADB 调试工具，起因是调试手表，普通安卓设备同样适用。无线 ADB + 有线 USB ADB + Fastboot + 屏幕投屏，全部在一台没有 root、没有电脑的安卓设备上完成，基于 Jetpack Compose 构建。

## 功能

- **无线 ADB** — NSD 自动发现 / 手动输入 IP:端口 / Android 11+ 配对码配对
- **有线 USB ADB** — USB Host API 直连，无需网络、无需 root、无需 adb 二进制文件
- **屏幕投屏与控制** — 基于 scrcpy 协议：H.264 实时解码 + 触摸/按键/滚动注入，支持熄屏投屏、保持唤醒、横屏分栏
- **Shell 终端** — 命令执行 + Shizuku/Scene/黑阈/冰箱等十余个一键激活快捷指令；粘贴 host 侧 `adb shell …` 完整命令时自动剥离前缀
- **设备信息** — 型号/系统/序列号/电量/屏幕/内存/存储
- **应用管理** — 列表/搜索/卸载/清除数据/禁用/启用
- **文件管理** — 目录浏览/查看/删除/Push/Pull（中文文件名完整支持）
- **高级操作** — 重启(Recovery/Bootloader/关机)/截屏/音量/WiFi/蓝牙/导航键/媒体控制
- **Fastboot 模式** — USB 直连 Bootloader 设备，刷入/擦除/临时启动/解锁/锁定/OEM 命令

## 界面

- **深浅色主题** — 跟随系统或手动切换，圆形揭露过渡动画（匀速 400ms）
- **一镜到底导航** — 页面间共享元素揭露过渡
- **异形屏适配** — 自动读取小米/Redmi 系统圆角资源，圆角卡片与屏幕贴合
- **Edge-to-Edge** — 沉浸式状态栏/导航栏，自动避让刘海与挖孔
- **横屏分栏** — 投屏页横屏时画面与控制区分栏布局

## 技术架构

```
┌────────────────────────────────────────────────────────┐
│                  UI (Jetpack Compose)                   │
├───────────────────┬────────────────────────────────────┤
│   无线 ADB 通道    │          有线 USB ADB 通道          │
│   AdbRepository   │   UsbAdbRepository                 │
│   (libadb-android)│   (自实现 ADB over USB Host API)    │
├───────────────────┴────────────────────────────────────┤
│    屏幕投屏 (scrcpy server + H.264 解码 + 控制消息注入)    │
├────────────────────────────────────────────────────────┤
│              Fastboot (自实现 USB Host 协议栈)            │
└────────────────────────────────────────────────────────┘
```

无线 ADB 使用 [libadb-android](https://github.com/MuntashirAkon/libadb-android) 库（本地化 fork 修复了中文路径缺陷，见踩坑记录 #8）；有线 USB ADB、Fastboot、投屏控制通道均为自实现。

## 屏幕投屏与控制

投屏不走 `screenrecord` 之类的取巧方案，而是完整对接 [scrcpy](https://github.com/Genymobile/scrcpy) 服务器协议：

1. 把 scrcpy server 推送到设备 `/data/local/tmp`，`app_process` 启动；
2. 设备端 H.264 裸流经 ADB 通道传回，`MediaCodec` 硬解后渲染到 `SurfaceView`；
3. 反向控制走 scrcpy v2.7 控制线协议子集（大端 + 16 位定点浮点）：
   `INJECT_KEYCODE / INJECT_TEXT / INJECT_TOUCH / INJECT_SCROLL / BACK_OR_SCREEN_ON / SET_DISPLAY_POWER / ROTATE_DEVICE`。

**为什么不用 `adb shell input`**：每个动作都要新建 shell 流、设备端冷启 `app_process` 跑 input 程序，单次 300-600ms，且拖动过程无法注入；控制通道是建会话时就连好的持久连接，单向写、无往返、数毫秒到达。

投屏通道无关：`MirrorStream` 接口统一了 USB 自研流与无线 libadb 流的读写语义，两种连接方式都能投屏。可调参数：分辨率上限（max_size）、码率、帧率上限、熄屏投屏、保持唤醒；音频恒不转发（`audio=false`）。

## 有线 USB ADB 实现

通过 Android USB Host API 直接与设备的 ADB 接口通信，无需 root，无需 adb 二进制文件。

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

### 8. libadb-android 的 A_OPEN 长度缺陷（中文文件名失败）

**坑**: 上游 `AdbProtocol.generateOpen` 按**字符数**（`destination.length()`）分配缓冲区，实际写入的却是 UTF-8 **字节**。只要命令里有一个非 ASCII 字符（中文文件名/路径就是），写入必然越界抛 `BufferOverflowException`——且异常发生在 `sendPacket` 之前，命令根本没发到设备，表现为「删除/读取中文文件名静默失败」。上游 issue #25，3.1.1 仍未修。

解决：本地化 fork 该模块，按编码后的真实字节长度分配（判据：`utf8Len > charLen` 即中招）。

### 9. scrcpy 触摸消息里 actionButton 在 buttons 之前

**坑**: `INJECT_TOUCH` 消息 32 字节中，`actionButton` 位于 `buttons` 之前（control_msg.c: `buf[24]=action_button, buf[28]=buttons`）。顺序写反后设备侧会把按键位当成手势按钮，表现为**点击无效**且无任何报错。

## 开源参考

本项目参考了以下开源实现，感谢这些项目的作者：

| 项目 | 语言 | 用途 | 链接 |
|------|------|------|------|
| **libadb-android** | Java/Kotlin | 无线 ADB 连接/TLS/配对 | [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android) |
| **AdbLib** | Java | ADB 协议参考 (流多路复用、WRTE 处理) | [cgutman/AdbLib](https://github.com/cgutman/AdbLib) |
| **ya-webadb** | TypeScript | ADB 协议参考 (WebUSB 实现) | [yume-chan/ya-webadb](https://github.com/yume-chan/ya-webadb) |
| **ADB-SafeScan** | - | USB 读写技巧 (header/payload 分离发送) | [nickelchan/ADB-SafeScan](https://github.com/nickelchan/ADB-SafeScan) |
| **scrcpy** | C/Java | 投屏服务器协议与控制线协议 | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) |

### ADB 协议规范

- 官方协议文档：`platform/system/core/adb/protocol.txt` (AOSP 源码)
- 消息格式：24 字节 header (command, arg0, arg1, dataLength, checksum, magic) + 变长 payload
- 命令：CNXN / AUTH / OPEN / OKAY / WRTE / CLSE / SYNC

### 关键依赖

| 库 | 版本 | 用途 |
|---|------|------|
| libadb-android | 3.1.1 (本地化) | 无线 ADB (TLS/配对/Shell/Sync) |
| Conscrypt | 2.5.3 | TLS (libadb-android 需要 exportKeyingMaterial) |
| BouncyCastle | 1.78 | RSA 密钥对生成 + X.509 自签名证书 (有线 ADB 认证) |
| Hilt | 2.51.1 | 依赖注入 |
| Compose BOM | - | UI 框架 |
| kotlinx-serialization | 1.7.3 | JSON 序列化 |
| DataStore | 1.1.1 | 持久化偏好设置 |

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
│   ├── WearAdbConnectionManager.kt  无线 ADB 连接管理器
│   ├── ScreenMirrorEngine.kt      投屏会话状态机 (推送/启动/解码/控制)
│   ├── ScreenMirrorDecoder.kt     H.264 硬解码渲染 (MediaCodec + Surface)
│   ├── ScrcpyControlProtocol.kt   scrcpy v2.7 控制线协议编码 (触摸/按键/滚动注入)
│   ├── AdbOutputParser.kt         Shell 输出解析 (设备信息/包列表/文件列表/存储)
│   ├── ShellQuoting.kt            Shell 参数单引号包裹 (防拆词/防元字符展开)
│   └── AdvancedOps.kt             高级操作封装 (按键码常量)
├── fastboot/                   Fastboot 协议层
│   ├── FastbootManager.kt         USB 设备枚举 + fastboot 协议通信
│   └── FastbootRepository.kt      Fastboot 仓库层
├── data/
│   ├── model/                  数据模型
│   └── repository/             数据仓库 (无线 AdbRepository 等)
├── di/
│   └── AppModule.kt            Hilt 依赖注入
├── ui/
│   ├── components/             通用 UI 组件
│   ├── navigation/             导航 (11 个路由) + 一镜到底揭露过渡
│   ├── screens/                页面 (主页/配对/Shell/设备信息/应用/文件/投屏/高级/Fastboot/USB)
│   ├── theme/                  主题系统 (深浅色/揭露切换动画/圆角适配)
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
- **横屏** — 投屏页横屏分栏，画面与控制区并排
- **Edge-to-Edge** — 沉浸式状态栏/导航栏，自动避让刘海
- **有线 ADB / Fastboot** — 通过 USB Host API 直接通信，无需 root，无需 adb 二进制文件

## 姊妹项目

- [adbmanager](https://github.com/xinxiangjueming/adbmanager) — Windows 端 ADB 管理工具 (C# / WinUI 3)，与本项目的投屏遮罩、Shell 工具箱等功能互为呼应。
