package com.wearadb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.wearadb.R

// 字符串资源按屏幕分组。原单一 Strings data class 有 260+ 个构造参数，
// rememberStrings() 作为 @Composable 还会被 Compose 编译器追加多个 int changed-mask
// 参数，逼近 DEX 单指令 255 寄存器上限——release 包（R8 混淆）在真机上触发
// java.lang.VerifyError（调用点参数寄存器数与方法签名不一致）导致启动即崩。
// 拆分后每组参数量 ≤49，外层包装类仅 12 个字段，远离危险阈值。

@Immutable
data class HomeStrings(
    val homeSubtitle: String,
    val sectionTools: String,
    val sectionHistory: String,
    val sectionUsbDebug: String,
    val usbDebugDesc: String,
    val featureShell: String,
    val featureShellDesc: String,
    val featureDevice: String,
    val featureDeviceDesc: String,
    val featureApps: String,
    val featureAppsDesc: String,
    val featureFiles: String,
    val featureFilesDesc: String,
    val featureAdvanced: String,
    val featureAdvancedDesc: String,
    val featureFastboot: String,
    val featureFastbootDesc: String,
    val featureUsbAdb: String,
    val featureUsbAdbDesc: String,
    val statusDisconnected: String,
    val statusConnecting: String,
    val statusAuth: String,
    val statusConnected: String,
    val statusError: String,
    val labelIp: String,
    val labelPort: String,
    val btnConnect: String,
    val btnConnecting: String,
    val btnDisconnect: String,
    val btnDiscover: String,
    val btnPair: String,
    val errorConnect: String,
    val btTitle: String,
    val btMessage: String,
    val btConfirm: String,
    val btDismiss: String,
    val actionFavorite: String,
    val actionDelete: String,
)

@Immutable
data class SharedStrings(
    val btnBack: String,
    val btnClose: String,
    val btnCancel: String,
    val btnRefresh: String,
    val btnSend: String,
)

@Immutable
data class ShellStrings(
    val shellTitle: String,
    val shellSubtitle: String,
    val shellWifiFix: String,
    val shellShizuku: String,
    val shellScene: String,
    val shellBrevent: String,
    val shellIceBox: String,
    val shellStopApp: String,
    val shellGreenify: String,
    val shellThanox: String,
    val shellAirFrozen: String,
    val shellFreezeYou: String,
    val shellIsland: String,
    val shellApkInstaller: String,
    val shellBlackHole: String,
    val shellSecondSpace: String,
    val shellHint: String,
    val shellInputHint: String,
)

@Immutable
data class DeviceInfoStrings(
    val deviceInfoTitle: String,
    val infoBasic: String,
    val infoBrand: String,
    val infoModel: String,
    val infoCodename: String,
    val infoSerial: String,
    val infoSystem: String,
    val infoFingerprint: String,
    val infoScreen: String,
    val infoResolution: String,
    val infoBattery: String,
    val infoDesignCapacity: String,
    val infoCurrentCapacity: String,
    val infoBatteryType: String,
    val infoHealth: String,
    val infoBatteryLevel: String,
    val infoBatteryStatus: String,
    val infoVoltage: String,
    val infoTemperature: String,
    val infoChipPlatform: String,
    val infoImei: String,
    val infoCycleCount: String,
    val infoBatteryHealthPct: String,
    val infoFlashLifespan: String,
    val infoMemStorage: String,
    val infoRam: String,
    val infoUsed: String,
    val infoAvailable: String,
    val infoTotal: String,
    val infoNoData: String,
    val infoInternalStorage: String,
    val infoNoDataRefresh: String,
)

@Immutable
data class AdvancedOpsStrings(
    val advancedTitle: String,
    val opsDisplay: String,
    val opsScreenshot: String,
    val opsScreenOn: String,
    val opsScreenOff: String,
    val opsVolume: String,
    val opsVolUp: String,
    val opsVolDown: String,
    val opsVolMute: String,
    val opsConnectivity: String,
    val opsWifiOn: String,
    val opsWifiOff: String,
    val opsBtOn: String,
    val opsBtOff: String,
    val opsNavigation: String,
    val opsBack: String,
    val opsPower: String,
    val opsMedia: String,
    val opsPrev: String,
    val opsPlayPause: String,
    val opsNext: String,
    val opsRebootDevice: String,
    val opsSelectReboot: String,
    val opsRebootNormal: String,
    val opsShutdown: String,
    val opsWiredDebug: String,
    val opsFastbootMode: String,
    val screenshotSuccess: String,
    val screenshotFailed: String,
    val screenshotSave: String,
)

@Immutable
data class MirrorStrings(
    val mirrorTitle: String,
    val mirrorStatusIdle: String,
    val mirrorStatusStarting: String,
    val mirrorStatusStreaming: String,
    val mirrorRetry: String,
    val mirrorStop: String,
    val mirrorResolution: (String, String) -> String,
    val mirrorOptionSize: String,
    val mirrorOptionBitrate: String,
    val mirrorOptionFps: String,
    val mirrorOptionShape: String,
    val mirrorShapeRect: String,
    val mirrorShapeCircle: String,
    val mirrorAuto: String,
    val mirrorOptionToggles: String,
    val mirrorOptionReadonly: String,
    val mirrorOptionScreenOff: String,
    val mirrorOptionStayAwake: String,
    val mirrorKeyHome: String,
    val mirrorKeyBack: String,
    val mirrorKeyRecents: String,
    val mirrorKeyPower: String,
    val mirrorKeyVolUp: String,
    val mirrorKeyVolDown: String,
    val mirrorOptions: String,
)

@Immutable
data class PairingStrings(
    val pairTitle: String,
    val pairWirelessTitle: String,
    val pairStep1: String,
    val pairStep2: String,
    val pairStep3: String,
    val pairPortNote: String,
    val labelPairCode: String,
    val pairCodePlaceholder: String,
    val btnPairing: String,
    val btnPairingProgress: String,
    val pairFailed: String,
    val pairTips: String,
    val pairTip1: String,
    val pairTip2: String,
    val pairTip3: String,
    val pairTip4: String,
    val btnRetry: String,
    val manualConnectTitle: String,
)

