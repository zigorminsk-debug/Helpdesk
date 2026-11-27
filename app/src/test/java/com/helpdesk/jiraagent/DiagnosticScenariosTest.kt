package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.DiagnosticScenarios
import com.helpdesk.jiraagent.domain.ScenarioStepResult
import com.helpdesk.jiraagent.domain.ScenarioStepStatus
import org.junit.Assert.*
import org.junit.Test

class DiagnosticScenariosTest {
    @Test
    fun checklistsHaveUniqueStableIdsAndOnlyDocumentedReadOnlyCommands() {
        val scenarios = DiagnosticScenarios.entries
        assertEquals(setOf("network", "domain", "printer", "storage", "service"), scenarios.map { it.id }.toSet())
        assertEquals(scenarios.size, scenarios.map { it.id }.distinct().size)
        scenarios.forEach { scenario ->
            assertTrue(scenario.steps.size >= 4)
            assertEquals(scenario.steps.size, scenario.steps.map { it.id }.distinct().size)
            scenario.steps.forEach { step ->
                val commands = step.commands()
                assertEquals(step.commandIds.size + step.extraCommands.size, commands.size)
                assertTrue(step.instruction.isNotBlank())
                commands.forEach { command ->
                    assertFalse(command.changesSystem)
                    assertTrue(command.title.isNotBlank())
                    assertTrue(command.command.isNotBlank())
                    assertTrue(command.description.isNotBlank())
                    assertTrue(command.instructions.isNotBlank())
                    assertTrue(command.impact.isNotBlank())
                }
            }
        }
    }

    @Test
    fun domainChecklistKeepsControllerWarningAndGuardedMemberTest() {
        val domain = DiagnosticScenarios.entries.single { it.id == "domain" }
        assertTrue(domain.introduction.contains("контроллер"))
        val trust = domain.steps.single { it.id == "trust" }
        assertTrue(trust.instruction.contains("не для контроллера"))
        val command = trust.commands().single()
        assertTrue(command.command.contains("DomainRole"))
        assertTrue(command.command.contains("Test-ComputerSecureChannel"))
        assertFalse(command.command.contains("-Repair"))
    }

    @Test
    fun reportIncludesEveryStepWithoutInventingResults() {
        val scenario = DiagnosticScenarios.entries.single { it.id == "network" }
        val results = mapOf("local" to ScenarioStepResult(ScenarioStepStatus.ISSUE, "DNS не отвечает"),
            "dns" to ScenarioStepResult(ScenarioStepStatus.NOT_APPLICABLE, "IP без имени"))
        val text = DiagnosticScenarios.formatReport(scenario, "ПК-1, с 10:00", results, "Передано сетевой группе")
        assertTrue(text.contains("ПК-1, с 10:00"))
        assertTrue(text.contains("1. Масштаб и симптомы: Не проверено"))
        assertTrue(text.contains("2. Подключение и адреса: Есть проблема"))
        assertTrue(text.contains("DNS не отвечает"))
        assertTrue(text.contains("3. Разрешение имени: Не применимо"))
        assertTrue(text.contains("Передано сетевой группе"))
        assertFalse(text.contains("ipconfig /all"))
    }

    @Test
    fun emptyReportExplicitlyMarksMissingContextAndNextSteps() {
        val scenario = DiagnosticScenarios.entries.first()
        val text = DiagnosticScenarios.formatReport(scenario, "  ", emptyMap(), "")
        assertTrue(text.contains("Объект и симптомы: не указано"))
        assertTrue(text.contains("Дальнейшие действия / эскалация: не указано"))
        assertEquals(scenario.steps.size, Regex("Не проверено").findAll(text).count())
    }
}
