package com.wearadb.data.model

data class AppEntry(
    val packageName: String,
    val label: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true,
    val sizeBytes: Long = 0
)
