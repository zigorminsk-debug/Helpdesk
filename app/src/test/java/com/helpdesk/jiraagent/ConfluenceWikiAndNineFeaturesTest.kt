package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.ConfluenceBlock
import com.helpdesk.jiraagent.data.ConfluenceBreadcrumb
import com.helpdesk.jiraagent.data.ConfluenceCalloutKind
import com.helpdesk.jiraagent.data.ConfluenceHtmlParser
import com.helpdesk.jiraagent.data.ConfluenceInlineTab
import com.helpdesk.jiraagent.data.ConfluenceOfflineSnapshot
import com.helpdesk.jiraagent.data.ConfluenceOfflineStore
import com.helpdesk.jiraagent.data.ConfluencePageDetail
import com.helpdesk.jiraagent.data.ConfluencePageSummary
import com.helpdesk.jiraagent.data.ConfluenceSpace
import com.helpdesk.jiraagent.data.ConfluenceSyncOutcome
import com.helpdesk.jiraagent.data.ConfluenceSyncProgress
import com.helpdesk.jiraagent.data.ConfluenceWikiClient
import kotlinx.coroutines.runBlocking
import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
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

    @Test
    fun `Watchlist normalizes host keys for IP labels and covers full sysadmin TCP port list`() {
        assertEquals("93.84.112.45", SysadminToolboxEngine.normalizeHostKey("  https://93.84.112.45/login  "))
        assertEquals("vpn.client.by:8443", SysadminToolboxEngine.normalizeHostKey("HTTP://VPN.Client.BY:8443/"))
        val portNumbers = NetworkDiagnosticsTool.WATCHLIST_PORTS.map { it.first }
        assertTrue(portNumbers.containsAll(listOf(22, 80, 443, 445, 1194, 1433, 3389, 5060, 5432, 8006, 8291, 8443)))
        assertTrue(portNumbers.size >= 18)
    }

    @Test
    fun `ConfluenceWikiClient sorts client pages strictly alphabetically and extracts inline HTML tabs`() {
        val raw = listOf(
            ConfluencePageSummary(id = "1", title = "Сейлзмодерн", spaceKey = "CSL"),
            ConfluencePageSummary(id = "2", title = "«Альфа-Банк»", spaceKey = "CSL"),
            ConfluencePageSummary(id = "3", title = "МБ", spaceKey = "CSL"),
            ConfluencePageSummary(id = "4", title = "\"Белвест\"", spaceKey = "CSL"),
            ConfluencePageSummary(id = "5", title = "1С Серверы", spaceKey = "CSL")
        )
        val sorted = ConfluenceWikiClient.sortPagesAlphabetically(raw)
        assertEquals(listOf("1С Серверы", "«Альфа-Банк»", "\"Белвест\"", "МБ", "Сейлзмодерн"), sorted.map { it.title })
        assertEquals("А", ConfluenceWikiClient.firstAlphabetLetter("«Альфа-Банк»"))
        assertEquals("0-9", ConfluenceWikiClient.firstAlphabetLetter("1С Серверы"))
        assertEquals("М", ConfluenceWikiClient.firstAlphabetLetter("МБ"))

        val tabsHtml = """
            <div class="aui-tabs horizontal-tabs">
              <ul class="tabs-menu">
                <li class="menu-item active-tab"><a href="#pane-net">Локальная сеть</a></li>
                <li class="menu-item"><a href="#pane-srv">Серверы 1С</a></li>
              </ul>
              <div class="tabs-pane active-pane" id="pane-net">
                <div>Шлюз: 192.168.1.1</div>
              </div>
              <div class="tabs-pane" id="pane-srv">
                <p>RDP: 192.168.1.10</p>
              </div>
            </div>
        """.trimIndent()
        val tabs = ConfluenceHtmlParser.parseInlineTabs(tabsHtml, "https://w.csl.by")
        assertEquals(2, tabs.size)
        assertEquals("Локальная сеть", tabs[0].title)
        assertEquals("Серверы 1С", tabs[1].title)
        assertTrue(tabs[0].blocks.any { it is ConfluenceBlock.Paragraph && it.text.contains("192.168.1.1") })

        val folderOnlyHtml = """<ul class="childpages-macro"><li><a href="/x">Sub</a></li></ul>"""
        val strippedFolderBlocks = ConfluenceHtmlParser.parseHtmlToBlocks(
            rawHtml = folderOnlyHtml,
            baseRoot = "https://w.csl.by",
            hasNativeChildPages = true
        )
        assertEquals(1, strippedFolderBlocks.size)
        assertEquals(
            ConfluenceHtmlParser.NO_TEXT_PLACEHOLDER,
            (strippedFolderBlocks.first() as ConfluenceBlock.Paragraph).text
        )
    }

    @Test
    fun `ConfluenceOfflineStore persists compressed offline DB enforces 24h sync window and wipes cleanly`() {
        val tempDir = java.nio.file.Files.createTempDirectory("helpcsl_offline_wiki_test").toFile()
        try {
            val store = ConfluenceOfflineStore(tempDir)
            assertFalse(store.hasOfflineSnapshot())
            assertTrue(store.isDailySyncDue())

            val subPageSummary = ConfluencePageSummary(id = "101", title = "Локальная сеть МБ", spaceKey = "CSL")
            val clientSummary = ConfluencePageSummary(id = "100", title = "МБ", spaceKey = "CSL")
            val clientDetail = ConfluencePageDetail(
                id = "100",
                title = "МБ",
                spaceKey = "CSL",
                spaceName = "CSL",
                versionNumber = 3,
                updatedBy = "ziv",
                updatedLabel = "22.11.2026 18:00",
                webUrl = "https://w.csl.by/pages/viewpage.action?pageId=100",
                breadcrumbs = emptyList(),
                childPages = listOf(subPageSummary),
                inlineTabs = emptyList(),
                attachments = emptyList(),
                blocks = listOf(ConfluenceBlock.Paragraph(ConfluenceHtmlParser.NO_TEXT_PLACEHOLDER)),
                hasSubstantialBody = false
            )
            val subPageDetail = ConfluencePageDetail(
                id = "101",
                title = "Локальная сеть МБ",
                spaceKey = "CSL",
                spaceName = "CSL",
                versionNumber = 5,
                updatedBy = "ziv",
                updatedLabel = "22.11.2026 18:30",
                webUrl = "https://w.csl.by/pages/viewpage.action?pageId=101",
                breadcrumbs = listOf(ConfluenceBreadcrumb("100", "МБ")),
                childPages = emptyList(),
                inlineTabs = listOf(
                    ConfluenceInlineTab(
                        id = "tab1",
                        title = "Маршрутизатор",
                        blocks = listOf(ConfluenceBlock.Paragraph("MikroTik IP: 10.77.0.1"))
                    )
                ),
                attachments = emptyList(),
                blocks = listOf(
                    ConfluenceBlock.Table(
                        headers = listOf("Узел", "IP"),
                        rows = listOf(listOf("Сервер 1С", "192.168.55.10"))
                    )
                ),
                hasSubstantialBody = true
            )

            val now = 1_800_000_000_000L
            val snapshot = ConfluenceOfflineSnapshot(
                syncedAtEpochMs = now,
                syncedAtLabel = "22.11.2026 19:00",
                syncedUsername = "ziv@csl.by",
                spaces = listOf(ConfluenceSpace(key = "CSL", name = "CSL")),
                rootPagesBySpace = mapOf("CSL" to listOf(clientSummary)),
                recentPages = listOf(subPageSummary),
                childPagesByParent = mapOf("100" to listOf(subPageSummary)),
                pagesById = mapOf("100" to clientDetail, "101" to subPageDetail)
            )
            store.persistSnapshot(snapshot)
            store.saveCachedMediaBytes("https://w.csl.by/download/net.png", byteArrayOf(1, 2, 3, 4))

            // Reload from disk with a brand-new instance
            val reloaded = ConfluenceOfflineStore(tempDir)
            assertTrue(reloaded.hasOfflineSnapshot())
            assertFalse(reloaded.isDailySyncDue(now + 12L * 3600_000L))
            assertTrue(reloaded.isDailySyncDue(now + ConfluenceOfflineStore.DAILY_SYNC_INTERVAL_MS))
            assertEquals("192.168.55.10", (reloaded.getPageDetail("101")!!.blocks.first() as ConfluenceBlock.Table).rows[0][1])

            val foundByTableIp = reloaded.searchOffline("192.168.55.10", "CSL")
            assertEquals(1, foundByTableIp.size)
            assertEquals("101", foundByTableIp.first().id)

            val foundByInlineTab = reloaded.searchOffline("10.77.0.1", "CSL")
            assertEquals(1, foundByInlineTab.size)
            assertNotNull(reloaded.getCachedMediaBytes("https://w.csl.by/download/net.png"))

            // Wipe when account is disabled
            reloaded.clearOfflineDatabase()
            assertFalse(reloaded.hasOfflineSnapshot())
            assertEquals(null, ConfluenceOfflineStore(tempDir).getSnapshot())
            assertEquals(null, reloaded.getCachedMediaBytes("https://w.csl.by/download/net.png"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `Watchlist background ping interval is 5 minutes and formats host names with labels`() {
        assertEquals(5L * 60L * 1000L, SysadminToolboxEngine.WATCHLIST_BACKGROUND_PING_INTERVAL_MS)
        val labels = mapOf("93.84.112.45" to "Шлюз Офис Минск")
        assertEquals(
            "Шлюз Офис Минск",
            SysadminToolboxEngine.resolveWatchlistShortTitle("https://93.84.112.45/", labels)
        )
        assertEquals(
            "Шлюз Офис Минск (93.84.112.45)",
            SysadminToolboxEngine.resolveWatchlistDisplayName("https://93.84.112.45/", labels)
        )
        assertEquals(
            "10.10.1.1",
            SysadminToolboxEngine.resolveWatchlistDisplayName("10.10.1.1", labels)
        )
    }

    @Test
    fun `ConfluenceOfflineStore syncFromServer downloads Confluence Server spaces without user_current and emits determinate progress`() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("wiki_sync_progress_test").toFile()
        try {
            val client = ConfluenceWikiClient()
            client.externalWebRestFetcher = { relPath ->
                when {
                    relPath.startsWith("space") -> """
                        {"results":[{"key":"CSL","name":"CSL База клиентов","type":"global","_expandable":{"homepage":"/rest/api/content/10"}}]}
                    """.trimIndent()
                    relPath.startsWith("content/search") -> """
                        {"results":[{"id":"11","title":"ООО ТестКлиент","space":{"key":"CSL","name":"CSL"}}]}
                    """.trimIndent()
                    relPath.startsWith("content/10/child/page") -> """
                        {"results":[{"id":"11","title":"ООО ТестКлиент","space":{"key":"CSL","name":"CSL"}}]}
                    """.trimIndent()
                    relPath.startsWith("content?spaceKey=CSL") && relPath.contains("expand=") -> """
                        {
                          "results":[
                            {
                              "id":"11",
                              "title":"ООО ТестКлиент",
                              "space":{"key":"CSL","name":"CSL База клиентов"},
                              "version":{"number":3,"when":"2026-11-20T10:00:00.000+03:00","by":{"displayName":"Игорь"}},
                              "ancestors":[{"id":"10","title":"Главная"}],
                              "body":{"view":{"value":"<p>Шлюз: 10.99.88.1</p>"}}
                            }
                          ]
                        }
                    """.trimIndent()
                    relPath.startsWith("content?spaceKey=CSL") -> """
                        {"results":[{"id":"11","title":"ООО ТестКлиент","space":{"key":"CSL","name":"CSL"}}]}
                    """.trimIndent()
                    else -> null
                }
            }

            val store = ConfluenceOfflineStore(tempDir)
            val recordedProgress = mutableListOf<ConfluenceSyncProgress>()
            val settings = AppSettings(
                baseUrl = "https://jira.csl.by",
                confluenceUrl = "http://127.0.0.1:9",
                usernameOrEmail = "ziv@csl.by",
                passwordOrToken = "secret"
            )

            val outcome = store.syncFromServer(
                wikiClient = client,
                settings = settings,
                onProgressDetail = { recordedProgress.add(it) }
            )

            assertTrue("Expected Synced outcome but got $outcome", outcome is ConfluenceSyncOutcome.Synced)
            val synced = outcome as ConfluenceSyncOutcome.Synced
            assertEquals(1, synced.snapshot.totalPagesCount)
            assertTrue(recordedProgress.isNotEmpty())
            assertEquals(100, recordedProgress.last().percentInt)
            assertEquals(1, ConfluenceOfflineStore(tempDir).searchOffline("10.99.88.1", "CSL").size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

