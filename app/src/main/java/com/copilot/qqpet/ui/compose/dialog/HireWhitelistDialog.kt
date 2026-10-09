package com.copilot.qqpet.ui.compose.dialog

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireWhitelistDialog(state: SettingsState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isInspection = LocalInspectionMode.current
    val sheetState = rememberBottomSheetState(
        initialValue = if (isInspection) SheetValue.Expanded else SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
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
            if (!isInspection) {
                Toast.makeText(context, "引擎尚未就绪，请稍候再试", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (refreshing) return
        refreshing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                active.withAccountSession(context, "friends_refresh") {
                    active.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = true)
                }
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
            val updated = withContext(Dispatchers.IO) {
                active.withAccountSession(context, "friend_enrich") {
                    val enriched = active.enrichFriendDetailsAwait(friend)
                    val cached = PetAccountGateway.loadCachedHireableFriends(context).toMutableList()
                    val index = cached.indexOfFirst { it.uin == friend.uin }
                    if (index < 0) return@withAccountSession false
                    cached[index] = enriched
                    PetAccountGateway.saveCachedHireableFriends(context, cached)
                    true
                }
            }
            if (updated == true) state.refresh()
        }
    }

    LaunchedEffect(Unit) {
        if (!isInspection && state.hireableFriends.isEmpty()) refreshFriends()
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
                text = "选择雇佣好友白名单",
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
                text = "未勾选的好友不会雇佣；已勾选好友中默认优先雇佣空闲且总资质最高者。",
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
                    text = if (refreshing) "正在同步..." else "已选 ${selected.size} / ${friends.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (selected.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            selected.clear()
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
                TextButton(onClick = { refreshFriends() }) {
                    Text(text = "刷新", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        refreshing -> "正在拉取养宠好友列表，请稍候..."
                        keyword.isNotEmpty() -> "未找到匹配「$keyword」的养宠好友"
                        else -> "暂无数据，请点击「刷新」"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
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
private fun HireFriendRow(
    friend: QQPetDirectBridge.HireableFriend,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${friend.friendNick.ifEmpty { "QQ好友" }} (${friend.uin})",
                style = if (checked) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
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
        Checkbox(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HireWhitelistDialogPreview() {
    MaterialTheme {
        HireWhitelistDialog(
            state = SettingsState(LocalContext.current, null, previewMode = true),
            onDismiss = {}
        )
    }
}
