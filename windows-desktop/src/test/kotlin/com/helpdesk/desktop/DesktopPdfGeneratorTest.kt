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

    @Test
    fun `desktop sound player builds valid 16 bit stereo RIFF WAV and desktop schedule defaults to 24x7`() {
        val (pcm, wav) = DesktopSoundPlayer.buildStereoPcmAndWav(
            listOf(587.33 to 120, 783.99 to 150)
        )
        assertTrue(pcm.isNotEmpty(), "Stereo PCM must not be empty")
        assertTrue(pcm.size % 4 == 0, "16-bit 2-channel stereo frames must be 4-byte aligned")
        assertTrue(wav.size == 44 + pcm.size, "RIFF WAV header must be 44 bytes")
        assertTrue(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        assertTrue(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")

        val defaultState = DesktopPersistedState()
        assertFalse(
            defaultState.respectWorkScheduleOnDesktop,
            "Desktop tray notifications must default to 24/7 active mode so weekends/evenings are never muted unexpectedly"
        )
    }

    @Test
    fun `tray badge renderer and repair Excel CSV exporter produce valid UTF8 BOM semicolon output`() {
        val base = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val cleanIcon = DesktopTrayIconRenderer.renderTrayIcon(base, unreadCount = 0)
        val badgedIcon = DesktopTrayIconRenderer.renderTrayIcon(base, unreadCount = 12)
        assertTrue(cleanIcon.width == 64 && cleanIcon.height == 64)
        assertTrue(badgedIcon.width == 64 && badgedIcon.height == 64)
        // Bottom-right corner pixel area must contain non-transparent badge pixels when unreadCount > 0
        var nonTransparentBadgePixels = 0
        for (y in 36 until 62) {
            for (x in 30 until 62) {
                if (((badgedIcon.getRGB(x, y) ushr 24) and 0xFF) > 0) {
                    nonTransparentBadgePixels++
                }
            }
        }
        assertTrue(nonTransparentBadgePixels > 100, "Expected visible badge pixels in bottom-right corner")

        val tempCsv = java.io.File.createTempFile("helpcsl-repairs-", ".csv")
        try {
            val exported = DesktopRepairCsvExporter.exportToCsv(
                items = listOf(
                    RepairEquipmentItem(
                        id = "rem-2001",
                        intakeNumber = "REM-2001",
                        clientName = "ООО «Сейлзмодерн»; Минск",
                        clientPhone = "+375293371412",
                        linkedTicketKey = "CSL-37500",
                        deviceCategory = "Ноутбук",
                        deviceModel = "Lenovo ThinkPad T14",
                        serialNumber = "PF3ABCD1",
                        reportedDefect = "Не заряжается от Type-C",
                        status = RepairStatus.DIAGNOSTICS,
                        assignedEngineer = "ziv@csl.by"
                    )
                ),
                outFile = tempCsv,
                connectedUserName = "Захаревич Игорь",
                currentUsernameOrEmail = "ziv@csl.by"
            )
            val rawBytes = exported.readBytes()
            // Verify UTF-8 BOM (EF BB BF) for Russian Excel
            assertTrue(
                rawBytes.size > 3 &&
                    rawBytes[0] == 0xEF.toByte() &&
                    rawBytes[1] == 0xBB.toByte() &&
                    rawBytes[2] == 0xBF.toByte(),
                "CSV must start with UTF-8 BOM"
            )
            val csvText = String(rawBytes, Charsets.UTF_8)
            assertTrue(csvText.contains("\"ООО «Сейлзмодерн»; Минск\""), "Semicolon in cell must be quoted: $csvText")
            assertTrue(csvText.contains("Захаревич Игорь"), "Engineer login ziv@csl.by must resolve to full name: $csvText")
            assertFalse(csvText.contains("ziv@csl.by"), "Raw email must not appear in CSV: $csvText")
        } finally {
            tempCsv.delete()
        }
    }

    @Test
    fun `generateRepairQrStickerPdf and generateShiftAnalyticsPdf render valid Cyrillic PDFs`() {
        val item = RepairEquipmentItem(
            id = "rem-3005",
            intakeNumber = "REM-3005",
            clientName = "ООО «Сейлзмодерн»",
            clientPhone = "+375293371412",
            linkedTicketKey = "CSL-37950",
            deviceCategory = "Ноутбук",
            deviceModel = "HP ProBook 450 G9",
            serialNumber = "5CD2419XYZ",
            status = RepairStatus.READY
        )
        val qrPdf = DesktopPdfGenerator.generateRepairQrStickerPdf(item)
        try {
            assertTrue(qrPdf.exists() && qrPdf.length() > 600L)
            val qrText = PDDocument.load(qrPdf).use { PDFTextStripper().getText(it) }
            assertTrue(qrText.contains("REM-3005"), "Sticker PDF must include intake number: $qrText")
        } finally {
            qrPdf.delete()
        }

        val summary = com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator.computeForPeriod(
            tickets = emptyList(),
            period = com.helpdesk.jiraagent.domain.ShiftReportPeriod.WEEK_7D,
            connectedUserDisplayName = "Захаревич Игорь",
            usernameOrEmail = "ziv@csl.by"
        )
        val reportPdf = DesktopPdfGenerator.generateShiftAnalyticsPdf(summary, "Захаревич Игорь")
        try {
            assertTrue(reportPdf.exists() && reportPdf.length() > 800L)
            val repText = PDDocument.load(reportPdf).use { PDFTextStripper().getText(it) }
            assertTrue(repText.contains("ОТЧЁТ ДЕЖУРНОЙ СМЕНЫ") || repText.contains("Фабрициуса, 9"), "Report PDF text: $repText")
        } finally {
            reportPdf.delete()
        }
    }
}
