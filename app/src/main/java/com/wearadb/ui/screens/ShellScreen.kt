package com.wearadb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.utils.adaptiveHorizontalPadding

data class TerminalLine(val text: String, val isCommand: Boolean = false)

@Composable
fun ShellScreen(
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val cr = WearAdbTheme.shape.cornerRadius
    var commandInput by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<TerminalLine>() }
    val listState = rememberLazyListState()
    val shellOutput by viewModel.shellOutput.collectAsState()

    LaunchedEffect(shellOutput) {
        if (shellOutput.isNotEmpty()) lines.add(TerminalLine(shellOutput))
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hPadding = adaptiveHorizontalPadding()
    val s = LocalStrings.current

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = hPadding).imePadding()
    ) {
        // ── Top Bar ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = statusBarPad + 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text("Shell", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Spacer(Modifier.weight(1f))
            Text(s.shellSubtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }

        // ── 快捷命令 ──
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            item {
                QuickCommandChip(
                    label = s.shellWifiFix,
                    onClick = {
                        val cmds = listOf(
                            "settings put global captive_portal_mode 0",
                            "settings put global captive_portal_https_url https://connect.rom.miui.com/generate_204",
                            "settings put global captive_portal_http_url http://connect.rom.miui.com/generate_204"
                        )
                        cmds.forEach { cmd ->
                            lines.add(TerminalLine(cmd, isCommand = true))
                        }
                        viewModel.executeCommands(cmds)
                    }
                )
            }
            item {
                QuickCommandChip(
                    label = s.shellShizuku,
                    onClick = {
                        val cmd = "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"
                        lines.add(TerminalLine(cmd, isCommand = true))
                        viewModel.executeCommand(cmd)
                    }
                )
            }
            item {
                QuickCommandChip(
                    label = s.shellScene,
                    onClick = {
                        val cmd = "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh"
                        lines.add(TerminalLine(cmd, isCommand = true))
                        viewModel.executeCommand(cmd)
                    }
                )
            }
            item {
                QuickCommandChip(
                    label = s.shellBrevent,
                    onClick = {
                        val cmd = "sh /data/data/me.piebridge.brevent/brevent.sh"
                        lines.add(TerminalLine(cmd, isCommand = true))
                        viewModel.executeCommand(cmd)
                    }
                )
            }
        }

        // ── Terminal Output ──
        val terminalShape = remember(cr) { RoundedCornerShape(cr) }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(terminalShape)
                .background(c.surface)
                .border(1.dp, c.outlineVariant, terminalShape)
                .padding(16.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = s.shellHint,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.onSurfaceVariant, lineHeight = 20.sp)
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        Text(
                            text = if (line.isCommand) "$ ${line.text}" else line.text,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = if (line.isCommand) c.terminalCommand else c.terminalOutput
                            )
                        )
                    }
                }
            }
        }

        // ── Input Bar ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = navBarPad + 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val inputShape = remember(cr) { RoundedCornerShape(cr) }
            BasicTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = c.onSurface),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (commandInput.isNotBlank()) {
                        lines.add(TerminalLine(commandInput, isCommand = true))
                        viewModel.executeCommand(commandInput)
                        commandInput = ""
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .clip(inputShape)
                    .background(c.surfaceVariant, inputShape)
                    .border(1.dp, c.outlineVariant, inputShape)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (commandInput.isEmpty()) {
                            Text(s.shellInputHint, style = TextStyle(color = c.onSurfaceVariant, fontSize = 14.sp))
                        }
                        inner()
                    }
                }
            )

            val sendShape = RoundedCornerShape(cr)
            IconButton(
                onClick = {
                    if (commandInput.isNotBlank()) {
                        lines.add(TerminalLine(commandInput, isCommand = true))
                        viewModel.executeCommand(commandInput)
                        commandInput = ""
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(sendShape)
                    .background(c.buttonPrimary, sendShape)
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, s.btnSend, tint = c.buttonPrimaryText, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun QuickCommandChip(label: String, onClick: () -> Unit) {
    val c = WearAdbTheme.colors
    val cr = WearAdbTheme.shape.cornerRadius
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(cr),
        color = c.accent.copy(alpha = 0.12f),
        modifier = Modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(label, color = c.accent, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }
    }
}