@Immutable
data class AppsStrings(
    val appsTitle: String,
    val appsInstallApk: String,
    val appsSearchHint: String,
    val appsFilterAll: String,
    val appsFilterSystem: String,
    val appsFilterThird: String,
    val appsFilterDisabled: String,
    val appsSystemCount: (Int) -> String,
    val appsThirdCount: (Int) -> String,
    val appsDisabledCount: (Int) -> String,
    val appsDisabled: String,
    val appsActionStop: String,
    val appsActionClear: String,
    val appsActionDisable: String,
    val appsActionEnable: String,
    val appsActionUninstall: String,
    val appsActionExtract: String,
    val appsActionUninstallKeep: String,
    val appsNotConnected: String,
    val appsOpNoOutput: String,
    val appsExtractRunning: (String) -> String,
    val appsExtractSaved: (String) -> String,
    val appsExtractNoPath: String,
    val appsExtractFailed: (String) -> String,
    val appsPickerTimeout: String,
)

@Immutable
data class FilesStrings(
    val filesTitle: String,
    val filesPushHint: String,
    val filesParent: String,
    val filesEmpty: String,
    val filesSize: (String) -> String,
    val filesPermission: (String) -> String,
    val filesModified: (String) -> String,
    val filesActionView: String,
    val filesActionPull: String,
    val filesActionInstall: String,
    val filesActionDelete: String,
    val filesContentTitle: String,
    val filesContentTooLong: (Int, Int) -> String,
)

@Immutable
data class DiscoveryStrings(
    val discoveryTitle: String,
    val discoveryScanning: String,
    val discoveryStopped: String,
    val discoveryConnectable: (Int) -> String,
    val discoveryPairable: (Int) -> String,
    val discoveryEmptyTitle: String,
    val discoveryEmptyHint: String,
    val discoveryNoPair: String,
    val discoveryNoPairHint: String,
    val discoveryActionConnect: String,
    val discoveryActionPair: String,
    val btnStop: String,
    val btnScan: String,
)

@Immutable
data class FastbootStrings(
    val fbModeTitle: String,
    val fbReboot: String,
    val fbRebootSystem: String,
    val fbRebootRecovery: String,
    val fbRebootBootloader: String,
    val fbPartitionOps: String,
    val fbFlashPartition: String,
    val fbErasePartition: String,
    val fbOemTitle: String,
    val fbOemExec: String,
    val fbUnlock: String,
    val fbLock: String,
    val fbAdvancedTransfer: String,
    val fbTempBoot: String,
    val fbStage: String,
    val fbFetch: String,
    val fbDeviceVars: String,
    val fbGetvarAll: String,
    val fbFlashSuccessTitle: String,
    val fbFlashSuccessMsg: (String) -> String,
    val fbStayFastboot: String,
    val fbUsbHint: String,
    val fbUsbPermission: String,
    val fbDevicesHeader: String,
    val fbEmptyHint: String,
    val fbSerial: (String) -> String,
    val fbFlashing: String,
    val fbConnectLog: String,
    val fbOemDialogTitle: String,
    val fbCmdLabel: String,
    val fbCmdPlaceholder: String,
    val fbExecute: String,
    val fbEraseTitle: String,
    val fbEraseWarning: String,
    val fbSelectPartition: String,
    val fbPartitionName: String,
    val fbManualInput: String,
    val fbEraseConfirm: String,
    val fbFlashTitle: String,
    val fbSelectImage: String,
    val fbFlashConfirm: String,
    val fbTempBootTitle: String,
    val fbTempBootWarning: String,
    val fbSelectBoot: String,
    val fbBootConfirm: String,
    val fbStageTitle: String,
    val fbStageDesc: String,
    val fbSelectUpload: String,
    val fbStageConfirm: String,
)

@Immutable
data class UsbAdbStrings(
    val usbConnectLog: String,
    val usbDescInfo: String,
    val usbDescShell: String,
    val usbDescApps: String,
    val usbDescFiles: String,
    val usbDescAdvanced: String,
    val usbConnectedFmt: (String) -> String,
    val usbNotConnected: String,
    val usbDetectedDevices: String,
    val usbNoDeviceHint: String,
    val usbSerial: (String) -> String,
    val usbRebootFastboot: String,
    val usbRebootFastbootDesc: String,
)

/**
 * 平铺外观类：保留原有 [Strings] 的扁平属性访问方式（s.xxx），
 * UI 层各 Screen 无需任何改动。仅 12 个分组字段，构造调用远离 DEX 寄存器上限。
 */
