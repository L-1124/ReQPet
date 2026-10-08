package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.model.StoryStatusResult
import com.copilot.qqpet.engine.task.PetCycleDispatcher
import com.copilot.qqpet.protocol.ProtoWire
import com.copilot.qqpet.protocol.client.PetCareerProtocolClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FaultInjectionAndConsistencyTest {
    private var originalSettle = false
    private var originalMaster = false
    private var originalEndTime = 0L
    private var originalStoryId: String? = null
    private var originalPendingId: String? = null
    private var originalReportedId: String? = null

    @Before
    fun saveState() {
        originalSettle = PetAdventureEngine.enableSettle
        originalMaster = PetAdventureEngine.masterEnabled
        originalEndTime = PetAdventureEngine.currentTaskEndTimeMillis
        originalStoryId = PetAdventureEngine.lastActiveStoryId
        originalPendingId = PetAdventureEngine.pendingSettlementStoryId
        originalReportedId = PetAdventureEngine.lastReportedOngoingStoryId
        PetAdventureEngine.enableSettle = true
        PetAdventureEngine.currentTaskEndTimeMillis = 0L
        PetAdventureEngine.lastActiveStoryId = null
        PetAdventureEngine.pendingSettlementStoryId = null
        PetAdventureEngine.lastReportedOngoingStoryId = null
    }

    @After
    fun restoreState() {
        PetAdventureEngine.enableSettle = originalSettle
        PetAdventureEngine.masterEnabled = originalMaster
        PetAdventureEngine.currentTaskEndTimeMillis = originalEndTime
        PetAdventureEngine.lastActiveStoryId = originalStoryId
        PetAdventureEngine.pendingSettlementStoryId = originalPendingId
        PetAdventureEngine.lastReportedOngoingStoryId = originalReportedId
    }

    @Test
    fun settlementRequiresStartedStoryAndElapsedLocalDeadline() {
        val reply = statusReply()
        assertTrue(reply.isReadyToSettle)
        assertEquals(1_000L, reply.startTimestamp)
        assertEquals(3_600L, reply.total)
        PetAdventureEngine.currentTaskEndTimeMillis = 100_001L
        assertNull(PetStoryHandlers.resolveSettlementStoryId(reply, now = 100_000L))
        assertEquals("6400-story", PetStoryHandlers.resolveSettlementStoryId(reply, now = 100_001L))
    }

    @Test
    fun unstartedOrUnknownStoryDoesNotSettle() {
        for (start in listOf(null, 0L, -1L)) {
            val reply = statusReply(startTimestamp = start)
            assertFalse(reply.isReadyToSettle)
            assertNull(PetStoryHandlers.resolveSettlementStoryId(reply))
        }
        val noStory = statusReply(status = 0L)
        assertFalse(noStory.isReadyToSettle)
        assertTrue(noStory.isIdle)
        assertNull(PetStoryHandlers.resolveSettlementStoryId(noStory))
        val idleWithStoryId = PetCareerProtocolClient.parseStoryStatus(
            0, ProtoWire.message().writeBytes(1, byteArrayOf()).writeString(2, "6400-story").toByteArray(), null
        )
        assertEquals(0L, idleWithStoryId.status)
        assertNull(idleWithStoryId.remaining)
        assertTrue(idleWithStoryId.isIdle)
        val noInfo = PetCareerProtocolClient.parseStoryStatus(
            0, ProtoWire.message().writeString(2, "6400-story").toByteArray(), null
        )
        assertNull(noInfo.status)
        assertFalse(noInfo.isIdle)
        assertNull(PetStoryHandlers.resolveSettlementStoryId(noInfo))
    }

    @Test
    fun runningAndFailedQueriesCannotAuthorizePendingSettlement() {
        PetAdventureEngine.markStoryRecalled("6400-story")
        val running = statusReply(remaining = 12L)
        assertTrue(running.isOngoing)
        assertNull(PetStoryHandlers.resolveSettlementStoryId(running))
        val failed = PetCareerProtocolClient.parseStoryStatus(-102, null, "rate limit")
        assertNull(PetStoryHandlers.resolveSettlementStoryId(failed))
        val missing = PetCareerProtocolClient.parseStoryStatus(0, null, null)
        assertTrue(missing.code != 0)
        assertNull(PetStoryHandlers.resolveSettlementStoryId(missing))
        assertEquals("6400-story", PetAdventureEngine.pendingSettlementStoryId)
    }

    @Test
    fun omittedZeroFieldsFollowHostDefaultsWithoutInventingStartTime() {
        val info = ProtoWire.message().writeVarint(1, 2L).writeVarint(4, 1_000L).toByteArray()
        val reply = PetCareerProtocolClient.parseStoryStatus(
            0, ProtoWire.message().writeBytes(1, info).writeString(2, "6400-story").toByteArray(), null
        )
        assertEquals(0L, reply.remaining)
        assertTrue(reply.isReadyToSettle)
        val unknownStatus = statusReply(status = 999L)
        assertFalse(unknownStatus.isReadyToSettle)
        assertFalse(unknownStatus.isOngoing)
    }

    @Test
    fun recallRetainsSettlementIdentityUntilConfirmedSuccess() {
        PetAdventureEngine.currentTaskEndTimeMillis = 9_999L
        PetAdventureEngine.lastReportedOngoingStoryId = "6400-story"
        PetAdventureEngine.markStoryRecalled("6400-story")
        assertEquals("6400-story", PetAdventureEngine.lastActiveStoryId)
        assertEquals("6400-story", PetAdventureEngine.pendingSettlementStoryId)
        assertEquals(0L, PetAdventureEngine.currentTaskEndTimeMillis)
        assertNull(PetAdventureEngine.lastReportedOngoingStoryId)
        val idle = PetCareerProtocolClient.parseStoryStatus(0, byteArrayOf(), null)
        assertTrue(idle.isIdle)
        assertEquals("6400-story", PetStoryHandlers.resolveSettlementStoryId(idle))
        PetAdventureEngine.clearSettledStory("different-story")
        assertEquals("6400-story", PetAdventureEngine.pendingSettlementStoryId)
        PetAdventureEngine.clearSettledStory("6400-story")
        assertNull(PetAdventureEngine.pendingSettlementStoryId)
        assertNull(PetAdventureEngine.lastActiveStoryId)
        assertNull(PetStoryHandlers.resolveSettlementStoryId(idle))
    }

    @Test
    fun disabledSettlementDoesNotIssueCommand() {
        PetAdventureEngine.enableSettle = false
        assertNull(PetStoryHandlers.resolveSettlementStoryId(statusReply()))
    }

    @Test
    fun formattingExpiredTaskDoesNotClearSettlementDeadline() {
        PetAdventureEngine.masterEnabled = true
        PetAdventureEngine.currentTaskEndTimeMillis = 1L
        PetAdventureEngine.formatLiveStatusText()
        assertEquals(1L, PetAdventureEngine.currentTaskEndTimeMillis)
    }

    @Test
    fun dispatchFailureBackoffHasBoundedFirstAttemptAndCeiling() {
        assertTrue(PetCycleDispatcher.calculateFailureBackoff(1) in 45_000L..60_000L)
        assertEquals(600_000L, PetCycleDispatcher.calculateFailureBackoff(100))
    }

    private fun statusReply(
        status: Long = 2L,
        remaining: Long = 0L,
        startTimestamp: Long? = 1_000L
    ): StoryStatusResult {
        val info = ProtoWire.message().writeVarint(1, status)
            .writeVarint(2, remaining).writeVarint(3, 3_600L)
        if (startTimestamp != null) info.writeVarint(4, startTimestamp)
        val body = ProtoWire.message().writeBytes(1, info.toByteArray())
            .writeString(2, "6400-story").toByteArray()
        return PetCareerProtocolClient.parseStoryStatus(0, body, null)
    }
}
