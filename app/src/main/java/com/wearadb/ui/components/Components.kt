package com.wearadb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.wearadb.ui.theme.WearAdbTheme

@Composable
fun WearCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = WearAdbTheme.colors
    val shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(c.surfaceVariant, shape)
            .border(1.dp, c.outlineVariant, shape)
            .padding(20.dp),
        content = content
    )
}

/** CompositionLocal that AlignedInputColumn provides to align all WearInput labels. */
val LocalAlignedLabelWidth = compositionLocalOf { 0.dp }

@Composable
fun WearInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val c = WearAdbTheme.colors
    val shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius)
    val alignedWidth = LocalAlignedLabelWidth.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = TextStyle(
                    color = c.label,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                modifier = Modifier
                    .then(if (alignedWidth > 0.dp) Modifier.width(alignedWidth) else Modifier)
                    .padding(end = 10.dp)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = c.onSurface,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .clip(shape)
                .background(c.surface, shape)
                .border(1.dp, c.outlineVariant, shape)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(color = c.onSurfaceVariant, fontSize = 15.sp)
                        )
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
fun WearButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Primary
) {
    val c = WearAdbTheme.colors
    val shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius)
    val colors = when (variant) {
        ButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = c.buttonPrimary,
            contentColor = c.buttonPrimaryText,
            disabledContainerColor = c.outlineVariant,
            disabledContentColor = c.onSurfaceVariant
        )
        ButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = c.buttonSecondary,
            contentColor = c.buttonSecondaryText,
            disabledContainerColor = c.disabledButtonBg,
            disabledContentColor = c.onSurfaceVariant
        )
        ButtonVariant.Danger -> ButtonDefaults.buttonColors(
            containerColor = c.buttonDanger,
            contentColor = c.buttonDangerText,
            disabledContainerColor = c.disabledDangerBg,
            disabledContentColor = c.disabledDangerText
        )
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

enum class ButtonVariant { Primary, Secondary, Danger }

@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val c = WearAdbTheme.colors
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) c.statusDotActive else c.statusDotInactive)
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = WearAdbTheme.colors.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/**
 * A Column wrapper that automatically aligns all WearInput labels.
 *
 * Measures each label text with [TextMeasurer] to find the widest one,
 * then provides the result via [LocalAlignedLabelWidth] so WearInput
 * applies a uniform label width. Works with any language — no hardcoded dp.
 *
 * @param labels The label strings of the WearInput composables inside [content].
 *               Must be in the same order as the WearInput calls.
 * @param content The WearInput composables.
 */
@Composable
fun AlignedInputColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    labels: List<String>,
    content: @Composable ColumnScope.() -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = WearAdbTheme.colors.label,
        fontSize = 14.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    )

    // Measure all labels to find the widest, then add the right padding (10.dp)
    val density = LocalDensity.current
    val alignedWidth = remember(labels, labelStyle, density) {
        val maxDp = labels.maxOfOrNull { label ->
            if (label.isEmpty()) 0.dp
            else with(density) { textMeasurer.measure(label, labelStyle).size.width.toDp() }
        } ?: 0.dp
        maxDp + 10.dp  // 10.dp = label's end padding in WearInput
    }

    CompositionLocalProvider(LocalAlignedLabelWidth provides alignedWidth) {
        Column(
            modifier = modifier,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

@Composable
fun WearSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val c = WearAdbTheme.colors
    val cr = WearAdbTheme.shape.cornerRadius
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(cr),
                containerColor = c.surfaceVariant,
                contentColor = c.onSurface,
                actionColor = c.accent
            )
        }
    )
}
