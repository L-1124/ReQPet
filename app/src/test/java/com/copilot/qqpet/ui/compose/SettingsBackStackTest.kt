package com.copilot.qqpet.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackStackTest {
    @Test
    fun `back returns the immediate parent and leaves root back to the host`() {
        val stack = SettingsBackStack()
        stack.navigate(SettingsPage.TASKS)
        stack.navigate(SettingsPage.CARE)
        stack.navigate(SettingsPage.PK)

        assertTrue(stack.popBack())
        assertEquals(SettingsPage.CARE, stack.currentPage)
        assertTrue(stack.popBack())
        assertEquals(SettingsPage.TASKS, stack.currentPage)
        assertTrue(stack.popBack())
        assertEquals(SettingsPage.HOME, stack.currentPage)
        assertFalse(stack.popBack())
        assertEquals(SettingsPage.HOME, stack.currentPage)
    }

    @Test
    fun `repeated destination does not require an extra back press`() {
        val stack = SettingsBackStack()
        stack.navigate(SettingsPage.SOCIAL)
        stack.navigate(SettingsPage.SOCIAL)

        assertTrue(stack.popBack())
        assertEquals(SettingsPage.HOME, stack.currentPage)
        assertFalse(stack.popBack())
    }

    @Test
    fun `restored history retains each parent rather than only the current page`() {
        val original = SettingsBackStack()
        original.navigate(SettingsPage.TASKS)
        original.navigate(SettingsPage.DIAGNOSTICS)
        val restored = SettingsBackStack.restore(original.save())

        assertEquals(SettingsPage.DIAGNOSTICS, restored.currentPage)
        assertTrue(restored.popBack())
        assertEquals(SettingsPage.TASKS, restored.currentPage)
        assertTrue(restored.popBack())
        assertEquals(SettingsPage.HOME, restored.currentPage)
        assertFalse(restored.popBack())
    }

    @Test
    fun `explicit home navigation clears nested history`() {
        val stack = SettingsBackStack()
        stack.navigate(SettingsPage.CARE)
        stack.navigate(SettingsPage.REWARDS)
        stack.navigate(SettingsPage.HOME)

        assertEquals(SettingsPage.HOME, stack.currentPage)
        assertFalse(stack.popBack())
        stack.navigate(SettingsPage.PK)
        assertTrue(stack.popBack())
        assertEquals(SettingsPage.HOME, stack.currentPage)
    }

    @Test
    fun `invalid saved routes recover to a usable root`() {
        val histories = listOf(
            emptyList(),
            listOf("TASKS"),
            listOf("HOME", "TASKS", "REMOVED_PAGE"),
            listOf("HOME", "TASKS", "HOME", "PK")
        )
        for (history in histories) {
            val restored = SettingsBackStack.restore(history)
            assertEquals(SettingsPage.HOME, restored.currentPage)
            assertFalse(restored.popBack())
            restored.navigate(SettingsPage.CARE)
            assertEquals(SettingsPage.CARE, restored.currentPage)
            assertTrue(restored.popBack())
            assertEquals(SettingsPage.HOME, restored.currentPage)
        }
    }

    @Test
    fun `a repeated destination during back preserves reverse direction until a real push`() {
        val stack = SettingsBackStack()
        stack.navigate(SettingsPage.TASKS)
        stack.navigate(SettingsPage.CARE)
        assertTrue(stack.popBack())
        assertEquals(SettingsPage.TASKS, stack.currentPage)
        assertTrue(stack.isBackNavigation)

        stack.navigate(SettingsPage.TASKS)
        assertTrue(stack.isBackNavigation)
        assertTrue(stack.popBack())
        assertEquals(SettingsPage.HOME, stack.currentPage)

        stack.navigate(SettingsPage.PK)
        assertFalse(stack.isBackNavigation)
        assertEquals(SettingsPage.PK, stack.currentPage)
        assertTrue(stack.popBack())
        assertEquals(SettingsPage.HOME, stack.currentPage)
    }
}
