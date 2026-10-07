package com.copilot.qqpet.ui.compose.dialog

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.engine.PetAccountGateway
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HIRE_LIST_HEIGHT = 260.dp

@Composable
fun HireWhitelistDialog(state: SettingsState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = remember {
        mutableStateListOf<Long>().apply { addAll(PetAccountGateway.loadSavedHireFriendUins(context)) }
    }
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    fun persistSelection() {
        PetAccountGateway.saveHireFriendUins(context, selected)
        state.syncConfig()
        state.refresh()
    }

    fun refreshFriends() {
        val active = HookEntry.globalEngine
        if (active == null) {
            Toast.makeText(context, "引擎尚未就绪，请稍候再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (refreshing) return
        refreshing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                active.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = true)
            }
            refreshing = false
            state.refresh()
        }
    }

    fun toggleFriend(friend: QQPetDirectBridge.HireableFriend) {
        val nowSelected = if (selected.contains(friend.uin)) {
            selected.remove(friend.uin)
            false
        } else {
            selected.add(friend.uin)
            true
        }
        persistSelection()
        if (!nowSelected || friend.totalAttr > 0L) return
        val active = HookEntry.globalEngine ?: return
        scope.launch {
            val enriched = withContext(Dispatchers.IO) { active.enrichFriendDetailsAwait(friend) }
            val cached = PetAccountGateway.loadCachedHireableFriends(context).toMutableList()
            val index = cached.indexOfFirst { it.uin == friend.uin }
            if (index >= 0) {
                cached[index] = enriched
                PetAccountGateway.saveCachedHireableFriends(context, cached)
                state.refresh()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state.hireableFriends.isEmpty()) refreshFriends()
    }

    val friends = state.hireableFriends
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) {
        friends
    } else {
        friends.filter { friend ->
            friend.friendNick.contains(keyword, ignoreCase = true) ||
                friend.petNick.contains(keyword, ignoreCase = true) ||
                friend.uin.toString().contains(keyword)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "选择雇佣好友白名单", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "未勾选的好友不会雇佣；已勾选好友中默认优先雇佣空闲且总资质最高者。",
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
                        text = when {
                            refreshing -> "正在同步好友列表与实测资质..."
                            keyword.isNotEmpty() -> "搜索到 ${filtered.size} 人 · 已勾选 ${selected.size} 人"
                            else -> "已勾选 ${selected.size} 人 · 共 ${friends.size} 位养宠好友"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            if (selected.isNotEmpty()) {
                                selected.clear()
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
                    TextButton(onClick = { refreshFriends() }) {
                        Text(text = "刷新好友与资质", style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (filtered.isEmpty()) {
                    Text(
                        text = when {
                            refreshing -> "正在拉取养宠好友列表，请稍候..."
                            keyword.isNotEmpty() -> "未找到匹配「$keyword」的养宠好友"
                            else -> "暂无数据，请点击「刷新好友与资质」"
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
                            .height(HIRE_LIST_HEIGHT)
                    ) {
                        itemsIndexed(filtered, key = { _, friend -> friend.uin }) { index, friend ->
                            HireFriendRow(
                                friend = friend,
                                checked = selected.contains(friend.uin),
                                onToggle = { toggleFriend(friend) }
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
}

@Composable
private fun HireFriendRow(
    friend: QQPetDirectBridge.HireableFriend,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${friend.friendNick.ifEmpty { "QQ好友" }} (${friend.uin})",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            val petPart = "小宠: ${friend.petNick.ifEmpty { "未知" }}"
            val attrPart = if (friend.totalAttr > 0L) {
                " · 总资质 ${friend.totalAttr} (力${friend.power}/智${friend.intel}/魅${friend.charm})"
            } else {
                " · 勾选刷新探测资质"
            }
            val idlePart = if (friend.totalAttr > 0L || !friend.isIdle) {
                if (friend.isIdle) " · 空闲" else " · 忙碌中"
            } else {
                ""
            }
            Text(
                text = "$petPart$attrPart$idlePart",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = checked,
            onClick = { onToggle() },
            label = {
                Text(
                    text = if (checked) "已选" else "未选",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = if (checked) {
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
