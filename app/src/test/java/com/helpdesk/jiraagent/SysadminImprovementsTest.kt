package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.HabrArticleBlock
import com.helpdesk.jiraagent.data.HabrArticleHtmlParser
import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.RemoteDesktopKind
import com.helpdesk.jiraagent.domain.SettingsTransferCodec
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.SlaCalculator
import com.helpdesk.jiraagent.domain.SlaUrgency
import com.helpdesk.jiraagent.domain.SysadminEntityExtractor
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.model.ClientDirectoryCodec
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.QueuedActionType
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.RepairSubTab
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.service.HelpCslCallScreeningService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysadminImprovementsTest {

    @Test
    fun `AdminChatCodec encodes and decodes reply quotes, ticket links, and pinned shift note`() {
        val encoded = AdminChatCodec.encodeMessage(
            text = "Проверяю #CSL-37368 и CSL-37366 прямо сейчас",
            replyToAuthor = "Игорь",
            replyToPreview = "Кто возьмёт ЖКХ?"
        )
        val decoded = AdminChatCodec.decodeMessage(encoded, chatRoomIssueKey = "CSL-100")
        assertEquals("Игорь", decoded.replyToAuthor)
        assertEquals("Кто возьмёт ЖКХ?", decoded.replyToPreview)
        assertEquals("Проверяю #CSL-37368 и CSL-37366 прямо сейчас", decoded.text)
        assertEquals(listOf("CSL-37368", "CSL-37366"), decoded.mentionedTicketKeys)

        val fullDesc = AdminChatCodec.encodeRoomDescriptionWithPinnedNote("Дежурит Алексей, плановые работы на АТС в 18:00")
        assertEquals(
            "Дежурит Алексей, плановые работы на АТС в 18:00",
            AdminChatCodec.extractPinnedNoteFromDescription(fullDesc)
        )
        assertEquals("", AdminChatCodec.extractPinnedNoteFromDescription(AdminChatCodec.ADMIN_CHAT_ROOM_DESCRIPTION))
    }

    @Test
    fun `AdminChat storage issue is excluded from client tickets`() {
        assertTrue(
            AdminChatCodec.isAdminChatStorageIssue(
                issueKey = "CSL-999",
                summary = AdminChatCodec.ADMIN_CHAT_ROOM_SUMMARY,
                labels = emptyList()
            )
        )

        val chatTicket = HelpdeskTicket(
            id = "999",
            key = "CSL-999",
            summary = AdminChatCodec.ADMIN_CHAT_ROOM_SUMMARY,
            description = "",
            issueType = "Task",
            isSubtask = false,
            customerRequestType = null,
            hasCustomerPortalInfo = false,
            status = "Closed",
            statusCategoryKey = "done",
            priority = "Low",
            projectKey = "CSL",
            projectName = "CSL",
            reporterName = "Admin",
            reporterLoginOrEmail = "admin",
            assigneeName = null,
            createdIso = "",
            createdEpochMillis = 0L,
            updatedIso = "",
            labels = listOf(AdminChatCodec.ADMIN_CHAT_LABEL),
            phoneNumbers = emptyList(),
            comments = emptyList(),
            webUrl = ""
        )
        val eval = TicketFilterEngine.evaluate(chatTicket, FilterSettings())
        assertFalse(eval.isClientTicket)
    }

    @Test
    fun `SysadminEntityExtractor finds AnyDesk, RustDesk, IPs, domains, MAC vendors, and street addresses`() {
        val text = """
            Добрый день! Не открывается 1С на сервере 192.168.15.20 и шлюз vpn.csl.by не пингуется.
            Роутер клиента D4-CA-6D-11-22-33 и виртуалка 0050.56AB.CD12.
            Адрес: ул. Притыцкого, д. 29, оф. 412
            Мой телефон +375 29 654-32-10, почта buh@csl.by, скриншот error_1c.png во вложении.
            Подключитесь по AnyDesk: 412 839 104 или резервный RustDesk 1982736450.
        """.trimIndent()

        val result = SysadminEntityExtractor.extract(
            text = text,
            excludeNineDigitTails = setOf("296543210")
        )

        assertEquals(2, result.remoteIds.size)
        assertEquals(RemoteDesktopKind.ANYDESK, result.remoteIds[0].kind)
        assertEquals("412839104", result.remoteIds[0].normalizedId)
        assertEquals(RemoteDesktopKind.RUSTDESK, result.remoteIds[1].kind)
        assertEquals("1982736450", result.remoteIds[1].normalizedId)

        val hosts = result.networkTargets.map { it.host }
        assertTrue(hosts.contains("192.168.15.20"))
        assertTrue(hosts.contains("vpn.csl.by"))
        assertFalse(hosts.contains("csl.by"))
        assertFalse(hosts.contains("error_1c.png"))

        assertEquals(2, result.macAddresses.size)
        assertEquals("MikroTik", result.macAddresses[0].vendorName)
        assertEquals("VMware ESXi", result.macAddresses[1].vendorName)

        assertTrue(result.streetAddresses.isNotEmpty())
        assertTrue(result.streetAddresses.first().searchQuery.contains("Притыцкого"))
    }

    @Test
    fun `SlaCalculator computes fresh, warning, and overdue waiting badges`() {
        val base = 1_700_000_000_000L
        val fresh = SlaCalculator.computeWaitBadge(base, nowMillis = base + 7 * 60_000L)
        assertNotNull(fresh)
        assertEquals(SlaUrgency.FRESH, fresh!!.urgency)
        assertEquals("⏱ 7 мин", fresh.label)

        val warn = SlaCalculator.computeWaitBadge(base, nowMillis = base + 22 * 60_000L)
        assertEquals(SlaUrgency.WARNING, warn!!.urgency)

        val overdue = SlaCalculator.computeWaitBadge(base, nowMillis = base + 95 * 60_000L)
        assertEquals(SlaUrgency.OVERDUE, overdue!!.urgency)
        assertEquals("⏱ 1 ч 35 м", overdue.label)
    }

    @Test
    fun `JiraJsonParser parses attachments and assignee matching for Mine filter`() {
        val json = """
            {
              "id": "10500",
              "key": "CSL-37400",
              "fields": {
                "summary": "Ошибка кассы",
                "description": "См. скриншот",
                "issuetype": { "name": "Service Request", "subtask": false },
                "status": { "name": "В работе", "statusCategory": { "key": "indeterminate" } },
                "priority": { "name": "High" },
                "project": { "key": "CSL", "name": "CSL Helpdesk" },
                "reporter": { "displayName": "ООО Ромашка", "name": "romashka" },
                "assignee": { "displayName": "Игорь Сисадмин", "name": "igor.admin", "emailAddress": "igor@csl.by" },
                "created": "2026-11-21T09:15:00.000+0300",
                "updated": "2026-11-21T09:20:00.000+0300",
                "attachment": [
                  {
                    "id": "551",
                    "filename": "screen_error.png",
                    "mimeType": "image/png",
                    "size": 148200,
                    "content": "https://jira.csl.by/secure/attachment/551/screen_error.png",
                    "thumbnail": "https://jira.csl.by/secure/thumbnail/551/_thumb_551.png"
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = JiraJsonParser.parseSingleIssue(
            jsonString = json,
            appSettings = AppSettings(serverUrl = "https://jira.csl.by", usernameOrEmail = "igor@csl.by"),
            filterSettings = FilterSettings()
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.attachments?.size)
        assertTrue(parsed.attachments!!.first().isImage)
        assertTrue(parsed.isAssignedToAgent(connectedUserDisplayName = null, usernameOrEmail = "igor@csl.by"))
        assertFalse(parsed.isAssignedToAgent(connectedUserDisplayName = "Павел", usernameOrEmail = "pavel@csl.by"))
    }

    @Test
    fun `OfflineQueuedAction builds clear Russian summary labels including worklog and internal notes`() {
        val act = OfflineQueuedAction(
            type = QueuedActionType.WORKFLOW_ACTION,
            ticketKey = "CSL-37368",
            workflowActionName = "TAKE_IN_WORK"
        )
        assertEquals("CSL-37368 · В работу", act.summaryLabel)

        val wl = OfflineQueuedAction(
            type = QueuedActionType.ADD_WORKLOG,
            ticketKey = "CSL-37368",
            worklogSeconds = 900
        )
        assertEquals("CSL-37368 · Worklog (15 мин)", wl.summaryLabel)
    }

    @Test
    fun `SettingsTransferCodec encrypts, strips credentials when requested, and rejects wrong PIN`() {
        val srcApp = AppSettings(
            serverUrl = "https://jira.csl.by",
            usernameOrEmail = "engineer1",
            passwordOrToken = "super-secret-token",
            darkThemePreset = DarkThemePreset.WATCHDOG_NAVY,
            workStartTime = "08:30"
        )
        val srcFilter = FilterSettings(projectKeys = "CSL, SUP")

        val noCredCode = SettingsTransferCodec.encode(
            appSettings = srcApp,
            filterSettings = srcFilter,
            includeCredentials = false,
            pinCode = "2580"
        )
        assertTrue(noCredCode.startsWith("HELPCSL1:"))
        assertTrue(SettingsTransferCodec.decode(noCredCode, pinCode = "0000").isFailure)

        val decoded = SettingsTransferCodec.decode(noCredCode, pinCode = "2580").getOrThrow()
        assertFalse(decoded.includesCredentials)
        assertEquals("https://jira.csl.by", decoded.appSettings.serverUrl)
        assertEquals("08:30", decoded.appSettings.workStartTime)
        assertEquals(DarkThemePreset.WATCHDOG_NAVY, decoded.appSettings.darkThemePreset)
        assertEquals("", decoded.appSettings.passwordOrToken)
        assertEquals("CSL, SUP", decoded.filterSettings.projectKeys)
    }

    @Test
    fun `CallerId matching and ShiftAnalyticsCalculator prioritize open client tickets accurately`() {
        val now = System.currentTimeMillis()
        val openTicket = HelpdeskTicket(
            id = "1",
            key = "CSL-101",
            summary = "Не печатает принтер",
            description = "",
            issueType = "Service Request",
            isSubtask = false,
            customerRequestType = "Проблемы с ПК",
            hasCustomerPortalInfo = true,
            status = "Ожидание поддержки",
            statusCategoryKey = "new",
            priority = "Medium",
            projectKey = "CSL",
            projectName = "CSL",
            reporterName = "ЖКХ",
            reporterLoginOrEmail = "jkh",
            assigneeName = null,
            createdIso = "",
            createdEpochMillis = now - 5 * 60_000L,
            updatedIso = "",
            updatedEpochMillis = now - 5 * 60_000L,
            labels = emptyList(),
            phoneNumbers = listOf(PhoneMatch("+375296543210", "+375296543210", "+375 29 654-32-10", "Тел")),
            comments = emptyList(),
            webUrl = "",
            isClientTicket = true
        )
        val closedTicket = openTicket.copy(
            id = "2",
            key = "CSL-100",
            status = "Закрыта",
            statusCategoryKey = "done",
            assigneeName = "Игорь",
            assigneeLogin = "igor"
        )

        val matched = HelpCslCallScreeningService.findMatchingTickets(
            listOf(closedTicket, openTicket),
            "296543210"
        )
        assertEquals(2, matched.size)
        assertEquals("CSL-101", matched.first().key)

        val stats = ShiftAnalyticsCalculator.compute(
            tickets = listOf(openTicket, closedTicket),
            connectedUserDisplayName = "Игорь",
            usernameOrEmail = "igor",
            nowMillis = now
        )
        assertEquals(2, stats.createdToday)
        assertEquals(1, stats.closedToday)
        assertEquals(1, stats.closedByMeToday)
        assertEquals(100, stats.slaGreenPercent)
    }

    @Test
    fun `repair registry codec encodes decodes merges and hides storage issue from client queues`() {
        val itemV1 = RepairEquipmentItem(
            id = "rep-1",
            intakeNumber = "REM-1001",
            deviceCategory = "Ноутбук",
            deviceModel = "HP ProBook 450 G8",
            serialNumber = "5CD1234XYZ",
            accessories = "Блок питания / ЗУ, Сумка / Чехол",
            reportedDefect = "Не включается после грозы",
            clientName = "ООО Сейлзмодерн",
            clientPhone = "+375291112233",
            linkedTicketKey = "CSL-37368",
            status = RepairStatus.RECEIVED,
            updatedAtEpochMs = 1_000L
        )
        val encoded = RepairEquipmentCodec.encodeEntryComment(itemV1)
        val decoded = RepairEquipmentCodec.decodeEntryComment(encoded)
        assertNotNull(decoded)
        assertEquals("REM-1001", decoded!!.intakeNumber)
        assertEquals("5CD1234XYZ", decoded.serialNumber)
        assertFalse(decoded.pendingRemoteSync)

        // Newer remote update wins over older local version
        val remoteV2 = itemV1.copy(
            status = RepairStatus.READY,
            repairNotes = "Заменён контроллер питания",
            updatedAtEpochMs = 2_500L,
            pendingRemoteSync = false
        )
        val merged = RepairEquipmentCodec.mergeItems(listOf(itemV1), listOf(remoteV2))
        assertEquals(1, merged.size)
        assertEquals(RepairStatus.READY, merged.first().status)
        assertEquals("Заменён контроллер питания", merged.first().repairNotes)
        assertFalse(merged.first().pendingRemoteSync)

        // Search matches by S/N, intake number, client, and linked ticket key
        assertTrue(RepairEquipmentCodec.matchesSearch(merged.first(), "5cd1234"))
        assertTrue(RepairEquipmentCodec.matchesSearch(merged.first(), "REM-1001"))
        assertTrue(RepairEquipmentCodec.matchesSearch(merged.first(), "CSL-37368"))
        assertTrue(RepairEquipmentCodec.matchesSearch(merged.first(), "сейлзмодерн"))

        // Soft-delete preserves item in RepairSubTab.DELETED with deletedBy & deleteReason audit trail
        val deletedEvent = remoteV2.copy(
            isDeleted = true,
            deletedBy = "Игорь Петров (igor)",
            deletedAtEpochMs = 3_500L,
            deleteReason = "Дубликат",
            updatedAtEpochMs = 3_500L
        )
        val decodedDeleted = RepairEquipmentCodec.decodeEntryComment(
            RepairEquipmentCodec.encodeEntryComment(deletedEvent)
        )
        assertNotNull(decodedDeleted)
        val afterDelete = RepairEquipmentCodec.mergeItems(merged, listOf(decodedDeleted!!))
        assertEquals(1, afterDelete.size)
        val deletedCard = afterDelete.first()
        assertTrue(deletedCard.isDeleted)
        assertEquals("Игорь Петров (igor)", deletedCard.deletedBy)
        assertEquals("Дубликат", deletedCard.deleteReason)
        assertFalse(RepairSubTab.ACTIVE.matchesItem(deletedCard))
        assertFalse(RepairSubTab.READY.matchesItem(deletedCard))
        assertTrue(RepairSubTab.DELETED.matchesItem(deletedCard))
        assertTrue(RepairEquipmentCodec.matchesSearch(deletedCard, "игорь петров"))

        // Storage hub issue is treated as internal storage issue (excluded from client queues & alerts)
        assertTrue(
            AdminChatCodec.isAdminChatStorageIssue(
                issueKey = "CSL-99999",
                summary = RepairEquipmentCodec.REPAIR_REGISTRY_SUMMARY,
                labels = listOf(RepairEquipmentCodec.REPAIR_REGISTRY_LABEL)
            )
        )
    }

    @Test
    fun `client directory loads Helpdesk clients filters bots and grows as new clients are entered`() {
        val helpdeskClients = listOf(
            ClientDirectoryEntry(name = "ООО Сейлзмодерн", phone = "+375291112233", source = "Helpdesk", lastSeenEpochMs = 1_000L),
            ClientDirectoryEntry(name = "ЖКХ Минского района", phone = "+375296543210", source = "JSM", lastSeenEpochMs = 900L),
            ClientDirectoryEntry(name = "zabbix", phone = null, source = "Helpdesk", lastSeenEpochMs = 2_000L),
            ClientDirectoryEntry(name = "Клиент", phone = null, source = "Helpdesk", lastSeenEpochMs = 2_000L)
        )
        val initialDir = ClientDirectoryCodec.mergeEntries(emptyList(), helpdeskClients, FilterSettings())
        // zabbix & generic placeholder are excluded
        assertEquals(2, initialDir.size)

        // Adding a brand-new client in «В ремонт» + updating phone on existing client
        val newlyEntered = listOf(
            ClientDirectoryEntry(name = "ИП Ковалёв А.С.", phone = "+375447778899", source = "В ремонт", lastSeenEpochMs = 5_000L),
            ClientDirectoryEntry(name = "ооо сейлзмодерн", phone = "+375299990000", source = "В ремонт", lastSeenEpochMs = 4_000L)
        )
        val grownDir = ClientDirectoryCodec.mergeEntries(initialDir, newlyEntered, FilterSettings())
        assertEquals(3, grownDir.size)

        // Word-boundary prefix search ("сейлз" matches "ООО Сейлзмодерн" first with updated phone)
        val suggestions = ClientDirectoryCodec.filterSuggestions(grownDir, "сейлз")
        assertEquals(1, suggestions.size)
        assertEquals("+375299990000", suggestions.first().phone)

        // Newly added client is immediately searchable
        val newMatch = ClientDirectoryCodec.filterSuggestions(grownDir, "ковал")
        assertEquals(1, newMatch.size)
        assertEquals("ИП Ковалёв А.С.", newMatch.first().name)
    }

    @Test
    fun `engineer name resolver replaces login or email with human full name`() {
        assertTrue(EngineerNameResolver.looksLikeLoginOrEmail("ziv@csl.by"))
        assertTrue(EngineerNameResolver.looksLikeLoginOrEmail("ziv"))
        assertFalse(EngineerNameResolver.looksLikeLoginOrEmail("Захаревич Игорь"))

        assertEquals(
            "Захаревич Игорь",
            EngineerNameResolver.resolve(
                rawEngineer = "ziv@csl.by",
                connectedUserDisplayName = "Захаревич Игорь",
                currentUsernameOrEmail = "ziv@csl.by"
            )
        )

        val colleagueTicket = HelpdeskTicket(
            id = "1",
            key = "CSL-100",
            summary = "Настройка VPN",
            description = "",
            issueType = "Task",
            isSubtask = false,
            customerRequestType = null,
            hasCustomerPortalInfo = true,
            status = "В работе",
            statusCategoryKey = "indeterminate",
            priority = "Normal",
            projectKey = "CSL",
            projectName = "CSL",
            reporterName = "Клиент",
            reporterLoginOrEmail = "client",
            assigneeName = "Петров Алексей",
            assigneeLogin = "petrov|petrov@csl.by",
            createdIso = "",
            createdEpochMillis = 1000L,
            updatedIso = "",
            labels = emptyList(),
            phoneNumbers = emptyList(),
            comments = emptyList(),
            webUrl = ""
        )

        assertEquals(
            "Петров Алексей",
            EngineerNameResolver.resolve(
                rawEngineer = "petrov@csl.by",
                connectedUserDisplayName = "Захаревич Игорь",
                currentUsernameOrEmail = "ziv@csl.by",
                knownTickets = listOf(colleagueTicket)
            )
        )
    }

    @Test
    fun `habr article html parser extracts id and structured reader blocks`() {
        assertEquals(
            "1110746",
            HabrArticleHtmlParser.extractArticleId("https://habr.com/ru/articles/1110746/")
        )
        assertEquals(
            "1110384",
            HabrArticleHtmlParser.extractArticleId("https://habr.com/ru/companies/selectel/articles/1110384/")
        )

        val sampleHtml = """
            <h2>Обновление Debian 13.8</h2>
            <p>Вышел релиз <b>Debian 13.8</b> на базе ядра <code>Linux 6.12 LTS</code>.</p>
            <pre><code class="bash">sudo apt update &amp;&amp; sudo apt full-upgrade</code></pre>
            <blockquote>Рекомендуется установить обновления безопасности.</blockquote>
            <ul><li>Исправлено 122 ошибки</li><li>Добавлено 150 патчей безопасности</li></ul>
        """.trimIndent()

        val blocks = HabrArticleHtmlParser.parseBlocks(
            rawHtml = sampleHtml,
            leadImageUrl = "//habrastorage.org/extt/cover.png"
        )

        assertTrue(blocks.any { it is HabrArticleBlock.Image && it.url == "https://habrastorage.org/extt/cover.png" })
        assertTrue(blocks.any { it is HabrArticleBlock.Heading && it.text.contains("Debian 13.8") })
        assertTrue(blocks.any { it is HabrArticleBlock.Paragraph && it.text.contains("`Linux 6.12 LTS`") })
        assertTrue(blocks.any { it is HabrArticleBlock.CodeBlock && it.code.contains("sudo apt update && sudo apt full-upgrade") })
        assertTrue(blocks.any { it is HabrArticleBlock.Quote && it.text.contains("обновления безопасности") })
        assertTrue(blocks.any { it is HabrArticleBlock.BulletList && it.items.size == 2 })
    }
}
