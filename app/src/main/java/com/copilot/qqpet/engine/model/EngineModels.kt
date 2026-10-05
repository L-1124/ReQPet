package com.copilot.qqpet.engine.model

import com.copilot.qqpet.protocol.QQPetDirectBridge

data class PetAttributes(
    val hunger: Float,
    val maxHunger: Float,
    val clean: Float,
    val maxClean: Float,
    val level: Int = 1,
    val isFatigued: Boolean = false
)

data class HiredProgress(
    val totalSec: Long,
    val remainingSec: Long,
    val progressRatio: Double
)

data class TaskDispatchResult(
    val isSuccess: Boolean,
    val taskType: String,
    val code: Int = 0,
    val message: String = ""
)

data class PkCandidate(
    val uin: Long,
    val name: String,
    val totalPower: Int,
    val isBlacklisted: Boolean = false
)

data class StoryStatusResult(
    val code: Int,
    val remaining: Long?,
    val total: Long?,
    val storyId: String?,
    val status: Long? = null,
    val bodyNote: String? = null
)

data class PetFriendsPageResult(
    val code: Int,
    val friends: List<QQPetDirectBridge.HireableFriend>,
    val hasMore: Boolean,
    val nextCookie: String,
    val errorMsg: String?
)
