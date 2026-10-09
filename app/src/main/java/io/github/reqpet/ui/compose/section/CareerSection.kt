package io.github.reqpet.ui.compose.section

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.reqpet.HookEntry
import io.github.reqpet.engine.PetAccountGateway
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.compose.ActionRow
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SegmentedChoiceRow
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.ExpandablePanel
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.ToggleRow
import io.github.reqpet.ui.compose.dialog.HireWhitelistDialog
import io.github.reqpet.ui.util.UiDescUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HIRED_RECALL_VALUES = listOf(0, 12, 42, 72)
private val HIRED_RECALL_LABELS = listOf("关闭", "12%", "42%", "72%")
private const val HIRED_RECALL_DEFAULT = 72

@Composable
fun CareerSection(state: SettingsState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHireWhitelist by remember { mutableStateOf(false) }

    val studyEnabled = state.bool(PreferencesHelper.KEY_STUDY, false)
    val workEnabled = state.bool(PreferencesHelper.KEY_WORK, false)
    val adventureEnabled = state.bool(PreferencesHelper.KEY_ADVENTURE, false)
    val hasAnyTask = studyEnabled || workEnabled || adventureEnabled
    val schoolDetails = state.schoolDetails
    val workPlaces = state.workPlaces
    val workJobs = state.workJobs
    LaunchedEffect(Unit) {
        if (!state.previewMode) {
            try {
                val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                if (!prefs.contains(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS)) {
                    prefs.edit().putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, HIRED_RECALL_DEFAULT).apply()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    SectionHeader("任务养成")
    SettingsGroup {
        item {
            ToggleRow(
                title = "自动学习",
                checked = studyEnabled,
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_STUDY, it) },
                subtitle = schoolStageSubtitle(state, schoolDetails)
            )
            ExpandablePanel(studyEnabled) {
                StudyPanel(state = state, details = schoolDetails)
            }
        }
        item {
            ToggleRow(
                title = "自动打工",
                checked = workEnabled,
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_WORK, it) },
                subtitle = workSubtitle(state, workPlaces)
            )
            ExpandablePanel(workEnabled) {
                WorkPanel(
                    state = state,
                    workPlaces = workPlaces,
                    workJobs = workJobs,
                    onWorkTypeSelected = { careerId ->
                        state.setInt(PreferencesHelper.KEY_WORK_TYPE, careerId)
                        refreshWorkJobs(context = context, scope = scope, state = state, careerId = careerId)
                    }
                )
                ToggleRow(
                    title = "打工自动雇佣好友",
                    checked = state.bool(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, it) },
                    subtitle = "仅在已勾选的好友中，默认雇佣空闲且收益最高的好友"
                )
                ActionRow(
                    title = "选择雇佣好友白名单",
                    onClick = { showHireWhitelist = true },
                    subtitle = hireWhitelistSummary(context),
                    trailing = "管理"
                )
            }
        }
        item {
            HiredRecallPanel(state = state)
        }
        item {
            ToggleRow(
                title = "自动冒险",
                checked = state.bool(PreferencesHelper.KEY_ADVENTURE, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_ADVENTURE, it) },
                subtitle = "自动深入野外林区探秘与冒险"
            )
        }
        item {
            ToggleRow(
                title = "任务收益结算",
                checked = hasAnyTask || state.bool(PreferencesHelper.KEY_SETTLE, true),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_SETTLE, it) },
                subtitle = if (hasAnyTask) "已开启自动任务，收益结算强制开启" else "外出任务完成后检查并结算收益",
                enabled = !hasAnyTask
            )
        }
        item {
            ToggleRow(
                title = "疲惫时自动转冒险",
                checked = state.bool(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, it) },
                subtitle = "检测到疲惫收益减少时，取消打工和学习转去冒险直至恢复"
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    TaskActions(state = state)

    if (showHireWhitelist) {
        HireWhitelistDialog(
            state = state,
            onDismiss = {
                showHireWhitelist = false
                state.refresh()
            }
        )
    }
}

