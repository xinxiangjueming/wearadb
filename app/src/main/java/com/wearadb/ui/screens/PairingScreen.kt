package com.wearadb.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.PairingState
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding
import com.wearadb.ui.utils.useDualPane

@Composable
fun PairingScreen(
    onBack: () -> Unit,
    onPaired: () -> Unit,
    initialHost: String = "",
    initialPort: Int = 0,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val pairingState by viewModel.pairingState.collectAsState()
    var hostInput by remember(initialHost) { mutableStateOf(initialHost) }
    var portInput by remember(initialPort) { mutableStateOf(if (initialPort > 0) initialPort.toString() else "") }
    var codeInput by remember { mutableStateOf("") }

    LaunchedEffect(pairingState) {
        android.util.Log.d("Pairing", "LaunchedEffect: pairingState=$pairingState")
        if (pairingState is PairingState.Success) {
            android.util.Log.d("Pairing", "Pairing success, waiting 1500ms then onPaired()")
            kotlinx.coroutines.delay(1500)
            android.util.Log.d("Pairing", "Calling onPaired() -> popBackStack")
            onPaired()
        }
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hPadding = adaptiveHorizontalPadding()
    val expanded = useDualPane()
    val s = LocalStrings.current

    // ── Top bar ──
    val topBar: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.resetPairingState(); onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text(s.pairTitle, style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
        }
    }

    // ── 配对说明卡片 ──
    val instructionCard: @Composable () -> Unit = {
        WearCard {
            Text(s.pairWirelessTitle, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(s.pairStep1, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Text(s.pairStep2, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Text(s.pairStep3, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(s.pairPortNote, style = MaterialTheme.typography.labelMedium, color = c.accent)
        }
    }

    // ── 输入 + 按钮 + 状态 ──
    val inputSection: @Composable () -> Unit = {
        AlignedInputColumn(
            labels = listOf(s.labelIp, s.labelPort, s.labelPairCode),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WearInput(
                value = hostInput, onValueChange = { hostInput = it },
                label = s.labelIp,
                placeholder = "192.168.1.100",
                modifier = Modifier.fillMaxWidth(),
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            )
            WearInput(
                value = portInput, onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                label = s.labelPort,
                placeholder = "37123",
                modifier = Modifier.fillMaxWidth(0.5f),
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            )
            WearInput(
                value = codeInput,
                onValueChange = { codeInput = it.filter { ch -> ch.isDigit() } },
                label = s.labelPairCode,
                placeholder = s.pairCodePlaceholder,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                modifier = Modifier.fillMaxWidth(0.6f)
            )
        }
        Spacer(Modifier.height(20.dp))

        when (val state = pairingState) {
            is PairingState.Idle -> {
                WearButton(
                    text = s.btnPairing,
                    onClick = { viewModel.pair(hostInput.trim(), portInput.toIntOrNull() ?: 0, codeInput.trim()) },
                    enabled = hostInput.isNotBlank() && portInput.isNotBlank() && codeInput.length >= 6
                )
            }
            is PairingState.Pairing -> {
                WearButton(text = s.btnPairingProgress, onClick = {}, enabled = false)
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
                            Text(s.pairFailed, style = MaterialTheme.typography.titleMedium, color = c.error)
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                WearCard {
                    Text(s.pairTips, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(s.pairTip1, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text(s.pairTip2, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text(s.pairTip3, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text(s.pairTip4, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                WearButton(text = s.btnRetry, onClick = { viewModel.resetPairingState() }, variant = ButtonVariant.Secondary)
            }
        }
    }

    if (expanded) {
        // ── 横屏：左栏配对说明 + 右栏输入 ──
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPadding)
                .padding(top = statusBarPad + 8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = navBarPad + 16.dp)
            ) {
                item { topBar() }
                item { instructionCard() }
            }

            Spacer(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))

            LazyColumn(
                modifier = Modifier.weight(1f).padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = navBarPad + 16.dp)
            ) {
                item { inputSection() }
            }
        }
    } else {
        // ── 竖屏：单栏 ──
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(top = statusBarPad + 8.dp, bottom = navBarPad + 16.dp)
        ) {
            item { topBar() }
            item { Spacer(Modifier.height(8.dp)) }
            item { instructionCard() }
            item { Spacer(Modifier.height(20.dp)) }
            item { inputSection() }
        }
    }
}
