package com.copilot.qqpet.ui.util

import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.component.SegmentItem
import com.copilot.qqpet.ui.component.WorkPlaceOption

object UiDescUtils {

    fun getSchoolStageDesc(stage: Int, highestStage: Int = 0): String {
        if (stage == 0) {
            val highName = when (highestStage) {
                1 -> "初级学园"
                2 -> "中级学园"
                3 -> "高级学园"
                4 -> "进修学园"
                else -> null
            }
            return if (highName != null) {
                "智能自适应 (当前最高: $highName)"
            } else {
                "智能自适应 (自动就读已解锁最高学园)"
            }
        }
        return when (stage) {
            1 -> "初级学园 (初阶课程 · 基础打底)"
            2 -> "中级学园 (进阶课程 · 技能专精)"
            3 -> "高级学园 (高阶深造 · 学府殿堂)"
            4 -> "进修学园 (最高学府 · 极限强化)"
            else -> "学园阶段 $stage"
        }
    }

    fun buildSchoolStageOptions(details: QQPetDirectBridge.SecondMapDetails?): List<SegmentItem> {
        val defaultOptions = listOf(
            SegmentItem("自适应", enabled = true),
            SegmentItem("初级", enabled = true),
            SegmentItem("中级", enabled = true),
            SegmentItem("高级", enabled = true),
            SegmentItem("进修", enabled = true)
        )
        if (details == null || details.code != 0) return defaultOptions

        val curStage = details.currentStage
        val stageItems = mutableListOf<SegmentItem>()
        stageItems.add(SegmentItem("自适应", enabled = true))

        val s1 = details.stages.find { it.stage == 1 }
        val s1Grad = curStage > 1 || (s1?.isGraduated == true)
        val s1Enable = curStage == 1
        stageItems.add(
            SegmentItem(
                if (s1Grad) "初级(已毕业)" else "初级",
                enabled = s1Enable,
                disabledTip = if (s1Grad) "初级学园已毕业（腾讯规则禁止重复就读）" else if (!s1Enable) "初级学园尚未解锁" else null
            )
        )

        val s2 = details.stages.find { it.stage == 2 }
        val s2Grad = curStage > 2 || (s2?.isGraduated == true)
        val s2Enable = curStage == 2
        stageItems.add(
            SegmentItem(
                if (s2Grad) "中级(已毕业)" else if (curStage < 2) "中级(未解锁)" else "中级",
                enabled = s2Enable,
                disabledTip = if (s2Grad) "中级学园已毕业（腾讯规则禁止重复就读）" else if (curStage < 2) "中级学园尚未解锁（需先完成初级修习）" else null
            )
        )

        val s3 = details.stages.find { it.stage == 3 }
        val s3Grad = curStage > 3 || (s3?.isGraduated == true)
        val s3Enable = curStage == 3
        stageItems.add(
            SegmentItem(
                if (s3Grad) "高级(已毕业)" else if (curStage < 3) "高级(未解锁)" else "高级",
                enabled = s3Enable,
                disabledTip = if (s3Grad) "高级学园已毕业" else if (curStage < 3) "高级学园尚未解锁（需先完成中级深造）" else null
            )
        )

        val s4Enable = curStage == 4
        stageItems.add(
            SegmentItem(
                if (!s4Enable) "进修(未解锁)" else "进修",
                enabled = s4Enable,
                disabledTip = if (!s4Enable) "进修学园尚未解锁（需先完成高级学府深造）" else null
            )
        )
        return stageItems
    }

    fun getWorkTypeDesc(careerId: Int, placeTitle: String? = null, workDetails: QQPetDirectBridge.SecondMapDetails? = null): String {
        if (careerId <= 0) {
            val starTower = workDetails?.stages?.find { it.stage == 3 }
            return if (starTower != null && starTower.limitStatus == 0) {
                "智能推荐 · 优先${starTower.title}(最高收益)"
            } else {
                "智能推荐 · 优先最高收益已解锁场所"
            }
        }
        val cleanName = (placeTitle ?: when (careerId) {
            1 -> "彩虹画室"
            2 -> "迷雾侦探所"
            3 -> "星尘魔法塔"
            4 -> "咕噜厨房"
            5 -> "竹影武馆"
            6 -> "云朵梦舍"
            7 -> "闪耀星屋"
            8 -> "风铃旅社"
            else -> "职业场所#$careerId"
        }).replace("(锁)", "").trim()
        return "$cleanName · 专属场所打工派遣"
    }

