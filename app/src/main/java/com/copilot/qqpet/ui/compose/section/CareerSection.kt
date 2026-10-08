package com.copilot.qqpet.ui.compose.section

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAccountGateway
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.ActionRow
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.ExpandablePanel
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SegmentedChoiceRow
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.ToggleRow
import com.copilot.qqpet.ui.compose.dialog.HireWhitelistDialog
import com.copilot.qqpet.ui.util.UiDescUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HIRED_RECALL_VALUES = listOf(0, 12, 42, 72)
private val HIRED_RECALL_LABELS = listOf("关闭", "12% 极速", "42% 均衡", "72% 顶格")
private const val HIRED_RECALL_DEFAULT = 72

@Composable
fun CareerSection(state: SettingsState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHireWhitelist by remember { mutableStateOf(false) }

    val studyEnabled = state.bool(PreferencesHelper.KEY_STUDY, false)
    val workEnabled = state.bool(PreferencesHelper.KEY_WORK, false)
    val schoolDetails = state.schoolDetails
    val workPlaces = state.workPlaces
    val workJobs = state.workJobs

    LaunchedEffect(Unit) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            if (!prefs.contains(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS)) {
                prefs.edit().putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, HIRED_RECALL_DEFAULT).apply()
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    SectionHeader("自动轮转调度")
    SettingsGroup {
        item {
            ToggleRow(
                title = "进阶学力研修",
                checked = studyEnabled,
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_STUDY, it) },
                subtitle = schoolStageSubtitle(state, schoolDetails)
            )
            ExpandablePanel(studyEnabled) {
                CardDivider()
                StudyPanel(state = state, details = schoolDetails)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        item {
            ToggleRow(
                title = "全自动打工派遣",
                checked = workEnabled,
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_WORK, it) },
                subtitle = workSubtitle(state, workPlaces)
            )
            ExpandablePanel(workEnabled) {
                CardDivider()
                WorkPanel(
                    state = state,
                    workPlaces = workPlaces,
                    workJobs = workJobs,
                    onWorkTypeSelected = { careerId ->
                        state.setInt(PreferencesHelper.KEY_WORK_TYPE, careerId)
                        refreshWorkJobs(context = context, scope = scope, state = state, careerId = careerId)
                    }
                )
                CardDivider()
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
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        item {
            HiredRecallPanel(state = state)
        }
    }

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
    ) {
        OptionLabel("学园阶段")
        SegmentedChoiceRow(
            options = stageItems.map { it.title },
            enabled = stageItems.map { it.enabled },
            selectedIndex = state.int(PreferencesHelper.KEY_SCHOOL_STAGE, 0),
            onSelect = { state.setInt(PreferencesHelper.KEY_SCHOOL_STAGE, it) },
            modifier = Modifier.padding(vertical = 4.dp)
        )
        OptionLabel("专攻科目")
        SegmentedChoiceRow(
            options = listOf("轮换", "智力", "力量", "魅力"),
            selectedIndex = state.int(PreferencesHelper.KEY_COURSE_SUBJECT, 0),
            onSelect = { state.setInt(PreferencesHelper.KEY_COURSE_SUBJECT, it) },
            modifier = Modifier.padding(vertical = 4.dp)
        )
        OptionLabel("课时时长偏好")
        SegmentedChoiceRow(
            options = listOf("任意课时", "基础短课", "进阶长课"),
            selectedIndex = state.int(PreferencesHelper.KEY_COURSE_DURATION, 0),
            onSelect = { state.setInt(PreferencesHelper.KEY_COURSE_DURATION, it) },
            modifier = Modifier.padding(vertical = 4.dp)
        )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp)
    ) {
        OptionLabel("打工场所")
        SegmentedChoiceRow(
            options = placeOptions.map { it.title },
            enabled = placeOptions.map { it.enabled },
            selectedIndex = placeIndex,
            onSelect = { index -> placeOptions.getOrNull(index)?.let { onWorkTypeSelected(it.careerId) } },
            modifier = Modifier.padding(vertical = 4.dp)
        )
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
            text = "被雇佣打工提前召回",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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
        StudyPanel(state = SettingsState(LocalContext.current, null), details = null)
    }
}

@Preview(showBackground = true)
@Composable
private fun HiredRecallPanelPreview() {
    MaterialTheme {
        HiredRecallPanel(state = SettingsState(LocalContext.current, null))
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
    val matchedNames = selectedUins.mapNotNull { uin ->
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
    val active = HookEntry.globalEngine ?: return
    scope.launch {
        val jobs = withContext(Dispatchers.IO) {
            active.withAccountSession(context) {
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