@Immutable
class Strings(
    val home: HomeStrings,
    val shared: SharedStrings,
    val shell: ShellStrings,
    val deviceInfo: DeviceInfoStrings,
    val advanced: AdvancedOpsStrings,
    val mirror: MirrorStrings,
    val pairing: PairingStrings,
    val apps: AppsStrings,
    val files: FilesStrings,
    val discovery: DiscoveryStrings,
    val fastboot: FastbootStrings,
    val usbAdb: UsbAdbStrings,
) {
    // HomeScreen
    val homeSubtitle get() = home.homeSubtitle
    val sectionTools get() = home.sectionTools
    val sectionHistory get() = home.sectionHistory
    val sectionUsbDebug get() = home.sectionUsbDebug
    val usbDebugDesc get() = home.usbDebugDesc
    val featureShell get() = home.featureShell
    val featureShellDesc get() = home.featureShellDesc
    val featureDevice get() = home.featureDevice
    val featureDeviceDesc get() = home.featureDeviceDesc
    val featureApps get() = home.featureApps
    val featureAppsDesc get() = home.featureAppsDesc
    val featureFiles get() = home.featureFiles
    val featureFilesDesc get() = home.featureFilesDesc
    val featureAdvanced get() = home.featureAdvanced
    val featureAdvancedDesc get() = home.featureAdvancedDesc
    val featureFastboot get() = home.featureFastboot
    val featureFastbootDesc get() = home.featureFastbootDesc
    val featureUsbAdb get() = home.featureUsbAdb
    val featureUsbAdbDesc get() = home.featureUsbAdbDesc
    val statusDisconnected get() = home.statusDisconnected
    val statusConnecting get() = home.statusConnecting
    val statusAuth get() = home.statusAuth
    val statusConnected get() = home.statusConnected
    val statusError get() = home.statusError
    val labelIp get() = home.labelIp
    val labelPort get() = home.labelPort
    val btnConnect get() = home.btnConnect
    val btnConnecting get() = home.btnConnecting
    val btnDisconnect get() = home.btnDisconnect
    val btnDiscover get() = home.btnDiscover
    val btnPair get() = home.btnPair
    val errorConnect get() = home.errorConnect
    val btTitle get() = home.btTitle
    val btMessage get() = home.btMessage
    val btConfirm get() = home.btConfirm
    val btDismiss get() = home.btDismiss
    val actionFavorite get() = home.actionFavorite
    val actionDelete get() = home.actionDelete
    // Shared
    val btnBack get() = shared.btnBack
    val btnClose get() = shared.btnClose
    val btnCancel get() = shared.btnCancel
    val btnRefresh get() = shared.btnRefresh
    val btnSend get() = shared.btnSend
    // ShellScreen
    val shellTitle get() = shell.shellTitle
    val shellSubtitle get() = shell.shellSubtitle
    val shellWifiFix get() = shell.shellWifiFix
    val shellShizuku get() = shell.shellShizuku
    val shellScene get() = shell.shellScene
    val shellBrevent get() = shell.shellBrevent
    val shellIceBox get() = shell.shellIceBox
    val shellStopApp get() = shell.shellStopApp
    val shellGreenify get() = shell.shellGreenify
    val shellThanox get() = shell.shellThanox
    val shellAirFrozen get() = shell.shellAirFrozen
    val shellFreezeYou get() = shell.shellFreezeYou
    val shellIsland get() = shell.shellIsland
    val shellApkInstaller get() = shell.shellApkInstaller
    val shellBlackHole get() = shell.shellBlackHole
    val shellSecondSpace get() = shell.shellSecondSpace
    val shellHint get() = shell.shellHint
    val shellInputHint get() = shell.shellInputHint
    // DeviceInfoScreen
    val deviceInfoTitle get() = deviceInfo.deviceInfoTitle
    val infoBasic get() = deviceInfo.infoBasic
    val infoBrand get() = deviceInfo.infoBrand
    val infoModel get() = deviceInfo.infoModel
    val infoCodename get() = deviceInfo.infoCodename
    val infoSerial get() = deviceInfo.infoSerial
    val infoSystem get() = deviceInfo.infoSystem
    val infoFingerprint get() = deviceInfo.infoFingerprint
    val infoScreen get() = deviceInfo.infoScreen
    val infoResolution get() = deviceInfo.infoResolution
    val infoBattery get() = deviceInfo.infoBattery
    val infoDesignCapacity get() = deviceInfo.infoDesignCapacity
    val infoCurrentCapacity get() = deviceInfo.infoCurrentCapacity
    val infoBatteryType get() = deviceInfo.infoBatteryType
    val infoHealth get() = deviceInfo.infoHealth
    val infoBatteryLevel get() = deviceInfo.infoBatteryLevel
    val infoBatteryStatus get() = deviceInfo.infoBatteryStatus
    val infoVoltage get() = deviceInfo.infoVoltage
    val infoTemperature get() = deviceInfo.infoTemperature
    val infoChipPlatform get() = deviceInfo.infoChipPlatform
    val infoImei get() = deviceInfo.infoImei
    val infoCycleCount get() = deviceInfo.infoCycleCount
    val infoBatteryHealthPct get() = deviceInfo.infoBatteryHealthPct
    val infoFlashLifespan get() = deviceInfo.infoFlashLifespan
    val infoMemStorage get() = deviceInfo.infoMemStorage
    val infoRam get() = deviceInfo.infoRam
    val infoUsed get() = deviceInfo.infoUsed
    val infoAvailable get() = deviceInfo.infoAvailable
    val infoTotal get() = deviceInfo.infoTotal
    val infoNoData get() = deviceInfo.infoNoData
    val infoInternalStorage get() = deviceInfo.infoInternalStorage
    val infoNoDataRefresh get() = deviceInfo.infoNoDataRefresh
    // AdvancedOpsScreen
    val advancedTitle get() = advanced.advancedTitle
    val opsDisplay get() = advanced.opsDisplay
    val opsScreenshot get() = advanced.opsScreenshot
    val opsScreenOn get() = advanced.opsScreenOn
    val opsScreenOff get() = advanced.opsScreenOff
    val opsVolume get() = advanced.opsVolume
    val opsVolUp get() = advanced.opsVolUp
    val opsVolDown get() = advanced.opsVolDown
    val opsVolMute get() = advanced.opsVolMute
    val opsConnectivity get() = advanced.opsConnectivity
    val opsWifiOn get() = advanced.opsWifiOn
    val opsWifiOff get() = advanced.opsWifiOff
    val opsBtOn get() = advanced.opsBtOn
    val opsBtOff get() = advanced.opsBtOff
    val opsNavigation get() = advanced.opsNavigation
    val opsBack get() = advanced.opsBack
    val opsPower get() = advanced.opsPower
    val opsMedia get() = advanced.opsMedia
    val opsPrev get() = advanced.opsPrev
    val opsPlayPause get() = advanced.opsPlayPause
    val opsNext get() = advanced.opsNext
    val opsRebootDevice get() = advanced.opsRebootDevice
    val opsSelectReboot get() = advanced.opsSelectReboot
    val opsRebootNormal get() = advanced.opsRebootNormal
    val opsShutdown get() = advanced.opsShutdown
    val opsWiredDebug get() = advanced.opsWiredDebug
    val opsFastbootMode get() = advanced.opsFastbootMode
    val screenshotSuccess get() = advanced.screenshotSuccess
    val screenshotFailed get() = advanced.screenshotFailed
    val screenshotSave get() = advanced.screenshotSave
    // ScreenMirrorScreen（B1 有线投屏）
    val mirrorTitle get() = mirror.mirrorTitle
    val mirrorStatusIdle get() = mirror.mirrorStatusIdle
    val mirrorStatusStarting get() = mirror.mirrorStatusStarting
    val mirrorStatusStreaming get() = mirror.mirrorStatusStreaming
    val mirrorRetry get() = mirror.mirrorRetry
    val mirrorStop get() = mirror.mirrorStop
    val mirrorResolution get() = mirror.mirrorResolution
    val mirrorOptionSize get() = mirror.mirrorOptionSize
    val mirrorOptionBitrate get() = mirror.mirrorOptionBitrate
    val mirrorOptionFps get() = mirror.mirrorOptionFps
    val mirrorOptionShape get() = mirror.mirrorOptionShape
    val mirrorShapeRect get() = mirror.mirrorShapeRect
    val mirrorShapeCircle get() = mirror.mirrorShapeCircle
    val mirrorAuto get() = mirror.mirrorAuto
    val mirrorOptionToggles get() = mirror.mirrorOptionToggles
    val mirrorOptionReadonly get() = mirror.mirrorOptionReadonly
    val mirrorOptionScreenOff get() = mirror.mirrorOptionScreenOff
    val mirrorOptionStayAwake get() = mirror.mirrorOptionStayAwake
    val mirrorKeyHome get() = mirror.mirrorKeyHome
    val mirrorKeyBack get() = mirror.mirrorKeyBack
    val mirrorKeyRecents get() = mirror.mirrorKeyRecents
    val mirrorKeyPower get() = mirror.mirrorKeyPower
    val mirrorKeyVolUp get() = mirror.mirrorKeyVolUp
    val mirrorKeyVolDown get() = mirror.mirrorKeyVolDown
    val mirrorOptions get() = mirror.mirrorOptions
    // PairingScreen
    val pairTitle get() = pairing.pairTitle
    val pairWirelessTitle get() = pairing.pairWirelessTitle
    val pairStep1 get() = pairing.pairStep1
    val pairStep2 get() = pairing.pairStep2
    val pairStep3 get() = pairing.pairStep3
    val pairPortNote get() = pairing.pairPortNote
    val labelPairCode get() = pairing.labelPairCode
    val pairCodePlaceholder get() = pairing.pairCodePlaceholder
    val btnPairing get() = pairing.btnPairing
    val btnPairingProgress get() = pairing.btnPairingProgress
    val pairFailed get() = pairing.pairFailed
    val pairTips get() = pairing.pairTips
    val pairTip1 get() = pairing.pairTip1
    val pairTip2 get() = pairing.pairTip2
    val pairTip3 get() = pairing.pairTip3
    val pairTip4 get() = pairing.pairTip4
    val btnRetry get() = pairing.btnRetry
    val manualConnectTitle get() = pairing.manualConnectTitle
    // AppsScreen
    val appsTitle get() = apps.appsTitle
    val appsInstallApk get() = apps.appsInstallApk
    val appsSearchHint get() = apps.appsSearchHint
    val appsFilterAll get() = apps.appsFilterAll
    val appsFilterSystem get() = apps.appsFilterSystem
    val appsFilterThird get() = apps.appsFilterThird
    val appsFilterDisabled get() = apps.appsFilterDisabled
    val appsSystemCount get() = apps.appsSystemCount
    val appsThirdCount get() = apps.appsThirdCount
    val appsDisabledCount get() = apps.appsDisabledCount
    val appsDisabled get() = apps.appsDisabled
    val appsActionStop get() = apps.appsActionStop
    val appsActionClear get() = apps.appsActionClear
    val appsActionDisable get() = apps.appsActionDisable
    val appsActionEnable get() = apps.appsActionEnable
    val appsActionUninstall get() = apps.appsActionUninstall
    val appsActionExtract get() = apps.appsActionExtract
    val appsActionUninstallKeep get() = apps.appsActionUninstallKeep
    val appsNotConnected get() = apps.appsNotConnected
    val appsOpNoOutput get() = apps.appsOpNoOutput
    val appsExtractRunning get() = apps.appsExtractRunning
    val appsExtractSaved get() = apps.appsExtractSaved
    val appsExtractNoPath get() = apps.appsExtractNoPath
    val appsExtractFailed get() = apps.appsExtractFailed
    val appsPickerTimeout get() = apps.appsPickerTimeout
    // FilesScreen
    val filesTitle get() = files.filesTitle
    val filesPushHint get() = files.filesPushHint
    val filesParent get() = files.filesParent
    val filesEmpty get() = files.filesEmpty
    val filesSize get() = files.filesSize
    val filesPermission get() = files.filesPermission
    val filesModified get() = files.filesModified
    val filesActionView get() = files.filesActionView
    val filesActionPull get() = files.filesActionPull
    val filesActionInstall get() = files.filesActionInstall
    val filesActionDelete get() = files.filesActionDelete
    val filesContentTitle get() = files.filesContentTitle
    val filesContentTooLong get() = files.filesContentTooLong
    // DiscoveryScreen
    val discoveryTitle get() = discovery.discoveryTitle
    val discoveryScanning get() = discovery.discoveryScanning
    val discoveryStopped get() = discovery.discoveryStopped
    val discoveryConnectable get() = discovery.discoveryConnectable
    val discoveryPairable get() = discovery.discoveryPairable
    val discoveryEmptyTitle get() = discovery.discoveryEmptyTitle
    val discoveryEmptyHint get() = discovery.discoveryEmptyHint
    val discoveryNoPair get() = discovery.discoveryNoPair
    val discoveryNoPairHint get() = discovery.discoveryNoPairHint
    val discoveryActionConnect get() = discovery.discoveryActionConnect
    val discoveryActionPair get() = discovery.discoveryActionPair
    val btnStop get() = discovery.btnStop
    val btnScan get() = discovery.btnScan
    // FastbootScreen
    val fbModeTitle get() = fastboot.fbModeTitle
    val fbReboot get() = fastboot.fbReboot
    val fbRebootSystem get() = fastboot.fbRebootSystem
    val fbRebootRecovery get() = fastboot.fbRebootRecovery
    val fbRebootBootloader get() = fastboot.fbRebootBootloader
    val fbPartitionOps get() = fastboot.fbPartitionOps
    val fbFlashPartition get() = fastboot.fbFlashPartition
    val fbErasePartition get() = fastboot.fbErasePartition
    val fbOemTitle get() = fastboot.fbOemTitle
    val fbOemExec get() = fastboot.fbOemExec
    val fbUnlock get() = fastboot.fbUnlock
    val fbLock get() = fastboot.fbLock
    val fbAdvancedTransfer get() = fastboot.fbAdvancedTransfer
    val fbTempBoot get() = fastboot.fbTempBoot
    val fbStage get() = fastboot.fbStage
    val fbFetch get() = fastboot.fbFetch
    val fbDeviceVars get() = fastboot.fbDeviceVars
    val fbGetvarAll get() = fastboot.fbGetvarAll
    val fbFlashSuccessTitle get() = fastboot.fbFlashSuccessTitle
    val fbFlashSuccessMsg get() = fastboot.fbFlashSuccessMsg
    val fbStayFastboot get() = fastboot.fbStayFastboot
    val fbUsbHint get() = fastboot.fbUsbHint
    val fbUsbPermission get() = fastboot.fbUsbPermission
    val fbDevicesHeader get() = fastboot.fbDevicesHeader
    val fbEmptyHint get() = fastboot.fbEmptyHint
    val fbSerial get() = fastboot.fbSerial
    val fbFlashing get() = fastboot.fbFlashing
    val fbConnectLog get() = fastboot.fbConnectLog
    val fbOemDialogTitle get() = fastboot.fbOemDialogTitle
    val fbCmdLabel get() = fastboot.fbCmdLabel
    val fbCmdPlaceholder get() = fastboot.fbCmdPlaceholder
    val fbExecute get() = fastboot.fbExecute
    val fbEraseTitle get() = fastboot.fbEraseTitle
    val fbEraseWarning get() = fastboot.fbEraseWarning
    val fbSelectPartition get() = fastboot.fbSelectPartition
    val fbPartitionName get() = fastboot.fbPartitionName
    val fbManualInput get() = fastboot.fbManualInput
    val fbEraseConfirm get() = fastboot.fbEraseConfirm
    val fbFlashTitle get() = fastboot.fbFlashTitle
    val fbSelectImage get() = fastboot.fbSelectImage
    val fbFlashConfirm get() = fastboot.fbFlashConfirm
    val fbTempBootTitle get() = fastboot.fbTempBootTitle
    val fbTempBootWarning get() = fastboot.fbTempBootWarning
    val fbSelectBoot get() = fastboot.fbSelectBoot
    val fbBootConfirm get() = fastboot.fbBootConfirm
    val fbStageTitle get() = fastboot.fbStageTitle
    val fbStageDesc get() = fastboot.fbStageDesc
    val fbSelectUpload get() = fastboot.fbSelectUpload
    val fbStageConfirm get() = fastboot.fbStageConfirm
    // UsbAdbScreen extras
    val usbConnectLog get() = usbAdb.usbConnectLog
    val usbDescInfo get() = usbAdb.usbDescInfo
    val usbDescShell get() = usbAdb.usbDescShell
    val usbDescApps get() = usbAdb.usbDescApps
    val usbDescFiles get() = usbAdb.usbDescFiles
    val usbDescAdvanced get() = usbAdb.usbDescAdvanced
    val usbConnectedFmt get() = usbAdb.usbConnectedFmt
    val usbNotConnected get() = usbAdb.usbNotConnected
    val usbDetectedDevices get() = usbAdb.usbDetectedDevices
    val usbNoDeviceHint get() = usbAdb.usbNoDeviceHint
    val usbSerial get() = usbAdb.usbSerial
    val usbRebootFastboot get() = usbAdb.usbRebootFastboot
    val usbRebootFastbootDesc get() = usbAdb.usbRebootFastbootDesc
}

