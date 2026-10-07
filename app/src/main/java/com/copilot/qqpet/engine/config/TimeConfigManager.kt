package com.copilot.qqpet.engine.config

import android.util.Log
import com.copilot.qqpet.engine.model.StoryStatusResult
import com.copilot.qqpet.engine.task.PetWorkTask
import com.copilot.qqpet.protocol.QQPetDirectBridge

/**
 * 时间配置管理器 - 从服务器响应中提取任务时长并支持运行时重载
 *
 * 解决硬编码时长问题（3600s/1800s），动态解析服务器返回的 duration 字段
 */
object TimeConfigManager {

    /**
     * 当前配置的各任务类型时长（秒）
     */
    @Volatile
    private var configuredDurations = TaskDurationConfig(
        study = 3600L,      // 学业修习默认 1 小时
        work = 3600L,       // 小镇打工默认 1 小时
        adventure = 1800L   // 森林探险默认 30 分钟
    )

    /**
     * 任务时长数据结构
     */
    data class TaskDurationConfig(
        val study: Long,
        val work: Long,
        val adventure: Long
    )

    /**
     * 缓存最近解析的时长（基于 StoryID 前缀）
     */
    private val durationCache = mutableMapOf<String, Long>()

    /**
     * 从服务器响应中提取时长并更新配置
     */
    fun extractAndConfigureDuration(serverResponse: StoryStatusResult) {
        if (serverResponse.remaining == null || serverResponse.storyId.isNullOrEmpty()) {
            return
        }

        val storyId = serverResponse.storyId
        val remainingSec = serverResponse.remaining

        // 根据 StoryID 前缀判断任务类型
        val taskType = when {
            storyId.startsWith("6100") -> "STUDY"      // 学园修习
            storyId.startsWith("6400") -> "WORK"       // 小镇打工
            storyId.startsWith("65") || storyId.startsWith("7") -> "ADVENTURE" // 森林探险等
            else -> null
        }

        if (taskType != null) {
            // 缓存该类型的时长
            durationCache["${taskType}_"] = remainingSec

            // 重新加载配置
            reloadConfiguredDurations()

            Log.d(TAG, "[$taskType] Extracted duration: ${remainingSec}s from server response (StoryID: $storyId)")
        }
    }

    /**
     * 刷新所有任务类型的时长配置（触发全量查询）
     */
    suspend fun reloadFromServer(petId: String, bridge: QQPetDirectBridge) {
        try {
            // 查询当前外出状态获取最新时长
            val result = PetWorkTask.queryStoryStatusAwait(bridge, petId)
            extractAndConfigureDuration(result)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload durations from server: ${e.message}")
        }
    }

    /**
     * 获取当前配置的任务时长（秒）
     */
    fun getCurrentDuration(taskType: String): Long {
        return when (taskType.uppercase()) {
            "STUDY" -> configuredDurations.study
            "WORK" -> configuredDurations.work
            "ADVENTURE" -> configuredDurations.adventure
            else -> 0L
        }
    }

    /**
     * 预设值（备用配置）
     */
    fun getDefaultValue(taskType: String): Long {
        return when (taskType.uppercase()) {
            "STUDY" -> 3600L
            "WORK" -> 3600L
            "ADVENTURE" -> 1800L
            else -> 0L
        }
    }

    /**
     * 设置手动配置的时长（用于调试或特殊场景）
     */
    fun setManualDuration(taskType: String, durationSec: Long) {
        val type = taskType.uppercase()
        configuredDurations = when (type) {
            "STUDY" -> configuredDurations.copy(study = durationSec)
            "WORK" -> configuredDurations.copy(work = durationSec)
            "ADVENTURE" -> configuredDurations.copy(adventure = durationSec)
            else -> throw IllegalArgumentException("Unknown task type: $taskType")
        }
        Log.i(TAG, "Manually set $type duration to ${durationSec}s")
    }

    /**
     * 获取所有当前配置
     */
    fun getAllDurations(): TaskDurationConfig {
        return configuredDurations
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cacheSize" to durationCache.size,
            "cachedEntries" to durationCache.keys.joinToString(", "),
            "currentDurations" to mapOf(
                "study" to configuredDurations.study,
                "work" to configuredDurations.work,
                "adventure" to configuredDurations.adventure
            )
        )
    }

    /**
     * 清除所有缓存（强制下次从服务器获取）
     */
    fun clearCache() {
        durationCache.clear()
        Log.d(TAG, "Time config cache cleared")
    }

    private fun reloadConfiguredDurations() {
        val studyDuration = durationCache["STUDY_"] ?: getDefaultValue("STUDY")
        val workDuration = durationCache["WORK_"] ?: getDefaultValue("WORK")
        val adventureDuration = durationCache["ADVENTURE_"] ?: getDefaultValue("ADVENTURE")

        configuredDurations = TaskDurationConfig(
            study = studyDuration,
            work = workDuration,
            adventure = adventureDuration
        )

        Log.d(
            TAG,
            "Reloaded durations: study=${studyDuration}s, work=${workDuration}s, adventure=${adventureDuration}s"
        )
    }

    private const val TAG = "TimeConfigManager"
}

/**
 * 计算任务结束时间的便捷函数
 */
fun calculateTaskEndTimeMillis(taskType: String, currentTimeMillis: Long = System.currentTimeMillis()): Long {
    val durationSeconds = TimeConfigManager.getCurrentDuration(taskType)
    return if (durationSeconds > 0) {
        currentTimeMillis + (durationSeconds * 1000L)
    } else {
        currentTimeMillis // 如果无法解析，返回当前时间表示立即结束
    }
}
