package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.CommandPlatform
import com.helpdesk.jiraagent.domain.CommandTopic
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
    fun topicsAndSearchCombineWithPlatform() {
        val domain = SysadminCommandCatalog.search("", topic = CommandTopic.DOMAIN)
        assertTrue(domain.any { it.id == "ad-trust-check" })
        assertTrue(domain.any { it.id == "ad-trust-repair" })
        val repair = SysadminCommandCatalog.search("доверие", CommandPlatform.POWERSHELL, CommandTopic.DOMAIN)
        assertTrue(repair.any { it.id == "ad-trust-repair" })
        assertTrue(repair.all { it.platform == CommandPlatform.POWERSHELL && it.topic == CommandTopic.DOMAIN })
        assertTrue(SysadminCommandCatalog.search("", CommandPlatform.LINUX, CommandTopic.DOMAIN).any { it.id == "linux-realm" })
        assertTrue(SysadminCommandCatalog.search("доверие", CommandPlatform.LINUX, CommandTopic.DOMAIN).isEmpty())
        assertTrue(SysadminCommandCatalog.entries.filter { it.changesSystem }.any { it.id == "win-sfc" })
        assertTrue(SysadminCommandCatalog.entries.filter { it.changesSystem }.any { it.id == "ad-trust-repair" })
    }

    @Test
    fun copyableInstructionContainsWarningsAndExactCommand() {
        val entry = SysadminCommandCatalog.entries.single { it.id == "ad-trust-repair" }
        val text = SysadminCommandCatalog.formatInstructions(entry)
        assertTrue(text.contains(entry.instructions))
        assertTrue(text.contains(entry.impact))
        assertTrue(text.endsWith(entry.command))
    }

    @Test
    fun domainRepairIsRestrictedAndRequestsCredentialsInteractively() {
        val entry = SysadminCommandCatalog.entries.single { it.id == "ad-trust-repair" }
        assertTrue(entry.changesSystem)
        assertTrue(entry.command.contains("PartOfDomain"))
        assertTrue(entry.command.contains("DomainRole -ge 4"))
        assertTrue(entry.command.contains("Get-Credential"))
        assertTrue(entry.command.contains("Test-ComputerSecureChannel -Repair -Credential"))
        assertTrue(entry.impact.contains("НЕ для контроллеров домена"))
        assertTrue(entry.instructions.contains("DNS"))
    }

    @Test
    fun scriptsThatModifyStateRequireExplicitConfirmation() {
        for (id in listOf("ps-service", "ps-dnsflush", "ad-trust-repair")) {
            val entry = SysadminCommandCatalog.entries.single { it.id == id }
            assertTrue(entry.command.startsWith("& {"))
            assertTrue(entry.command.contains("-ceq 'YES'"))
            assertTrue(entry.impact.contains("Изменяет"))
            assertTrue(entry.instructions.contains("администратор", ignoreCase = true))
        }
    }
}