@Composable
fun rememberStrings(): Strings = Strings(
    home = rememberHomeStrings(),
    shared = rememberSharedStrings(),
    shell = rememberShellStrings(),
    deviceInfo = rememberDeviceInfoStrings(),
    advanced = rememberAdvancedOpsStrings(),
    mirror = rememberMirrorStrings(),
    pairing = rememberPairingStrings(),
    apps = rememberAppsStrings(),
    files = rememberFilesStrings(),
    discovery = rememberDiscoveryStrings(),
    fastboot = rememberFastbootStrings(),
    usbAdb = rememberUsbAdbStrings(),
)

@Composable
private fun rememberHomeStrings() = HomeStrings(
    homeSubtitle = stringResource(R.string.home_subtitle),
    sectionTools = stringResource(R.string.section_tools),
    sectionHistory = stringResource(R.string.section_history),
    sectionUsbDebug = stringResource(R.string.section_usb_debug),
    usbDebugDesc = stringResource(R.string.usb_debug_desc),
    featureShell = stringResource(R.string.feature_shell),
    featureShellDesc = stringResource(R.string.feature_shell_desc),
    featureDevice = stringResource(R.string.feature_device),
    featureDeviceDesc = stringResource(R.string.feature_device_desc),
    featureApps = stringResource(R.string.feature_apps),
    featureAppsDesc = stringResource(R.string.feature_apps_desc),
    featureFiles = stringResource(R.string.feature_files),
    featureFilesDesc = stringResource(R.string.feature_files_desc),
    featureAdvanced = stringResource(R.string.feature_advanced),
    featureAdvancedDesc = stringResource(R.string.feature_advanced_desc),
    featureFastboot = stringResource(R.string.feature_fastboot),
    featureFastbootDesc = stringResource(R.string.feature_fastboot_desc),
    featureUsbAdb = stringResource(R.string.feature_usb_adb),
    featureUsbAdbDesc = stringResource(R.string.feature_usb_adb_desc),
    statusDisconnected = stringResource(R.string.status_disconnected),
    statusConnecting = stringResource(R.string.status_connecting),
    statusAuth = stringResource(R.string.status_auth),
    statusConnected = stringResource(R.string.status_connected),
    statusError = stringResource(R.string.status_error),
    labelIp = stringResource(R.string.label_ip),
    labelPort = stringResource(R.string.label_port),
    btnConnect = stringResource(R.string.btn_connect),
    btnConnecting = stringResource(R.string.btn_connecting),
    btnDisconnect = stringResource(R.string.btn_disconnect),
    btnDiscover = stringResource(R.string.btn_discover),
    btnPair = stringResource(R.string.btn_pair),
    errorConnect = stringResource(R.string.error_connect),
    btTitle = stringResource(R.string.bt_title),
    btMessage = stringResource(R.string.bt_message),
    btConfirm = stringResource(R.string.bt_confirm),
    btDismiss = stringResource(R.string.bt_dismiss),
    actionFavorite = stringResource(R.string.action_favorite),
    actionDelete = stringResource(R.string.action_delete),
)

