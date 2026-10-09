package io.github.reqpet.engine.model

import io.github.reqpet.protocol.QQPetDirectBridge

/**
 * 学业研修智能派发入参
 */
data class StudyDispatchParam(
    val studyMode: Int = 0,
    val customSchoolStage: Int = 0,
    val customCourseSubject: Int = 0,
    val customCourseDuration: Int = 0,
    val enableFatigueToAdventure: Boolean = true,
    val studyAttributeCursor: Int = 0,
    val learnedStudySubEvent: Long? = null,
    val learnedStudyName: String? = null
)

/**
 * 学业研修派发结果
 */
data class StudyDispatchResult(
    val code: Int,
    val storyId: String? = null,
    val courseName: String? = null,
    val subEventType: Long? = null,
    val isFatigued: Boolean = false,
    val fatigueTip: String? = null,
    val errorMsg: String? = null
) {
    val isSuccess: Boolean get() = code == 0 && !storyId.isNullOrEmpty()
}

/**
 * 小镇打工智能派发入参
 */
data class WorkDispatchParam(
    val workMode: Int = 0,
    val customWorkType: Int = 0,
    val customWorkDuration: Int = 0,
    val enableHireFriend: Boolean = true,
    val enableFatigueToAdventure: Boolean = true,
    val cachedWorkPlaces: QQPetDirectBridge.SecondMapDetails? = null,
    val hireCandidates: List<QQPetDirectBridge.HireableFriend> = emptyList(),
    val workJobCursor: Int = 0,
    val learnedWorkSubEvent: Long? = null,
    val learnedWorkName: String? = null
)

/**
 * 小镇打工派发结果
 */
data class WorkDispatchResult(
    val code: Int,
    val storyId: String? = null,
    val jobName: String? = null,
    val placeName: String? = null,
    val subEventType: Long? = null,
    val hiredFriend: QQPetDirectBridge.HireableFriend? = null,
    val isFatigued: Boolean = false,
    val fatigueTip: String? = null,
    val errorMsg: String? = null
) {
    val isSuccess: Boolean get() = code == 0 && !storyId.isNullOrEmpty()
}
