package io.github.reqpet.ui.model

/** 分段选择项；disabledTip 供界面解释为何不可选。 */
data class SegmentItem(
    val title: String,
    val enabled: Boolean = true,
    val disabledTip: String? = null
)

data class WorkPlaceOption(
    val careerId: Int,
    val title: String,
    val enabled: Boolean = true,
    val disabledTip: String? = null
)