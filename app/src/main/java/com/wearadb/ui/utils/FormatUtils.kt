package com.wearadb.ui.utils

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${"%.0f".format(bytes / 1024.0)}KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    bytes < 1024L * 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0))}TB"
}
