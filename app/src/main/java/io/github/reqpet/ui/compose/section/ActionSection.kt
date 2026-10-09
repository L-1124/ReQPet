package io.github.reqpet.ui.compose.section

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.github.reqpet.ui.compose.QPetExpressiveTheme
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.dialog.ConfirmDialog

private data class ActionItemDef(
    val title: String,
    val confirmTitle: String,
    val confirmMessage: String,
    val confirmBtnText: String,
    val actionCmd: String,
    val isBold: Boolean = false,
    val isDestructive: Boolean = false
)

private val RECALL_ACTION_ITEMS = listOf(
    ActionItemDef(
        "立即召回宠物回家 (中断当前打工/学习)", "确认召回宠物回家？",
        "此操作将强制中断小宠当前正在进行的打工或学习派遣，提前返程回家。",
        "确认召回", "recall", isBold = true, isDestructive = true
    )
)

private val TASK_ACTION_ITEMS = listOf(
    ActionItemDef(
        "立即启程学习", "立即启程学习？",
        "将根据设定的学府与专攻科目偏好，立即为小宠安排官方课程研修。",
        "立即学习", "school"
    ),
    ActionItemDef(
        "立即派遣打工", "立即派遣打工？",
        "将根据设定的打工场所与时长偏好，立即为小宠开启新一轮勤劳打工。",
        "立即打工", "work"
    ),
    ActionItemDef(
        "立即野外探险", "立即野外探险？",
        "将立即启程前往神秘森林，开启野外探秘与修行历练。",
        "立即探险", "adventure"
    ),
    ActionItemDef(
        "立即结算任务收益", "结算任务收益？",
        "将请求结算当前记录的外出任务收益；任务尚未完成时可能无法领取。",
        "立即结算", "settle"
    )
)

private val CARE_ACTION_ITEMS = listOf(
    ActionItemDef(
        "立即帮全部好友喂食与洗澡", "立即帮好友宠物喂食洗澡？",
        "将立即检测全部养宠好友的实时体力与清洁度，低于设定阈值时自动帮好友喂食与搓澡。",
        "立即照料好友", "friend_care"
    )
)

private val SOCIAL_ACTION_ITEMS = listOf(
    ActionItemDef(
        "立即回踩访客 (互相踩踩)", "立即回踩访客？",
        "将拉取最近造访小家的记录，并依次向未回赠的好友与陌生访客发起回踩送心。",
        "立即回礼", "like_back"
    ),
    ActionItemDef(
        "立即主动串门踩踩 (好友+随机陌生人)", "立即主动串门踩踩？",
        "将自动筛选今日尚未踩过的好友与随机陌生小宠，保持拟人离散间隔主动串门送心。",
        "立即串门", "active_visit"
    )
)

private val PK_ACTION_ITEMS = listOf(
    ActionItemDef(
        "立即发起 PK 挑战", "确认发起自动 PK 对决？",
        "将自动筛选三维属性低于我方的对手（包含好友与访客陌生人），每次随机休眠1~3分钟，连打10场自动领奖。",
        "立即对决", "pk_auto"
    )
)

private val REWARD_ACTION_ITEMS = listOf(
    ActionItemDef(
        "立即领取金币福袋", "立即领取金币福袋？",
        "将立即扫描自己小窝及全部好友小窝，发现掉落福袋时自动拆袋领取金币奖励。",
        "立即拆福袋", "coinbag"
    )
)

@Composable
fun OverviewActions(state: SettingsState) {
    ActionGroupSection(
        title = "手动即时指令",
        items = if (state.hasOngoingTask) RECALL_ACTION_ITEMS else emptyList(),
        state = state
    )
}

@Composable
fun TaskActions(state: SettingsState) {
    ActionGroupSection(
        title = "手动任务操作",
        items = TASK_ACTION_ITEMS,
        state = state
    )
}

@Composable
fun CareActions(state: SettingsState) {
    ActionGroupSection(
        title = "手动照料操作",
        items = CARE_ACTION_ITEMS,
        state = state
    )
}

@Composable
fun SocialActions(state: SettingsState) {
    ActionGroupSection(
        title = "手动社交操作",
        items = SOCIAL_ACTION_ITEMS,
        state = state
    )
}

@Composable
fun PkActions(state: SettingsState) {
    ActionGroupSection(
        title = "手动对决操作",
        items = PK_ACTION_ITEMS,
        state = state
    )
}

@Composable
fun RewardActions(state: SettingsState) {
    ActionGroupSection(
        title = "手动福袋操作",
        items = REWARD_ACTION_ITEMS,
        state = state
    )
}

@Composable
private fun ActionGroupSection(
    title: String,
    items: List<ActionItemDef>,
    state: SettingsState
) {
    if (items.isEmpty()) return
    var pending by remember { mutableStateOf<ActionItemDef?>(null) }

    SectionHeader(title)
    SettingsGroup {
        items(items) { item ->
            ActionCommandRow(item) { pending = item }
        }
    }

    pending?.let { item ->
        ConfirmDialog(
            title = item.confirmTitle,
            message = item.confirmMessage,
            confirmText = item.confirmBtnText,
            isDestructive = item.isDestructive,
            onConfirm = {
                state.action(item.actionCmd)
                pending = null
            },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun ActionCommandRow(item: ActionItemDef, onClick: () -> Unit) {
    val color = if (item.isDestructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.title,
            style = if (item.isBold) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
            color = color
        )
    }
}

@Preview(name = "手动操作颜色", showBackground = true, widthDp = 412)
@Composable
private fun ManualActionsPreview() {
    QPetExpressiveTheme(dark = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                SectionHeader("手动操作")
                SettingsGroup {
                    listOf(
                        TASK_ACTION_ITEMS, CARE_ACTION_ITEMS, SOCIAL_ACTION_ITEMS,
                        PK_ACTION_ITEMS, REWARD_ACTION_ITEMS, RECALL_ACTION_ITEMS
                    ).forEach { actions ->
                        items(actions) { item ->
                            ActionCommandRow(item, onClick = {})
                        }
                    }
                }
            }
        }
    }
}
