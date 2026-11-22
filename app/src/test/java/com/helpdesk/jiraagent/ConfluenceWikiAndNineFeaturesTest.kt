package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.ConfluenceBlock
import com.helpdesk.jiraagent.data.ConfluenceCalloutKind
import com.helpdesk.jiraagent.data.ConfluenceHtmlParser
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.ShiftReportPeriod
import com.helpdesk.jiraagent.domain.SysadminToolboxEngine
import com.helpdesk.jiraagent.domain.TicketStopwatchTracker
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ClientTechPassport
import com.helpdesk.jiraagent.model.ClientTechPassportCodec
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceWikiAndNineFeaturesTest {

    @Test
    fun `AppSettings defaults Confluence URL to w_csl_by and validates Jira credential sharing`() {
        val unconfigured = AppSettings()
        assertEquals("https://w.csl.by", unconfigured.confluenceUrl)
        assertFalse(unconfigured.isConfluenceReady)

        val configured = AppSettings(
            serverUrl = "https://jira.csl.by",
            usernameOrEmail = "ziv@csl.by",
            passwordOrToken = "secret123"
        )
        assertTrue(configured.isConfluenceReady)

        val patOnly = AppSettings(confluenceToken = "pat_confluence_2fa_token")
        assertTrue(patOnly.isConfluenceReady)
    }

    @Test
    fun `ConfluenceHtmlParser parses headings tables callouts code blocks lists and relative image URLs`() {
        val html = """
            <h1>Карточка клиента ООО «Сейлзмодерн»</h1>
            <div class="confluence-information-macro confluence-information-macro-warning">
                <p>Перед перезагрузкой маршрутизатора предупредить главного бухгалтера!</p>
            </div>
            <table>
                <tr><th>Узел</th><th>IP / Порт</th><th>Учётная запись</th></tr>
                <tr><td>MikroTik GW</td><td>192.168.88.1:8291</td><td>admin</td></tr>
                <tr><td>Сервер 1С</td><td>192.168.88.10:3389</td><td>CSL\Support</td></tr>
            </table>
            <pre>ip route add dst-address=10.10.0.0/16 gateway=192.168.88.254</pre>
            <ul>
                <li>Основной канал: Белтелеком оптика</li>
                <li>Резервный канал: A1 LTE</li>
            </ul>
            <p><img src="/download/attachments/12345/network_topology.png" alt="Схема сети" /></p>
        """.trimIndent()

        val blocks = ConfluenceHtmlParser.parseHtmlToBlocks(html, "https://w.csl.by")
        assertTrue(blocks.any { it is ConfluenceBlock.Heading && it.text.contains("Сейлзмодерн") })
        assertTrue(
            blocks.any {
                it is ConfluenceBlock.Callout &&
                    it.kind == ConfluenceCalloutKind.WARNING &&
                    it.body.contains("бухгалтера")
            }
        )
        val table = blocks.filterIsInstance<ConfluenceBlock.Table>().firstOrNull()
        assertNotNull(table)
        assertEquals(listOf("Узел", "IP / Порт", "Учётная запись"), table!!.headers)
        assertEquals(2, table.rows.size)
        assertEquals("192.168.88.1:8291", table.rows[0][1])

        val code = blocks.filterIsInstance<ConfluenceBlock.Code>().firstOrNull()
        assertNotNull(code)
        assertTrue(code!!.code.contains("192.168.88.254"))

        val list = blocks.filterIsInstance<ConfluenceBlock.ListItems>().firstOrNull()
        assertNotNull(list)
        assertEquals(2, list!!.items.size)

        val img = blocks.filterIsInstance<ConfluenceBlock.Image>().firstOrNull()
        assertNotNull(img)
        assertEquals("https://w.csl.by/download/attachments/12345/network_topology.png", img!!.url)
    }

    @Test
    fun `ClientTechPassportCodec encodes decodes and merges last-write-wins with legacy local notes`() {
        val passport = ClientTechPassport(
            clientKey = "ооо сейлзмодерн",
            clientDisplayName = "ООО Сейлзмодерн",
            addressesAndBranches = "г. Минск, ул. Фабрициуса, 9",
            gatewayAndProvider = "MikroTik RB4011 · 192.168.88.1",
            vpnAndRemoteNotes = "AnyDesk: 481 920 115",
            serversAndServices = "SRV-1C (192.168.88.10)",
            contactsAndNotes = "Главбух Елена +375291112233",
            updatedBy = "Захаревич Игорь",
            updatedAtEpochMs = 1_700_000_500L
        )
        val encoded = ClientTechPassportCodec.encodeComment(passport)
        assertTrue(encoded.startsWith(ClientTechPassportCodec.PASSPORT_PREFIX))

        val decoded = ClientTechPassportCodec.decodeComment(encoded)
        assertNotNull(decoded)
        assertEquals("MikroTik RB4011 · 192.168.88.1", decoded!!.gatewayAndProvider)
        assertEquals("AnyDesk: 481 920 115", decoded.vpnAndRemoteNotes)

        val merged = ClientTechPassportCodec.mergePassports(
            localMap = emptyMap(),
            remoteEvents = listOf(decoded),
            legacyLocalNotes = mapOf("ООО Сейлзмодерн" to "Старая локальная заметка")
        )
        val winner = merged[ClientTechPassportCodec.normalizeKey("  ООО   Сейлзмодерн ")]
        assertNotNull(winner)
        assertEquals("MikroTik RB4011 · 192.168.88.1", winner!!.gatewayAndProvider)
        assertEquals("Захаревич Игорь", winner.updatedBy)
    }

    @Test
    fun `TicketStopwatchTracker tracks start pause resume and rounds worklog minutes accurately`() {
        var map = TicketStopwatchTracker.startOrResume(emptyMap(), "csl-37500", nowEpochMs = 1_000L)
        assertEquals("02:05", TicketStopwatchTracker.formatTimerDisplay(map["CSL-37500"], nowEpochMs = 126_000L))
        assertEquals(3, TicketStopwatchTracker.roundedMinutesForWorklog(map["CSL-37500"], nowEpochMs = 126_000L))

        map = TicketStopwatchTracker.pause(map, "CSL-37500", nowEpochMs = 126_000L)
        assertFalse(map["CSL-37500"]!!.isRunning)
        assertEquals(125_000L, map["CSL-37500"]!!.accumulatedMillis)

        map = TicketStopwatchTracker.startOrResume(map, "CSL-37500", nowEpochMs = 200_000L)
        assertEquals(4, TicketStopwatchTracker.roundedMinutesForWorklog(map["CSL-37500"], nowEpochMs = 260_000L))

        map = TicketStopwatchTracker.stopAndClear(map, "CSL-37500")
        assertTrue(map.isEmpty())
    }

    @Test
    fun `RepairEquipmentCodec encodes QR sticker payload looks up scanned codes and builds client ready notice`() {
        val item = RepairEquipmentItem(
            id = "id-777",
            intakeNumber = "REM-0777",
            clientName = "ООО Белинвестторг",
            clientPhone = "+375291234567",
            linkedTicketKey = "CSL-37700",
            deviceCategory = "МФУ",
            deviceModel = "Kyocera M2040dn",
            serialNumber = "VCF9123456",
            status = RepairStatus.READY
        )
        val qrPayload = RepairEquipmentCodec.encodeQrStickerPayload(item)
        assertTrue(qrPayload.startsWith("HELPCSL_REM:"))

        val list = listOf(item)
        assertEquals("REM-0777", RepairEquipmentCodec.findByScannedQrOrCode(list, qrPayload)?.intakeNumber)
        assertEquals("REM-0777", RepairEquipmentCodec.findByScannedQrOrCode(list, "REM-0777")?.intakeNumber)
        assertEquals("REM-0777", RepairEquipmentCodec.findByScannedQrOrCode(list, "vcf9123456")?.intakeNumber)

        val readyMsg = RepairEquipmentCodec.buildClientReadyMessage(item)
        assertTrue(readyMsg.contains("REM-0777"))
        assertTrue(readyMsg.contains("Kyocera M2040dn"))
        assertTrue(readyMsg.contains("Фабрициуса, 9"))
    }

    @Test
    fun `AdminChatCodec encodes attachments extracts mentions and matches user handles`() {
        val body = AdminChatCodec.encodeMessage(
            text = "@Игорь посмотри скриншот ошибки #CSL-37800",
            replyToAuthor = "Алексей",
            replyToPreview = "Что на сервере?",
            attachmentName = "bsod_server.png",
            attachmentUrl = "https://jira.csl.by/secure/attachment/991/bsod_server.png"
        )
        val decoded = AdminChatCodec.decodeMessage(body, chatRoomIssueKey = "CSL-100")
        assertEquals("bsod_server.png", decoded.attachmentName)
        assertEquals("https://jira.csl.by/secure/attachment/991/bsod_server.png", decoded.attachmentUrl)
        assertTrue(decoded.mentionedHandles.any { it.equals("Игорь", ignoreCase = true) })
        assertEquals(listOf("CSL-37800"), decoded.mentionedTicketKeys)

        assertTrue(
            AdminChatCodec.isUserMentioned(
                mentionedHandles = decoded.mentionedHandles,
                displayName = "Захаревич Игорь",
                usernameOrEmail = "ziv@csl.by"
            )
        )
        assertFalse(
            AdminChatCodec.isUserMentioned(
                mentionedHandles = decoded.mentionedHandles,
                displayName = "Петров Дмитрий",
                usernameOrEmail = "petrov@csl.by"
            )
        )
    }

    @Test
    fun `SysadminToolboxEngine builds 102-byte WoL magic packet calculates IPv4 subnets and generates passwords`() {
        val packet = SysadminToolboxEngine.buildMagicPacket("AA-BB-CC-DD-EE-FF")
        assertNotNull(packet)
        assertEquals(102, packet!!.size)
        for (i in 0 until 6) {
            assertEquals(0xFF.toByte(), packet[i])
        }
        assertEquals(0xAA.toByte(), packet[6])
        assertEquals(0xFF.toByte(), packet[11])

        val sub24 = SysadminToolboxEngine.calculateSubnet("192.168.10.25/24").getOrNull()
        assertNotNull(sub24)
        assertEquals("192.168.10.0", sub24!!.networkAddress)
        assertEquals("255.255.255.0", sub24.subnetMask)
        assertEquals("192.168.10.1", sub24.firstUsableHost)
        assertEquals("192.168.10.254", sub24.lastUsableHost)
        assertEquals("192.168.10.255", sub24.broadcastAddress)
        assertEquals(254L, sub24.usableHostCount)

        val subMask = SysadminToolboxEngine.calculateSubnet("10.8.3.15 255.255.252.0").getOrNull()
        assertNotNull(subMask)
        assertEquals(22, subMask!!.cidrPrefix)
        assertEquals("10.8.0.0", subMask.networkAddress)
        assertEquals(1022L, subMask.usableHostCount)

        val pwd = SysadminToolboxEngine.generateStrongPassword(length = 20, includeSymbols = true)
        assertEquals(20, pwd.length)
        val card = SysadminToolboxEngine.formatCredentialCard("VPN Офис", "ziv", pwd)
        assertTrue(card.contains("VPN Офис"))
        assertTrue(card.contains("ziv"))
        assertTrue(card.contains(pwd))
    }

    @Test
    fun `ShiftAnalyticsCalculator computes period summaries and exports UTF-8 BOM Excel CSV`() {
        val nowMs = System.currentTimeMillis()
        val sampleTicket = HelpdeskTicket(
            id = "1",
            key = "CSL-37901",
            summary = "Настройка VPN",
            description = "",
            issueType = "Service Request",
            isSubtask = false,
            customerRequestType = null,
            hasCustomerPortalInfo = false,
            status = "Закрыта",
            statusCategoryKey = "done",
            priority = "Normal",
            projectKey = "CSL",
            projectName = "CSL",
            reporterName = "ООО Сейлзмодерн",
            reporterLoginOrEmail = "sales",
            assigneeName = "Захаревич Игорь",
            createdIso = "",
            createdEpochMillis = nowMs - 600_000L,
            updatedIso = "",
            updatedEpochMillis = nowMs - 60_000L,
            labels = emptyList(),
            phoneNumbers = emptyList(),
            comments = emptyList(),
            webUrl = "https://jira.csl.by/browse/CSL-37901"
        )
        val summary = ShiftAnalyticsCalculator.computeForPeriod(
            tickets = listOf(sampleTicket),
            period = ShiftReportPeriod.WEEK_7D,
            connectedUserDisplayName = "Захаревич Игорь",
            usernameOrEmail = "ziv@csl.by"
        )
        assertTrue(summary.closedInPeriod >= 1)
        val csvBytes = ShiftAnalyticsCalculator.buildExcelCsvBytes(summary)
        assertTrue(
            csvBytes.size > 3 &&
                csvBytes[0] == 0xEF.toByte() &&
                csvBytes[1] == 0xBB.toByte() &&
                csvBytes[2] == 0xBF.toByte()
        )
        val text = String(csvBytes, Charsets.UTF_8)
        assertTrue(text.contains("CSL-37901"))
        assertTrue(text.contains("ООО Сейлзмодерн"))
    }
}
