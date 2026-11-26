package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.CommandPlatform
import com.helpdesk.jiraagent.domain.SysadminCommandCatalog
import org.junit.Assert.*
import org.junit.Test

class SysadminCommandCatalogTest {
    @Test
    fun everyEntryHasUniqueIdAndCompleteCopyableInstructions() {
        val entries = SysadminCommandCatalog.entries
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertTrue(CommandPlatform.entries.all { platform -> entries.any { it.platform == platform } })
        entries.forEach { entry ->
            assertTrue(entry.title.isNotBlank())
            assertTrue(entry.description.isNotBlank())
            assertTrue(entry.instructions.isNotBlank())
            assertTrue(entry.impact.isNotBlank())
            assertTrue(entry.command.isNotBlank())
            assertFalse(entry.command.contains("\r"))
        }
    }

    @Test
    fun filtersByPlatformAndCaseInsensitiveSearchAcrossDetails() {
        assertTrue(SysadminCommandCatalog.search("   ").size == SysadminCommandCatalog.entries.size)
        assertTrue(SysadminCommandCatalog.search("DNS", CommandPlatform.WINDOWS).all { it.platform == CommandPlatform.WINDOWS })
        assertTrue(SysadminCommandCatalog.search("dNs", CommandPlatform.LINUX).any { it.id == "linux-dns" })
        assertTrue(SysadminCommandCatalog.search("Restart-Service", CommandPlatform.POWERSHELL).any { it.id == "ps-service" })
        assertTrue(SysadminCommandCatalog.search("очистка DNS", CommandPlatform.LINUX).isEmpty())
        assertTrue(SysadminCommandCatalog.search("несуществующая-команда-xyz").isEmpty())
    }

    @Test
    fun scriptsThatModifyStateRequireExplicitConfirmation() {
        for (id in listOf("ps-service", "ps-dnsflush")) {
            val entry = SysadminCommandCatalog.entries.single { it.id == id }
            assertTrue(entry.command.startsWith("& {"))
            assertTrue(entry.command.contains("-ceq 'YES'"))
            assertTrue(entry.impact.contains("Изменяет"))
            assertTrue(entry.instructions.contains("администратор", ignoreCase = true))
        }
    }
}