@Composable
private fun rememberSharedStrings() = SharedStrings(
    btnBack = stringResource(R.string.btn_back),
    btnClose = stringResource(R.string.btn_close),
    btnCancel = stringResource(R.string.btn_cancel),
    btnRefresh = stringResource(R.string.btn_refresh),
    btnSend = stringResource(R.string.btn_send),
)

@Composable
private fun rememberShellStrings() = ShellStrings(
    shellTitle = stringResource(R.string.shell_title),
    shellSubtitle = stringResource(R.string.shell_subtitle),
    shellWifiFix = stringResource(R.string.shell_wifi_fix),
    shellShizuku = stringResource(R.string.shell_shizuku),
    shellScene = stringResource(R.string.shell_scene),
    shellBrevent = stringResource(R.string.shell_brevent),
    shellIceBox = stringResource(R.string.shell_icebox),
    shellStopApp = stringResource(R.string.shell_stop_app),
    shellGreenify = stringResource(R.string.shell_greenify),
    shellThanox = stringResource(R.string.shell_thanox),
    shellAirFrozen = stringResource(R.string.shell_air_frozen),
    shellFreezeYou = stringResource(R.string.shell_freeze_you),
    shellIsland = stringResource(R.string.shell_island),
    shellApkInstaller = stringResource(R.string.shell_apk_installer),
    shellBlackHole = stringResource(R.string.shell_black_hole),
    shellSecondSpace = stringResource(R.string.shell_second_space),
    shellHint = stringResource(R.string.shell_hint),
    shellInputHint = stringResource(R.string.shell_input_hint),
)

