package com.copilot.qqpet.ui.compose.dialog

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAccountGateway
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.CompactSearchBar
import com.copilot.qqpet.ui.compose.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PkTarget(
    val uin: Long,
    val nick: String,
    val petNick: String,
    val role: String,
    val totalAttr: Long = 0L
)

private fun buildInitialCandidates(context: Context, blacklistUins: List<Long>): List<PkTarget> {
    val candidates = mutableListOf<PkTarget>()
    val seen = mutableSetOf<Long>()
    for (friend in PetAccountGateway.loadCachedHireableFriends(context)) {
        if (friend.uin <= 0L || seen.contains(friend.uin)) continue
        seen.add(friend.uin)
        candidates.add(
            PkTarget(
                uin = friend.uin,
                nick = friend.friendNick.ifEmpty { "好友_${friend.uin}" },
                petNick = friend.petNick.ifEmpty { "小宠" },
                role = "好友",
                totalAttr = friend.totalAttr
            )
        )
    }
    for (uin in blacklistUins) {
        if (uin > 0L && !seen.contains(uin)) {
            seen.add(uin)
            candidates.add(PkTarget(uin, "自定义免战目标", "-", "已拉黑", 0L))
        }
    }
    return candidates
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PkBlacklistDialog(state: SettingsState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = if (isInspection) SheetValue.Expanded else SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val blacklistUins = remember {
        mutableStateListOf<Long>().apply { addAll(PetAccountGateway.loadSavedPkBlacklistUins(context)) }
    }
    val candidates = remember {
        mutableStateListOf<PkTarget>().apply { addAll(buildInitialCandidates(context, blacklistUins.toList())) }
    }
    var query by remember { mutableStateOf("") }
    var showManualAdd by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }

    fun persistSelection() {
        PetAccountGateway.savePkBlacklistUins(context, blacklistUins)
        state.syncConfig()
        state.refresh()
    }

    LaunchedEffect(Unit) {
        if (isInspection) return@LaunchedEffect
        val active = HookEntry.globalEngine ?: return@LaunchedEffect
        val visitors = withContext(Dispatchers.IO) {
            try {
                active.withAccountSession(context, "pk_roster_refresh") {
                    val result = active.fetchLikeListAwait("")
                    if (result.first == 0) result.second else emptyList()
                } ?: emptyList<QQPetDirectBridge.LikeMember>()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList<QQPetDirectBridge.LikeMember>()
            }
        }
        for ((uin, nick) in visitors) {
            if (uin > 0L && candidates.none { it.uin == uin }) {
                candidates.add(
                    PkTarget(
                        uin = uin,
                        nick = nick.ifEmpty { "访客_$uin" },
                        petNick = "小宠",
                        role = "访客"
                    )
                )
            }
        }
    }

    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) {
        candidates.toList()
    } else {
        candidates.filter { item ->
            item.nick.contains(keyword, ignoreCase = true) ||
                    item.petNick.contains(keyword, ignoreCase = true) ||
                    item.uin.toString().contains(keyword)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            persistSelection()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 0.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PK 免战黑名单",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    persistSelection()
                    scope.launch {
                        try {
                            sheetState.hide()
                        } finally {
                            onDismiss()
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = "已勾选的目标将跳过挑战；未勾选且属性低于我方的对手正常发起挑战。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            CompactSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索昵称 / QQ 号"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已拉黑 ${blacklistUins.size} / ${candidates.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (blacklistUins.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            blacklistUins.clear()
                            persistSelection()
                        }
                    ) {
                        Text(
                            text = "清空",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                TextButton(onClick = { showManualAdd = !showManualAdd }) {
                    Text(
                        text = if (showManualAdd) "收起" else "+ 添加",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            AnimatedVisibility(
                visible = showManualAdd,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 14.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (manualInput.isEmpty()) {
                                Text(
                                    text = "输入 QQ 号免战",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = manualInput,
                                onValueChange = { manualInput = it.filter { ch -> ch.isDigit() } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (manualInput.isNotEmpty()) {
                            IconButton(
                                onClick = { manualInput = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "清空",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Button(
                            onClick = {
                                val rawUin = manualInput.trim().toLongOrNull()
                                if (rawUin != null && rawUin > 10000L) {
                                    if (!blacklistUins.contains(rawUin)) blacklistUins.add(rawUin)
                                    if (candidates.none { it.uin == rawUin }) {
                                        candidates.add(0, PkTarget(rawUin, "手动免战号", "-", "手动免战"))
                                    }
                                    persistSelection()
                                    manualInput = ""
                                    showManualAdd = false
                                } else {
                                    Toast.makeText(context, "请输入有效的纯数字 QQ 号", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.height(34.dp),
                            shape = CircleShape,
                            enabled = manualInput.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                        ) {
                            Text("添加", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (filtered.isEmpty()) {
            val searchUin = keyword.toLongOrNull()
            if (searchUin != null && searchUin > 10000L) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "未在候选列表中找到此 QQ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = {
                            if (!blacklistUins.contains(searchUin)) blacklistUins.add(searchUin)
                            if (candidates.none { it.uin == searchUin }) {
                                candidates.add(0, PkTarget(searchUin, "手动免战号", "-", "手动免战"))
                            }
                            persistSelection()
                            query = ""
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("将「$searchUin」加入免战黑名单")
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 24.dp, vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (keyword.isNotEmpty()) "未找到匹配「$keyword」的好友" else "暂无好友数据，可点击「添加QQ」直接录入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
            ) {
                itemsIndexed(filtered, key = { _, item -> item.uin }) { index, item ->
                    PkTargetRow(
                        item = item,
                        blocked = blacklistUins.contains(item.uin),
                        onToggle = {
                            if (blacklistUins.contains(item.uin)) {
                                blacklistUins.remove(item.uin)
                            } else {
                                blacklistUins.add(item.uin)
                            }
                            persistSelection()
                        }
                    )
                    if (index != filtered.lastIndex) CardDivider(startPadding = 0)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    persistSelection()
                    scope.launch {
                        try {
                            sheetState.hide()
                        } finally {
                            onDismiss()
                        }
                    }
                }
            ) {
                Text(text = "完成并保存", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

}

@Composable
private fun PkTargetRow(
    item: PkTarget,
    blocked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = blocked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.nick} (${item.uin})",
                style = if (blocked) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
                color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            val petPart = if (item.petNick.isNotBlank() && item.petNick != "-") " · 小宠: ${item.petNick}" else ""
            val attrPart = if (item.totalAttr > 0L) " · 战力 ${item.totalAttr}" else ""
            Text(
                text = "[${item.role}]$petPart$attrPart",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Checkbox(
            checked = blocked,
            onCheckedChange = null
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PkBlacklistDialogUiPreview() {
    MaterialTheme {
        PkBlacklistDialog(
            state = SettingsState(LocalContext.current, null, previewMode = true),
            onDismiss = {}
        )
    }
}
