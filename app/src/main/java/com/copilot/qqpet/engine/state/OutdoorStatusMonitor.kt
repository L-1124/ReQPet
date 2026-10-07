package com.copilot.qqpet.engine.state

import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.engine.model.StoryStatusResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 户外状态信息
 */
data class StoryInfo(
    val storyId: String,
    val remainingSec: Long,
    val totalSec: Long
)

/**
 * 外出状态机 - 使用 sealed interface 定义明确的状态转换
 */
sealed interface AdventureState {
    /**
     * 空闲状态 - 可以派遣新任务
     */
    object Idle : AdventureState

    /**
     * 正在外出中 - 包含当前任务详情
     */
    data class OutAndAbout(val story: StoryInfo) : AdventureState

    /**
     * 离线/不可用状态
     */
    object Offline : AdventureState
}

/**
 * 户外状态监控器 - 单源真理模式消除竞态条件
 *
 * 替代之前的多次 queryStoryStatus 调用，提供统一的流式状态查询
 */
class OutdoorStatusMonitor(
    private val queryStoryStatus: suspend (String) -> StoryStatusResult
) {
    private val _statusFlow = MutableStateFlow<AdventureState>(AdventureState.Idle)
    val statusFlow: StateFlow<AdventureState> = _statusFlow.asStateFlow()

    private var monitorJob: kotlinx.coroutines.Job? = null

    /**
     * 开始监控（在协程范围内调用）
     */
    fun monitor(
        petId: String,
        scope: kotlinx.coroutines.CoroutineScope,
        pollIntervalMs: Long = 10_000L
    ): kotlinx.coroutines.Job {
        monitorJob?.cancel()

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val result = queryStoryStatus(petId)
                    updateStateFromResult(result)

                    // 成功轮询后重置失败计数
                    resetFailureCount()
                } catch (e: Exception) {
                    EngineLog.w("OutdoorStatusMonitor", "状态监控异常：${e.message}")
                    incrementFailureCount()

                    if (failureCount >= MAX_FAILURE_BEFORE_OFFLINE) {
                        transitionTo(AdventureState.Offline)
                        delay(30_000) // 故障时延长延迟
                    } else {
                        delay(15_000) // 正常轮询间隔的 1.5 倍
                    }
                }

                delay(pollIntervalMs)
            }
        }

        return monitorJob!!
    }

    /**
     * 立即更新状态（用于同步查询场景）
     */
    suspend fun updateFromQuery(petId: String): Boolean {
        return try {
            val result = queryStoryStatus(petId)
            val wasUpdated = updateStateFromResult(result)
            if (wasUpdated) {
                resetFailureCount()
            } else {
                incrementFailureCount()
            }
            true
        } catch (e: Exception) {
            EngineLog.w("OutdoorStatusMonitor", "同步状态查询失败：${e.message}")
            false
        }
    }

    /**
     * 检查是否可以派遣新任务（主循环入口）
     */
    fun shouldBeDispatchingNewTask(): Boolean =
        when (val state = _statusFlow.value) {
            is AdventureState.Idle -> true
            is AdventureState.OutAndAbout -> {
                // 任务剩余时间 <= 0 时也可以派遣新任务
                state.story.remainingSec <= 0
            }

            is AdventureState.Offline -> false
        }

    /**
     * 检查是否正在外出中
     */
    fun isCurrentlyOut(): Boolean =
        _statusFlow.value is AdventureState.OutAndAbout

    /**
     * 获取当前的故事信息（如果正在外出）
     */
    fun getCurrentStory(): StoryInfo? =
        when (val state = _statusFlow.value) {
            is AdventureState.OutAndAbout -> state.story
            else -> null
        }

    /**
     * 获取当前状态文本（用于日志）
     */
    fun getStateText(): String {
        return when (val state = _statusFlow.value) {
            is AdventureState.Idle -> "空闲"
            is AdventureState.OutAndAbout -> "外出中 (${state.story.storyId}, 剩余 ${state.story.remainingSec}s)"
            is AdventureState.Offline -> "离线"
        }
    }

    /**
     * 强制转换为空闲状态（用于调试或特殊场景）
     */
    fun forceIdle() {
        transitionTo(AdventureState.Idle)
        EngineLog.i("OutdoorStatusMonitor", "强制设置为空闲状态")
    }

    /**
     * 强制转换为离线状态
     */
    fun forceOffline() {
        transitionTo(AdventureState.Offline)
        EngineLog.i("OutdoorStatusMonitor", "强制设置为离线状态")
    }

    /**
     * 重置故障计数
     */
    internal fun resetFailureCount() {
        failureCount = 0
    }

    /**
     * 增加故障计数
     */
    internal fun incrementFailureCount() {
        failureCount++
        if (failureCount >= MAX_FAILURE_BEFORE_OFFLINE) {
            EngineLog.w("OutdoorStatusMonitor", "连续 $failureCount 次查询失败，标记为离线")
        }
    }

    /**
     * 停止监控
     */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * 清除所有状态
     */
    fun clear() {
        _statusFlow.value = AdventureState.Idle
        failureCount = 0
    }

    private fun updateStateFromResult(result: StoryStatusResult): Boolean {
        val oldState = _statusFlow.value

        // 只有当状态真正改变时才返回 true
        if (result.code == 0 && result.remaining != null && !result.storyId.isNullOrEmpty()) {
            val newStory = StoryInfo(
                storyId = result.storyId,
                remainingSec = result.remaining,
                totalSec = result.total ?: 0L
            )

            val newState = when {
                // 有剩余时间 -> 外出中
                newStory.remainingSec > 0 -> AdventureState.OutAndAbout(newStory)
                // 无剩余时间 -> 空闲
                else -> AdventureState.Idle
            }

            if (oldState != newState) {
                transitionTo(newState)
                return true
            }
        } else {
            // 查询失败或无外出记录 -> 空闲
            if (oldState != AdventureState.Idle && oldState !is AdventureState.Offline) {
                transitionTo(AdventureState.Idle)
                return true
            }
        }

        return false
    }

    private fun transitionTo(newState: AdventureState) {
        val oldState = _statusFlow.value
        if (oldState != newState) {
            EngineLog.d("OutdoorStatusMonitor", "状态切换：$oldState → $newState")
            _statusFlow.value = newState
        }
    }

    companion object {
        private const val MAX_FAILURE_BEFORE_OFFLINE = 5

        @Volatile
        private var failureCount = 0
    }
}

/**
 * 计算基于剩余时间的睡眠时长
 */
fun OutdoorStatusMonitor.computeSleepTime(currentTimeMillis: Long = System.currentTimeMillis()): Long {
    val story = getCurrentStory() ?: return 30_000L

    if (story.remainingSec <= 0) {
        // 任务已完成或即将完成，立即唤醒
        return 1_000L
    }

    // 剩余时间转换为毫秒，减去 60 秒缓冲
    val remainingMillis = (story.remainingSec - 60) * 1000L
    return maxOf(1_000L, remainingMillis)
}
