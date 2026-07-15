package com.wearadb.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.preferencesDataStore
import com.wearadb.data.model.SavedDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceStore: DataStore<Preferences> by preferencesDataStore("devices")

@Singleton
class DeviceRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val devicesKey = stringPreferencesKey("saved_devices")
    private val lastHostKey = stringPreferencesKey("last_host")
    private val lastPortKey = intPreferencesKey("last_port")

    suspend fun getLastHost(): String {
        return context.deviceStore.data.first()[lastHostKey] ?: ""
    }

    suspend fun saveLastHost(host: String) {
        context.deviceStore.edit { it[lastHostKey] = host }
    }

    suspend fun getLastPort(): Int {
        return context.deviceStore.data.first()[lastPortKey] ?: 0
    }

    suspend fun saveLastPort(port: Int) {
        context.deviceStore.edit { it[lastPortKey] = port }
    }

    val devices: Flow<List<SavedDevice>> = context.deviceStore.data.map { prefs ->
        val raw = prefs[devicesKey] ?: "[]"
        try {
            json.decodeFromString<List<SavedDevice>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveDevice(device: SavedDevice) {
        context.deviceStore.edit { prefs ->
            val current = try {
                json.decodeFromString<List<SavedDevice>>(prefs[devicesKey] ?: "[]")
            } catch (_: Exception) {
                emptyList()
            }
            // 按 host 去重，只保留同一台设备
            val updated = (current.filter { it.host != device.host } + device)
                .sortedByDescending { it.lastConnected }
            prefs[devicesKey] = json.encodeToString(updated)
        }
    }

    suspend fun removeDevice(address: String) {
        context.deviceStore.edit { prefs ->
            val current = try {
                json.decodeFromString<List<SavedDevice>>(prefs[devicesKey] ?: "[]")
            } catch (_: Exception) {
                emptyList()
            }
            // address 现在就是 host
            prefs[devicesKey] = json.encodeToString(current.filter { it.host != address })
        }
    }

    suspend fun toggleFavorite(address: String) {
        context.deviceStore.edit { prefs ->
            val current = try {
                json.decodeFromString<List<SavedDevice>>(prefs[devicesKey] ?: "[]")
            } catch (_: Exception) {
                emptyList()
            }
            val updated = current.map {
                if (it.host == address) it.copy(isFavorite = !it.isFavorite) else it
            }
            prefs[devicesKey] = json.encodeToString(updated)
        }
    }
}