@Composable
private fun StudyPanel(state: SettingsState, details: QQPetDirectBridge.SecondMapDetails?) {
    val stageItems = UiDescUtils.buildSchoolStageOptions(details)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            OptionLabel("学园阶段")
            SegmentedChoiceRow(
                options = stageItems.map { it.title },
                enabled = stageItems.map { it.enabled },
                selectedIndex = state.int(PreferencesHelper.KEY_SCHOOL_STAGE, 0),
                onSelect = { state.setInt(PreferencesHelper.KEY_SCHOOL_STAGE, it) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            OptionLabel("专攻科目")
            SegmentedChoiceRow(
                options = listOf("轮换", "智力", "力量", "魅力"),
                selectedIndex = state.int(PreferencesHelper.KEY_COURSE_SUBJECT, 0),
                onSelect = { state.setInt(PreferencesHelper.KEY_COURSE_SUBJECT, it) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            OptionLabel("课时时长偏好")
            SegmentedChoiceRow(
                options = listOf("任意课时", "基础短课", "进阶长课"),
                selectedIndex = state.int(PreferencesHelper.KEY_COURSE_DURATION, 0),
                onSelect = { state.setInt(PreferencesHelper.KEY_COURSE_DURATION, it) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun WorkPanel(
    state: SettingsState,
    workPlaces: QQPetDirectBridge.SecondMapDetails?,
    workJobs: List<QQPetDirectBridge.SelectEvent>?,
    onWorkTypeSelected: (Int) -> Unit
) {
    val placeOptions = UiDescUtils.buildWorkPlaceOptions(workPlaces)
    val workTypePref = state.int(PreferencesHelper.KEY_WORK_TYPE, 0)
    val placeIndex = placeOptions.indexOfFirst { it.careerId == workTypePref }.let { if (it >= 0) it else 0 }
    val durationOptions = workDurationOptions(workJobs)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            OptionLabel("打工场所")
            SegmentedChoiceRow(
                options = placeOptions.map { it.title },
                enabled = placeOptions.map { it.enabled },
                selectedIndex = placeIndex,
                onSelect = { index -> placeOptions.getOrNull(index)?.let { onWorkTypeSelected(it.careerId) } },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            OptionLabel("打工时长偏好")
            SegmentedChoiceRow(
                options = durationOptions.labels,
                enabled = durationOptions.enabled,
                selectedIndex = state.int(PreferencesHelper.KEY_WORK_DURATION, 0),
                onSelect = { state.setInt(PreferencesHelper.KEY_WORK_DURATION, it) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
            durationOptions.tips.filterNotNull().forEach { tip ->
                Text(
                    text = tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HiredRecallPanel(state: SettingsState) {
    val current = state.int(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, HIRED_RECALL_DEFAULT)
    val selectedIndex = HIRED_RECALL_VALUES.indexOf(current).let { if (it >= 0) it else 3 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp)
    ) {
        Text(
            text = "被好友雇佣时提前召回",
            style = MaterialTheme.typography.bodyMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = UiDescUtils.getHiredRecallDesc(current),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        SegmentedChoiceRow(
            options = HIRED_RECALL_LABELS,
            selectedIndex = selectedIndex,
            onSelect = { index ->
                state.setInt(
                    PreferencesHelper.KEY_HIRED_RECALL_PROGRESS,
                    HIRED_RECALL_VALUES.getOrElse(index) { HIRED_RECALL_DEFAULT })
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}


@Preview(showBackground = true)
@Composable
private fun StudyPanelPreview() {
    MaterialTheme {
        val state = SettingsState(LocalContext.current, null, previewMode = true)
        SettingsGroup {
            item {
                StudyPanel(state = state, details = null)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HiredRecallPanelPreview() {
    MaterialTheme {
        HiredRecallPanel(state = SettingsState(LocalContext.current, null, previewMode = true))
    }
}

private class WorkDurationOptions(
    val labels: List<String>,
    val enabled: List<Boolean>,
    val tips: List<String?> = emptyList()
)

private fun workDurationOptions(jobs: List<QQPetDirectBridge.SelectEvent>?): WorkDurationOptions {
    if (jobs.isNullOrEmpty()) {
        return WorkDurationOptions(
            labels = listOf("智能挂机", "10分钟", "45分钟", "2小时", "4小时"),
            enabled = List(5) { true }
        )
    }
    val can10 = jobs.find { it.costTime.contains("10") }?.canDo ?: true
    val can45 = jobs.find { it.costTime.contains("45") }?.canDo ?: true
    val can2h = jobs.find { it.costTime.contains("2小时") }?.canDo ?: true
    val can4h = jobs.find { it.costTime.contains("4小时") }?.canDo ?: true
    return WorkDurationOptions(
        labels = listOf(
            "智能挂机",
            if (can10) "10分钟" else "10分(锁)",
            if (can45) "45分钟" else "45分(锁)",
            if (can2h) "2小时" else "2小时(锁)",
            if (can4h) "4小时" else "4小时(锁)"
        ),
        enabled = listOf(true, can10, can45, can2h, can4h),
        tips = listOf(
            null,
            if (can10) null else "10分钟兼职暂未满足解锁条件",
            if (can45) null else "45分钟兼职暂未满足解锁条件",
            if (can2h) null else "2小时兼职暂未满足解锁条件",
            if (can4h) null else "4小时兼职暂未满足解锁条件"
        )
    )
}

private fun schoolStageSubtitle(state: SettingsState, details: QQPetDirectBridge.SecondMapDetails?): String {
    val savedStage = state.int(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
    val highestStage = details?.currentStage ?: 0
    if (details == null || details.code != 0) {
        return UiDescUtils.getSchoolStageDesc(savedStage, highestStage)
    }
    val attrPart = if (details.power > 0L || details.intel > 0L || details.charm > 0L) {
        " · 力量${details.power} 智力${details.intel} 魅力${details.charm}"
    } else {
        ""
    }
    return if (savedStage == 0) {
        "智能自适应: 当前就读 ${schoolStageName(details.currentStage)}$attrPart"
    } else {
        "${UiDescUtils.getSchoolStageDesc(savedStage, highestStage)}$attrPart"
    }
}

private fun schoolStageName(stage: Int): String = when (stage) {
    1 -> "初级学园"
    2 -> "中级学园"
    3 -> "高级学园"
    4 -> "进修学园"
    else -> "第${stage}阶段学园"
}

private fun workSubtitle(state: SettingsState, workPlaces: QQPetDirectBridge.SecondMapDetails?): String {
    val workTypePref = state.int(PreferencesHelper.KEY_WORK_TYPE, 0)
    val placeOptions = UiDescUtils.buildWorkPlaceOptions(workPlaces)
    val placeIndex = placeOptions.indexOfFirst { it.careerId == workTypePref }.let { if (it >= 0) it else 0 }
    return UiDescUtils.getWorkTypeDesc(workTypePref, placeOptions.getOrNull(placeIndex)?.title, workPlaces)
}

private fun hireWhitelistSummary(context: Context): String {
    val selectedUins = PetAccountGateway.loadSavedHireFriendUins(context)
    if (selectedUins.isEmpty()) {
        return "当前未勾选好友 (未选择的好友不会雇佣 · 点击搜索勾选)"
    }
    val cachedFriends = PetAccountGateway.loadCachedHireableFriends(context)
    val matchedNames = selectedUins.map { uin ->
        val friend = cachedFriends.find { it.uin == uin }
        if (friend != null && friend.friendNick.isNotBlank()) friend.friendNick else uin.toString()
    }
    val preview = matchedNames.take(3).joinToString("、")
    val more = if (matchedNames.size > 3) " 等" else ""
    return "已勾选 ${selectedUins.size} 位好友 ($preview$more) · 优先空闲最高收益"
}

private fun refreshWorkJobs(
    context: Context,
    scope: CoroutineScope,
    state: SettingsState,
    careerId: Int
) {
    if (state.previewMode) return
    val active = HookEntry.globalEngine ?: return
    scope.launch {
        val jobs = withContext(Dispatchers.IO) {
            active.withAccountSession(context, "jobs_refresh") {
                val petId = PetAdventureEngine.cachedPetId
                    ?: active.queryOwnPetAwait().second?.also { PetAdventureEngine.saveScopedPetId(context, it) }
                if (petId.isNullOrEmpty()) return@withAccountSession null
                val (code, list) = active.querySelectEventsAwait(
                    6400L,
                    petId,
                    schoolStage = 0,
                    careerType = if (careerId > 0) careerId else 3
                )
                if (code == 0 && list.isNotEmpty()) {
                    PetAdventureEngine.cachedWorkJobs = list
                    list
                } else {
                    null
                }
            }
        }
        if (jobs != null) state.refresh()
    }
}