    fun buildWorkPlaceOptions(workDetails: QQPetDirectBridge.SecondMapDetails?): List<WorkPlaceOption> {
        val list = mutableListOf<WorkPlaceOption>()
        list.add(WorkPlaceOption(0, "智能推荐", enabled = true))
        if (workDetails != null && workDetails.code == 0 && workDetails.stages.isNotEmpty()) {
            for (s in workDetails.stages) {
                val isLocked = (s.limitStatus != 0)
                val rawTitle = s.title.trim()
                val realTitle = if (rawTitle.isNotEmpty() && rawTitle != "???") rawTitle else "隐藏职业"
                val displayTitle = if (isLocked) "$realTitle(锁)" else realTitle
                val tip = if (isLocked) (if (s.lockReason.isNotEmpty()) s.lockReason else "还没有解锁这个职业") else null
                list.add(WorkPlaceOption(s.stage, displayTitle, enabled = !isLocked, disabledTip = tip))
            }
        } else {
            list.add(WorkPlaceOption(1, "彩虹画室", enabled = true))
            list.add(WorkPlaceOption(2, "迷雾侦探所", enabled = true))
            list.add(WorkPlaceOption(3, "星尘魔法塔", enabled = true))
            list.add(WorkPlaceOption(4, "咕噜厨房", enabled = true))
            list.add(WorkPlaceOption(5, "竹影武馆", enabled = true))
            list.add(WorkPlaceOption(6, "云朵梦舍", enabled = true))
            list.add(WorkPlaceOption(7, "闪耀星屋", enabled = true))
            list.add(WorkPlaceOption(8, "风铃旅社", enabled = true))
        }
        return list
    }

    fun schoolStageLabel(stage: Int): String = when (stage) {
        0 -> "智能自适应"
        1 -> "初级学园"
        2 -> "中级学园"
        3 -> "高级学园"
        4 -> "进修学园"
        else -> "阶段$stage"
    }

    fun courseSubjectLabel(subject: Int): String = when (subject) {
        1 -> "智力"
        2 -> "力量"
        3 -> "魅力"
        else -> "智能轮换"
    }

    fun courseDurationLabel(duration: Int): String = when (duration) {
        1 -> "短课"
        2 -> "长课"
        else -> "任意课时"
    }

    fun workDurationLabel(duration: Int): String = when (duration) {
        1 -> "10分钟"
        2 -> "45分钟"
        3 -> "2小时"
        4 -> "4小时"
        else -> "智能挂机"
    }

    fun workTypeLabel(careerId: Int, placeTitle: String? = null): String {
        if (careerId <= 0) return "智能推荐"
        val clean = placeTitle?.replace("(锁)", "")?.trim().orEmpty()
        if (clean.isNotEmpty() && clean != "???" && clean != "隐藏职业") return clean
        return when (careerId) {
            1 -> "彩虹画室"
            2 -> "迷雾侦探所"
            3 -> "星尘魔法塔"
            4 -> "咕噜厨房"
            5 -> "竹影武馆"
            6 -> "云朵梦舍"
            7 -> "闪耀星屋"
            8 -> "风铃旅社"
            else -> "场所#$careerId"
        }
    }

    fun getHiredRecallDesc(progress: Int): String = when (progress) {
        12 -> "12% 早期保底档 (收益起跑即撤，极速刷新)"
        42 -> "42% 中期平衡档 (兼顾打工收益与体力回流)"
        72 -> "72% 收益最大档 (默认推荐 · 斩获大额金币稳妥结算)"
        else -> "$progress% 召回"
    }

    fun getCareSubtitle(energy: Int, clean: Int): String {
        return "自身体力低于 ${energy} / 清洁度低于 ${clean} 立即照料"
    }

    fun getFriendCareSubtitle(energy: Int, clean: Int): String {
        return "好友体力低于 ${energy} / 清洁度低于 ${clean} 立即照料"
    }
}
