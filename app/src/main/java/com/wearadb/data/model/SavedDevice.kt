package com.wearadb.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedDevice(
    val host: String,
    val name: String = "",
    val lastConnected: Long = 0L,
    val isFavorite: Boolean = false
) {
    val address: String get() = host
    val displayName: String get() = name.ifEmpty { host }
}