@Composable
private fun rememberDeviceInfoStrings() = DeviceInfoStrings(
    deviceInfoTitle = stringResource(R.string.device_info_title),
    infoBasic = stringResource(R.string.info_basic),
    infoBrand = stringResource(R.string.info_brand),
    infoModel = stringResource(R.string.info_model),
    infoCodename = stringResource(R.string.info_codename),
    infoSerial = stringResource(R.string.info_serial),
    infoSystem = stringResource(R.string.info_system),
    infoFingerprint = stringResource(R.string.info_fingerprint),
    infoScreen = stringResource(R.string.info_screen),
    infoResolution = stringResource(R.string.info_resolution),
    infoBattery = stringResource(R.string.info_battery),
    infoDesignCapacity = stringResource(R.string.info_design_capacity),
    infoCurrentCapacity = stringResource(R.string.info_current_capacity),
    infoBatteryType = stringResource(R.string.info_battery_type),
    infoHealth = stringResource(R.string.info_health),
    infoBatteryLevel = stringResource(R.string.info_battery_level),
    infoBatteryStatus = stringResource(R.string.info_battery_status),
    infoVoltage = stringResource(R.string.info_voltage),
    infoTemperature = stringResource(R.string.info_temperature),
    infoChipPlatform = stringResource(R.string.info_chip_platform),
    infoImei = stringResource(R.string.info_imei),
    infoCycleCount = stringResource(R.string.info_cycle_count),
    infoBatteryHealthPct = stringResource(R.string.info_battery_health_pct),
    infoFlashLifespan = stringResource(R.string.info_flash_lifespan),
    infoMemStorage = stringResource(R.string.info_mem_storage),
    infoRam = stringResource(R.string.info_ram),
    infoUsed = stringResource(R.string.info_used),
    infoAvailable = stringResource(R.string.info_available),
    infoTotal = stringResource(R.string.info_total),
    infoNoData = stringResource(R.string.info_no_data),
    infoInternalStorage = stringResource(R.string.info_internal_storage),
    infoNoDataRefresh = stringResource(R.string.info_no_data_refresh),
)

@Composable
private fun rememberAdvancedOpsStrings() = AdvancedOpsStrings(
    advancedTitle = stringResource(R.string.advanced_title),
    opsDisplay = stringResource(R.string.ops_display),
    opsScreenshot = stringResource(R.string.ops_screenshot),
    opsScreenOn = stringResource(R.string.ops_screen_on),
    opsScreenOff = stringResource(R.string.ops_screen_off),
    opsVolume = stringResource(R.string.ops_volume),
    opsVolUp = stringResource(R.string.ops_vol_up),
    opsVolDown = stringResource(R.string.ops_vol_down),
    opsVolMute = stringResource(R.string.ops_vol_mute),
    opsConnectivity = stringResource(R.string.ops_connectivity),
    opsWifiOn = stringResource(R.string.ops_wifi_on),
    opsWifiOff = stringResource(R.string.ops_wifi_off),
    opsBtOn = stringResource(R.string.ops_bt_on),
    opsBtOff = stringResource(R.string.ops_bt_off),
    opsNavigation = stringResource(R.string.ops_navigation),
    opsBack = stringResource(R.string.ops_back),
    opsPower = stringResource(R.string.ops_power),
    opsMedia = stringResource(R.string.ops_media),
    opsPrev = stringResource(R.string.ops_prev),
    opsPlayPause = stringResource(R.string.ops_play_pause),
    opsNext = stringResource(R.string.ops_next),
    opsRebootDevice = stringResource(R.string.ops_reboot_device),
    opsSelectReboot = stringResource(R.string.ops_select_reboot),
    opsRebootNormal = stringResource(R.string.ops_reboot_normal),
    opsShutdown = stringResource(R.string.ops_shutdown),
    opsWiredDebug = stringResource(R.string.ops_wired_debug),
    opsFastbootMode = stringResource(R.string.ops_fastboot_mode),
    screenshotSuccess = stringResource(R.string.screenshot_success),
    screenshotFailed = stringResource(R.string.screenshot_failed),
    screenshotSave = stringResource(R.string.screenshot_save),
)

