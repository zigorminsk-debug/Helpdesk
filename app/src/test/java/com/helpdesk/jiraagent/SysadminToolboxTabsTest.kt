package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.SysadminToolboxTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SysadminToolboxTabsTest {
    @Test
    fun commandsAreFirstAndEveryLogicalTabHasAUniquePosition() {
        val tabs = SysadminToolboxTabs.ordered
        assertEquals(SysadminToolboxTabs.COMMANDS, tabs.first().id)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7), tabs.map { it.id }.toSet())
        assertEquals(tabs.size, tabs.map { it.id }.distinct().size)
        tabs.forEachIndexed { position, tab ->
            assertEquals(position, SysadminToolboxTabs.positionFor(tab.id))
            assertEquals(tab.id, SysadminToolboxTabs.validIdOrDefault(tab.id))
            assertTrue(tab.androidTitle.isNotBlank())
            assertTrue(tab.desktopTitle.isNotBlank())
        }
    }

    @Test
    fun watchlistShortcutStillSelectsWatchlistAfterReordering() {
        assertEquals(SysadminToolboxTabs.WATCHLIST, SysadminToolboxTabs.validIdOrDefault(3))
        val position = SysadminToolboxTabs.positionFor(3)
        assertEquals(SysadminToolboxTabs.WATCHLIST, SysadminToolboxTabs.ordered[position].id)
        assertEquals(SysadminToolboxTabs.WAKE_ON_LAN, SysadminToolboxTabs.validIdOrDefault(-1))
    }
}
