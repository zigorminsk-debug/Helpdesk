package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.DomainDiagnosticRole
import com.helpdesk.jiraagent.domain.DomainDiagnostics
import org.junit.Assert.*
import org.junit.Test

class DomainDiagnosticsTest {
    @Test
    fun eachRoleHasDocumentedNonMutatingDiagnosticsWithLocations() {
        DomainDiagnosticRole.entries.forEach { role ->
            val checks = DomainDiagnostics.checks(role)
            assertTrue(checks.isNotEmpty())
            assertEquals(checks.size, checks.map { it.command.id }.distinct().size)
            checks.forEach {
                assertTrue(it.location.isNotBlank())
                assertTrue(it.guidance.isNotBlank())
                assertFalse(it.command.changesSystem)
                assertTrue(it.command.instructions.isNotBlank())
                assertTrue(it.command.impact.isNotBlank())
                assertTrue(it.command.command.isNotBlank())
            }
        }
    }

    @Test
    fun memberChannelTestAndRepairAreNotAvailableForDcOrRsat() {
        val member = DomainDiagnostics.checks(DomainDiagnosticRole.MEMBER).map { it.command.id }
        assertTrue(member.contains("ad-trust-check"))
        for (role in listOf(DomainDiagnosticRole.CONTROLLER, DomainDiagnosticRole.RSAT)) {
            val commands = DomainDiagnostics.checks(role).map { it.command.id }
            assertFalse(commands.contains("ad-trust-check"))
            assertFalse(commands.contains("ad-trust-repair"))
            assertNull(DomainDiagnostics.repairFor(role))
        }
        assertTrue(DomainDiagnosticRole.CONTROLLER.warning.contains("Test-ComputerSecureChannel"))
    }

    @Test
    fun repairUsesFailClosedRoleCheckAndInteractiveConfirmation() {
        val repair = DomainDiagnostics.repairFor(DomainDiagnosticRole.MEMBER)!!
        assertTrue(repair.changesSystem)
        assertTrue(repair.command.contains("${'$'}null -eq ${'$'}computer.DomainRole"))
        assertTrue(repair.command.contains("DomainRole -notin @(1, 3)"))
        assertTrue(repair.command.contains("PartOfDomain"))
        assertTrue(repair.command.indexOf("Read-Host") < repair.command.indexOf("Get-Credential"))
        assertTrue(repair.command.indexOf("Get-Credential") < repair.command.indexOf("Test-ComputerSecureChannel -Repair"))
        assertTrue(repair.command.contains("-cne 'YES'"))
        val check = DomainDiagnostics.checks(DomainDiagnosticRole.MEMBER).single { it.command.id == "ad-trust-check" }.command
        assertTrue(check.command.contains("${'$'}null -eq ${'$'}computer.DomainRole"))
        assertTrue(check.command.contains("DomainRole -notin @(1, 3)"))
        assertFalse(check.command.contains("-Repair"))
    }

    @Test
    fun dcDnsCheckDoesNotUseDefaultDnsAllOrDynamicUpdate() {
        val dns = DomainDiagnostics.checks(DomainDiagnosticRole.CONTROLLER).single { it.command.id == "ad-dns-basic" }.command
        assertEquals("dcdiag /test:DNS /DnsBasic /v", dns.command)
        assertFalse(dns.command.contains("/DnsAll", ignoreCase = true))
        assertFalse(dns.command.contains("/DnsDynamicUpdate", ignoreCase = true))
        assertFalse(dns.command.contains("/fix", ignoreCase = true))
    }
}
