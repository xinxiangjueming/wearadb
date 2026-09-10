package com.wearadb.data.model

data class DeviceInfo(
    val model: String = "",
    val brand: String = "",
    val device: String = "",
    val androidVersion: String = "",
    val sdkVersion: String = "",
    val buildId: String = "",
    val fingerprint: String = "",
    val batteryLevel: Int = -1,
    val batteryStatus: String = "",
    val batteryHealth: String = "",
    val batteryTechnology: String = "",
    val batteryDesignCapacity: Int = 0,  // 设计容量 mAh
    val batteryCurrentCapacity: Int = 0, // 当前容量 mAh
    val batteryVoltage: Int = 0,         // 电压 mV
    val batteryTemperature: Int = 0,     // 温度 0.1°C
    val batteryHealthPct: Float = 0f,    // 电池健康度（容量百分比），0 = 未知；由当前/设计容量相除，免 root
    val batteryCycleCount: Int = 0,      // 循环次数，0 = 未知（仅 root 可读）
    val chipPlatform: String = "",       // 芯片平台 ro.board.platform（免 root）
    val imei: String = "",               // IMEI（可能多行：SIM1/SIM2），空 = 未知
    val flashLifeA: Int = -1,            // UFS 闪存寿命 A 剩余%，-1 = 未知（仅 root 可读）
    val flashLifeB: Int = -1,            // UFS 闪存寿命 B 剩余%，-1 = 未知（仅 root 可读）
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val density: Int = 0,
    val abi: String = "",
    val serialno: String = "",
    val ipAddr: String = "",
    val uptime: String = "",
    val memTotal: String = "",
    val memAvail: String = "",
    // 存储信息 (bytes)
    val storageTotal: Long = 0,
    val storageUsed: Long = 0,
    val storageFree: Long = 0
)
