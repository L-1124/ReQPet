package com.copilot.qqpet.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
    )
}

class SettingsGroupScope {
    private val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items.add(content)
    }

    fun <T> items(list: List<T>, itemContent: @Composable (T) -> Unit) {
        for (el in list) {
            items.add { itemContent(el) }
        }
    }

    fun expandableItem(visible: Boolean, content: @Composable () -> Unit) {
        items.add {
            AnimatedVisibility(
                visible = visible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                content()
            }
        }
    }

    fun expandablePanel(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
        items.add {
            AnimatedVisibility(
                visible = visible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }
        }
    }

    internal fun getItems() = items
}

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 3.dp,
    outerCornerRadius: Dp = 20.dp,
    innerCornerRadius: Dp = 4.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: SettingsGroupScope.() -> Unit
) {
    val scope = SettingsGroupScope().apply(content)
    val items = scope.getItems()
    if (items.isEmpty()) return

    val total = items.size
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items.forEachIndexed { index, itemContent ->
            val shape = when {
                total <= 1 -> RoundedCornerShape(outerCornerRadius)
                index == 0 -> RoundedCornerShape(
                    topStart = outerCornerRadius,
                    topEnd = outerCornerRadius,
                    bottomStart = innerCornerRadius,
                    bottomEnd = innerCornerRadius
                )

                index == total - 1 -> RoundedCornerShape(
                    topStart = innerCornerRadius,
                    topEnd = innerCornerRadius,
                    bottomStart = outerCornerRadius,
                    bottomEnd = outerCornerRadius
                )

                else -> RoundedCornerShape(innerCornerRadius)
            }
            Surface(
                shape = shape,
                color = containerColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    itemContent()
                }
            }
        }
    }
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroup(modifier = modifier) {
        item {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun CardDivider(startPadding: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startPadding, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
fun CardDivider(startPadding: Int) = CardDivider(startPadding = startPadding.dp)

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = if (checked) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else {
                null
            }
        )
    }
}

@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    valueLabel: String,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null
) {
    // 拖动中仅本地回显，松手才落盘提交，避免连续触发配置同步与日志刷屏
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val sliderState = remember(value, range) {
        SliderState(
            value = value.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            trackRange = range.first.toFloat()..range.last.toFloat()
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Slider(
            state = sliderState,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            interactionSource = interactionSource,
            onValueChangeFinished = {
                if (dragging) {
                    dragging = false
                    onValueChange(dragValue.roundToInt())
                }
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    drawStopIndicator = null
                )
            },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedChoiceRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: List<Boolean>? = null
) {
    if (options.isEmpty()) return
    val safeSelected = selectedIndex.coerceIn(0, options.lastIndex)
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val iconSpacePx = with(density) { (SegmentedButtonDefaults.IconSize + 8.dp).toPx() }
        val paddingPx = with(density) { 24.dp.toPx() }
        val requiredWidths = remember(options, availableWidthPx, textStyle) {
            options.map { label ->
                val textW = textMeasurer.measure(label, textStyle).size.width
                textW + iconSpacePx.toInt() + paddingPx.toInt()
            }
        }
        val totalRequiredWidthPx = remember(requiredWidths) { requiredWidths.sum() }
        val canFitEqually = totalRequiredWidthPx <= availableWidthPx

        if (canFitEqually) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == safeSelected
                    SegmentedButton(
                        selected = isSelected,
                        onClick = { onSelect(index) },
                        enabled = enabled?.getOrElse(index) { true } ?: true,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = { SegmentedButtonDefaults.Icon(isSelected) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            style = textStyle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
            ) {
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { index, label ->
                        val isSelected = index == safeSelected
                        val minWidthDp = with(density) { requiredWidths[index].toDp() }
                        SegmentedButton(
                            selected = isSelected,
                            onClick = { onSelect(index) },
                            enabled = enabled?.getOrElse(index) { true } ?: true,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            icon = { SegmentedButtonDefaults.Icon(isSelected) },
                            modifier = Modifier.widthIn(min = minWidthDp)
                        ) {
                            Text(
                                text = label,
                                style = textStyle,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceToggleRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    subtitle: String? = null,
    enabled: List<Boolean>? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        SegmentedChoiceRow(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            enabled = enabled,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SectionHeaderPreview() {
    MaterialTheme {
        SectionHeader("通用设置")
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsCardPreview() {
    MaterialTheme {
        SettingsCard(modifier = Modifier.padding(16.dp)) {
            ToggleRow("示例开关", true, {})
            CardDivider()
            ToggleRow("未开启开关", false, {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActionRowPreview() {
    MaterialTheme {
        ActionRow(
            title = "管理名单",
            subtitle = "已拉黑 10 位对手",
            trailing = "点击进入",
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SliderRowPreview() {
    MaterialTheme {
        SliderRow(
            title = "阈值调节",
            value = 60,
            range = 0..100,
            valueLabel = "60%",
            onValueChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChoiceToggleRowPreview() {
    MaterialTheme {
        ChoiceToggleRow(
            title = "单选设置",
            options = listOf("短课", "长课", "自适应"),
            selectedIndex = 1,
            onSelect = {}
        )
    }
}

/** 分节面板的展开收起；默认 spring 规格即 expressive 运动。 */
@Composable
fun ExpandablePanel(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun CompactSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "清空",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}