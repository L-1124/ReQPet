package com.copilot.qqpet.ui.compose

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver

internal class SettingsBackStack(initialPage: SettingsPage = SettingsPage.HOME) {
    private val pages = mutableStateListOf(SettingsPage.HOME).apply {
        if (initialPage != SettingsPage.HOME) add(initialPage)
    }

    val currentPage: SettingsPage
        get() = pages.last()

    fun navigate(page: SettingsPage) {
        if (page == currentPage) return
        if (page == SettingsPage.HOME) {
            pages.removeRange(1, pages.size)
        } else {
            pages.add(page)
        }
    }

    fun popBack(): Boolean {
        if (pages.size == 1) return false
        pages.removeAt(pages.lastIndex)
        return true
    }

    fun save(): List<String> = pages.map { it.name }

    companion object {
        val Saver = listSaver<SettingsBackStack, String>(
            save = { it.save() },
            restore = { restore(it) }
        )

        fun restore(saved: List<String>): SettingsBackStack {
            val stack = SettingsBackStack()
            if (saved.firstOrNull() != SettingsPage.HOME.name) return stack
            for (index in 1 until saved.size) {
                val page = SettingsPage.entries.firstOrNull { it.name == saved[index] }
                    ?: return SettingsBackStack()
                if (page == SettingsPage.HOME) return SettingsBackStack()
                stack.navigate(page)
            }
            return stack
        }
    }
}
