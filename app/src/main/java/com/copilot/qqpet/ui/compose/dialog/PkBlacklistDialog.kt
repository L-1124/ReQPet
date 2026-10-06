package com.copilot.qqpet.ui.compose.dialog

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PK_LIST_HEIGHT = 260.dp

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
    for (friend in PetAdventureEngine.loadCachedHireableFriends(context)) {
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

@Composable
fun PkBlacklistDialog(state: SettingsState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blacklistUins = remember {
        mutableStateListOf<Long>().apply { addAll(PetAdventureEngine.loadSavedPkBlacklistUins(context)) }
    }
    val candidates = remember {
        mutableStateListOf<PkTarget>().apply { addAll(buildInitialCandidates(context, blacklistUins.toList())) }
    }
    var query by remember { mutableStateOf("") }
    var showManualAdd by remember { mutableStateOf(false) }

    fun persistSelection() {
        PetAdventureEngine.savePkBlacklistUins(context, blacklistUins)
        state.syncConfig()
        state.refresh()
    }

    LaunchedEffect(Unit) {
        val active = HookEntry.globalEngine ?: return@LaunchedEffect
        val visitors = withContext(Dispatchers.IO) {
            try {
                val result = active.fetchLikeListAwait("")
                if (result.first == 0) result.second else emptyList<QQPetDirectBridge.LikeMember>()
            } catch (_: Throwable) {
                emptyList<QQPetDirectBridge.LikeMember>()
            }
        }
        for (visitor in visitors) {
            if (visitor.uin > 0L && candidates.none { it.uin == visitor.uin }) {
                candidates.add(
                    PkTarget(
                        uin = visitor.uin,
                        nick = visitor.nick.ifEmpty { "访客_${visitor.uin}" },
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "选择 PK 免战黑名单", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "已勾选目标将绝对免战跳过，零发包防误打；未勾选且三维低于我方的对手正常挑战。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    singleLine = true,
                    placeholder = { Text("搜索昵称 / QQ 号") },
                    trailingIcon = if (query.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "清空",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (keyword.isNotEmpty()) {
                            "搜索到 ${filtered.size} 人 · 已拉黑 ${blacklistUins.size} 人"
                        } else {
                            "已拉黑 ${blacklistUins.size} 人 · 候选池共 ${candidates.size} 人"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            if (blacklistUins.isNotEmpty()) {
                                blacklistUins.clear()
                                persistSelection()
                            }
                        }
                    ) {
                        Text(
                            text = "全不选",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { showManualAdd = true }) {
                        Text(text = "+ 输入QQ拉黑", style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (filtered.isEmpty()) {
                    Text(
                        text = if (keyword.isNotEmpty()) {
                            "未找到匹配「$keyword」的对象，可点击右上角「+ 输入QQ拉黑」直接添加"
                        } else {
                            "暂无候选好友，可点击右上角「+ 输入QQ拉黑」添加免战对象"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 36.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PK_LIST_HEIGHT)
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    persistSelection()
                    onDismiss()
                }
            ) {
                Text(text = "完成并保存", style = MaterialTheme.typography.labelLarge)
            }
        }
    )

    if (showManualAdd) {
        var manualInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualAdd = false },
            title = {
                Text(text = "手动添加免战 QQ 号", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入 QQ 号") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val rawUin = manualInput.trim().toLongOrNull()
                        if (rawUin != null && rawUin > 0L) {
                            if (!blacklistUins.contains(rawUin)) blacklistUins.add(rawUin)
                            if (candidates.none { it.uin == rawUin }) {
                                candidates.add(0, PkTarget(rawUin, "手动免战号", "-", "手动免战"))
                            }
                            persistSelection()
                            showManualAdd = false
                        } else {
                            Toast.makeText(context, "请输入有效的纯数字 QQ 号", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(text = "确认免战", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualAdd = false }) {
                    Text(text = "取消", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
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
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.nick} (${item.uin})",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (blocked) FontWeight.Bold else FontWeight.Normal,
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
        FilterChip(
            selected = blocked,
            onClick = { onToggle() },
            label = {
                Text(
                    text = if (blocked) "免战" else "正常",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = if (blocked) {
                {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else {
                null
            }
        )
    }
}
