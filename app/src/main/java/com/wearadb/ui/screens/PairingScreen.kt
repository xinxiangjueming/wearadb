package com.wearadb.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.PairingState
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding

@Composable
fun PairingScreen(
    onBack: () -> Unit,
    onPaired: () -> Unit,
    initialHost: String = "",
    initialPort: Int = 0,
    viewModel: AppViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val pairingState by viewModel.pairingState.collectAsState()
    var hostInput by remember(initialHost) { mutableStateOf(initialHost) }
    var portInput by remember(initialPort) { mutableStateOf(if (initialPort > 0) initialPort.toString() else "") }
    var codeInput by remember { mutableStateOf("") }

    LaunchedEffect(pairingState) {
        if (pairingState is PairingState.Success) {
            kotlinx.coroutines.delay(1500)
            onPaired()
        }
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hPadding = adaptiveHorizontalPadding()
    val maxInputWidth = 500.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPadding)
            .padding(top = statusBarPad + 8.dp, bottom = navBarPad + 16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.resetPairingState(); onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text("设备配对", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
        }

        Spacer(Modifier.height(8.dp))

        WearCard {
            Text("Android 11+ 无线配对", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("1. 在目标设备上打开「开发者选项」→「无线调试」", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Text("2. 点击「使用配对码配对设备」", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Text("3. 输入弹窗中显示的「配对码」和「IP 地址及端口」", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("⚠️ 注意：端口是「配对用配对码」弹窗中的端口，不是「无线调试」页面的端口", style = MaterialTheme.typography.labelMedium, color = c.accent)
        }

        Spacer(Modifier.height(20.dp))

        WearInput(
            value = hostInput, onValueChange = { hostInput = it },
            label = "IP",
            placeholder = "192.168.1.100",
            modifier = Modifier.fillMaxWidth().widthIn(max = maxInputWidth),
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )
        Spacer(Modifier.height(10.dp))
        WearInput(
            value = portInput, onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
            label = "端口",
            placeholder = "37123",
            modifier = Modifier.fillMaxWidth(0.4f).widthIn(max = maxInputWidth),
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )
        Spacer(Modifier.height(10.dp))
        WearInput(
            value = codeInput,
            onValueChange = { codeInput = it.filter { ch -> ch.isDigit() } },
            label = "配对码",
            placeholder = "6位数字",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            modifier = Modifier.fillMaxWidth(0.5f).widthIn(max = maxInputWidth)
        )

        Spacer(Modifier.height(20.dp))

        when (val state = pairingState) {
            is PairingState.Idle -> {
                WearButton(
                    text = "配对",
                    onClick = { viewModel.pair(hostInput.trim(), portInput.toIntOrNull() ?: 0, codeInput.trim()) },
                    enabled = hostInput.isNotBlank() && portInput.isNotBlank() && codeInput.length >= 6
                )
            }
            is PairingState.Pairing -> {
                WearButton(text = "配对中...", onClick = {}, enabled = false)
            }
            is PairingState.Success -> {
                WearCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = c.accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    }
                }
            }
            is PairingState.Error -> {
                WearCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Error, null, tint = c.error, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("配对失败", style = MaterialTheme.typography.titleMedium, color = c.error)
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                WearCard {
                    Text("💡 提示", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("1. 确保手机和目标设备在同一 WiFi 下", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text("2. 确认输入的是「配对用配对码」端口，不是「无线调试」端口", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text("3. 配对码每次打开都会变化，确保用最新的", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text("4. 如果配对一直失败，可以用 adb pair <ip>:<port> <code> 先在电脑上配对", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                WearButton(text = "重试", onClick = { viewModel.resetPairingState() }, variant = ButtonVariant.Secondary)
            }
        }
    }
}
