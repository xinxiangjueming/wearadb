package com.wearadb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.wearadb.ui.AppViewModel

data class TerminalLine(val text: String, val isCommand: Boolean = false)

@Composable
fun ShellScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = hiltViewModel()
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

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).imePadding()
    ) {
        // ── Top Bar ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = statusBarPad + 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text("Shell", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Spacer(Modifier.weight(1f))
            Text("交互式终端", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
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
                    text = "输入命令开始交互...\n例如: ls -la, getprop ro.product.model",
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
                            Text("输入命令...", style = TextStyle(color = c.onSurfaceVariant, fontSize = 14.sp))
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
                Icon(Icons.AutoMirrored.Outlined.Send, "发送", tint = c.buttonPrimaryText, modifier = Modifier.size(22.dp))
            }
        }
    }
}
