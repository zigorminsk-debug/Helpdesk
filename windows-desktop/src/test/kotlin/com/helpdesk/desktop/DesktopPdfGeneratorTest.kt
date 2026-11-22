package com.helpdesk.desktop

import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPdfGeneratorTest {

    @Test
    fun `generateTicketServiceActPdf handles checkmarks emojis and Cyrillic without moveTo text block error`() {
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
            engineerName = "Захаревич Игорь",
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
        pdfFile.delete()
    }

    @Test
    fun `generateRepairReceiptPdf handles special Unicode symbols cleanly`() {
        val item = RepairEquipmentItem(
            id = "rem-1",
            intakeNumber = "REM-1005",
            clientName = "ООО «Сейлзмодерн»",
            clientPhone = "+375291234567",
            linkedTicketKey = "CSL-37312",
            deviceCategory = "Ноутбук 💻",
            deviceModel = "HP ProBook 450 G9",
            serialNumber = "5CD2211XYZ",
            accessories = "Блок питания ✓, сумка",
            externalCondition = "Потёртости на крышке — без трещин",
            reportedDefect = "Не включается после обновления BIOS ⚡",
            repairNotes = "✓ Восстановлен дамп BIOS, заменена термопаста MX-4",
            status = RepairStatus.READY,
            assignedEngineer = "Захаревич Игорь"
        )

        val pdfFile = DesktopPdfGenerator.generateRepairReceiptPdf(
            item = item,
            engineerName = "Захаревич Игорь"
        )

        assertTrue(pdfFile.exists(), "Repair PDF file must exist")
        assertTrue(pdfFile.length() > 800L, "Repair PDF file must be non-empty (${pdfFile.length()} bytes)")
        pdfFile.delete()
    }
}
