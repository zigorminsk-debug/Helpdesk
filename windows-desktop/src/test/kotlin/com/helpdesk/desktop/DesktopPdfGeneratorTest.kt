package com.helpdesk.desktop

import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPdfGeneratorTest {

    @Test
    fun `generateTicketServiceActPdf embeds csl by contacts and handles checkmarks without text block error`() {
        val ticket = HelpdeskTicket(
            id = "37221",
            key = "CSL-37221",
            summary = "Создать отдельный рабочий стол на компьютере для Сотниковой Марии ✓ 🔥💻",
            description = "Описание заявки клиента «Сейлзмодерн»",
            issueType = "Service Request",
            isSubtask = false,
            customerRequestType = null,
            hasCustomerPortalInfo = false,
            status = "В работе",
            statusCategoryKey = "indeterminate",
            priority = "Normal",
            projectKey = "CSL",
            projectName = "CSL Helpdesk",
            reporterName = "Сейлзмодерн",
            reporterLoginOrEmail = "salesmodern",
            assigneeName = "Захаревич Игорь",
            createdIso = "2026-11-21T10:00:00.000+0300",
            createdEpochMillis = 1795244400000L,
            updatedIso = "2026-11-21T10:30:00.000+0300",
            labels = emptyList(),
            phoneNumbers = emptyList(),
            comments = emptyList(),
            webUrl = "https://jira.csl.by/browse/CSL-37221"
        )

        val pdfFile = DesktopPdfGenerator.generateTicketServiceActPdf(
            ticket = ticket,
            engineerName = "ziv@csl.by",
            performedWorks = listOf(
                "✓ Настроен удалённый рабочий стол RDP и профиль пользователя Сотниковой Марии",
                "Материалы/комплектующие: не требовались — программная настройка 🔧",
                "Затраченное время: 45 мин."
            ),
            extraNotes = "Проверка выполнена совместно с представителем «Сейлзмодерн» ✔",
            serialNumber = "SN-5CD1234ABC"
        )

        assertTrue(pdfFile.exists(), "PDF file must exist")
        assertTrue(pdfFile.length() > 800L, "PDF file must be non-empty (${pdfFile.length()} bytes)")

        val text = PDDocument.load(pdfFile).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Фабрициуса, 9"), "Header must include csl.by street address: $text")
        assertTrue(text.contains("info@csl.by"), "Header must include info@csl.by: $text")
        assertTrue(text.contains("Захаревич Игорь"), "Act must print full human name: $text")
        assertFalse(text.contains("ziv@csl.by"), "Act must not print raw login/email: $text")

        pdfFile.delete()
    }

    @Test
    fun `generateRepairReceiptPdf replaces saved email login ziv at csl by with engineer full name and csl contacts`() {
        val item = RepairEquipmentItem(
            id = "rem-1001",
            intakeNumber = "REM-1001",
            clientName = "ООО Белинвестторг",
            clientPhone = "+375291234567",
            linkedTicketKey = "CSL-37312",
            deviceCategory = "Монитор",
            deviceModel = "BenQ",
            serialNumber = "ETG9M03224SL0",
            accessories = "Без комплекта",
            externalCondition = "Б/У, следы эксплуатации",
            reportedDefect = "Включается через раз",
            repairNotes = "Диагностика и обслуживание выполнены, оборудование исправно.",
            status = RepairStatus.ISSUED,
            assignedEngineer = "ziv@csl.by"
        )

        val pdfFile = DesktopPdfGenerator.generateRepairReceiptPdf(
            item = item,
            engineerName = "Захаревич Игорь"
        )

        assertTrue(pdfFile.exists(), "Repair PDF file must exist")
        assertTrue(pdfFile.length() > 800L, "Repair PDF file must be non-empty (${pdfFile.length()} bytes)")

        val text = PDDocument.load(pdfFile).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Фабрициуса, 9"), "Header must include csl.by address: $text")
        assertTrue(text.contains("337-14-12"), "Header must include csl.by phone: $text")
        assertTrue(text.contains("info@csl.by"), "Header must include info@csl.by: $text")
        assertTrue(text.contains("Захаревич Игорь"), "Receipt must display human full name: $text")
        assertFalse(text.contains("ziv@csl.by"), "Receipt must NOT display raw email ziv@csl.by: $text")

        pdfFile.delete()
    }
}