@Composable
private fun rememberMirrorStrings() = MirrorStrings(
    mirrorTitle = stringResource(R.string.mirror_title),
    mirrorStatusIdle = stringResource(R.string.mirror_status_idle),
    mirrorStatusStarting = stringResource(R.string.mirror_status_starting),
    mirrorStatusStreaming = stringResource(R.string.mirror_status_streaming),
    mirrorRetry = stringResource(R.string.mirror_retry),
    mirrorStop = stringResource(R.string.mirror_stop),
    mirrorResolution = run {
        val f = stringResource(R.string.mirror_resolution)
        val formatter: (String, String) -> String = { video, device -> f.format(video, device) }
        formatter
    },
    mirrorOptionSize = stringResource(R.string.mirror_option_size),
    mirrorOptionBitrate = stringResource(R.string.mirror_option_bitrate),
    mirrorOptionFps = stringResource(R.string.mirror_option_fps),
    mirrorOptionShape = stringResource(R.string.mirror_option_shape),
    mirrorShapeRect = stringResource(R.string.mirror_shape_rect),
    mirrorShapeCircle = stringResource(R.string.mirror_shape_circle),
    mirrorAuto = stringResource(R.string.mirror_auto),
    mirrorOptionToggles = stringResource(R.string.mirror_option_toggles),
    mirrorOptionReadonly = stringResource(R.string.mirror_option_readonly),
    mirrorOptionScreenOff = stringResource(R.string.mirror_option_screen_off),
    mirrorOptionStayAwake = stringResource(R.string.mirror_option_stay_awake),
    mirrorKeyHome = stringResource(R.string.mirror_key_home),
    mirrorKeyBack = stringResource(R.string.mirror_key_back),
    mirrorKeyRecents = stringResource(R.string.mirror_key_recents),
    mirrorKeyPower = stringResource(R.string.mirror_key_power),
    mirrorKeyVolUp = stringResource(R.string.mirror_key_vol_up),
    mirrorKeyVolDown = stringResource(R.string.mirror_key_vol_down),
    mirrorOptions = stringResource(R.string.mirror_options),
)

@Composable
private fun rememberPairingStrings() = PairingStrings(
    pairTitle = stringResource(R.string.pair_title),
    pairWirelessTitle = stringResource(R.string.pair_wireless_title),
    pairStep1 = stringResource(R.string.pair_step1),
    pairStep2 = stringResource(R.string.pair_step2),
    pairStep3 = stringResource(R.string.pair_step3),
    pairPortNote = stringResource(R.string.pair_port_note),
    labelPairCode = stringResource(R.string.label_pair_code),
    pairCodePlaceholder = stringResource(R.string.pair_code_placeholder),
    btnPairing = stringResource(R.string.btn_pairing),
    btnPairingProgress = stringResource(R.string.btn_pairing_progress),
    pairFailed = stringResource(R.string.pair_failed),
    pairTips = stringResource(R.string.pair_tips),
    pairTip1 = stringResource(R.string.pair_tip1),
    pairTip2 = stringResource(R.string.pair_tip2),
    pairTip3 = stringResource(R.string.pair_tip3),
    pairTip4 = stringResource(R.string.pair_tip4),
    btnRetry = stringResource(R.string.btn_retry),
    manualConnectTitle = stringResource(R.string.manual_connect_title),
)

@Composable
private fun rememberAppsStrings() = AppsStrings(
    appsTitle = stringResource(R.string.apps_title),
    appsInstallApk = stringResource(R.string.apps_install_apk),
    appsSearchHint = stringResource(R.string.apps_search_hint),
    appsFilterAll = stringResource(R.string.apps_filter_all),
    appsFilterSystem = stringResource(R.string.apps_filter_system),
    appsFilterThird = stringResource(R.string.apps_filter_third),
    appsFilterDisabled = stringResource(R.string.apps_filter_disabled),
    appsSystemCount = run { val f = stringResource(R.string.apps_system_count); { count: Int -> f.format(count) } },
    appsThirdCount = run { val f = stringResource(R.string.apps_third_count); { count: Int -> f.format(count) } },
    appsDisabledCount = run { val f = stringResource(R.string.apps_disabled_count); { count: Int -> f.format(count) } },
    appsDisabled = stringResource(R.string.apps_disabled),
    appsActionStop = stringResource(R.string.apps_action_stop),
    appsActionClear = stringResource(R.string.apps_action_clear),
    appsActionDisable = stringResource(R.string.apps_action_disable),
    appsActionEnable = stringResource(R.string.apps_action_enable),
    appsActionUninstall = stringResource(R.string.apps_action_uninstall),
    appsActionExtract = stringResource(R.string.apps_action_extract),
    appsActionUninstallKeep = stringResource(R.string.apps_action_uninstall_keep),
    appsNotConnected = stringResource(R.string.apps_not_connected),
    appsOpNoOutput = stringResource(R.string.apps_op_no_output),
    appsExtractRunning = run { val f = stringResource(R.string.apps_extract_running); { n: String -> f.format(n) } },
    appsExtractSaved = run { val f = stringResource(R.string.apps_extract_saved); { n: String -> f.format(n) } },
    appsExtractNoPath = stringResource(R.string.apps_extract_no_path),
    appsExtractFailed = run { val f = stringResource(R.string.apps_extract_failed); { n: String -> f.format(n) } },
    appsPickerTimeout = stringResource(R.string.apps_picker_timeout),
)

@Composable
private fun rememberFilesStrings() = FilesStrings(
    filesTitle = stringResource(R.string.files_title),
    filesPushHint = stringResource(R.string.files_push_hint),
    filesParent = stringResource(R.string.files_parent),
    filesEmpty = stringResource(R.string.files_empty),
    filesSize = run { val f = stringResource(R.string.files_size); { s: String -> f.format(s) } },
    filesPermission = run { val f = stringResource(R.string.files_permission); { s: String -> f.format(s) } },
    filesModified = run { val f = stringResource(R.string.files_modified); { s: String -> f.format(s) } },
    filesActionView = stringResource(R.string.files_action_view),
    filesActionPull = stringResource(R.string.files_action_pull),
    filesActionInstall = stringResource(R.string.files_action_install),
    filesActionDelete = stringResource(R.string.files_action_delete),
    filesContentTitle = stringResource(R.string.files_content_title),
    filesContentTooLong = run { val f = stringResource(R.string.files_content_too_long); { shown: Int, total: Int -> f.format(shown, total) } },
)

