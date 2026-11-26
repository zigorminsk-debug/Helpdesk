package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AdminChatRoomSnapshot
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.ClientDirectoryCodec
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.ClientTechPassport
import com.helpdesk.jiraagent.model.ClientTechPassportCodec
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.TicketStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class JiraApiException(
    message: String,
    val httpCode: Int? = null,
    val isNetworkError: Boolean = false
) : Exception(message)

enum class TicketWorkflowAction {
    TAKE_IN_WORK,
    COMPLETE,
    REJECT
}

data class JiraAssignableUser(
    val displayName: String,
    val username: String?,
    val accountId: String?,
    val emailAddress: String?
)

data class JiraTransitionOption(
    val id: String,
    val name: String,
    val toStatusName: String,
    val toCategoryKey: String,
    val hasResolutionField: Boolean = false,
    val resolutionRequired: Boolean = false,
    val allowedResolutions: List<Pair<String, String>> = emptyList()
)

data class WorkflowActionOutcome(
    val updatedTicket: HelpdeskTicket?,
    val statusMessage: String,
    val needsManualTransitionChoice: Boolean = false,
    val pendingAction: TicketWorkflowAction? = null,
    val availableTransitions: List<JiraTransitionOption> = emptyList()
)

data class PostponeTicketOutcome(
    val closedTicket: HelpdeskTicket?,
    val postponedTicket: PostponedTicket?,
    val statusMessage: String,
    val needsManualTransitionChoice: Boolean = false,
    val availableTransitions: List<JiraTransitionOption> = emptyList()
)

data class SystemRoomsCleanupReport(
    val foundTotal: Int,
    val deletedCount: Int,
    val closedCount: Int,
    val alreadyClosedCount: Int,
    val failedCount: Int
) {
    val cleanedCount: Int get() = deletedCount + closedCount

    fun formatSummaryMessage(): String = when {
        foundTotal == 0 -> "Служебных заявок [HELPCSL_*_ROOM] в очереди Jira не обнаружено"
        cleanedCount == 0 && alreadyClosedCount > 0 && failedCount == 0 ->
            "Найдено служебных заявок: $foundTotal — все уже закрыты"
        else -> buildString {
            append("Очистка [HELPCSL_*_ROOM]: ")
            val parts = mutableListOf<String>()
            if (deletedCount > 0) parts.add("удалено $deletedCount")
            if (closedCount > 0) parts.add("закрыто $closedCount")
            if (alreadyClosedCount > 0) parts.add("уже были закрыты $alreadyClosedCount")
            if (failedCount > 0) parts.add("не удалось закрыть $failedCount (нет прав в Jira)")
            append(parts.joinToString(", "))
        }
    }
}

private data class AuthenticatedJiraUser(
    val name: String?,
    val accountId: String?,
    val key: String?,
    val displayName: String
)

class JiraApiClient {

    private val standardClient: OkHttpClient = sharedStandardClient
    private val selfSignedClient: OkHttpClient = sharedTrustAllClient

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var cachedUserSignature: String? = null
    @Volatile
    private var cachedAuthenticatedUser: AuthenticatedJiraUser? = null

    private fun clientFor(settings: AppSettings): OkHttpClient {
        return if (settings.trustSelfSignedSsl) selfSignedClient else standardClient
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw JiraApiException("Укажите адрес сервера Jira в настройках")
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        if (withScheme.toHttpUrlOrNull() == null) {
            throw JiraApiException("Некорректный адрес сервера: $rawUrl")
        }
        return withScheme
    }

    private fun isCloudTarget(settings: AppSettings, baseUrl: String): Boolean {
        return when (settings.serverType) {
            JiraServerType.CLOUD -> true
            JiraServerType.SERVER_DC -> false
            JiraServerType.AUTO -> baseUrl.contains(".atlassian.net", ignoreCase = true) ||
                settings.authType == AuthType.CLOUD_EMAIL_TOKEN
        }
    }

    private fun Request.Builder.applyAuth(settings: AppSettings): Request.Builder {
        header("Accept", "application/json")
        header("X-Atlassian-Token", "no-check")
        when (settings.authType) {
            AuthType.BASIC_LOGIN_PASSWORD, AuthType.CLOUD_EMAIL_TOKEN -> {
                val credential = Credentials.basic(
                    settings.usernameOrEmail.trim(),
                    settings.passwordOrToken,
                    Charsets.UTF_8
                )
                header("Authorization", credential)
            }
            AuthType.BEARER_PAT -> {
                header("Authorization", "Bearer ${settings.passwordOrToken.trim()}")
            }
        }
        return this
    }

    /**
     * Tests connectivity and authentication against `/rest/api/2/myself` or `/rest/api/3/myself`.
     * Returns the authenticated user's display name.
     */
    suspend fun testConnection(settings: AppSettings): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferV3 = isCloudTarget(settings, baseUrl)
            val endpoints = if (preferV3) {
                listOf("$baseUrl/rest/api/3/myself", "$baseUrl/rest/api/2/myself")
            } else {
                listOf("$baseUrl/rest/api/2/myself", "$baseUrl/rest/api/3/myself")
            }

            var lastErr: Exception? = null
            for (url in endpoints) {
                try {
                    val req = Request.Builder().url(url).get().applyAuth(settings).build()
                    val (code, bodyStr) = executeIdempotentCallWithRetry(settings, req)
                    if (code in 200..299) {
                        val json = JsonParser.parseString(bodyStr).asJsonObject
                        val displayName = json.get("displayName")?.asString
                            ?: json.get("name")?.asString
                            ?: json.get("emailAddress")?.asString
                            ?: "Успешно"
                        return@withContext Result.success(displayName)
                    } else if (code == 401 || code == 403) {
                        throw mapHttpError(code, bodyStr)
                    } else {
                        lastErr = mapHttpError(code, bodyStr)
                    }
                } catch (e: JiraApiException) {
                    if (e.httpCode == 401 || e.httpCode == 403) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            throw (lastErr ?: JiraApiException("Не удалось подключиться к серверу Jira"))
        }
    }

    /**
     * Fetches active (Новые + В работе) and recently closed (Закрытые) tickets from Jira,
     * compatible with Jira Service Management queues, Jira Server/DC v2, and Jira Cloud v3.
     *
     * Uses a 3-layer active discovery strategy so real client tickets in "В работе" and
     * "Ожидание поддержки" (even weeks/months old) are NEVER drowned out by thousands of
     * unclosed automated Zabbix / Backup Error issues:
     *  1. Dedicated server-verified `IN_PROGRESS` status IDs query (`В работе`, `Ожидание клиента`, etc.).
     *  2. Jira Service Management active human queues (`В работе`, `Ремонт техники`, etc., skipping `Zabbix`/`BACKUP ERRORS`).
     *  3. Noise-filtered + latest `statusCategory != Done` JQL pass for brand-new & service-tab items.
     */
    suspend fun fetchRecentTickets(
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxResults: Int = 50
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)

            val mergedByKey = LinkedHashMap<String, HelpdeskTicket>()
            fun appendTickets(items: List<HelpdeskTicket>) {
                for (ticket in items) {
                    if (!AdminChatCodec.isAdminChatStorageIssue(
                            issueKey = ticket.key,
                            summary = ticket.summary,
                            labels = ticket.labels,
                            configuredChatKey = settings.adminChatIssueKey
                        )
                    ) {
                        mergedByKey[ticket.key] = ticket
                    }
                }
            }

            val hasCustomFullJql = filterSettings.customJqlCondition.let {
                it.contains("ORDER BY", ignoreCase = true) || it.contains("statusCategory", ignoreCase = true)
            }

            // 1. Discover numeric status IDs from `/rest/api/2/status` (100% locale-independent across
            //    Russian & English Jira Server/DC and Jira Cloud) and pull all "В работе" tickets first.
            val statusCatalog = if (!hasCustomFullJql) {
                discoverServerStatusCatalog(baseUrl, preferCloud, settings)
            } else {
                CachedStatusCatalog(emptyList(), emptyList(), 0L)
            }
            if (!hasCustomFullJql) {
                val inProgressJql = TicketFilterEngine.buildInProgressJql(filterSettings, statusCatalog.inProgressIds)
                if (inProgressJql != null) {
                    runCatching {
                        val inProgJson = executeSearchWithFallback(
                            baseUrl = baseUrl,
                            jql = inProgressJql,
                            maxResults = 60,
                            preferCloud = preferCloud,
                            settings = settings,
                            fieldsSpec = "*all,-worklog",
                            expandNames = true
                        )
                        appendTickets(JiraJsonParser.parseSearchResponse(inProgJson, settings, filterSettings))
                    }
                }
            }

            // 2. Discover & pull Jira Service Management queues:
            //    - activeHumanQueues ("В работе", "Ремонт техники", ...) -> Новые & В работе
            //    - closedHumanQueues ("Готовые Заявки", "Закрытые", ...) -> Закрытые
            //    - serviceQueues ("Zabbix", "BACKUP ERRORS", ...)        -> Сервисные
            var jsmFetchedActive = false
            val jsmCatalog = if (!hasCustomFullJql) {
                discoverJsmQueuesCatalog(baseUrl, settings, filterSettings)
            } else {
                CachedJsmQueues(emptyList(), emptyList(), emptyList(), emptyList(), 0L)
            }

            if (!hasCustomFullJql) {
                for (queue in jsmCatalog.activeHumanQueues) {
                    val queueTickets = fetchIssuesFromJsmQueue(
                        baseUrl = baseUrl,
                        preferCloud = preferCloud,
                        queue = queue,
                        settings = settings,
                        filterSettings = filterSettings,
                        maxItems = 60
                    )
                    if (queueTickets.isNotEmpty()) {
                        jsmFetchedActive = true
                        appendTickets(queueTickets)
                    }
                }

                for (closedQueue in jsmCatalog.closedHumanQueues) {
                    val closedQueueTickets = fetchIssuesFromJsmQueue(
                        baseUrl = baseUrl,
                        preferCloud = preferCloud,
                        queue = closedQueue,
                        settings = settings,
                        filterSettings = filterSettings,
                        maxItems = 30,
                        fieldsSpec = "*all,-worklog,-comment"
                    )
                    for (ticket in closedQueueTickets) {
                        if (!AdminChatCodec.isAdminChatStorageIssue(
                                issueKey = ticket.key,
                                summary = ticket.summary,
                                labels = ticket.labels,
                                configuredChatKey = settings.adminChatIssueKey
                            )
                        ) {
                            mergedByKey.putIfAbsent(ticket.key, ticket)
                        }
                    }
                }

                for (serviceQueue in jsmCatalog.serviceQueues) {
                    val serviceTickets = fetchIssuesFromJsmQueue(
                        baseUrl = baseUrl,
                        preferCloud = preferCloud,
                        queue = serviceQueue,
                        settings = settings,
                        filterSettings = filterSettings,
                        maxItems = 20,
                        fieldsSpec = "*all,-worklog,-comment"
                    )
                    for (ticket in serviceTickets) {
                        if (!AdminChatCodec.isAdminChatStorageIssue(
                                issueKey = ticket.key,
                                summary = ticket.summary,
                                labels = ticket.labels,
                                configuredChatKey = settings.adminChatIssueKey
                            )
                        ) {
                            mergedByKey.putIfAbsent(ticket.key, ticket)
                        }
                    }
                }
            }

            // 3. Locale-safe Active + Service JQL passes (works on Russian Jira Server where Done = Готово)
            if (!hasCustomFullJql && !jsmFetchedActive) {
                val cleanCandidates = TicketFilterEngine.buildLocaleSafeActiveJqlCandidates(
                    filter = filterSettings,
                    doneStatusIds = statusCatalog.doneIds,
                    fallbackProjectKeys = jsmCatalog.projectKeys,
                    excludeNoise = true
                )
                runCatching {
                    val cleanJson = executeFirstValidSearchJql(
                        baseUrl = baseUrl,
                        jqlCandidates = cleanCandidates,
                        maxResults = maxResults,
                        preferCloud = preferCloud,
                        settings = settings,
                        fieldsSpec = "*all,-worklog",
                        expandNames = true
                    )
                    appendTickets(JiraJsonParser.parseSearchResponse(cleanJson, settings, filterSettings))
                }
            }

            val rawActiveLimit = if (mergedByKey.isNotEmpty() && !hasCustomFullJql) 35 else maxResults
            val activeCandidates = TicketFilterEngine.buildLocaleSafeActiveJqlCandidates(
                filter = filterSettings,
                doneStatusIds = statusCatalog.doneIds,
                fallbackProjectKeys = jsmCatalog.projectKeys,
                excludeNoise = false
            )
            try {
                val activeJson = executeFirstValidSearchJql(
                    baseUrl = baseUrl,
                    jqlCandidates = activeCandidates,
                    maxResults = rawActiveLimit,
                    preferCloud = preferCloud,
                    settings = settings,
                    fieldsSpec = "*all,-worklog",
                    expandNames = true
                )
                for (ticket in JiraJsonParser.parseSearchResponse(activeJson, settings, filterSettings)) {
                    if (!AdminChatCodec.isAdminChatStorageIssue(
                            issueKey = ticket.key,
                            summary = ticket.summary,
                            labels = ticket.labels,
                            configuredChatKey = settings.adminChatIssueKey
                        )
                    ) {
                        mergedByKey.putIfAbsent(ticket.key, ticket)
                    }
                }
            } catch (e: Exception) {
                // Only fail the whole refresh if earlier active layers also yielded nothing (e.g. offline / 401)
                if (mergedByKey.isEmpty()) throw e
            }

            // 4. Locale-safe Recently Closed tickets for "Закрытые" & "Сервисные" tabs:
            //    Pass 4a: human closed client tickets (excluding zabbix flood so real closed tickets are always visible)
            //    Pass 4b: general recent closed tickets (brings closed service tickets into "Сервисные" as well)
            val humanClosedCandidates = TicketFilterEngine.buildLocaleSafeClosedJqlCandidates(
                filter = filterSettings,
                doneStatusIds = statusCatalog.doneIds,
                fallbackProjectKeys = jsmCatalog.projectKeys,
                excludeZabbixReporter = true
            )
            if (humanClosedCandidates.isNotEmpty()) {
                runCatching {
                    val closedJson = executeFirstValidSearchJql(
                        baseUrl = baseUrl,
                        jqlCandidates = humanClosedCandidates,
                        maxResults = 30,
                        preferCloud = preferCloud,
                        settings = settings,
                        fieldsSpec = "*all,-worklog,-comment",
                        expandNames = true
                    )
                    for (ticket in JiraJsonParser.parseSearchResponse(closedJson, settings, filterSettings)) {
                        if (!AdminChatCodec.isAdminChatStorageIssue(
                                issueKey = ticket.key,
                                summary = ticket.summary,
                                labels = ticket.labels,
                                configuredChatKey = settings.adminChatIssueKey
                            )
                        ) {
                            mergedByKey.putIfAbsent(ticket.key, ticket)
                        }
                    }
                }
            }

            val allClosedCandidates = TicketFilterEngine.buildLocaleSafeClosedJqlCandidates(
                filter = filterSettings,
                doneStatusIds = statusCatalog.doneIds,
                fallbackProjectKeys = jsmCatalog.projectKeys,
                excludeZabbixReporter = false
            )
            if (allClosedCandidates.isNotEmpty()) {
                runCatching {
                    val closedAllJson = executeFirstValidSearchJql(
                        baseUrl = baseUrl,
                        jqlCandidates = allClosedCandidates,
                        maxResults = 25,
                        preferCloud = preferCloud,
                        settings = settings,
                        fieldsSpec = "*all,-worklog,-comment",
                        expandNames = true
                    )
                    for (ticket in JiraJsonParser.parseSearchResponse(closedAllJson, settings, filterSettings)) {
                        if (!AdminChatCodec.isAdminChatStorageIssue(
                                issueKey = ticket.key,
                                summary = ticket.summary,
                                labels = ticket.labels,
                                configuredChatKey = settings.adminChatIssueKey
                            )
                        ) {
                            mergedByKey.putIfAbsent(ticket.key, ticket)
                        }
                    }
                }
            }

