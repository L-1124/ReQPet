package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.config.TimeConfigManager
import com.copilot.qqpet.engine.config.calculateTaskEndTimeMillis
import com.copilot.qqpet.engine.model.StoryStatusResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TimeConfigManagerTest {
    @Before
    fun resetDurations() {
        TimeConfigManager.clearCache()
    }

    @After
    fun cleanUpDurations() {
        TimeConfigManager.clearCache()
    }

    @Test
    fun countdownUsesTotalRatherThanRemainingForFutureDispatches() {
        for (remaining in 12L downTo 0L) {
            TimeConfigManager.extractAndConfigureDuration(
                StoryStatusResult(code = 0, remaining = remaining, total = 3600L, storyId = "6100123")
            )

            assertEquals(3600L, TimeConfigManager.getCurrentDuration("STUDY"))
            assertEquals(3_605_000L, calculateTaskEndTimeMillis("STUDY", 5_000L))
        }
    }

    @Test
    fun invalidOrMissingTotalsDoNotOverwriteLearnedDuration() {
        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 12L, total = 4200L, storyId = "6400123")
        )
        val expected = TimeConfigManager.getAllDurations()
        val expectedCache = TimeConfigManager.getCacheStats()

        for (total in listOf(null, 0L, -1L)) {
            TimeConfigManager.extractAndConfigureDuration(
                StoryStatusResult(code = 0, remaining = 0L, total = total, storyId = "6400123")
            )

            assertEquals(expected, TimeConfigManager.getAllDurations())
            assertEquals(expectedCache, TimeConfigManager.getCacheStats())
        }
    }

    @Test
    fun failedResponsesAndUnrecognizedStoriesDoNotCalibrate() {
        TimeConfigManager.setManualDuration("STUDY", 5400L)
        val expected = TimeConfigManager.getAllDurations()
        val expectedCache = TimeConfigManager.getCacheStats()

        for (code in listOf(-1, 1)) {
            TimeConfigManager.extractAndConfigureDuration(
                StoryStatusResult(code = code, remaining = 12L, total = 900L, storyId = "6100123")
            )
            assertEquals(expected, TimeConfigManager.getAllDurations())
            assertEquals(expectedCache, TimeConfigManager.getCacheStats())
        }
        for (storyId in listOf(null, "", "9990123")) {
            TimeConfigManager.extractAndConfigureDuration(
                StoryStatusResult(code = 0, remaining = 12L, total = 900L, storyId = storyId)
            )
            assertEquals(expected, TimeConfigManager.getAllDurations())
            assertEquals(expectedCache, TimeConfigManager.getCacheStats())
        }
    }

    @Test
    fun recognizedPrefixesCalibrateOnlyTheirOwnTypeEvenWithoutRemaining() {
        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = null, total = 4200L, storyId = "6100123")
        )
        assertEquals(TimeConfigManager.TaskDurationConfig(4200L, 3600L, 1800L), TimeConfigManager.getAllDurations())

        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 12L, total = 3000L, storyId = "6400123")
        )
        assertEquals(TimeConfigManager.TaskDurationConfig(4200L, 3000L, 1800L), TimeConfigManager.getAllDurations())

        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 0L, total = 1200L, storyId = "6500123")
        )
        assertEquals(TimeConfigManager.TaskDurationConfig(4200L, 3000L, 1200L), TimeConfigManager.getAllDurations())

        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 0L, total = 1500L, storyId = "7000123")
        )
        assertEquals(TimeConfigManager.TaskDurationConfig(4200L, 3000L, 1500L), TimeConfigManager.getAllDurations())
    }

    @Test
    fun anotherTypeCalibrationPreservesManualOverrideOfPreviouslyLearnedDuration() {
        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 12L, total = 4200L, storyId = "6100123")
        )
        TimeConfigManager.setManualDuration("study", 5400L)
        TimeConfigManager.setManualDuration("ADVENTURE", 900L)

        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 12L, total = 3000L, storyId = "6400123")
        )

        assertEquals(TimeConfigManager.TaskDurationConfig(5400L, 3000L, 900L), TimeConfigManager.getAllDurations())
        assertEquals(5_405_000L, calculateTaskEndTimeMillis("study", 5_000L))
    }

    @Test
    fun clearCacheResetsBothLearnedAndManualConfiguration() {
        TimeConfigManager.extractAndConfigureDuration(
            StoryStatusResult(code = 0, remaining = 12L, total = 4200L, storyId = "6100123")
        )
        TimeConfigManager.setManualDuration("WORK", 3000L)
        TimeConfigManager.setManualDuration("ADVENTURE", 900L)

        TimeConfigManager.clearCache()

        assertEquals(TimeConfigManager.TaskDurationConfig(3600L, 3600L, 1800L), TimeConfigManager.getAllDurations())
        assertEquals(0, TimeConfigManager.getCacheStats()["cacheSize"])
        assertEquals("", TimeConfigManager.getCacheStats()["cachedEntries"])
    }
}