@Composable
private fun rememberDiscoveryStrings() = DiscoveryStrings(
    discoveryTitle = stringResource(R.string.discovery_title),
    discoveryScanning = stringResource(R.string.discovery_scanning),
    discoveryStopped = stringResource(R.string.discovery_stopped),
    discoveryConnectable = run { val f = stringResource(R.string.discovery_connectable); { count: Int -> f.format(count) } },
    discoveryPairable = run { val f = stringResource(R.string.discovery_pairable); { count: Int -> f.format(count) } },
    discoveryEmptyTitle = stringResource(R.string.discovery_empty_title),
    discoveryEmptyHint = stringResource(R.string.discovery_empty_hint),
    discoveryNoPair = stringResource(R.string.discovery_no_pair),
    discoveryNoPairHint = stringResource(R.string.discovery_no_pair_hint),
    discoveryActionConnect = stringResource(R.string.discovery_action_connect),
    discoveryActionPair = stringResource(R.string.discovery_action_pair),
    btnStop = stringResource(R.string.btn_stop),
    btnScan = stringResource(R.string.btn_scan),
)

@Composable
private fun rememberFastbootStrings() = FastbootStrings(
    fbModeTitle = stringResource(R.string.fb_mode_title),
    fbReboot = stringResource(R.string.fb_reboot),
    fbRebootSystem = stringResource(R.string.fb_reboot_system),
    fbRebootRecovery = stringResource(R.string.fb_reboot_recovery),
    fbRebootBootloader = stringResource(R.string.fb_reboot_bootloader),
    fbPartitionOps = stringResource(R.string.fb_partition_ops),
    fbFlashPartition = stringResource(R.string.fb_flash_partition),
    fbErasePartition = stringResource(R.string.fb_erase_partition),
    fbOemTitle = stringResource(R.string.fb_oem_title),
    fbOemExec = stringResource(R.string.fb_oem_exec),
    fbUnlock = stringResource(R.string.fb_unlock),
    fbLock = stringResource(R.string.fb_lock),
    fbAdvancedTransfer = stringResource(R.string.fb_advanced_transfer),
    fbTempBoot = stringResource(R.string.fb_temp_boot),
    fbStage = stringResource(R.string.fb_stage),
    fbFetch = stringResource(R.string.fb_fetch),
    fbDeviceVars = stringResource(R.string.fb_device_vars),
    fbGetvarAll = stringResource(R.string.fb_getvar_all),
    fbFlashSuccessTitle = stringResource(R.string.fb_flash_success_title),
    fbFlashSuccessMsg = run { val f = stringResource(R.string.fb_flash_success_msg); { partition: String -> f.format(partition) } },
    fbStayFastboot = stringResource(R.string.fb_stay_fastboot),
    fbUsbHint = stringResource(R.string.fb_usb_hint),
    fbUsbPermission = stringResource(R.string.fb_usb_permission),
    fbDevicesHeader = stringResource(R.string.fb_devices_header),
    fbEmptyHint = stringResource(R.string.fb_empty_hint),
    fbSerial = run { val f = stringResource(R.string.fb_serial); { serial: String -> f.format(serial) } },
    fbFlashing = stringResource(R.string.fb_flashing),
    fbConnectLog = stringResource(R.string.fb_connect_log),
    fbOemDialogTitle = stringResource(R.string.fb_oem_dialog_title),
    fbCmdLabel = stringResource(R.string.fb_cmd_label),
    fbCmdPlaceholder = stringResource(R.string.fb_cmd_placeholder),
    fbExecute = stringResource(R.string.fb_execute),
    fbEraseTitle = stringResource(R.string.fb_erase_title),
    fbEraseWarning = stringResource(R.string.fb_erase_warning),
    fbSelectPartition = stringResource(R.string.fb_select_partition),
    fbPartitionName = stringResource(R.string.fb_partition_name),
    fbManualInput = stringResource(R.string.fb_manual_input),
    fbEraseConfirm = stringResource(R.string.fb_erase_confirm),
    fbFlashTitle = stringResource(R.string.fb_flash_title),
    fbSelectImage = stringResource(R.string.fb_select_image),
    fbFlashConfirm = stringResource(R.string.fb_flash_confirm),
    fbTempBootTitle = stringResource(R.string.fb_temp_boot_title),
    fbTempBootWarning = stringResource(R.string.fb_temp_boot_warning),
    fbSelectBoot = stringResource(R.string.fb_select_boot),
    fbBootConfirm = stringResource(R.string.fb_boot_confirm),
    fbStageTitle = stringResource(R.string.fb_stage_title),
    fbStageDesc = stringResource(R.string.fb_stage_desc),
    fbSelectUpload = stringResource(R.string.fb_select_upload),
    fbStageConfirm = stringResource(R.string.fb_stage_confirm),
)

@Composable
private fun rememberUsbAdbStrings() = UsbAdbStrings(
    usbConnectLog = stringResource(R.string.usb_connect_log),
    usbDescInfo = stringResource(R.string.usb_desc_info),
    usbDescShell = stringResource(R.string.usb_desc_shell),
    usbDescApps = stringResource(R.string.usb_desc_apps),
    usbDescFiles = stringResource(R.string.usb_desc_files),
    usbDescAdvanced = stringResource(R.string.usb_desc_advanced),
    usbConnectedFmt = run { val f = stringResource(R.string.usb_connected_fmt); { name: String -> f.format(name) } },
    usbNotConnected = stringResource(R.string.usb_not_connected),
    usbDetectedDevices = stringResource(R.string.usb_detected_devices),
    usbNoDeviceHint = stringResource(R.string.usb_no_device_hint),
    usbSerial = run { val f = stringResource(R.string.usb_serial); { serial: String -> f.format(serial) } },
    usbRebootFastboot = stringResource(R.string.usb_reboot_fastboot),
    usbRebootFastbootDesc = stringResource(R.string.usb_reboot_fastboot_desc),
)

val LocalStrings = staticCompositionLocalOf<Strings> {
    error("No Strings provided")
}