            mergedByKey.values
                .sortedByDescending { it.createdEpochMillis }
        }
    }

    private fun executeFirstValidSearchJql(
        baseUrl: String,
        jqlCandidates: List<String>,
        maxResults: Int,
        preferCloud: Boolean,
        settings: AppSettings,
        fieldsSpec: String = "*all,-worklog",
        expandNames: Boolean = true
    ): String {
        var lastErr: Exception? = null
        for (candidate in jqlCandidates.distinct()) {
            try {
                return executeSearchWithFallback(
                    baseUrl = baseUrl,
                    jql = candidate,
                    maxResults = maxResults,
                    preferCloud = preferCloud,
                    settings = settings,
                    fieldsSpec = fieldsSpec,
                    expandNames = expandNames
                )
            } catch (e: JiraApiException) {
                lastErr = e
                // HTTP 400 means JQL field/value error (e.g. localized statusCategory on Russian Jira Server) -> try next candidate
                if (e.httpCode == 400) continue
                throw e
            } catch (e: Exception) {
                throw e
            }
        }
        throw lastErr ?: JiraApiException("Не удалось выполнить JQL-запрос к Jira")
    }

    private fun discoverServerStatusCatalog(
        baseUrl: String,
        preferCloud: Boolean,
        settings: AppSettings
    ): CachedStatusCatalog {
        val now = System.currentTimeMillis()
        statusCatalogCache[baseUrl]?.let { cached ->
            if (now - cached.fetchedAtMs < METADATA_CACHE_TTL_MS) {
                return cached
            }
        }
        val fetched = runCatching {
            val apiVer = if (preferCloud) "3" else "2"
            val req = Request.Builder()
                .url("$baseUrl/rest/api/$apiVer/status")
                .get()
                .applyAuth(settings)
                .build()
            val (code, bodyStr) = executeIdempotentCallWithRetry(settings, req)
            if (code !in 200..299) return@runCatching null
            val arr = JsonParser.parseString(bodyStr).asJsonArray
            val inProgressIds = mutableListOf<String>()
            val doneIds = mutableListOf<String>()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = obj.takeString("id")?.trim().orEmpty()
                if (id.isEmpty() || !id.all(Char::isDigit)) continue
                val name = obj.takeString("name").orEmpty()
                val catKey = obj.getAsJsonObject("statusCategory")?.takeString("key").orEmpty()
                when (TicketStage.fromJiraStatus(name, catKey)) {
                    TicketStage.IN_PROGRESS -> inProgressIds.add(id)
                    TicketStage.CLOSED -> doneIds.add(id)
                    TicketStage.NEW -> Unit
                }
            }
            CachedStatusCatalog(
                inProgressIds = inProgressIds.distinct(),
                doneIds = doneIds.distinct(),
                fetchedAtMs = now
            )
        }.getOrNull()

        if (fetched != null) {
            statusCatalogCache[baseUrl] = fetched
            return fetched
        }
        return statusCatalogCache[baseUrl] ?: CachedStatusCatalog(emptyList(), emptyList(), 0L)
    }

    private fun discoverJsmQueuesCatalog(
        baseUrl: String,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): CachedJsmQueues {
        val allowedProjects = TicketFilterEngine.splitCsv(filterSettings.projectKeys)
            .map { it.uppercase(Locale.ROOT) }
            .toSet()
        val cacheKey = "$baseUrl|${allowedProjects.sorted().joinToString(",")}"
        val now = System.currentTimeMillis()
        jsmQueuesCache[cacheKey]?.let { cached ->
            if (now - cached.fetchedAtMs < METADATA_CACHE_TTL_MS) {
                return cached
            }
        }

        val discovered = runCatching {
            val sdReq = Request.Builder()
                .url("$baseUrl/rest/servicedeskapi/servicedesk?limit=50")
                .get()
                .header("X-ExperimentalApi", "opt-in")
                .applyAuth(settings)
                .build()
            val (sdCode, sdBody) = executeIdempotentCallWithRetry(settings, sdReq)
            if (sdCode !in 200..299) {
                if (sdCode == 404 || sdCode == 403 || sdCode == 401) {
                    return@runCatching CachedJsmQueues(emptyList(), emptyList(), emptyList(), emptyList(), now)
                }
                return@runCatching null
            }

            val sdValues = JsonParser.parseString(sdBody).asJsonObject.getAsJsonArray("values")
                ?: return@runCatching CachedJsmQueues(emptyList(), emptyList(), emptyList(), emptyList(), now)

            val matchingDesks = mutableListOf<Pair<String, String>>()
            for (el in sdValues) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val sdId = o.takeString("id")?.trim().orEmpty()
                val projKey = o.takeString("projectKey")?.trim().orEmpty()
                if (sdId.isEmpty()) continue
                if (allowedProjects.isEmpty() || projKey.uppercase(Locale.ROOT) in allowedProjects) {
                    matchingDesks.add(sdId to projKey)
                }
            }

            val activeQueues = mutableListOf<DiscoveredJsmQueue>()
            val closedQueues = mutableListOf<DiscoveredJsmQueue>()
            val serviceQueues = mutableListOf<DiscoveredJsmQueue>()
            val discoveredProjects = matchingDesks.map { it.second }.filter { it.isNotBlank() }.distinct()

            for ((sdId, projKey) in matchingDesks.take(3)) {
                val qReq = Request.Builder()
                    .url("$baseUrl/rest/servicedeskapi/servicedesk/${URLEncoder.encode(sdId, "UTF-8")}/queue?limit=50")
                    .get()
                    .header("X-ExperimentalApi", "opt-in")
                    .applyAuth(settings)
                    .build()
                val (qCode, qBody) = executeIdempotentCallWithRetry(settings, qReq)
                if (qCode !in 200..299) continue
                val qValues = JsonParser.parseString(qBody).asJsonObject.getAsJsonArray("values") ?: continue
                for (qEl in qValues) {
                    if (!qEl.isJsonObject) continue
                    val qObj = qEl.asJsonObject
                    val qId = qObj.takeString("id")?.trim().orEmpty()
                    val qName = qObj.takeString("name")?.trim().orEmpty()
                    val qJql = qObj.takeString("jql")?.trim().orEmpty()
                    if (qId.isEmpty()) continue
                    val item = DiscoveredJsmQueue(
                        serviceDeskId = sdId,
                        projectKey = projKey,
                        queueId = qId,
                        queueName = qName,
                        rawJql = qJql
                    )
                    when {
                        TicketFilterEngine.isHumanActiveSupportQueueName(qName) -> activeQueues.add(item)
                        TicketFilterEngine.isClosedHumanQueueName(qName) -> closedQueues.add(item)
                        TicketFilterEngine.isAutomatedServiceQueueName(qName) -> serviceQueues.add(item)
                    }
                }
            }

            CachedJsmQueues(
                activeHumanQueues = activeQueues.sortedBy { q ->
                    if (q.queueName.trim().equals("В работе", ignoreCase = true)) 0 else 1
                }.take(4),
                closedHumanQueues = closedQueues.take(2),
                serviceQueues = serviceQueues.take(2),
                projectKeys = discoveredProjects,
                fetchedAtMs = now
            )
        }.getOrNull()

        if (discovered != null) {
            jsmQueuesCache[cacheKey] = discovered
            return discovered
        }
        return jsmQueuesCache[cacheKey] ?: CachedJsmQueues(emptyList(), emptyList(), emptyList(), emptyList(), 0L)
    }

    private fun fetchIssuesFromJsmQueue(
        baseUrl: String,
        preferCloud: Boolean,
        queue: DiscoveredJsmQueue,
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxItems: Int = 60,
        fieldsSpec: String = "*all,-worklog"
    ): List<HelpdeskTicket> {
        // Primary path: execute the queue's JQL via `/search` with `expand=names` for full custom phone fields
        if (queue.rawJql.isNotBlank()) {
            val scopedJql = TicketFilterEngine.scopeJsmQueueJql(queue.rawJql, queue.projectKey)
            val viaJql = runCatching {
                val json = executeSearchWithFallback(
                    baseUrl = baseUrl,
                    jql = scopedJql,
                    maxResults = maxItems,
                    preferCloud = preferCloud,
                    settings = settings,
                    fieldsSpec = fieldsSpec,
                    expandNames = true
                )
                JiraJsonParser.parseSearchResponse(json, settings, filterSettings)
            }.getOrNull()
            if (viaJql != null) {
                return viaJql
            }
        }

        // Fallback path: evaluate queue directly via JSM `/queue/{queueId}/issue` and hydrate keys via `/search`
        return runCatching {
            val encodedSd = URLEncoder.encode(queue.serviceDeskId, "UTF-8")
            val encodedQ = URLEncoder.encode(queue.queueId, "UTF-8")
            val req = Request.Builder()
                .url("$baseUrl/rest/servicedeskapi/servicedesk/$encodedSd/queue/$encodedQ/issue?limit=${maxItems.coerceIn(10, 50)}")
                .get()
                .header("X-ExperimentalApi", "opt-in")
                .applyAuth(settings)
                .build()
            val (code, bodyStr) = executeIdempotentCallWithRetry(settings, req)
            if (code !in 200..299) return@runCatching emptyList()
            val values = JsonParser.parseString(bodyStr).asJsonObject.getAsJsonArray("values")
                ?: return@runCatching emptyList()
            val keys = values.mapNotNull { el ->
                if (el.isJsonObject) el.asJsonObject.takeString("key")?.trim()?.takeIf { it.isNotEmpty() } else null
            }.distinct()
            if (keys.isEmpty()) return@runCatching emptyList()

            val keyJql = "key in (${keys.joinToString(", ") { "\"${it.replace("\"", "")}\"" }}) ORDER BY created DESC"
            val hydratedJson = executeSearchWithFallback(
                baseUrl = baseUrl,
                jql = keyJql,
                maxResults = keys.size.coerceAtLeast(10),
                preferCloud = preferCloud,
                settings = settings,
                fieldsSpec = fieldsSpec,
                expandNames = true
            )
            JiraJsonParser.parseSearchResponse(hydratedJson, settings, filterSettings)
        }.getOrDefault(emptyList())
    }

    private fun executeSearchWithFallback(
        baseUrl: String,
        jql: String,
        maxResults: Int,
        preferCloud: Boolean,
        settings: AppSettings,
        fieldsSpec: String = "*all,-worklog",
        expandNames: Boolean = true
    ): String {
        return if (preferCloud) {
            try {
                executeCloudJqlSearch(baseUrl, jql, maxResults, settings, fieldsSpec, expandNames)
            } catch (e: JiraApiException) {
                if (e.httpCode == 404 || e.httpCode == 405 || e.httpCode == 410) {
                    executeV2GetSearch(baseUrl, jql, maxResults, settings, fieldsSpec, expandNames)
                } else {
                    throw e
                }
            }
        } else {
            try {
                executeV2GetSearch(baseUrl, jql, maxResults, settings, fieldsSpec, expandNames)
            } catch (e: JiraApiException) {
                if (e.httpCode == 404 || e.httpCode == 410) {
                    executeCloudJqlSearch(baseUrl, jql, maxResults, settings, fieldsSpec, expandNames)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Fetches full details & comments for a single Jira issue key (e.g. "HD-1042").
     */
    suspend fun fetchTicketByKey(
        issueKey: String,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<HelpdeskTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val apiVersion = if (preferCloud) "3" else "2"
            val url = "$baseUrl/rest/api/$apiVersion/issue/${URLEncoder.encode(issueKey, "UTF-8")}?expand=names"

            val req = Request.Builder().url(url).get().applyAuth(settings).build()
            val json = try {
                val (code, bodyStr) = executeIdempotentCallWithRetry(settings, req)
                if (code !in 200..299) throw mapHttpError(code, bodyStr)
                bodyStr
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }

            JiraJsonParser.parseSingleIssue(json, settings, filterSettings)
                ?: throw JiraApiException("Не удалось разобрать данные заявки $issueKey")
        }
    }

    /**
     * Executes Helpdesk workflow actions on [issueKey]:
     * - [TicketWorkflowAction.TAKE_IN_WORK]: assigns the currently logged-in Jira user as assignee
     *   and transitions the ticket into "В работе" (In Progress).
     * - [TicketWorkflowAction.COMPLETE]: posts optional comment and transitions ticket to completed/resolved/closed.
     * - [TicketWorkflowAction.REJECT]: posts optional comment and transitions ticket to rejected/cancelled/declined.
     */
    suspend fun performWorkflowAction(
        issueKey: String,
        action: TicketWorkflowAction,
        commentText: String? = null,
        explicitTransitionId: String? = null,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<WorkflowActionOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)

            var assignedDisplayName: String? = null

            // 1. For "В работу", always assign the currently logged-in user first
            if (action == TicketWorkflowAction.TAKE_IN_WORK) {
                val me = fetchAuthenticatedUser(baseUrl, preferCloud, settings)
                assignIssueToUser(baseUrl, preferCloud, issueKey, me, settings)
                assignedDisplayName = me.displayName
            }

            // 2. Post optional comment if provided
            val cleanComment = commentText?.trim().orEmpty()
            if (cleanComment.isNotEmpty()) {
                addCommentToIssue(baseUrl, preferCloud, issueKey, cleanComment, settings)
            }

            // 3. Fetch available Jira workflow transitions for this issue
            val transitions = fetchAvailableTransitions(baseUrl, preferCloud, issueKey, settings)

            val chosenTransition: JiraTransitionOption? = if (explicitTransitionId != null) {
                transitions.firstOrNull { it.id == explicitTransitionId }
            } else {
                selectBestTransition(transitions, action)
            }

            if (chosenTransition != null) {
                // If rejecting via a generic "Close/Resolve" transition and user left comment blank,
                // leave a concise audit comment so rejection is clear in Jira history.
                if (action == TicketWorkflowAction.REJECT &&
                    cleanComment.isEmpty() &&
                    !containsAnyKeyword(
                        "${chosenTransition.name} ${chosenTransition.toStatusName}",
                        REJECT_KEYWORDS
                    )
                ) {
                    runCatching {
                        addCommentToIssue(baseUrl, preferCloud, issueKey, "Заявка отклонена.", settings)
                    }
                }

                executeTransition(baseUrl, preferCloud, issueKey, chosenTransition, action, settings)

                val closedNowMs = System.currentTimeMillis()
                val closedNowIso = Instant.ofEpochMilli(closedNowMs).toString()
                val rawRefreshed = fetchTicketByKey(issueKey, settings, filterSettings).getOrNull()
                val refreshed = if (
                    rawRefreshed != null &&
                    (action == TicketWorkflowAction.COMPLETE || action == TicketWorkflowAction.REJECT) &&
                    rawRefreshed.resolvedEpochMillis <= 0L
                ) {
                    rawRefreshed.copy(
                        resolvedIso = closedNowIso,
                        resolvedEpochMillis = closedNowMs
                    )
                } else {
                    rawRefreshed
                }
                val statusLabel = refreshed?.status ?: chosenTransition.toStatusName
                val msg = when (action) {
                    TicketWorkflowAction.TAKE_IN_WORK ->
                        "Взята в работу · Исполнитель: ${assignedDisplayName ?: "назначен"} ($statusLabel)"
                    TicketWorkflowAction.COMPLETE ->
                        "Заявка завершена ($statusLabel)"
                    TicketWorkflowAction.REJECT ->
                        "Заявка отклонена ($statusLabel)"
                }
                return@runCatching WorkflowActionOutcome(
                    updatedTicket = refreshed,
                    statusMessage = msg
                )
            }

            // If "В работу" was clicked and ticket is ALREADY in progress (or has no further transitions needed),
            // assignment already succeeded!
            val refreshedAfterAssign = fetchTicketByKey(issueKey, settings, filterSettings).getOrNull()
            if (action == TicketWorkflowAction.TAKE_IN_WORK &&
                refreshedAfterAssign?.stage == com.helpdesk.jiraagent.model.TicketStage.IN_PROGRESS
            ) {
                return@runCatching WorkflowActionOutcome(
                    updatedTicket = refreshedAfterAssign,
                    statusMessage = "Назначен исполнитель: ${assignedDisplayName ?: "вы"} (${refreshedAfterAssign.status})"
                )
            }

            // If transitions exist in Jira with custom names, let the user tap the desired Jira transition
            if (transitions.isNotEmpty()) {
                val prefix = if (assignedDisplayName != null) {
                    "Исполнитель $assignedDisplayName назначен. Выберите переход статуса Jira:"
                } else {
                    "Выберите доступный переход статуса в вашем процессе Jira:"
                }
                return@runCatching WorkflowActionOutcome(
                    updatedTicket = refreshedAfterAssign,
                    statusMessage = prefix,
                    needsManualTransitionChoice = true,
                    pendingAction = action,
                    availableTransitions = transitions
                )
            }

            if (action == TicketWorkflowAction.TAKE_IN_WORK && assignedDisplayName != null) {
                return@runCatching WorkflowActionOutcome(
                    updatedTicket = refreshedAfterAssign,
                    statusMessage = "Назначен исполнитель: $assignedDisplayName (доступных переходов статуса нет)"
                )
            }

            throw JiraApiException(
                "Для заявки $issueKey в текущем статусе нет доступных переходов в Jira"
            )
        }
    }

    /**
     * Captures full metadata of [issueKey] for later recreation, then closes [issueKey] immediately.
     */
    suspend fun postponeAndCloseTicket(
        issueKey: String,
        fallbackTicket: HelpdeskTicket?,
        scheduledAtMillis: Long,
        scheduledDisplay: String,
        explicitTransitionId: String? = null,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<PostponeTicketOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val draft = capturePostponedDraft(
                issueKey = issueKey,
                fallbackTicket = fallbackTicket,
                scheduledAtMillis = scheduledAtMillis,
                scheduledDisplay = scheduledDisplay,
                settings = settings,
                filterSettings = filterSettings
            )

            // Offline DEMO tickets close locally
            if (issueKey.startsWith("DEMO-", ignoreCase = true)) {
                val closedDemo = fallbackTicket?.copy(
                    status = "Закрыта (отложена)",
                    statusCategoryKey = "done"
                )
                return@runCatching PostponeTicketOutcome(
                    closedTicket = closedDemo,
                    postponedTicket = draft,
                    statusMessage = "Заявка $issueKey закрыта и отложена до $scheduledDisplay"
                )
            }

            val closeComment = "Заявка отложена до $scheduledDisplay (автоматически пересоздастся в Help-CSL)."
            val closeOutcome = performWorkflowAction(
                issueKey = issueKey,
                action = TicketWorkflowAction.COMPLETE,
                commentText = closeComment,
                explicitTransitionId = explicitTransitionId,
                settings = settings,
                filterSettings = filterSettings
            ).getOrThrow()

            if (closeOutcome.needsManualTransitionChoice) {
                return@runCatching PostponeTicketOutcome(
                    closedTicket = closeOutcome.updatedTicket,
                    postponedTicket = null,
                    statusMessage = "Выберите переход закрытия заявки перед откладыванием до $scheduledDisplay:",
                    needsManualTransitionChoice = true,
                    availableTransitions = closeOutcome.availableTransitions
                )
            }

            PostponeTicketOutcome(
                closedTicket = closeOutcome.updatedTicket,
                postponedTicket = draft,
                statusMessage = "Заявка $issueKey закрыта и пересоздастся $scheduledDisplay"
            )
        }
    }

    /**
     * Posts a standalone text/voice comment to [issueKey] and returns the refreshed ticket.
     */
    suspend fun postIssueComment(
        issueKey: String,
        commentText: String,
        settings: AppSettings,
        filterSettings: FilterSettings,
        internalOnly: Boolean = false
    ): Result<HelpdeskTicket?> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = commentText.trim()
            if (clean.isEmpty()) throw JiraApiException("Введите или надиктуйте текст комментария")
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            addCommentToIssue(baseUrl, preferCloud, issueKey, clean, settings, internalOnly = internalOnly)
            fetchTicketByKey(issueKey, settings, filterSettings).getOrNull()
        }
    }

    /**
     * Logs engineer time spent (`Worklog`) on a Jira issue (`timeSpentSeconds` >= 60).
     */
    suspend fun addIssueWorklog(
        issueKey: String,
        timeSpentSeconds: Int,
        commentText: String?,
        settings: AppSettings
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val safeSeconds = timeSpentSeconds.coerceAtLeast(60)
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encodedKey = URLEncoder.encode(issueKey, "UTF-8")

            val v2Payload = JsonObject().apply {
                addProperty("timeSpentSeconds", safeSeconds)
                if (!commentText.isNullOrBlank()) addProperty("comment", commentText.trim())
            }
            val v3Payload = JsonObject().apply {
                addProperty("timeSpentSeconds", safeSeconds)
                if (!commentText.isNullOrBlank()) add("comment", buildAdfDocument(commentText.trim()))
            }

            val attempts = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/issue/$encodedKey/worklog" to v3Payload,
                    "$baseUrl/rest/api/2/issue/$encodedKey/worklog" to v2Payload
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/issue/$encodedKey/worklog" to v2Payload,
                    "$baseUrl/rest/api/3/issue/$encodedKey/worklog" to v3Payload
                )
            }

            var lastErr: Exception? = null
            for ((url, payload) in attempts) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) return@runCatching
                        if (resp.code == 401 || resp.code == 403) throw mapHttpError(resp.code, bodyStr)
                        lastErr = mapHttpError(resp.code, bodyStr)
                    }
                } catch (e: JiraApiException) {
                    if (e.httpCode == 401 || e.httpCode == 403) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            throw (lastErr ?: JiraApiException("Не удалось списать время в Worklog для $issueKey"))
        }
    }

    /**
     * Fetches Jira colleagues assignable to [issueKey] so an engineer can hand off a ticket.
     */
    suspend fun fetchAssignableUsers(
        issueKey: String,
        settings: AppSettings
    ): Result<List<JiraAssignableUser>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
            val urls = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/user/assignable/search?issueKey=$encodedKey&maxResults=50",
                    "$baseUrl/rest/api/2/user/assignable/search?issueKey=$encodedKey&maxResults=50"
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/user/assignable/search?issueKey=$encodedKey&maxResults=50",
                    "$baseUrl/rest/api/3/user/assignable/search?issueKey=$encodedKey&maxResults=50"
                )
            }

            for (url in urls) {
                try {
                    val req = Request.Builder().url(url).get().applyAuth(settings).build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val arr = JsonParser.parseString(bodyStr).asJsonArray
                            return@runCatching buildList {
                                for (el in arr) {
                                    if (!el.isJsonObject) continue
                                    val obj = el.asJsonObject
                                    val active = obj.get("active")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                                    if (!active) continue
                                    val displayName = obj.takeString("displayName") ?: continue
                                    add(
                                        JiraAssignableUser(
                                            displayName = displayName,
                                            username = obj.takeString("name") ?: obj.takeString("key"),
                                            accountId = obj.takeString("accountId"),
                                            emailAddress = obj.takeString("emailAddress")
                                        )
                                    )
                                }
                            }.distinctBy { it.accountId ?: it.username ?: it.displayName }
                        }
                    }
                } catch (_: Exception) {
                    // Try fallback
                }
            }
            emptyList()
        }
    }

    /**
     * Resolves a Jira account login/email (e.g. `ziv@csl.by` or `ziv`) to the user's
     * full human displayName (`Захаревич Игорь`) via `/myself` or `/user/search`.
     */
    suspend fun resolveUserDisplayName(
        loginOrEmail: String,
        settings: AppSettings
    ): String? = withContext(Dispatchers.IO) {
        val raw = loginOrEmail.trim()
        if (!EngineerNameResolver.looksLikeLoginOrEmail(raw)) return@withContext raw
        if (!settings.isConfigured) return@withContext null

        val baseUrl = runCatching { normalizeBaseUrl(settings.serverUrl) }.getOrNull() ?: return@withContext null
        val preferCloud = isCloudTarget(settings, baseUrl)

        val myClean = settings.usernameOrEmail.trim().lowercase(Locale.ROOT)
        val rawLow = raw.lowercase(Locale.ROOT)
        val rawPrefix = rawLow.substringBefore('@').trim()
        val myPrefix = myClean.substringBefore('@').trim()

        if (rawLow.isEmpty() || rawLow == myClean || (rawPrefix.length >= 2 && rawPrefix == myPrefix)) {
            val me = runCatching { fetchAuthenticatedUser(baseUrl, preferCloud, settings) }.getOrNull()
            if (me != null && !EngineerNameResolver.looksLikeLoginOrEmail(me.displayName)) {
                return@withContext me.displayName.trim()
            }
        }

        val queries = listOf(raw, rawPrefix).map { it.trim() }.filter { it.length >= 2 }.distinct()
        for (q in queries) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val urls = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/user/search?query=$encoded&maxResults=10",
                    "$baseUrl/rest/api/2/user/search?username=$encoded&maxResults=10",
                    "$baseUrl/rest/api/2/user?username=$encoded"
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/user/search?username=$encoded&maxResults=10",
                    "$baseUrl/rest/api/2/user?username=$encoded",
                    "$baseUrl/rest/api/3/user/search?query=$encoded&maxResults=10"
                )
            }
            for (url in urls) {
                val found = runCatching {
                    val req = Request.Builder().url(url).get().applyAuth(settings).build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bodyStr = resp.body?.string().orEmpty()
                        val el = JsonParser.parseString(bodyStr)
                        val candidates = if (el.isJsonArray) {
                            el.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                        } else if (el.isJsonObject) {
                            listOf(el.asJsonObject)
                        } else emptyList()

                        for (obj in candidates) {
                            val disp = obj.takeString("displayName")?.trim().orEmpty()
                            if (EngineerNameResolver.looksLikeLoginOrEmail(disp)) continue
                            val uName = obj.takeString("name")?.lowercase(Locale.ROOT).orEmpty()
                            val uEmail = obj.takeString("emailAddress")?.lowercase(Locale.ROOT).orEmpty()
                            if (uEmail == rawLow || uName == rawLow || uName == rawPrefix || uEmail.substringBefore('@') == rawPrefix) {
                                return@use disp
                            }
                        }
                        candidates.firstOrNull()
                            ?.takeString("displayName")
                            ?.trim()
                            ?.takeIf { !EngineerNameResolver.looksLikeLoginOrEmail(it) }
                    }
                }.getOrNull()
                if (!found.isNullOrBlank()) return@withContext found
            }
        }
        null
    }

    /**
     * Reassigns [issueKey] to a chosen colleague in Jira.
     */
    suspend fun assignTicketToColleague(
        issueKey: String,
        colleague: JiraAssignableUser,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<HelpdeskTicket?> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encodedKey = URLEncoder.encode(issueKey, "UTF-8")

            val payloads = buildList {
                if (!colleague.accountId.isNullOrBlank()) {
                    add("$baseUrl/rest/api/3/issue/$encodedKey/assignee" to JsonObject().apply {
                        addProperty("accountId", colleague.accountId)
                    })
                }
                if (!colleague.username.isNullOrBlank()) {
                    add("$baseUrl/rest/api/2/issue/$encodedKey/assignee" to JsonObject().apply {
                        addProperty("name", colleague.username)
                    })
                }
            }

            var assigned = false
            var lastErr: Exception? = null
            for ((url, payload) in payloads) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .put(payload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful || resp.code == 204) {
                            assigned = true
                        } else {
                            lastErr = mapHttpError(resp.code, bodyStr)
                        }
                    }
                    if (assigned) break
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            if (!assigned) {
                throw (lastErr ?: JiraApiException("Не удалось переназначить заявку на ${colleague.displayName}"))
            }
            fetchTicketByKey(issueKey, settings, filterSettings).getOrNull()
        }
    }

    /**
     * Searches Jira across historical tickets by scanned hardware barcode / serial number (`S/N`).
     */
    suspend fun searchTicketsBySerialOrText(
        rawSerial: String,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = rawSerial.trim().replace("\"", "").replace("\\", "")
            if (clean.isEmpty()) return@runCatching emptyList()
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val jql = "text ~ \"$clean\" ORDER BY created DESC"
            val resultJson = if (preferCloud) {
                runCatching { executeCloudJqlSearch(baseUrl, jql, 25, settings) }
                    .getOrElse { executeV2GetSearch(baseUrl, jql, 25, settings) }
            } else {
                runCatching { executeV2GetSearch(baseUrl, jql, 25, settings) }
                    .getOrElse { executeCloudJqlSearch(baseUrl, jql, 25, settings) }
            }
            JiraJsonParser.parseSearchResponse(resultJson, settings, filterSettings)
                .filterNot {
                    AdminChatCodec.isAdminChatStorageIssue(
                        issueKey = it.key,
                        summary = it.summary,
                        labels = it.labels
                    )
                }
        }
    }

    /**
     * Searches historical resolved/closed tickets in Jira («База решений — как мы это уже чинили?»).
     */
    suspend fun searchKnowledgeBaseArchive(
        rawQuery: String,
        excludeTicketKey: String? = null,
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxResults: Int = 30
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = rawQuery.trim()
                .replace("\\", " ")
                .replace("\"", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isEmpty()) return@runCatching emptyList()
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)

            val jqlResolved = "text ~ \"$clean\" AND statusCategory = Done ORDER BY updated DESC"
            val jqlAny = "text ~ \"$clean\" ORDER BY updated DESC"

            val resultJson = runCatching {
                if (preferCloud) {
                    runCatching { executeCloudJqlSearch(baseUrl, jqlResolved, maxResults, settings) }
                        .getOrElse { executeV2GetSearch(baseUrl, jqlResolved, maxResults, settings) }
                } else {
                    runCatching { executeV2GetSearch(baseUrl, jqlResolved, maxResults, settings) }
                        .getOrElse { executeCloudJqlSearch(baseUrl, jqlResolved, maxResults, settings) }
                }
            }.getOrElse {
                if (preferCloud) {
                    runCatching { executeCloudJqlSearch(baseUrl, jqlAny, maxResults, settings) }
                        .getOrElse { executeV2GetSearch(baseUrl, jqlAny, maxResults, settings) }
                } else {
                    runCatching { executeV2GetSearch(baseUrl, jqlAny, maxResults, settings) }
                        .getOrElse { executeCloudJqlSearch(baseUrl, jqlAny, maxResults, settings) }
                }
            }

            JiraJsonParser.parseSearchResponse(resultJson, settings, filterSettings)
                .filterNot { t ->
                    (!excludeTicketKey.isNullOrBlank() && t.key.equals(excludeTicketKey, ignoreCase = true)) ||
                        AdminChatCodec.isAdminChatStorageIssue(
                            issueKey = t.key,
                            summary = t.summary,
                            labels = t.labels,
                            configuredChatKey = settings.adminChatIssueKey
                        )
                }
        }
    }

    /**
     * Recreates a postponed ticket in Jira (or locally for DEMO-*), returning the newly created
     * [HelpdeskTicket] ready to appear in "Новые" and trigger a notification banner.
     */
    suspend fun recreatePostponedTicket(
        item: PostponedTicket,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<HelpdeskTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val nowMillis = System.currentTimeMillis()
            val nowIso = java.time.Instant.ofEpochMilli(nowMillis).toString()
            if (item.originalKey.startsWith("DEMO-", ignoreCase = true) || !settings.isConfigured) {
                val randomSuffix = (1000..9999).random()
                val newDemoKey = "DEMO-$randomSuffix"
                return@runCatching HelpdeskTicket(
                    id = UUID.randomUUID().toString(),
                    key = newDemoKey,
                    summary = item.summary,
                    description = buildRecreatedDescription(item, includeReporterNote = false),
                    issueType = item.issueTypeName,
                    isSubtask = false,
                    customerRequestType = item.customerRequestTypeName,
                    hasCustomerPortalInfo = !item.customerRequestTypeName.isNullOrBlank(),
                    status = "Ожидание поддержки",
                    statusCategoryKey = "new",
                    priority = item.priorityName.ifBlank { "Средний" },
                    projectKey = item.projectKey.ifBlank { "DEMO" },
                    projectName = item.projectKey.ifBlank { "DEMO" },
                    reporterName = item.reporterDisplayName.ifBlank { "Клиент" },
                    reporterLoginOrEmail = item.reporterEmail.orEmpty(),
                    assigneeName = null,
                    createdIso = nowIso,
                    createdEpochMillis = nowMillis,
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMillis,
                    labels = item.labels.orEmpty(),
                    phoneNumbers = item.phoneNumbers.orEmpty(),
                    telegramHandles = item.telegramHandles.orEmpty(),
                    comments = emptyList(),
                    webUrl = "",
                    isClientTicket = true,
                    filterReason = null
                )
            }

            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)

            var createdKey: String? = null

            // 1. Try Jira Service Management request creation first if JSM serviceDeskId + requestTypeId exist
            if (!item.serviceDeskId.isNullOrBlank() && !item.requestTypeId.isNullOrBlank()) {
                createdKey = tryCreateViaServiceDeskApi(baseUrl, item, settings)
            }

            // 2. Fallback to standard Jira REST /rest/api/{2|3}/issue with smart adaptive field retry
            if (createdKey.isNullOrBlank()) {
                createdKey = createViaCoreIssueApi(baseUrl, preferCloud, item, settings)
            }

            // Best-effort copy of custom phone fields if any were stored on original issue
            if (!item.customFieldsJson.isNullOrEmpty()) {
                runCatching {
                    updateCustomFieldsBestEffort(baseUrl, preferCloud, createdKey, item.customFieldsJson, settings)
                }
            }

            val fetched = fetchTicketByKey(createdKey, settings, filterSettings).getOrNull()
            val mergedPhones = if (!fetched?.phoneNumbers.isNullOrEmpty()) {
                fetched!!.phoneNumbers
            } else {
                item.phoneNumbers.orEmpty()
            }
            val mergedTelegram = if (!fetched?.telegramHandles.isNullOrEmpty()) {
                fetched!!.telegramHandles.orEmpty()
            } else {
                item.telegramHandles.orEmpty()
            }

            if (fetched != null) {
                fetched.copy(
                    customerRequestType = fetched.customerRequestType ?: item.customerRequestTypeName,
                    phoneNumbers = mergedPhones,
                    telegramHandles = mergedTelegram,
                    isClientTicket = true,
                    filterReason = null
                )
            } else {
                HelpdeskTicket(
                    id = createdKey,
                    key = createdKey,
                    summary = item.summary,
                    description = buildRecreatedDescription(item, includeReporterNote = true),
                    issueType = item.issueTypeName,
                    isSubtask = false,
                    customerRequestType = item.customerRequestTypeName,
                    hasCustomerPortalInfo = !item.customerRequestTypeName.isNullOrBlank(),
                    status = "Ожидание поддержки",
                    statusCategoryKey = "new",
                    priority = item.priorityName,
                    projectKey = item.projectKey,
                    projectName = item.projectKey,
                    reporterName = item.reporterDisplayName.ifBlank { "Клиент" },
                    reporterLoginOrEmail = item.reporterEmail.orEmpty(),
                    assigneeName = null,
                    createdIso = nowIso,
                    createdEpochMillis = nowMillis,
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMillis,
                    labels = item.labels.orEmpty(),
                    phoneNumbers = mergedPhones,
                    telegramHandles = mergedTelegram,
                    comments = emptyList(),
                    webUrl = "$baseUrl/browse/$createdKey",
                    isClientTicket = true,
                    filterReason = null
                )
            }
        }
    }

    private fun capturePostponedDraft(
        issueKey: String,
        fallbackTicket: HelpdeskTicket?,
        scheduledAtMillis: Long,
        scheduledDisplay: String,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): PostponedTicket {
        val id = UUID.randomUUID().toString()
        if (issueKey.startsWith("DEMO-", ignoreCase = true) || !settings.isConfigured) {
            val fb = fallbackTicket
            return PostponedTicket(
                id = id,
                originalKey = issueKey,
                projectKey = fb?.projectKey?.ifBlank { "DEMO" } ?: "DEMO",
                issueTypeName = fb?.issueType ?: "Service Request",
                priorityName = fb?.priority.orEmpty(),
                summary = fb?.summary ?: issueKey,
                description = fb?.description.orEmpty(),
                reporterDisplayName = fb?.reporterName.orEmpty(),
                reporterEmail = fb?.reporterLoginOrEmail,
                customerRequestTypeName = fb?.customerRequestType,
                labels = fb?.labels.orEmpty(),
                phoneNumbers = fb?.phoneNumbers.orEmpty(),
                telegramHandles = fb?.telegramHandles.orEmpty(),
                scheduledAtMillis = scheduledAtMillis,
                scheduledDisplay = scheduledDisplay
            )
        }

        val baseUrl = normalizeBaseUrl(settings.serverUrl)
        val preferCloud = isCloudTarget(settings, baseUrl)
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val apiVer = if (preferCloud) "3" else "2"
        val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey?expand=names"

        val req = Request.Builder().url(url).get().applyAuth(settings).build()
        val rawBody = try {
            clientFor(settings).newCall(req).execute().use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw mapHttpError(resp.code, str)
                str
            }
        } catch (e: Exception) {
            throw wrapNetworkException(e, settings.trustSelfSignedSsl)
        }

        val parsedTicket = JiraJsonParser.parseSingleIssue(rawBody, settings, filterSettings) ?: fallbackTicket
        val root = JsonParser.parseString(rawBody).asJsonObject
        val fields = root.getAsJsonObject("fields") ?: JsonObject()
        val namesObj = root.getAsJsonObject("names")

        val projectObj = fields.getAsJsonObject("project")
        val projectId = projectObj?.takeString("id")
        val projectKey = projectObj?.takeString("key")
            ?: parsedTicket?.projectKey
            ?: issueKey.substringBefore('-', "CSL")

        val issueTypeObj = fields.getAsJsonObject("issuetype")
        val issueTypeId = issueTypeObj?.takeString("id")
        val issueTypeName = issueTypeObj?.takeString("name")
            ?: parsedTicket?.issueType
            ?: "Task"

        val priorityObj = fields.getAsJsonObject("priority")
        val priorityId = priorityObj?.takeString("id")
        val priorityName = priorityObj?.takeString("name") ?: parsedTicket?.priority.orEmpty()

        val reporterObj = fields.getAsJsonObject("reporter")
        val reporterUsername = reporterObj?.takeString("name")
        val reporterAccountId = reporterObj?.takeString("accountId")
        val reporterDisplayName = reporterObj?.takeString("displayName")
            ?: parsedTicket?.reporterName.orEmpty()
        val reporterEmail = reporterObj?.takeString("emailAddress")
            ?: parsedTicket?.reporterLoginOrEmail

        // Extract JSM serviceDeskId & requestTypeId if available, plus custom phone string fields
        var serviceDeskId: String? = null
        var requestTypeId: String? = null
        var customerRequestTypeName: String? = parsedTicket?.customerRequestType
        val customPhoneFields = mutableMapOf<String, String>()

        for ((fieldKey, fieldVal) in fields.entrySet()) {
            if (!fieldKey.startsWith("customfield_") || fieldVal == null || fieldVal.isJsonNull) continue

            if (fieldVal.isJsonObject) {
                val obj = fieldVal.asJsonObject
                val reqType = obj.getAsJsonObject("requestType")
                if (reqType != null) {
                    requestTypeId = reqType.takeString("id") ?: requestTypeId
                    serviceDeskId = reqType.takeString("serviceDeskId")
                        ?: obj.takeString("serviceDeskId")
                        ?: serviceDeskId
                    customerRequestTypeName = reqType.takeString("name") ?: customerRequestTypeName
                }
            } else if (fieldVal.isJsonPrimitive && fieldVal.asJsonPrimitive.isString) {
                val fieldLabel = namesObj?.takeString(fieldKey)?.lowercase(Locale.ROOT).orEmpty()
                val looksLikeContactField = PHONE_FIELD_HINTS.any { fieldLabel.contains(it) }
                val rawStr = fieldVal.asString.trim()
                if (looksLikeContactField && rawStr.isNotEmpty() && rawStr.length <= 120) {
                    customPhoneFields[fieldKey] = rawStr
                }
            }
        }

        return PostponedTicket(
            id = id,
            originalKey = issueKey,
            projectKey = projectKey,
            projectId = projectId,
            issueTypeId = issueTypeId,
            issueTypeName = issueTypeName,
            priorityId = priorityId,
            priorityName = priorityName,
            summary = parsedTicket?.summary ?: fields.takeString("summary") ?: issueKey,
            description = parsedTicket?.description.orEmpty(),
            reporterDisplayName = reporterDisplayName,
            reporterUsername = reporterUsername,
            reporterAccountId = reporterAccountId,
            reporterEmail = reporterEmail,
            serviceDeskId = serviceDeskId,
            requestTypeId = requestTypeId,
            customerRequestTypeName = customerRequestTypeName,
            customFieldsJson = customPhoneFields,
            labels = parsedTicket?.labels.orEmpty(),
            phoneNumbers = parsedTicket?.phoneNumbers.orEmpty(),
            telegramHandles = parsedTicket?.telegramHandles.orEmpty(),
            scheduledAtMillis = scheduledAtMillis,
            scheduledDisplay = scheduledDisplay
        )
    }

    private fun buildRecreatedDescription(
        item: PostponedTicket,
        includeReporterNote: Boolean
    ): String {
        val sb = StringBuilder()
        val cleanOriginalDesc = item.description
            .replace(Regex("\n*\\[Пересоздана из отложенной заявки [^\\]]+]"), "")
            .trim()
        if (cleanOriginalDesc.isNotEmpty()) {
            sb.append(cleanOriginalDesc)
        }

        // Ensure phone numbers are always present in text if not already included in description
        val missingPhones = item.phoneNumbers.orEmpty()
            .map { it.e164 }
            .filter { e164 -> !cleanOriginalDesc.contains(e164) && !cleanOriginalDesc.contains(e164.removePrefix("+")) }
        if (missingPhones.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("Телефон клиента: ").append(missingPhones.joinToString(", "))
        }

        if (includeReporterNote && item.reporterDisplayName.isNotBlank() &&
            !cleanOriginalDesc.contains(item.reporterDisplayName, ignoreCase = true)
        ) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Клиент: ").append(item.reporterDisplayName)
        }

        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("[Пересоздана из отложенной заявки ${item.originalKey} · ${item.scheduledDisplay}]")
        return sb.toString()
    }

    private fun tryCreateViaServiceDeskApi(
        baseUrl: String,
        item: PostponedTicket,
        settings: AppSettings
    ): String? {
        val sdId = item.serviceDeskId ?: return null
        val rtId = item.requestTypeId ?: return null
        val url = "$baseUrl/rest/servicedeskapi/request"

        val behalfCandidates = listOfNotNull(
            item.reporterUsername?.takeIf { it.isNotBlank() },
            item.reporterAccountId?.takeIf { it.isNotBlank() },
            item.reporterEmail?.takeIf { it.isNotBlank() }
        ).distinct()

        val attempts = mutableListOf<String?>()
        attempts.addAll(behalfCandidates)
        attempts.add(null) // Final fallback without raiseOnBehalfOf

        for (behalf in attempts) {
            val reqValues = JsonObject().apply {
                addProperty("summary", item.summary)
                addProperty(
                    "description",
                    buildRecreatedDescription(item, includeReporterNote = (behalf == null))
                )
            }
            val payload = JsonObject().apply {
                addProperty("serviceDeskId", sdId)
                addProperty("requestTypeId", rtId)
                add("requestFieldValues", reqValues)
                if (!behalf.isNullOrBlank()) {
                    addProperty("raiseOnBehalfOf", behalf)
                }
            }

            try {
                val req = Request.Builder()
                    .url(url)
                    .header("X-ExperimentalApi", "opt-in")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .applyAuth(settings)
                    .build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val json = JsonParser.parseString(bodyStr).asJsonObject
                        val key = json.takeString("issueKey") ?: json.takeString("key")
                        if (!key.isNullOrBlank()) return key
                    }
                }
            } catch (_: Exception) {
                // Fall through to next attempt / Core Issue API
            }
        }
        return null
    }

    private fun createViaCoreIssueApi(
        baseUrl: String,
        preferCloud: Boolean,
        item: PostponedTicket,
        settings: AppSettings
    ): String {
        val apiVer = if (preferCloud) "3" else "2"
        val url = "$baseUrl/rest/api/$apiVer/issue"
        val descriptionStr = buildRecreatedDescription(item, includeReporterNote = true)

        val fields = JsonObject().apply {
            add("project", JsonObject().apply {
                if (!item.projectId.isNullOrBlank()) {
                    addProperty("id", item.projectId)
                } else {
                    addProperty("key", item.projectKey)
                }
            })
            add("issuetype", JsonObject().apply {
                if (!item.issueTypeId.isNullOrBlank()) {
                    addProperty("id", item.issueTypeId)
                } else {
                    addProperty("name", item.issueTypeName)
                }
            })
            addProperty("summary", item.summary)

            if (preferCloud) {
                add("description", buildAdfDocument(descriptionStr))
            } else {
                addProperty("description", descriptionStr)
            }

            if (!item.priorityId.isNullOrBlank()) {
                add("priority", JsonObject().apply { addProperty("id", item.priorityId) })
            }

            if (preferCloud && !item.reporterAccountId.isNullOrBlank()) {
                add("reporter", JsonObject().apply { addProperty("accountId", item.reporterAccountId) })
            } else if (!preferCloud && !item.reporterUsername.isNullOrBlank()) {
                add("reporter", JsonObject().apply { addProperty("name", item.reporterUsername) })
            }

            if (!item.labels.isNullOrEmpty()) {
                val labelsArr = JsonArray()
                item.labels.forEach { labelsArr.add(it) }
                add("labels", labelsArr)
            }

            item.customFieldsJson?.forEach { (cfKey, cfVal) ->
                addProperty(cfKey, cfVal)
            }
        }

        val protectedFields = setOf("project", "issuetype", "summary")
        var lastException: Exception? = null

        repeat(4) {
            val payload = JsonObject().apply { add("fields", fields) }
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .applyAuth(settings)
                    .build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val json = JsonParser.parseString(bodyStr).asJsonObject
                        val newKey = json.takeString("key")
                        if (!newKey.isNullOrBlank()) return newKey
                    }

                    // If HTTP 400 tells us specific optional fields cannot be set on Create Screen,
                    // remove them and retry immediately!
                    if (resp.code == 400) {
                        val rejectedKeys = runCatching {
                            JsonParser.parseString(bodyStr)
                                .asJsonObject
                                .getAsJsonObject("errors")
                                ?.keySet()
                                ?.filter { it !in protectedFields }
                                .orEmpty()
                        }.getOrDefault(emptyList())

                        if (rejectedKeys.isNotEmpty()) {
                            var removedAny = false
                            for (rk in rejectedKeys) {
                                if (fields.has(rk)) {
                                    fields.remove(rk)
                                    removedAny = true
                                }
                            }
                            if (removedAny) {
                                lastException = mapHttpError(resp.code, bodyStr)
                                return@use
                            }
                        }
                    }

                    throw mapHttpError(resp.code, bodyStr)
                }
            } catch (e: JiraApiException) {
                throw e
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }
        }

        throw (lastException ?: JiraApiException("Не удалось пересоздать отложенную заявку ${item.originalKey}"))
    }

    private fun updateCustomFieldsBestEffort(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        customFields: Map<String, String>,
        settings: AppSettings
    ) {
        if (customFields.isEmpty()) return
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val apiVer = if (preferCloud) "3" else "2"
        val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey"
        val fieldsObj = JsonObject().apply {
            customFields.forEach { (k, v) -> addProperty(k, v) }
        }
        val payload = JsonObject().apply { add("fields", fieldsObj) }
        val req = Request.Builder()
            .url(url)
            .put(payload.toString().toRequestBody(jsonMediaType))
            .applyAuth(settings)
            .build()
        clientFor(settings).newCall(req).execute().close()
    }

    private fun buildAdfDocument(text: String): JsonObject {
        val textNode = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
        val contentArr = JsonArray().apply { add(textNode) }
        val paragraph = JsonObject().apply {
            addProperty("type", "paragraph")
            add("content", contentArr)
        }
        val docContent = JsonArray().apply { add(paragraph) }
        return JsonObject().apply {
            addProperty("type", "doc")
            addProperty("version", 1)
            add("content", docContent)
        }
    }

    private fun fetchAuthenticatedUser(
        baseUrl: String,
        preferCloud: Boolean,
        settings: AppSettings
    ): AuthenticatedJiraUser {
        val endpoints = if (preferCloud) {
            listOf("$baseUrl/rest/api/3/myself", "$baseUrl/rest/api/2/myself")
        } else {
            listOf("$baseUrl/rest/api/2/myself", "$baseUrl/rest/api/3/myself")
        }

        var lastErr: Exception? = null
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url).get().applyAuth(settings).build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val json = JsonParser.parseString(bodyStr).asJsonObject
                        val name = json.takeString("name")
                        val accountId = json.takeString("accountId")
                        val key = json.takeString("key")
                        val display = json.takeString("displayName")
                            ?: name
                            ?: json.takeString("emailAddress")
                            ?: settings.usernameOrEmail.ifBlank { "Исполнитель" }
                        return AuthenticatedJiraUser(
                            name = name,
                            accountId = accountId,
                            key = key,
                            displayName = display
                        )
                    } else {
                        lastErr = mapHttpError(resp.code, bodyStr)
                    }
                }
            } catch (e: Exception) {
                lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
            }
        }
        throw (lastErr ?: JiraApiException("Не удалось получить профиль текущего пользователя Jira"))
    }

    private fun assignIssueToUser(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        user: AuthenticatedJiraUser,
        settings: AppSettings
    ) {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val candidates = mutableListOf<Pair<String, JsonObject>>()

        if (preferCloud) {
            if (!user.accountId.isNullOrBlank()) {
                candidates.add(
                    "$baseUrl/rest/api/3/issue/$encodedKey/assignee" to
                        JsonObject().apply { addProperty("accountId", user.accountId) }
                )
            }
            if (!user.name.isNullOrBlank()) {
                candidates.add(
                    "$baseUrl/rest/api/2/issue/$encodedKey/assignee" to
                        JsonObject().apply { addProperty("name", user.name) }
                )
            }
        } else {
            val loginName = user.name ?: settings.usernameOrEmail.trim().takeIf { it.isNotEmpty() }
            if (!loginName.isNullOrBlank()) {
                candidates.add(
                    "$baseUrl/rest/api/2/issue/$encodedKey/assignee" to
                        JsonObject().apply { addProperty("name", loginName) }
                )
            }
            if (!user.accountId.isNullOrBlank()) {
                candidates.add(
                    "$baseUrl/rest/api/2/issue/$encodedKey/assignee" to
                        JsonObject().apply { addProperty("accountId", user.accountId) }
                )
                candidates.add(
                    "$baseUrl/rest/api/3/issue/$encodedKey/assignee" to
                        JsonObject().apply { addProperty("accountId", user.accountId) }
                )
            }
        }

        if (candidates.isEmpty()) {
            throw JiraApiException("Не удалось определить идентификатор пользователя для назначения исполнителя")
        }

        var lastError: Exception? = null
        for ((url, payload) in candidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .put(payload.toString().toRequestBody(jsonMediaType))
                    .applyAuth(settings)
                    .build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return
                    lastError = mapHttpError(resp.code, bodyStr)
                }
            } catch (e: Exception) {
                lastError = wrapNetworkException(e, settings.trustSelfSignedSsl)
            }
        }
        throw (lastError ?: JiraApiException("Не удалось назначить исполнителя на заявку $issueKey"))
    }

    private fun fetchAvailableTransitions(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        settings: AppSettings
    ): List<JiraTransitionOption> {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val apiVer = if (preferCloud) "3" else "2"
        val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey/transitions?expand=transitions.fields"

        val req = Request.Builder().url(url).get().applyAuth(settings).build()
        val bodyStr = try {
            clientFor(settings).newCall(req).execute().use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw mapHttpError(resp.code, str)
                str
            }
        } catch (e: Exception) {
            throw wrapNetworkException(e, settings.trustSelfSignedSsl)
        }

        val root = JsonParser.parseString(bodyStr).asJsonObject
        val arr = root.getAsJsonArray("transitions") ?: return emptyList()

        val result = mutableListOf<JiraTransitionOption>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val id = obj.takeString("id") ?: continue
            val name = obj.takeString("name") ?: id
            val toObj = obj.getAsJsonObject("to")
            val toStatusName = toObj?.takeString("name") ?: name
            val toCategoryKey = toObj
                ?.getAsJsonObject("statusCategory")
                ?.takeString("key")
                .orEmpty()

            val fieldsObj = obj.getAsJsonObject("fields")
            val resolutionField = fieldsObj?.getAsJsonObject("resolution")
            val hasRes = resolutionField != null
            val resRequired = resolutionField?.get("required")?.let {
                it.isJsonPrimitive && it.asBoolean
            } ?: false
            val allowedRes = mutableListOf<Pair<String, String>>()
            resolutionField?.getAsJsonArray("allowedValues")?.forEach { valEl ->
                if (valEl.isJsonObject) {
                    val rObj = valEl.asJsonObject
                    val rId = rObj.takeString("id")
                    val rName = rObj.takeString("name") ?: rObj.takeString("value")
                    if (rId != null && rName != null) {
                        allowedRes.add(rId to rName)
                    }
                }
            }

            result.add(
                JiraTransitionOption(
                    id = id,
                    name = name,
                    toStatusName = toStatusName,
                    toCategoryKey = toCategoryKey,
                    hasResolutionField = hasRes,
                    resolutionRequired = resRequired,
                    allowedResolutions = allowedRes
                )
            )
        }
        return result
    }

    private fun selectBestTransition(
        transitions: List<JiraTransitionOption>,
        action: TicketWorkflowAction
    ): JiraTransitionOption? {
        if (transitions.isEmpty()) return null

        return when (action) {
            TicketWorkflowAction.TAKE_IN_WORK -> {
                transitions.firstOrNull { t ->
                    val text = "${t.name} ${t.toStatusName}"
                    containsAnyKeyword(text, IN_WORK_KEYWORDS) &&
                        !containsAnyKeyword(text, REJECT_KEYWORDS) &&
                        !t.toCategoryKey.equals("done", ignoreCase = true)
                } ?: transitions.firstOrNull { t ->
                    t.toCategoryKey.equals("indeterminate", ignoreCase = true) &&
                        !containsAnyKeyword(t.toStatusName, WAITING_SUPPORT_KEYWORDS) &&
                        !containsAnyKeyword("${t.name} ${t.toStatusName}", REJECT_KEYWORDS)
                }
            }

            TicketWorkflowAction.COMPLETE -> {
                transitions.firstOrNull { t ->
                    val text = "${t.name} ${t.toStatusName}"
                    containsAnyKeyword(text, COMPLETE_KEYWORDS) &&
                        !containsAnyKeyword(text, REJECT_KEYWORDS)
                } ?: transitions.firstOrNull { t ->
                    t.toCategoryKey.equals("done", ignoreCase = true) &&
                        !containsAnyKeyword("${t.name} ${t.toStatusName}", REJECT_KEYWORDS)
                }
            }

            TicketWorkflowAction.REJECT -> {
                // 1. Direct reject/cancel/decline transition
                transitions.firstOrNull { t ->
                    containsAnyKeyword("${t.name} ${t.toStatusName}", REJECT_KEYWORDS)
                }
                    // 2. Done transition that offers a reject/decline resolution option
                    ?: transitions.firstOrNull { t ->
                        t.toCategoryKey.equals("done", ignoreCase = true) &&
                            t.allowedResolutions.any { (_, rName) ->
                                containsAnyKeyword(rName, REJECT_KEYWORDS)
                            }
                    }
                    // 3. Single done transition if workflow uses one universal Close step
                    ?: transitions.singleOrNull { t ->
                        t.toCategoryKey.equals("done", ignoreCase = true)
                    }
            }
        }
    }

    private fun executeTransition(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        transition: JiraTransitionOption,
        action: TicketWorkflowAction,
        settings: AppSettings
    ) {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val apiVer = if (preferCloud) "3" else "2"
        val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey/transitions"

        val chosenResolutionId = pickResolutionId(transition, action)
        val payloadWithResolution = JsonObject().apply {
            add("transition", JsonObject().apply { addProperty("id", transition.id) })
            if (chosenResolutionId != null) {
                add(
                    "fields",
                    JsonObject().apply {
                        add("resolution", JsonObject().apply { addProperty("id", chosenResolutionId) })
                    }
                )
            }
        }

        try {
            val req = Request.Builder()
                .url(url)
                .post(payloadWithResolution.toString().toRequestBody(jsonMediaType))
                .applyAuth(settings)
                .build()
            clientFor(settings).newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return
                // If resolution wasn't strictly required and Jira rejected setting it on screen, retry plain transition
                if (resp.code == 400 && chosenResolutionId != null && !transition.resolutionRequired) {
                    val plainPayload = JsonObject().apply {
                        add("transition", JsonObject().apply { addProperty("id", transition.id) })
                    }
                    val retryReq = Request.Builder()
                        .url(url)
                        .post(plainPayload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    clientFor(settings).newCall(retryReq).execute().use { retryResp ->
                        val retryBody = retryResp.body?.string().orEmpty()
                        if (retryResp.isSuccessful) return
                        throw mapHttpError(retryResp.code, retryBody)
                    }
                }
                throw mapHttpError(resp.code, bodyStr)
            }
        } catch (e: Exception) {
            throw wrapNetworkException(e, settings.trustSelfSignedSsl)
        }
    }

    private fun pickResolutionId(
        transition: JiraTransitionOption,
        action: TicketWorkflowAction
    ): String? {
        if (!transition.hasResolutionField || transition.allowedResolutions.isEmpty()) return null
        return when (action) {
            TicketWorkflowAction.REJECT -> {
                transition.allowedResolutions.firstOrNull { (_, name) ->
                    containsAnyKeyword(name, REJECT_KEYWORDS)
                }?.first ?: if (transition.resolutionRequired) transition.allowedResolutions.last().first else null
            }
            TicketWorkflowAction.COMPLETE -> {
                transition.allowedResolutions.firstOrNull { (_, name) ->
                    containsAnyKeyword(name, COMPLETE_KEYWORDS) && !containsAnyKeyword(name, REJECT_KEYWORDS)
                }?.first ?: if (transition.resolutionRequired) transition.allowedResolutions.first().first else null
            }
            TicketWorkflowAction.TAKE_IN_WORK -> null
        }
    }

    private fun addCommentToIssue(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        commentText: String,
        settings: AppSettings,
        internalOnly: Boolean = false
    ) {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val internalProps = JsonArray().apply {
            add(
                JsonObject().apply {
                    addProperty("key", "sd.public.comment")
                    add("value", JsonObject().apply { addProperty("internal", true) })
                }
            )
        }
        val v2Payload = JsonObject().apply {
            addProperty("body", commentText)
            if (internalOnly) {
                add("properties", internalProps)
            }
        }
        val v3Payload = JsonObject().apply {
            add(
                "body",
                JsonObject().apply {
                    addProperty("type", "doc")
                    addProperty("version", 1)
                    add(
                        "content",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("type", "paragraph")
                                    add(
                                        "content",
                                        JsonArray().apply {
                                            add(
                                                JsonObject().apply {
                                                    addProperty("type", "text")
                                                    addProperty("text", commentText)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            if (internalOnly) {
                add("properties", internalProps)
            }
        }

        val attempts = if (preferCloud) {
            listOf(
                "$baseUrl/rest/api/3/issue/$encodedKey/comment" to v3Payload,
                "$baseUrl/rest/api/2/issue/$encodedKey/comment" to v2Payload
            )
        } else {
            listOf(
                "$baseUrl/rest/api/2/issue/$encodedKey/comment" to v2Payload,
                "$baseUrl/rest/api/3/issue/$encodedKey/comment" to v3Payload
            )
        }

        for ((url, payload) in attempts) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .applyAuth(settings)
                    .build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return
                }
            }
        }
    }

    private fun JsonObject.takeString(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive && !el.isJsonNull) el.asString else null
    }

    private fun containsAnyKeyword(text: String, keywords: List<String>): Boolean {
        val normalized = text.trim().lowercase().replace('ё', 'е')
        return keywords.any { normalized.contains(it) }
    }

    private fun executeCloudJqlSearch(
        baseUrl: String,
        jql: String,
        maxResults: Int,
        settings: AppSettings,
        fieldsSpec: String = "*all,-worklog",
        expandNames: Boolean = true
    ): String {
        val fieldsArray = JsonArray().apply {
            fieldsSpec.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("*all") }
                .forEach { add(it) }
        }
        val payload = JsonObject().apply {
            addProperty("jql", jql)
            addProperty("maxResults", maxResults)
            add("fields", fieldsArray)
            if (expandNames) {
                addProperty("expand", "names")
            }
        }
        val bodyBytes = payload.toString().toRequestBody(jsonMediaType)

        // Try modern Jira Cloud /rest/api/3/search/jql first, then fallback to /rest/api/3/search
        val urls = listOf(
            "$baseUrl/rest/api/3/search/jql",
            "$baseUrl/rest/api/3/search"
        )

        var lastError: Exception? = null
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).post(bodyBytes).applyAuth(settings).build()
                val (code, bodyStr) = executeIdempotentCallWithRetry(settings, req)
                if (code in 200..299) return bodyStr
                if (code == 401 || code == 403 || code == 400) {
                    throw mapHttpError(code, bodyStr)
                }
                lastError = mapHttpError(code, bodyStr)
            } catch (e: JiraApiException) {
                if (e.httpCode == 401 || e.httpCode == 403 || e.httpCode == 400) throw e
                lastError = e
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }
        }
        throw (lastError ?: JiraApiException("Ошибка поиска заявок в Jira Cloud"))
    }

    private fun executeV2GetSearch(
        baseUrl: String,
        jql: String,
        maxResults: Int,
        settings: AppSettings,
        fieldsSpec: String = "*all,-worklog",
        expandNames: Boolean = true
    ): String {
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        val encodedFields = URLEncoder.encode(fieldsSpec.ifBlank { "*all" }, "UTF-8")
        val expandQuery = if (expandNames) "&expand=names" else ""
        val url = "$baseUrl/rest/api/2/search?jql=$encodedJql&maxResults=$maxResults&fields=$encodedFields$expandQuery"
        val req = Request.Builder().url(url).get().applyAuth(settings).build()
        try {
            val (code, bodyStr) = executeIdempotentCallWithRetry(settings, req)
            if (code !in 200..299) throw mapHttpError(code, bodyStr)
            return bodyStr
        } catch (e: Exception) {
            throw wrapNetworkException(e, settings.trustSelfSignedSsl)
        }
    }

    /**
     * Resolves an explicitly configured shared Admin Chat room Jira issue.
     * IMPORTANT: Automatic issue creation in Jira is strictly disabled so Help-CSL
     * never creates `[HELPCSL_ADMIN_CHAT_ROOM]` tickets behind the user's back.
     */
    suspend fun resolveOrCreateAdminChatRoom(
        settings: AppSettings,
        filterSettings: FilterSettings,
        cachedRoomKey: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        roomResolveMutex.withLock {
            runCatching {
                val baseUrl = normalizeBaseUrl(settings.serverUrl)
                val preferCloud = isCloudTarget(settings, baseUrl)

                val manualKey = settings.adminChatIssueKey.trim().uppercase(Locale.ROOT)
                if (manualKey.isEmpty()) {
                    resolvedChatRoomCache.remove(baseUrl)
                    throw JiraApiException(
                        "Синхронизация комнаты через Jira отключена (чтобы не создавать служебные тикеты в очереди). " +
                            "При необходимости укажите номер одной существующей задачи вручную в Настройках."
                    )
                }

                if (verifyIssueAccessible(baseUrl, preferCloud, manualKey, settings)) {
                    resolvedChatRoomCache[baseUrl] = manualKey
                    return@runCatching manualKey
                } else {
                    throw JiraApiException("Задача комнаты чата $manualKey не найдена или недоступна")
                }
            }
        }
    }

    suspend fun fetchAdminChatRoomSnapshot(
        roomIssueKey: String,
        settings: AppSettings
    ): Result<AdminChatRoomSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val me = getCachedOrFetchAuthenticatedUser(baseUrl, preferCloud, settings)
            val myIds = buildUserIdentifiers(me, settings)

            val rawComments = fetchLatestRawCommentsJson(baseUrl, preferCloud, roomIssueKey, settings)
            val parsedMessages = rawComments.mapNotNull { commentEl ->
                if (!commentEl.isJsonObject) return@mapNotNull null
                parseSingleChatComment(
                    commentObj = commentEl.asJsonObject,
                    roomIssueKey = roomIssueKey,
                    myIdentifiers = myIds,
                    myDisplayName = me.displayName
                )
            }.sortedWith(
                compareBy<AdminChatMessage> { it.createdEpochMillis }
                    .thenBy { it.id.toLongOrNull() ?: 0L }
            )

            val pinnedNote = fetchRoomPinnedNoteBestEffort(baseUrl, preferCloud, roomIssueKey, settings)

            AdminChatRoomSnapshot(
                roomIssueKey = roomIssueKey,
                currentUserDisplayName = me.displayName,
                currentUserIdentifiers = myIds,
                messages = parsedMessages,
                pinnedNote = pinnedNote
            )
        }
    }

    private fun fetchRoomPinnedNoteBestEffort(
        baseUrl: String,
        preferCloud: Boolean,
        roomIssueKey: String,
        settings: AppSettings
    ): String {
        return runCatching {
            val encodedKey = URLEncoder.encode(roomIssueKey, "UTF-8")
            val apiVer = if (preferCloud) "3" else "2"
            val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey?fields=description"
            val req = Request.Builder().url(url).get().applyAuth(settings).build()
            clientFor(settings).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val root = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                val descEl = root.getAsJsonObject("fields")?.get("description")
                val readable = JiraJsonParser.extractReadableText(descEl)
                AdminChatCodec.extractPinnedNoteFromDescription(readable)
            }
        }.getOrDefault("")
    }

    suspend fun updateAdminChatPinnedNote(
        roomIssueKey: String,
        pinnedNote: String,
        settings: AppSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encodedKey = URLEncoder.encode(roomIssueKey, "UTF-8")
            val fullDescription = AdminChatCodec.encodeRoomDescriptionWithPinnedNote(pinnedNote)

            val v2Payload = JsonObject().apply {
                add("fields", JsonObject().apply { addProperty("description", fullDescription) })
            }
            val v3Payload = JsonObject().apply {
                add("fields", JsonObject().apply { add("description", buildAdfDocument(fullDescription)) })
            }

            val attempts = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/issue/$encodedKey" to v3Payload,
                    "$baseUrl/rest/api/2/issue/$encodedKey" to v2Payload
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/issue/$encodedKey" to v2Payload,
                    "$baseUrl/rest/api/3/issue/$encodedKey" to v3Payload
                )
            }

            var lastErr: Exception? = null
            for ((url, payload) in attempts) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .put(payload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful || resp.code == 204) {
                            return@runCatching pinnedNote.trim()
                        }
                        lastErr = mapHttpError(resp.code, bodyStr)
                    }
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            throw (lastErr ?: JiraApiException("Не удалось обновить закреплённую заметку смены"))
        }
    }

    /**
     * Downloads an attachment or thumbnail byte array from Jira using authenticated credentials.
     */
    suspend fun downloadAttachmentBytes(
        attachmentUrl: String,
        settings: AppSettings
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val resolvedUrl = if (attachmentUrl.startsWith("http://", ignoreCase = true) ||
                attachmentUrl.startsWith("https://", ignoreCase = true)
            ) {
                attachmentUrl
            } else {
                "$baseUrl/${attachmentUrl.trimStart('/')}"
            }

            val reqBuilder = Request.Builder().url(resolvedUrl).get()
            reqBuilder.applyAuth(settings)
            reqBuilder.header("Accept", "*/*")

            try {
                clientFor(settings).newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw mapHttpError(resp.code, "")
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        throw JiraApiException("Пустой файл вложения")
                    }
                    bytes
                }
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }
        }
    }

    /**
     * Uploads a photo/screenshot/file attachment to [issueKey] via Multipart POST with X-Atlassian-Token: no-check.
     */
    suspend fun uploadIssueAttachment(
        issueKey: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<HelpdeskTicket?> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
            val safeMime = mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
                ?: "application/octet-stream".toMediaType()
            val safeFileName = fileName.ifBlank { "photo_${System.currentTimeMillis()}.jpg" }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", safeFileName, fileBytes.toRequestBody(safeMime))
                .build()

            val urls = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/issue/$encodedKey/attachments",
                    "$baseUrl/rest/api/2/issue/$encodedKey/attachments"
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/issue/$encodedKey/attachments",
                    "$baseUrl/rest/api/3/issue/$encodedKey/attachments"
                )
            }

            var uploaded = false
            var lastErr: Exception? = null
            for (url in urls) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .post(multipartBody)
                        .applyAuth(settings)
                        .header("X-Atlassian-Token", "no-check")
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            uploaded = true
                        } else if (resp.code == 401 || resp.code == 403) {
                            throw mapHttpError(resp.code, bodyStr)
                        } else {
                            lastErr = mapHttpError(resp.code, bodyStr)
                        }
                    }
                    if (uploaded) break
                } catch (e: JiraApiException) {
                    if (e.httpCode == 401 || e.httpCode == 403) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }

            if (!uploaded) {
                throw (lastErr ?: JiraApiException("Не удалось прикрепить файл к заявке $issueKey"))
            }

            fetchTicketByKey(issueKey, settings, filterSettings).getOrNull()
        }
    }

    /**
     * Creates a new Jira ticket directly from the mobile app (with optional self-assignment).
     */
    suspend fun createManualTicket(
        projectKeyHint: String,
        issueTypeHint: String,
        summary: String,
        description: String,
        assignToSelf: Boolean,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<HelpdeskTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSummary = summary.trim()
            if (cleanSummary.isEmpty()) {
                throw JiraApiException("Введите тему новой заявки")
            }
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val apiVer = if (preferCloud) "3" else "2"

            data class CreateCandidate(
                val projectKey: String?,
                val projectId: String?,
                val issueTypeId: String?,
                val issueTypeName: String?
            )

            val candidates = mutableListOf<CreateCandidate>()
            val requestedProj = projectKeyHint.trim().uppercase(Locale.ROOT)
                .ifEmpty { TicketFilterEngine.splitCsv(filterSettings.projectKeys).firstOrNull()?.uppercase(Locale.ROOT).orEmpty() }
            val requestedTypeLower = issueTypeHint.trim().lowercase(Locale.ROOT)

            val searchJql = if (requestedProj.isNotEmpty()) {
                "project = \"$requestedProj\" ORDER BY created DESC"
            } else {
                "ORDER BY created DESC"
            }

            runCatching {
                val json = executeSearchWithFallback(baseUrl, searchJql, 15, preferCloud, settings)
                val issues = JsonParser.parseString(json).asJsonObject.getAsJsonArray("issues")
                val found = mutableListOf<Triple<String?, String?, Pair<String, String>>>()
                issues?.forEach { el ->
                    if (!el.isJsonObject) return@forEach
                    val f = el.asJsonObject.getAsJsonObject("fields") ?: return@forEach
                    val proj = f.getAsJsonObject("project")
                    val iType = f.getAsJsonObject("issuetype")
                    val sub = iType?.get("subtask")?.let { it.isJsonPrimitive && it.asBoolean } ?: false
                    val tId = iType?.takeString("id")
                    val tName = iType?.takeString("name").orEmpty()
                    val pId = proj?.takeString("id")
                    val pKey = proj?.takeString("key")
                    if (!sub && !tId.isNullOrBlank() && (!pId.isNullOrBlank() || !pKey.isNullOrBlank())) {
                        found.add(Triple(pKey, pId, tId to tName))
                    }
                }
                val ordered = if (requestedTypeLower.isNotEmpty()) {
                    found.sortedByDescending { (_, _, pair) ->
                        pair.second.lowercase(Locale.ROOT).contains(requestedTypeLower)
                    }
                } else {
                    found
                }
                for ((pKey, pId, typePair) in ordered) {
                    val c = CreateCandidate(pKey, pId, typePair.first, typePair.second)
                    if (candidates.none { it.projectKey == c.projectKey && it.issueTypeId == c.issueTypeId }) {
                        candidates.add(c)
                    }
                }
            }

            if (candidates.isEmpty() && requestedProj.isNotEmpty()) {
                val fallbackType = issueTypeHint.trim().ifEmpty { "Service Request" }
                candidates.add(CreateCandidate(requestedProj, null, null, fallbackType))
                candidates.add(CreateCandidate(requestedProj, null, null, "Task"))
                candidates.add(CreateCandidate(requestedProj, null, null, "Задача"))
            }

            if (candidates.isEmpty()) {
                throw JiraApiException("Укажите ключ проекта (например CSL) для создания заявки")
            }

            val createUrl = "$baseUrl/rest/api/$apiVer/issue"
            var createdKey: String? = null
            var lastErr: Exception? = null

            for (cand in candidates.take(4)) {
                val fields = JsonObject().apply {
                    add(
                        "project",
                        JsonObject().apply {
                            if (!cand.projectId.isNullOrBlank()) addProperty("id", cand.projectId)
                            else addProperty("key", cand.projectKey)
                        }
                    )
                    add(
                        "issuetype",
                        JsonObject().apply {
                            if (!cand.issueTypeId.isNullOrBlank()) addProperty("id", cand.issueTypeId)
                            else addProperty("name", cand.issueTypeName)
                        }
                    )
                    addProperty("summary", cleanSummary)
                    if (description.isNotBlank()) {
                        if (preferCloud) {
                            add("description", buildAdfDocument(description.trim()))
                        } else {
                            addProperty("description", description.trim())
                        }
                    }
                }

                val payload = JsonObject().apply { add("fields", fields) }
                try {
                    val req = Request.Builder()
                        .url(createUrl)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            createdKey = JsonParser.parseString(bodyStr).asJsonObject.takeString("key")
                        } else {
                            lastErr = mapHttpError(resp.code, bodyStr)
                        }
                    }
                    if (!createdKey.isNullOrBlank()) break
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }

            val finalKey = createdKey ?: throw (lastErr ?: JiraApiException("Не удалось создать заявку в Jira"))

            if (assignToSelf) {
                runCatching {
                    val me = getCachedOrFetchAuthenticatedUser(baseUrl, preferCloud, settings)
                    assignIssueToUser(baseUrl, preferCloud, finalKey, me, settings)
                }
            }

            fetchTicketByKey(finalKey, settings, filterSettings).getOrElse {
                val nowMs = System.currentTimeMillis()
                val nowIso = java.time.Instant.ofEpochMilli(nowMs).toString()
                HelpdeskTicket(
                    id = finalKey,
                    key = finalKey,
                    summary = cleanSummary,
                    description = description.trim(),
                    issueType = issueTypeHint.ifBlank { "Задача" },
                    isSubtask = false,
                    customerRequestType = null,
                    hasCustomerPortalInfo = false,
                    status = "Открыта",
                    statusCategoryKey = "new",
                    priority = "Normal",
                    projectKey = finalKey.substringBefore('-', requestedProj),
                    projectName = finalKey.substringBefore('-', requestedProj),
                    reporterName = settings.usernameOrEmail.ifBlank { "Администратор" },
                    reporterLoginOrEmail = settings.usernameOrEmail,
                    assigneeName = if (assignToSelf) settings.usernameOrEmail else null,
                    createdIso = nowIso,
                    createdEpochMillis = nowMs,
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMs,
                    labels = emptyList(),
                    phoneNumbers = emptyList(),
                    telegramHandles = emptyList(),
                    comments = emptyList(),
                    attachments = emptyList(),
                    webUrl = "${baseUrl.trimEnd('/')}/browse/$finalKey",
                    isClientTicket = true
                )
            }
        }
    }

    /**
     * Searches Jira across pages for the complete ticket history of the given client/organization
     * (up to 500 historical issues, sorted newest-first).
     */
    suspend fun fetchClientHistory(
        reporterName: String,
        excludeTicketKey: String,
        settings: AppSettings,
        filterSettings: FilterSettings,
        reporterLoginOrEmail: String = ""
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanReporter = reporterName.trim()
                .replace("\"", "")
                .replace("\\", "")
            if (cleanReporter.length < 2) return@runCatching emptyList()

            val primaryLogin = reporterLoginOrEmail
                .split('|')
                .map { it.trim().replace("\"", "").replace("\\", "") }
                .firstOrNull { it.length >= 2 && !it.equals(cleanReporter, ignoreCase = true) }
                .orEmpty()

            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val projectClause = TicketFilterEngine.splitCsv(filterSettings.projectKeys)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "\"${it.replace("\"", "")}\"" }
                ?.let { "project in ($it) AND " }
                .orEmpty()

            val safeExclude = excludeTicketKey.replace("\"", "")
            val jqlCandidates = buildList {
                add("${projectClause}key != \"$safeExclude\" AND reporter = \"$cleanReporter\" ORDER BY created DESC")
                if (primaryLogin.isNotEmpty()) {
                    add("${projectClause}key != \"$safeExclude\" AND reporter = \"$primaryLogin\" ORDER BY created DESC")
                }
                add("${projectClause}key != \"$safeExclude\" AND text ~ \"$cleanReporter\" ORDER BY created DESC")
            }

            for (jql in jqlCandidates) {
                val list = runCatching {
                    executePaginatedHistorySearch(
                        baseUrl = baseUrl,
                        jql = jql,
                        preferCloud = preferCloud,
                        settings = settings,
                        filterSettings = filterSettings,
                        maxTotal = 500
                    )
                        .filterNot {
                            it.key.equals(excludeTicketKey, ignoreCase = true) ||
                                AdminChatCodec.isAdminChatStorageIssue(
                                    issueKey = it.key,
                                    summary = it.summary,
                                    labels = it.labels,
                                    configuredChatKey = settings.adminChatIssueKey
                                )
                        }
                        .filter {
                            it.reporterName.contains(cleanReporter, ignoreCase = true) ||
                                cleanReporter.contains(it.reporterName, ignoreCase = true) ||
                                (primaryLogin.isNotEmpty() && it.reporterLoginOrEmail.contains(primaryLogin, ignoreCase = true))
                        }
                        .distinctBy { it.key.uppercase(Locale.ROOT) }
                        .sortedByDescending { it.createdEpochMillis }
                }.getOrNull()
                if (!list.isNullOrEmpty()) return@runCatching list
            }
            emptyList()
        }
    }

    /**
     * Fetches a broad, locale-safe team ticket sample over the requested [daysBack] window
     * (including `created`, `updated`, and `resolutiondate` across all staff members) so the
     * Employee KPI & Activity Monitor (`📈 KPI сотрудников`) displays complete, accurate metrics
     * even when hundreds of tickets were closed over 7 or 30 days.
     */
    suspend fun fetchEmployeeKpiTickets(
        daysBack: Int,
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxTotal: Int = 500
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeServerUrl(settings.serverUrl)
            val preferCloud = isCloudEndpoint(baseUrl, settings.serverType)
            val safeDays = (daysBack + 1).coerceIn(1, 45)

            val projectKeys = filterSettings.projectKeys
                .split(',', ';', ' ')
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.matches(Regex("[A-Z][A-Z0-9_]{0,19}")) }
                .distinct()

            val projectPrefix = if (projectKeys.isNotEmpty()) {
                "project in (${projectKeys.joinToString(",")}) AND "
            } else {
                ""
            }

            val candidates = buildList {
                add("${projectPrefix}(created >= \"-${safeDays}d\" OR updated >= \"-${safeDays}d\") ORDER BY updated DESC")
                if (projectPrefix.isNotEmpty()) {
                    add("(created >= \"-${safeDays}d\" OR updated >= \"-${safeDays}d\") ORDER BY updated DESC")
                }
                add("${projectPrefix}ORDER BY updated DESC")
                add("ORDER BY updated DESC")
            }.distinct()

            for (jql in candidates) {
                val list = runCatching {
                    executePaginatedHistorySearch(
                        baseUrl = baseUrl,
                        jql = jql,
                        preferCloud = preferCloud,
                        settings = settings,
                        filterSettings = filterSettings,
                        maxTotal = maxTotal
                    )
                }.getOrNull()
                if (!list.isNullOrEmpty()) {
                    return@runCatching list
                        .filterNot {
                            AdminChatCodec.isAdminChatStorageIssue(
                                issueKey = it.key,
                                summary = it.summary,
                                labels = it.labels,
                                configuredChatKey = settings.adminChatIssueKey
                            )
                        }
                        .distinctBy { it.key.uppercase(Locale.ROOT) }
                }
            }
            emptyList()
        }
    }

    private fun executePaginatedHistorySearch(
        baseUrl: String,
        jql: String,
        preferCloud: Boolean,
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxTotal: Int = 500
    ): List<HelpdeskTicket> {
        return if (preferCloud) {
            try {
                executePaginatedCloudHistorySearch(baseUrl, jql, settings, filterSettings, maxTotal)
            } catch (e: JiraApiException) {
                if (e.httpCode == 404 || e.httpCode == 405 || e.httpCode == 410) {
                    executePaginatedV2HistorySearch(baseUrl, jql, settings, filterSettings, maxTotal)
                } else {
                    throw e
                }
            }
        } else {
            try {
                executePaginatedV2HistorySearch(baseUrl, jql, settings, filterSettings, maxTotal)
            } catch (e: JiraApiException) {
                if (e.httpCode == 404 || e.httpCode == 410) {
                    executePaginatedCloudHistorySearch(baseUrl, jql, settings, filterSettings, maxTotal)
                } else {
                    throw e
                }
            }
        }
    }

    private fun executePaginatedV2HistorySearch(
        baseUrl: String,
        jql: String,
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxTotal: Int
    ): List<HelpdeskTicket> {
        val fieldsCsv = "summary,status,priority,issuetype,project,reporter,creator,assignee,created,updated,resolutiondate,labels"
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        val collected = mutableListOf<HelpdeskTicket>()
        var startAt = 0
        val pageSize = 100

        while (collected.size < maxTotal) {
            val batchSize = minOf(pageSize, maxTotal - collected.size)
            val url = "$baseUrl/rest/api/2/search?jql=$encodedJql&startAt=$startAt&maxResults=$batchSize&fields=$fieldsCsv"
            val req = Request.Builder().url(url).get().applyAuth(settings).build()
            val bodyStr = try {
                clientFor(settings).newCall(req).execute().use { resp ->
                    val str = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw mapHttpError(resp.code, str)
                    str
                }
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }

            val root = JsonParser.parseString(bodyStr).asJsonObject
            val issuesArr = root.get("issues")?.takeIf { it.isJsonArray }?.asJsonArray
            if (issuesArr == null || issuesArr.size() == 0) break

            val pageTickets = JiraJsonParser.parseSearchResponse(bodyStr, settings, filterSettings)
            collected.addAll(pageTickets)

            val total = root.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: issuesArr.size()
            startAt += issuesArr.size()
            if (startAt >= total) break
        }
        return collected
    }

    private fun executePaginatedCloudHistorySearch(
        baseUrl: String,
        jql: String,
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxTotal: Int
    ): List<HelpdeskTicket> {
        val fieldsList = listOf(
            "summary", "status", "priority", "issuetype", "project",
            "reporter", "creator", "assignee", "created", "updated", "resolutiondate", "labels"
        )
        val collected = mutableListOf<HelpdeskTicket>()
        var nextPageToken: String? = null
        var startAt = 0
        var useClassicEndpoint = false
        val pageSize = 100

        while (collected.size < maxTotal) {
            val batchSize = minOf(pageSize, maxTotal - collected.size)
            val payload = JsonObject().apply {
                addProperty("jql", jql)
                addProperty("maxResults", batchSize)
                add("fields", JsonArray().apply { fieldsList.forEach { add(it) } })
                if (useClassicEndpoint) {
                    addProperty("startAt", startAt)
                } else if (!nextPageToken.isNullOrBlank()) {
                    addProperty("nextPageToken", nextPageToken)
                }
            }
            val bodyBytes = payload.toString().toRequestBody(jsonMediaType)
            val targetUrl = if (useClassicEndpoint) "$baseUrl/rest/api/3/search" else "$baseUrl/rest/api/3/search/jql"

            val bodyStr = try {
                val req = Request.Builder().url(targetUrl).post(bodyBytes).applyAuth(settings).build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    val str = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        str
                    } else if (!useClassicEndpoint && (resp.code == 404 || resp.code == 405 || resp.code == 410)) {
                        useClassicEndpoint = true
                        ""
                    } else {
                        throw mapHttpError(resp.code, str)
                    }
                }
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }

            if (bodyStr.isEmpty() && useClassicEndpoint) continue

            val root = JsonParser.parseString(bodyStr).asJsonObject
            val issuesArr = root.get("issues")?.takeIf { it.isJsonArray }?.asJsonArray
            if (issuesArr == null || issuesArr.size() == 0) break

            val pageTickets = JiraJsonParser.parseSearchResponse(bodyStr, settings, filterSettings)
            collected.addAll(pageTickets)

            if (useClassicEndpoint) {
                val total = root.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: issuesArr.size()
                startAt += issuesArr.size()
                if (startAt >= total) break
            } else {
                val isLast = root.get("isLast")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                val token = root.get("nextPageToken")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                if (isLast || token == null) break
                nextPageToken = token
            }
        }
        return collected
    }

    suspend fun sendAdminChatMessage(
        roomIssueKey: String,
        rawText: String,
        replyToAuthor: String? = null,
        replyToPreview: String? = null,
        attachmentName: String? = null,
        attachmentUrl: String? = null,
        settings: AppSettings
    ): Result<AdminChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanText = rawText.trim()
            if (cleanText.isEmpty() && attachmentUrl.isNullOrBlank()) {
                throw JiraApiException("Введите текст сообщения")
            }
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val me = getCachedOrFetchAuthenticatedUser(baseUrl, preferCloud, settings)
            val myIds = buildUserIdentifiers(me, settings)

            val encodedBody = AdminChatCodec.encodeMessage(
                text = cleanText,
                replyToAuthor = replyToAuthor,
                replyToPreview = replyToPreview,
                attachmentName = attachmentName,
                attachmentUrl = attachmentUrl
            )
            val encodedKey = URLEncoder.encode(roomIssueKey, "UTF-8")

            val internalPropsArray = JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("key", "sd.public.comment")
                        add("value", JsonObject().apply { addProperty("internal", true) })
                    }
                )
            }

            val v2Internal = JsonObject().apply {
                addProperty("body", encodedBody)
                add("properties", internalPropsArray)
            }
            val v2Plain = JsonObject().apply {
                addProperty("body", encodedBody)
            }
            val v3Internal = JsonObject().apply {
                add("body", buildAdfDocument(encodedBody))
                add("properties", internalPropsArray)
            }
            val v3Plain = JsonObject().apply {
                add("body", buildAdfDocument(encodedBody))
            }

            val attempts = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/issue/$encodedKey/comment" to v3Internal,
                    "$baseUrl/rest/api/3/issue/$encodedKey/comment" to v3Plain,
                    "$baseUrl/rest/api/2/issue/$encodedKey/comment" to v2Plain
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/issue/$encodedKey/comment" to v2Internal,
                    "$baseUrl/rest/api/2/issue/$encodedKey/comment" to v2Plain,
                    "$baseUrl/rest/api/3/issue/$encodedKey/comment" to v3Plain
                )
            }

            var lastErr: Exception? = null
            for ((url, payload) in attempts) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val parsedObj = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
                            if (parsedObj != null) {
                                parseSingleChatComment(
                                    commentObj = parsedObj,
                                    roomIssueKey = roomIssueKey,
                                    myIdentifiers = myIds,
                                    myDisplayName = me.displayName
                                )?.let { return@runCatching it.copy(isOwnMessage = true) }
                            }
                            val decoded = AdminChatCodec.decodeMessage(encodedBody, roomIssueKey)
                            val nowMs = System.currentTimeMillis()
                            return@runCatching AdminChatMessage(
                                id = nowMs.toString(),
                                authorDisplayName = me.displayName,
                                authorIdentifier = me.accountId ?: me.name ?: settings.usernameOrEmail,
                                isOwnMessage = true,
                                text = decoded.text,
                                replyToAuthor = decoded.replyToAuthor,
                                replyToPreview = decoded.replyToPreview,
                                mentionedTicketKeys = decoded.mentionedTicketKeys,
                                mentionedHandles = decoded.mentionedHandles,
                                attachmentName = decoded.attachmentName,
                                attachmentUrl = decoded.attachmentUrl,
                                createdIso = java.time.Instant.ofEpochMilli(nowMs).toString(),
                                createdEpochMillis = nowMs
                            )
                        }
                        if (resp.code == 401 || resp.code == 403) {
                            throw mapHttpError(resp.code, bodyStr)
                        }
                        lastErr = mapHttpError(resp.code, bodyStr)
                    }
                } catch (e: JiraApiException) {
                    if (e.httpCode == 401 || e.httpCode == 403) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            throw (lastErr ?: JiraApiException("Не удалось отправить сообщение в чат"))
        }
    }

    suspend fun deleteAdminChatMessage(
        roomIssueKey: String,
        commentId: String,
        settings: AppSettings
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encodedKey = URLEncoder.encode(roomIssueKey, "UTF-8")
            val encodedCommentId = URLEncoder.encode(commentId, "UTF-8")
            val urls = if (preferCloud) {
                listOf(
                    "$baseUrl/rest/api/3/issue/$encodedKey/comment/$encodedCommentId",
                    "$baseUrl/rest/api/2/issue/$encodedKey/comment/$encodedCommentId"
                )
            } else {
                listOf(
                    "$baseUrl/rest/api/2/issue/$encodedKey/comment/$encodedCommentId",
                    "$baseUrl/rest/api/3/issue/$encodedKey/comment/$encodedCommentId"
                )
            }

            var lastErr: Exception? = null
            for (url in urls) {
                try {
                    val req = Request.Builder().url(url).delete().applyAuth(settings).build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful || resp.code == 204) return@runCatching
                        lastErr = mapHttpError(resp.code, bodyStr)
                    }
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            throw (lastErr ?: JiraApiException("Не удалось удалить сообщение"))
        }
    }

    private fun verifyIssueAccessible(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        settings: AppSettings
    ): Boolean {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val apiVer = if (preferCloud) "3" else "2"
        val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey?fields=summary"
        return runCatching {
            val req = Request.Builder().url(url).get().applyAuth(settings).build()
            clientFor(settings).newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun createSharedAdminChatRoomIssue(
        baseUrl: String,
        preferCloud: Boolean,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): String = createSharedSystemStorageIssue(
        baseUrl = baseUrl,
        preferCloud = preferCloud,
        settings = settings,
        filterSettings = filterSettings,
        summaryText = AdminChatCodec.ADMIN_CHAT_ROOM_SUMMARY,
        descriptionText = AdminChatCodec.ADMIN_CHAT_ROOM_DESCRIPTION,
        labelText = AdminChatCodec.ADMIN_CHAT_LABEL
    )

    /**
     * Resolves an explicitly configured shared Jira storage issue if `adminChatIssueKey` is set.
     * IMPORTANT: Automatic creation of `[HELPCSL_REPAIR_REGISTRY_ROOM]` issues in Jira is strictly
     * disabled so Help-CSL never pollutes live JSM queues with auto-created service tickets.
     */
    suspend fun resolveOrCreateRepairRegistryRoom(
        settings: AppSettings,
        filterSettings: FilterSettings,
        cachedRoomKey: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        roomResolveMutex.withLock {
            runCatching {
                val baseUrl = normalizeBaseUrl(settings.serverUrl)
                val preferCloud = isCloudTarget(settings, baseUrl)

                val manualKey = settings.adminChatIssueKey.trim().uppercase(Locale.ROOT)
                if (manualKey.isEmpty()) {
                    resolvedRepairRoomCache.remove(baseUrl)
                    throw JiraApiException("Автосоздание служебной комнаты журнала ремонта в Jira отключено")
                }

                if (verifyIssueAccessible(baseUrl, preferCloud, manualKey, settings)) {
                    resolvedRepairRoomCache[baseUrl] = manualKey
                    return@runCatching manualKey
                }
                throw JiraApiException("Указанная служебная задача $manualKey недоступна")
            }
        }
    }

    /**
     * Finds and removes (deletes if Jira permissions allow, otherwise transitions to Done/Closed/Cancelled
     * across multi-step JSM workflows) all previously auto-created `[HELPCSL_REPAIR_REGISTRY_ROOM]`
     * and `[HELPCSL_ADMIN_CHAT_ROOM]` issues so they no longer clutter Jira queues.
     */
    suspend fun cleanupSystemRoomIssues(
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<SystemRoomsCleanupReport> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            resolvedChatRoomCache.remove(baseUrl)
            resolvedRepairRoomCache.remove(baseUrl)

            data class FoundRoomIssue(
                val key: String,
                val summary: String,
                val statusCategoryKey: String
            )

            val foundByKey = LinkedHashMap<String, FoundRoomIssue>()
            val projectKeys = TicketFilterEngine.splitCsv(filterSettings.projectKeys)
            val queries = buildList {
                if (projectKeys.isNotEmpty()) {
                    val quoted = projectKeys.joinToString(", ") { "\"$it\"" }
                    add("project in ($quoted) ORDER BY created DESC")
                }
                add("""summary ~ "Журнал техники" OR summary ~ "Внутренний чат админов" ORDER BY created DESC""")
                add("""summary ~ "${RepairEquipmentCodec.REPAIR_REGISTRY_MARKER}" OR summary ~ "${AdminChatCodec.ADMIN_CHAT_MARKER}" ORDER BY created DESC""")
                add("ORDER BY created DESC")
            }

            for (jql in queries) {
                runCatching {
                    val json = executeSearchWithFallback(
                        baseUrl = baseUrl,
                        jql = jql,
                        maxResults = 200,
                        preferCloud = preferCloud,
                        settings = settings,
                        fieldsSpec = "key,summary,status",
                        expandNames = false
                    )
                    val arr = JsonParser.parseString(json).asJsonObject.getAsJsonArray("issues")
                    arr?.forEach { el ->
                        if (!el.isJsonObject) return@forEach
                        val obj = el.asJsonObject
                        val key = obj.takeString("key")?.uppercase(Locale.ROOT) ?: return@forEach
                        val fields = obj.getAsJsonObject("fields") ?: return@forEach
                        val summary = fields.takeString("summary").orEmpty()
                        val isAutoRoom = summary.contains(RepairEquipmentCodec.REPAIR_REGISTRY_MARKER, ignoreCase = true) ||
                            summary.contains(AdminChatCodec.ADMIN_CHAT_MARKER, ignoreCase = true)
                        if (!isAutoRoom) return@forEach

                        val catKey = fields.getAsJsonObject("status")
                            ?.getAsJsonObject("statusCategory")
                            ?.takeString("key")
                            .orEmpty()
                        foundByKey.putIfAbsent(key, FoundRoomIssue(key, summary, catKey))
                    }
                }
            }

            if (foundByKey.isEmpty()) {
                return@runCatching SystemRoomsCleanupReport(
                    foundTotal = 0,
                    deletedCount = 0,
                    closedCount = 0,
                    alreadyClosedCount = 0,
                    failedCount = 0
                )
            }

            val apiVer = if (preferCloud) "3" else "2"
            var deletedCount = 0
            var closedCount = 0
            var alreadyClosedCount = 0
            var failedCount = 0

            for (item in foundByKey.values) {
                val encodedKey = URLEncoder.encode(item.key, "UTF-8")

                // 1. Try permanent DELETE first (if Jira user has Delete Issue permission)
                val deleted = runCatching {
                    val delUrl = "$baseUrl/rest/api/$apiVer/issue/$encodedKey?deleteSubtasks=true"
                    val req = Request.Builder().url(delUrl).delete().applyAuth(settings).build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        resp.isSuccessful || resp.code == 204
                    }
                }.getOrDefault(false)

                if (deleted) {
                    deletedCount++
                    continue
                }

                // 2. If already in Done status category and cannot delete, skip
                if (item.statusCategoryKey.equals("done", ignoreCase = true)) {
                    alreadyClosedCount++
                    continue
                }

                // 3. Multi-hop workflow transition into Done/Closed/Cancelled (up to 4 hops for JSM workflows)
                val closedOk = closeSystemJunkIssueMultiHop(
                    baseUrl = baseUrl,
                    preferCloud = preferCloud,
                    issueKey = item.key,
                    settings = settings
                )
                if (closedOk) {
                    closedCount++
                } else {
                    failedCount++
                }
            }

            SystemRoomsCleanupReport(
                foundTotal = foundByKey.size,
                deletedCount = deletedCount,
                closedCount = closedCount,
                alreadyClosedCount = alreadyClosedCount,
                failedCount = failedCount
            )
        }
    }

    private fun closeSystemJunkIssueMultiHop(
        baseUrl: String,
        preferCloud: Boolean,
        issueKey: String,
        settings: AppSettings
    ): Boolean {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val apiVer = if (preferCloud) "3" else "2"

        fun fetchCurrentCategoryKey(): String = runCatching {
            val url = "$baseUrl/rest/api/$apiVer/issue/$encodedKey?fields=status"
            val req = Request.Builder().url(url).get().applyAuth(settings).build()
            clientFor(settings).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                JsonParser.parseString(resp.body?.string().orEmpty())
                    .asJsonObject
                    .getAsJsonObject("fields")
                    ?.getAsJsonObject("status")
                    ?.getAsJsonObject("statusCategory")
                    ?.takeString("key")
                    .orEmpty()
            }
        }.getOrDefault("")

        val visitedTransitionIds = mutableSetOf<String>()
        repeat(4) {
            if (fetchCurrentCategoryKey().equals("done", ignoreCase = true)) {
                return true
            }
            val transitions = runCatching {
                fetchAvailableTransitions(baseUrl, preferCloud, issueKey, settings)
            }.getOrDefault(emptyList())
            if (transitions.isEmpty()) return false

            // Priority A: Any direct transition into Done (Reject / Cancel / Close / Resolve / Done)
            val doneTransition = transitions.firstOrNull { it.toCategoryKey.equals("done", ignoreCase = true) }
                ?: selectBestTransition(transitions, TicketWorkflowAction.REJECT)
                ?: selectBestTransition(transitions, TicketWorkflowAction.COMPLETE)

            if (doneTransition != null) {
                val actionHint = if (containsAnyKeyword("${doneTransition.name} ${doneTransition.toStatusName}", REJECT_KEYWORDS)) {
                    TicketWorkflowAction.REJECT
                } else {
                    TicketWorkflowAction.COMPLETE
                }
                val transitioned = runCatching {
                    executeTransition(baseUrl, preferCloud, issueKey, doneTransition, actionHint, settings)
                    true
                }.getOrElse {
                    // Fallback: try plain transition payload or first allowed resolution
                    runCatching {
                        val fallbackResId = doneTransition.allowedResolutions.firstOrNull()?.first
                        val fallbackPayload = JsonObject().apply {
                            add("transition", JsonObject().apply { addProperty("id", doneTransition.id) })
                            if (fallbackResId != null && doneTransition.resolutionRequired) {
                                add(
                                    "fields",
                                    JsonObject().apply {
                                        add("resolution", JsonObject().apply { addProperty("id", fallbackResId) })
                                    }
                                )
                            }
                        }
                        val req = Request.Builder()
                            .url("$baseUrl/rest/api/$apiVer/issue/$encodedKey/transitions")
                            .post(fallbackPayload.toString().toRequestBody(jsonMediaType))
                            .applyAuth(settings)
                            .build()
                        clientFor(settings).newCall(req).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                }
                if (transitioned && fetchCurrentCategoryKey().equals("done", ignoreCase = true)) {
                    return true
                }
                if (!transitioned) return false
            } else {
                // Priority B: Advance JSM issue out of "Ожидание поддержки" into "В работе" so Done transitions unlock
                val stepTransition = (selectBestTransition(transitions, TicketWorkflowAction.TAKE_IN_WORK)
                    ?: transitions.firstOrNull { it.id !in visitedTransitionIds })
                    ?: return false
                visitedTransitionIds.add(stepTransition.id)
                val stepped = runCatching {
                    executeTransition(baseUrl, preferCloud, issueKey, stepTransition, TicketWorkflowAction.TAKE_IN_WORK, settings)
                    true
                }.getOrDefault(false)
                if (!stepped) return false
            }
        }
        return fetchCurrentCategoryKey().equals("done", ignoreCase = true)
    }

    suspend fun fetchRepairRegistryRemoteEntries(
        roomIssueKey: String,
        settings: AppSettings
    ): Result<List<RepairEquipmentItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val rawComments = fetchLatestRawCommentsJson(baseUrl, preferCloud, roomIssueKey, settings)
            rawComments.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val bodyText = JiraJsonParser.extractReadableText(el.asJsonObject.get("body"))
                RepairEquipmentCodec.decodeEntryComment(bodyText)
            }
        }
    }

    suspend fun postRepairRegistryEntry(
        roomIssueKey: String,
        item: RepairEquipmentItem,
        settings: AppSettings
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encoded = RepairEquipmentCodec.encodeEntryComment(item)
            addCommentToIssue(
                baseUrl = baseUrl,
                preferCloud = preferCloud,
                issueKey = roomIssueKey,
                commentText = encoded,
                settings = settings,
                internalOnly = true
            )
        }
    }

    suspend fun fetchClientTechPassports(
        roomIssueKey: String,
        settings: AppSettings
    ): Result<List<ClientTechPassport>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val rawComments = fetchLatestRawCommentsJson(baseUrl, preferCloud, roomIssueKey, settings)
            rawComments.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val bodyText = JiraJsonParser.extractReadableText(el.asJsonObject.get("body"))
                ClientTechPassportCodec.decodeComment(bodyText)
            }
        }
    }

    suspend fun postClientTechPassport(
        roomIssueKey: String,
        passport: ClientTechPassport,
        settings: AppSettings
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val encoded = ClientTechPassportCodec.encodeComment(passport)
            addCommentToIssue(
                baseUrl = baseUrl,
                preferCloud = preferCloud,
                issueKey = roomIssueKey,
                commentText = encoded,
                settings = settings,
                internalOnly = true
            )
        }
    }

    /**
     * Fetches the client & organization directory from Jira Helpdesk:
     * 1) Jira Service Management `/rest/servicedeskapi/organization` (up to 200 organizations),
     * 2) Recent Helpdesk issues (client reporters + JSM `Organizations` / company custom fields + phones).
     */
    suspend fun fetchHelpdeskClientsDirectory(
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<List<ClientDirectoryEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val collected = mutableListOf<ClientDirectoryEntry>()

            // 1. Best-effort JSM Organizations API (/rest/servicedeskapi/organization)
            runCatching {
                var start = 0
                repeat(2) {
                    val url = "$baseUrl/rest/servicedeskapi/organization?start=$start&limit=100"
                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .applyAuth(settings)
                        .header("X-ExperimentalApi", "opt-in")
                        .build()
                    val fetchMore = clientFor(settings).newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val root = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                        val values = root.getAsJsonArray("values") ?: return@use false
                        for (el in values) {
                            if (!el.isJsonObject) continue
                            val orgName = el.asJsonObject.takeString("name")?.trim().orEmpty()
                            if (ClientDirectoryCodec.isValidClientName(orgName, filterSettings)) {
                                collected.add(
                                    ClientDirectoryEntry(
                                        name = orgName,
                                        source = "JSM",
                                        lastSeenEpochMs = 100L
                                    )
                                )
                            }
                        }
                        val isLast = root.get("isLast")?.let { it.isJsonPrimitive && it.asBoolean } ?: true
                        !isLast && values.size() >= 100
                    }
                    if (!fetchMore) return@runCatching
                    start += 100
                }
            }

            // 2. Historical & recent Helpdesk tickets (reporters + custom Organization fields + phones)
            val projectKeys = TicketFilterEngine.splitCsv(filterSettings.projectKeys)
            val jql = if (projectKeys.isNotEmpty()) {
                val quoted = projectKeys.joinToString(", ") { "\"$it\"" }
                "project in ($quoted) ORDER BY updated DESC"
            } else {
                "ORDER BY updated DESC"
            }
            val searchJson = executeSearchWithFallback(
                baseUrl = baseUrl,
                jql = jql,
                maxResults = 100,
                preferCloud = preferCloud,
                settings = settings,
                fieldsSpec = "*all,-comment,-attachment,-worklog,-description",
                expandNames = true
            )
            val parsedTickets = JiraJsonParser.parseSearchResponse(searchJson, settings, filterSettings)
            val ticketByKey = HashMap<String, HelpdeskTicket>(parsedTickets.size)

            for (rawTicket in parsedTickets) {
                if (AdminChatCodec.isAdminChatStorageIssue(rawTicket.key, rawTicket.summary, rawTicket.labels)) {
                    continue
                }
                val eval = TicketFilterEngine.evaluate(rawTicket, filterSettings, settings.usernameOrEmail)
                val evaluated = rawTicket.copy(isClientTicket = eval.isClientTicket, filterReason = eval.reason)
                ticketByKey[evaluated.key.uppercase(Locale.ROOT)] = evaluated
                if (evaluated.isClientTicket &&
                    ClientDirectoryCodec.isValidClientName(evaluated.reporterName, filterSettings)
                ) {
                    collected.add(
                        ClientDirectoryEntry(
                            name = evaluated.reporterName,
                            phone = evaluated.phoneNumbers.firstOrNull()?.e164,
                            source = "Helpdesk",
                            lastSeenEpochMs = evaluated.updatedEpochMillis.coerceAtLeast(evaluated.createdEpochMillis)
                        )
                    )
                }
            }

            // Extract JSM `Organizations` / company custom fields from the raw search JSON
            runCatching {
                val root = JsonParser.parseString(searchJson).asJsonObject
                val namesObj = root.getAsJsonObject("names")
                val orgFieldKeys = mutableSetOf<String>()
                namesObj?.entrySet()?.forEach { (fieldId, labelEl) ->
                    if (labelEl.isJsonPrimitive) {
                        val labelLow = labelEl.asString.lowercase(Locale.ROOT)
                        if (labelLow.contains("organization") ||
                            labelLow.contains("организац") ||
                            labelLow.contains("компан") ||
                            labelLow.contains("контрагент")
                        ) {
                            orgFieldKeys.add(fieldId)
                        }
                    }
                }
                if (orgFieldKeys.isNotEmpty()) {
                    val issuesArr = root.getAsJsonArray("issues")
                    issuesArr?.forEach { issueEl ->
                        if (!issueEl.isJsonObject) return@forEach
                        val issueObj = issueEl.asJsonObject
                        val keyUpper = issueObj.takeString("key")?.uppercase(Locale.ROOT).orEmpty()
                        val matchedTicket = ticketByKey[keyUpper]
                        if (matchedTicket != null && !matchedTicket.isClientTicket) return@forEach
                        val phone = matchedTicket?.phoneNumbers?.firstOrNull()?.e164
                        val stamp = matchedTicket?.updatedEpochMillis?.coerceAtLeast(matchedTicket.createdEpochMillis) ?: 500L
                        val fieldsObj = issueObj.getAsJsonObject("fields") ?: return@forEach

                        for (fKey in orgFieldKeys) {
                            val rawVal = fieldsObj.get(fKey) ?: continue
                            when {
                                rawVal.isJsonArray -> rawVal.asJsonArray.forEach { item ->
                                    val orgName = when {
                                        item.isJsonPrimitive -> item.asString
                                        item.isJsonObject -> item.asJsonObject.takeString("name")
                                            ?: item.asJsonObject.takeString("value")
                                        else -> null
                                    }?.trim().orEmpty()
                                    if (ClientDirectoryCodec.isValidClientName(orgName, filterSettings)) {
                                        collected.add(
                                            ClientDirectoryEntry(
                                                name = orgName,
                                                phone = phone,
                                                source = "Helpdesk",
                                                lastSeenEpochMs = stamp
                                            )
                                        )
                                    }
                                }
                                rawVal.isJsonObject -> {
                                    val orgName = (rawVal.asJsonObject.takeString("name")
                                        ?: rawVal.asJsonObject.takeString("value"))?.trim().orEmpty()
                                    if (ClientDirectoryCodec.isValidClientName(orgName, filterSettings)) {
                                        collected.add(
                                            ClientDirectoryEntry(
                                                name = orgName,
                                                phone = phone,
                                                source = "Helpdesk",
                                                lastSeenEpochMs = stamp
                                            )
                                        )
                                    }
                                }
                                rawVal.isJsonPrimitive -> {
                                    val orgName = rawVal.asString.trim()
                                    if (ClientDirectoryCodec.isValidClientName(orgName, filterSettings)) {
                                        collected.add(
                                            ClientDirectoryEntry(
                                                name = orgName,
                                                phone = phone,
                                                source = "Helpdesk",
                                                lastSeenEpochMs = stamp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ClientDirectoryCodec.mergeEntries(emptyList(), collected, filterSettings)
        }
    }

    private fun createSharedSystemStorageIssue(
        baseUrl: String,
        preferCloud: Boolean,
        settings: AppSettings,
        filterSettings: FilterSettings,
        summaryText: String,
        descriptionText: String,
        labelText: String
    ): String {
        data class ProjectTypeCandidate(
            val projectId: String?,
            val projectKey: String?,
            val issueTypeId: String
        )

        val candidates = mutableListOf<ProjectTypeCandidate>()

        // Candidate Source A: Inspect recent existing issues in Jira (guaranteed valid project + non-subtask issueType)
        val projectFilterKeys = TicketFilterEngine.splitCsv(filterSettings.projectKeys)
        val sampleJqls = buildList {
            if (projectFilterKeys.isNotEmpty()) {
                val quoted = projectFilterKeys.joinToString(", ") { "\"$it\"" }
                add("project in ($quoted) ORDER BY created DESC")
            }
            add("ORDER BY created DESC")
        }

        for (jql in sampleJqls) {
            runCatching {
                val json = executeSearchWithFallback(baseUrl, jql, 8, preferCloud, settings)
                val issues = JsonParser.parseString(json).asJsonObject.getAsJsonArray("issues")
                issues?.forEach { el ->
                    if (!el.isJsonObject) return@forEach
                    val fields = el.asJsonObject.getAsJsonObject("fields") ?: return@forEach
                    val proj = fields.getAsJsonObject("project")
                    val iType = fields.getAsJsonObject("issuetype")
                    val isSubtask = iType?.get("subtask")?.let { it.isJsonPrimitive && it.asBoolean } ?: false
                    val typeId = iType?.takeString("id")
                    val pId = proj?.takeString("id")
                    val pKey = proj?.takeString("key")
                    if (!isSubtask && !typeId.isNullOrBlank() && (!pId.isNullOrBlank() || !pKey.isNullOrBlank())) {
                        val cand = ProjectTypeCandidate(pId, pKey, typeId)
                        if (candidates.none { it.projectKey == cand.projectKey && it.issueTypeId == cand.issueTypeId }) {
                            candidates.add(cand)
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) break
        }

        // Candidate Source B: Fallback to /project + /issuetype metadata endpoints
        if (candidates.isEmpty()) {
            runCatching {
                val apiVer = if (preferCloud) "3" else "2"
                val projReq = Request.Builder().url("$baseUrl/rest/api/$apiVer/project").get().applyAuth(settings).build()
                val firstProj = clientFor(settings).newCall(projReq).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val arr = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonArray
                        val preferredKey = projectFilterKeys.firstOrNull()?.uppercase(Locale.ROOT)
                        val chosen = if (preferredKey != null) {
                            arr.firstOrNull {
                                it.isJsonObject && it.asJsonObject.takeString("key")?.uppercase(Locale.ROOT) == preferredKey
                            } ?: arr.firstOrNull()
                        } else {
                            arr.firstOrNull()
                        }
                        chosen?.asJsonObject
                    }
                }

                val typeReq = Request.Builder().url("$baseUrl/rest/api/$apiVer/issuetype").get().applyAuth(settings).build()
                val nonSubtaskTypes = clientFor(settings).newCall(typeReq).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else {
                        JsonParser.parseString(resp.body?.string().orEmpty()).asJsonArray.mapNotNull { el ->
                            if (!el.isJsonObject) return@mapNotNull null
                            val o = el.asJsonObject
                            val sub = o.get("subtask")?.let { it.isJsonPrimitive && it.asBoolean } ?: false
                            if (sub) null else (o.takeString("id") to o.takeString("name").orEmpty())
                        }
                    }
                }

                val pId = firstProj?.takeString("id")
                val pKey = firstProj?.takeString("key")
                val sortedTypes = nonSubtaskTypes.sortedBy { (_, name) ->
                    val low = name.lowercase(Locale.ROOT)
                    when {
                        low.contains("task") || low.contains("задач") -> 0
                        low.contains("service") || low.contains("запрос") -> 1
                        else -> 2
                    }
                }
                for ((tId, _) in sortedTypes.take(3)) {
                    if (!tId.isNullOrBlank() && (!pId.isNullOrBlank() || !pKey.isNullOrBlank())) {
                        candidates.add(ProjectTypeCandidate(pId, pKey, tId))
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            throw JiraApiException(
                "Не удалось автоматически подобрать проект для комнаты чата. " +
                    "Укажите ключ любой служебной задачи (например CSL-100) в настройках комнаты чата."
            )
        }

        val apiVer = if (preferCloud) "3" else "2"
        val createUrl = "$baseUrl/rest/api/$apiVer/issue"
        val protectedFields = setOf("project", "issuetype", "summary")
        var lastError: Exception? = null

        for (cand in candidates.take(4)) {
            val fields = JsonObject().apply {
                add(
                    "project",
                    JsonObject().apply {
                        if (!cand.projectId.isNullOrBlank()) addProperty("id", cand.projectId)
                        else addProperty("key", cand.projectKey)
                    }
                )
                add("issuetype", JsonObject().apply { addProperty("id", cand.issueTypeId) })
                addProperty("summary", summaryText)
                if (preferCloud) {
                    add("description", buildAdfDocument(descriptionText))
                } else {
                    addProperty("description", descriptionText)
                }
                add("labels", JsonArray().apply { add(labelText) })
            }

            repeat(3) {
                val payload = JsonObject().apply { add("fields", fields) }
                try {
                    val req = Request.Builder()
                        .url(createUrl)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .applyAuth(settings)
                        .build()
                    val createdKey = clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            JsonParser.parseString(bodyStr).asJsonObject.takeString("key")
                        } else if (resp.code == 400) {
                            val rejectedKeys = runCatching {
                                JsonParser.parseString(bodyStr)
                                    .asJsonObject
                                    .getAsJsonObject("errors")
                                    ?.keySet()
                                    ?.filter { it !in protectedFields }
                                    .orEmpty()
                            }.getOrDefault(emptyList())
                            var stripped = false
                            for (rk in rejectedKeys) {
                                if (fields.has(rk)) {
                                    fields.remove(rk)
                                    stripped = true
                                }
                            }
                            lastError = mapHttpError(resp.code, bodyStr)
                            if (!stripped) null else ""
                        } else {
                            lastError = mapHttpError(resp.code, bodyStr)
                            null
                        }
                    }

                    if (!createdKey.isNullOrBlank()) {
                        // Best-effort close the chat storage issue so it never clutters active Jira queues
                        runCatching {
                            val transitions = fetchAvailableTransitions(baseUrl, preferCloud, createdKey, settings)
                            val closeTrans = selectBestTransition(transitions, TicketWorkflowAction.COMPLETE)
                            if (closeTrans != null) {
                                executeTransition(baseUrl, preferCloud, createdKey, closeTrans, TicketWorkflowAction.COMPLETE, settings)
                            }
                        }
                        return createdKey.uppercase(Locale.ROOT)
                    } else if (createdKey == null) {
                        return@repeat
                    }
                } catch (e: Exception) {
                    lastError = wrapNetworkException(e, settings.trustSelfSignedSsl)
                    return@repeat
                }
            }
        }

        throw (lastError ?: JiraApiException(
            "Не удалось создать комнату чата в Jira. Укажите существующий номер задачи вручную в настройках чата."
        ))
    }

    private fun getCachedOrFetchAuthenticatedUser(
        baseUrl: String,
        preferCloud: Boolean,
        settings: AppSettings
    ): AuthenticatedJiraUser {
        val sig = "$baseUrl|${settings.authType}|${settings.usernameOrEmail}|${settings.passwordOrToken.hashCode()}"
        val existing = cachedAuthenticatedUser
        if (existing != null && cachedUserSignature == sig) {
            return existing
        }
        val fetched = fetchAuthenticatedUser(baseUrl, preferCloud, settings)
        cachedUserSignature = sig
        cachedAuthenticatedUser = fetched
        return fetched
    }

    private fun buildUserIdentifiers(
        user: AuthenticatedJiraUser,
        settings: AppSettings
    ): Set<String> {
        return buildSet {
            user.accountId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
            user.name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
            user.key?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
            settings.usernameOrEmail.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private fun fetchLatestRawCommentsJson(
        baseUrl: String,
        preferCloud: Boolean,
        roomIssueKey: String,
        settings: AppSettings
    ): JsonArray {
        val encodedKey = URLEncoder.encode(roomIssueKey, "UTF-8")
        val maxFetch = 80

        if (preferCloud) {
            val cloudUrls = listOf(
                "$baseUrl/rest/api/3/issue/$encodedKey/comment?orderBy=-created&maxResults=$maxFetch",
                "$baseUrl/rest/api/3/issue/$encodedKey/comment?maxResults=$maxFetch",
                "$baseUrl/rest/api/2/issue/$encodedKey/comment?maxResults=$maxFetch"
            )
            for (url in cloudUrls) {
                val arr = runCatching { fetchCommentsPageAndTailIfNeeded(baseUrl, "3", encodedKey, url, maxFetch, settings) }.getOrNull()
                if (arr != null) return arr
            }
        } else {
            val serverUrls = listOf(
                "$baseUrl/rest/api/2/issue/$encodedKey/comment?maxResults=$maxFetch",
                "$baseUrl/rest/api/3/issue/$encodedKey/comment?orderBy=-created&maxResults=$maxFetch"
            )
            for (url in serverUrls) {
                val ver = if (url.contains("/api/3/")) "3" else "2"
                val arr = runCatching { fetchCommentsPageAndTailIfNeeded(baseUrl, ver, encodedKey, url, maxFetch, settings) }.getOrNull()
                if (arr != null) return arr
            }
        }

        // Fallback: fetch issue with ?fields=comment
        val apiVer = if (preferCloud) "3" else "2"
        val issueUrl = "$baseUrl/rest/api/$apiVer/issue/$encodedKey?fields=comment"
        val req = Request.Builder().url(issueUrl).get().applyAuth(settings).build()
        clientFor(settings).newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw mapHttpError(resp.code, bodyStr)
            val root = JsonParser.parseString(bodyStr).asJsonObject
            return root.getAsJsonObject("fields")
                ?.getAsJsonObject("comment")
                ?.getAsJsonArray("comments")
                ?: JsonArray()
        }
    }

    private fun fetchCommentsPageAndTailIfNeeded(
        baseUrl: String,
        apiVer: String,
        encodedKey: String,
        initialUrl: String,
        maxFetch: Int,
        settings: AppSettings
    ): JsonArray {
        val req = Request.Builder().url(initialUrl).get().applyAuth(settings).build()
        val firstObj = clientFor(settings).newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw mapHttpError(resp.code, bodyStr)
            JsonParser.parseString(bodyStr).asJsonObject
        }

        val firstComments = firstObj.getAsJsonArray("comments") ?: JsonArray()
        val total = firstObj.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: firstComments.size()
        val isOrderedDesc = initialUrl.contains("orderBy=-created")

        if (!isOrderedDesc && total > firstComments.size()) {
            val tailStart = (total - maxFetch).coerceAtLeast(0)
            val tailUrl = "$baseUrl/rest/api/$apiVer/issue/$encodedKey/comment?startAt=$tailStart&maxResults=$maxFetch"
            val tailReq = Request.Builder().url(tailUrl).get().applyAuth(settings).build()
            clientFor(settings).newCall(tailReq).execute().use { tailResp ->
                if (tailResp.isSuccessful) {
                    val tailObj = JsonParser.parseString(tailResp.body?.string().orEmpty()).asJsonObject
                    tailObj.getAsJsonArray("comments")?.let { return it }
                }
            }
        }
        return firstComments
    }

    private fun parseSingleChatComment(
        commentObj: JsonObject,
        roomIssueKey: String,
        myIdentifiers: Set<String>,
        myDisplayName: String
    ): AdminChatMessage? {
        val id = commentObj.takeString("id") ?: return null
        val rawBody = JiraJsonParser.extractReadableText(commentObj.get("body")).trim()
        if (rawBody.isEmpty()) return null

        val authorObj = commentObj.getAsJsonObject("author")
            ?: commentObj.getAsJsonObject("updateAuthor")
        val authorDisplay = authorObj?.takeString("displayName")
            ?: authorObj?.takeString("name")
            ?: authorObj?.takeString("emailAddress")
            ?: "Администратор"

        val authorCandidates = buildSet {
            authorObj?.takeString("accountId")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
            authorObj?.takeString("name")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
            authorObj?.takeString("key")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
            authorObj?.takeString("emailAddress")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::add)
        }

        val isMine = (authorCandidates.isNotEmpty() && authorCandidates.any { it in myIdentifiers }) ||
            (authorCandidates.isEmpty() && authorDisplay.equals(myDisplayName, ignoreCase = true))

        val createdIso = commentObj.takeString("created").orEmpty()
        val createdMillis = JiraJsonParser.parseJiraTimestampMillis(createdIso)
        val decoded = AdminChatCodec.decodeMessage(rawBody, roomIssueKey)

        return AdminChatMessage(
            id = id,
            authorDisplayName = authorDisplay,
            authorIdentifier = authorCandidates.firstOrNull() ?: authorDisplay,
            isOwnMessage = isMine,
            text = decoded.text,
            replyToAuthor = decoded.replyToAuthor,
            replyToPreview = decoded.replyToPreview,
            mentionedTicketKeys = decoded.mentionedTicketKeys,
            mentionedHandles = decoded.mentionedHandles,
            attachmentName = decoded.attachmentName,
            attachmentUrl = decoded.attachmentUrl,
            createdIso = createdIso,
            createdEpochMillis = createdMillis
        )
    }

    private fun mapHttpError(code: Int, body: String): JiraApiException {
        val serverMsg = runCatching {
            val obj = JsonParser.parseString(body).asJsonObject
            val arr = obj.getAsJsonArray("errorMessages")
            val firstMsg = if (arr != null && arr.size() > 0) arr[0].asString else null
            if (!firstMsg.isNullOrBlank()) {
                firstMsg
            } else {
                val errorsObj = obj.getAsJsonObject("errors")
                errorsObj?.entrySet()?.firstOrNull()?.let { (k, v) -> "$k: ${v.asString}" }
            }
        }.getOrNull()

        val message = when (code) {
            400 -> "Ошибка запроса Jira (400): ${serverMsg ?: "некорректные параметры или поле в Jira"}"
            401 -> "Ошибка авторизации (401): проверьте логин/email и пароль или токен"
            403 -> "Доступ запрещён (403): недостаточно прав или сработала защита CAPTCHA в Jira"
            404 -> "Ресурс не найден (404): проверьте адрес сервера Jira или права доступа к задаче"
            else -> "Ошибка сервера Jira ($code)${if (serverMsg != null) ": $serverMsg" else ""}"
        }
        return JiraApiException(message, code)
    }

    private fun isRetryableStaleOrTimeoutError(e: Throwable): Boolean {
        if (e is java.io.InterruptedIOException) return true // includes SocketTimeoutException("timeout")
        if (e is java.io.EOFException) return true
        if (e is java.net.SocketException) return true
        if (e is java.io.IOException) {
            val m = e.message?.lowercase(Locale.ROOT).orEmpty()
            if (m.contains("timeout") ||
                m.contains("reset") ||
                m.contains("closed") ||
                m.contains("broken pipe") ||
                m.contains("unexpected end") ||
                e.javaClass.simpleName.contains("StreamReset", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    private fun executeIdempotentCallWithRetry(
        settings: AppSettings,
        request: Request
    ): Pair<Int, String> {
        val client = clientFor(settings)
        try {
            client.newCall(request).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        } catch (firstErr: Exception) {
            if (!isRetryableStaleOrTimeoutError(firstErr)) {
                throw firstErr
            }
            // Evict dead/half-open mobile NAT or HTTP/2 socket and retry once on a clean TCP socket
            evictAllConnections()
            runCatching { Thread.sleep(250L) }
            val freshReq = request.newBuilder()
                .header("Connection", "close")
                .build()
            client.newCall(freshReq).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        }
    }

    private fun wrapNetworkException(e: Exception, trustSelfSigned: Boolean): Exception {
        if (e is JiraApiException) return e
        val msg = e.message.orEmpty()
        if (e is javax.net.ssl.SSLException && !trustSelfSigned) {
            return JiraApiException(
                message = "Ошибка SSL-сертификата. Если используется внутренний корпоративный сервер, " +
                    "включите «Доверять самоподписанным SSL» в настройках.",
                httpCode = null,
                isNetworkError = false
            )
        }
        if (e is java.net.SocketTimeoutException || msg.equals("timeout", ignoreCase = true)) {
            return JiraApiException(
                message = "Сервер Jira не ответил вовремя (таймаут соединения). Нажмите «Повтор».",
                httpCode = null,
                isNetworkError = true
            )
        }
        return JiraApiException(
            message = "Ошибка сети: ${msg.ifBlank { e.javaClass.simpleName }}",
            httpCode = null,
            isNetworkError = true
        )
    }

    companion object {
        private val IN_WORK_KEYWORDS = listOf(
            "в работу", "взять в работу", "в работе", "начать", "принять", "приступить",
            "выполнять", "исполнение", "обработк", "ответить клиенту",
            "start progress", "in progress", "in work", "start", "respond", "begin"
        )

        private val COMPLETE_KEYWORDS = listOf(
            "заверш", "выполн", "реш", "закр", "готов", "исполн",
            "resolve", "complete", "close", "done", "finish", "fixed"
        )

        private val REJECT_KEYWORDS = listOf(
            "отклон", "отмен", "отказ", "не будет", "ложн", "дубликат",
            "decline", "reject", "cancel", "won't", "wont", "invalid"
        )

        private val WAITING_SUPPORT_KEYWORDS = listOf(
            "ожидание поддержки", "ожидаие поддержки", "ожидает поддержки", "waiting for support"
        )

        private val PHONE_FIELD_HINTS = listOf(
            "phone", "телефон", "тел.", "тел ", "мобил", "контакт", "номер связи", "mobile", "callback"
        )

        private const val METADATA_CACHE_TTL_MS = 30L * 60_000L

        private data class CachedStatusCatalog(
            val inProgressIds: List<String>,
            val doneIds: List<String>,
            val fetchedAtMs: Long
        )

        private data class DiscoveredJsmQueue(
            val serviceDeskId: String,
            val projectKey: String,
            val queueId: String,
            val queueName: String,
            val rawJql: String
        )

        private data class CachedJsmQueues(
            val activeHumanQueues: List<DiscoveredJsmQueue>,
            val closedHumanQueues: List<DiscoveredJsmQueue>,
            val serviceQueues: List<DiscoveredJsmQueue>,
            val projectKeys: List<String>,
            val fetchedAtMs: Long
        )

        private val statusCatalogCache = ConcurrentHashMap<String, CachedStatusCatalog>()
        private val jsmQueuesCache = ConcurrentHashMap<String, CachedJsmQueues>()
        private val resolvedChatRoomCache = ConcurrentHashMap<String, String>()
        private val resolvedRepairRoomCache = ConcurrentHashMap<String, String>()
        private val roomResolveMutex = Mutex()

        private val jiraConnectionPool = okhttp3.ConnectionPool(8, 60, TimeUnit.SECONDS)

        val sharedStandardClient: OkHttpClient by lazy { buildBaseClient() }
        val sharedTrustAllClient: OkHttpClient by lazy { buildTrustAllOverlay(sharedStandardClient) }

        fun sharedClient(trustAllSsl: Boolean): OkHttpClient {
            return if (trustAllSsl) sharedTrustAllClient else sharedStandardClient
        }

        fun evictAllConnections() {
            runCatching { jiraConnectionPool.evictAll() }
        }

        private fun buildBaseClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectionPool(jiraConnectionPool)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private fun buildTrustAllOverlay(base: OkHttpClient): OkHttpClient {
            val builder = base.newBuilder()
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
            return builder.build()
        }
    }
}
