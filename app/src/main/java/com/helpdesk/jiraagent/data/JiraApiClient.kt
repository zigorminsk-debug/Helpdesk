package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.TicketStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class JiraApiException(message: String, val httpCode: Int? = null) : Exception(message)

enum class TicketWorkflowAction {
    TAKE_IN_WORK,
    COMPLETE,
    REJECT
}

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

private data class AuthenticatedJiraUser(
    val name: String?,
    val accountId: String?,
    val key: String?,
    val displayName: String
)

class JiraApiClient {

    private val standardClient: OkHttpClient = buildClient(trustAllSsl = false)
    private val selfSignedClient: OkHttpClient = buildClient(trustAllSsl = true)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val json = JsonParser.parseString(bodyStr).asJsonObject
                            val displayName = json.get("displayName")?.asString
                                ?: json.get("name")?.asString
                                ?: json.get("emailAddress")?.asString
                                ?: "Успешно"
                            return@withContext Result.success(displayName)
                        } else if (resp.code == 401 || resp.code == 403) {
                            throw mapHttpError(resp.code, bodyStr)
                        } else {
                            lastErr = mapHttpError(resp.code, bodyStr)
                        }
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
     * Fetches active (Новые + В работе) and recently closed (Закрытые) tickets from Jira
     * concurrently, compatible with both Jira Cloud v3 `/search/jql` and Server/DC v2 `/search`.
     */
    suspend fun fetchRecentTickets(
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxResults: Int = 50
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)

            val activeJql = TicketFilterEngine.buildActiveJql(filterSettings)
            val closedJql = TicketFilterEngine.buildClosedJql(filterSettings)

            coroutineScope {
                val activeDeferred = async {
                    val json = executeSearchWithFallback(baseUrl, activeJql, maxResults, preferCloud, settings)
                    JiraJsonParser.parseSearchResponse(json, settings, filterSettings)
                }
                val closedDeferred = async {
                    if (closedJql == null) {
                        emptyList()
                    } else {
                        runCatching {
                            val json = executeSearchWithFallback(baseUrl, closedJql, 30, preferCloud, settings)
                            JiraJsonParser.parseSearchResponse(json, settings, filterSettings)
                        }.getOrDefault(emptyList())
                    }
                }

                val mergedByKey = LinkedHashMap<String, HelpdeskTicket>()
                for (ticket in activeDeferred.await()) {
                    mergedByKey[ticket.key] = ticket
                }
                for (ticket in closedDeferred.await()) {
                    mergedByKey.putIfAbsent(ticket.key, ticket)
                }
                mergedByKey.values.toList()
            }
        }
    }

    private fun executeSearchWithFallback(
        baseUrl: String,
        jql: String,
        maxResults: Int,
        preferCloud: Boolean,
        settings: AppSettings
    ): String {
        return if (preferCloud) {
            try {
                executeCloudJqlSearch(baseUrl, jql, maxResults, settings)
            } catch (e: JiraApiException) {
                if (e.httpCode == 404 || e.httpCode == 405 || e.httpCode == 410) {
                    executeV2GetSearch(baseUrl, jql, maxResults, settings)
                } else {
                    throw e
                }
            }
        } else {
            try {
                executeV2GetSearch(baseUrl, jql, maxResults, settings)
            } catch (e: JiraApiException) {
                if (e.httpCode == 404 || e.httpCode == 410) {
                    executeCloudJqlSearch(baseUrl, jql, maxResults, settings)
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
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw mapHttpError(resp.code, bodyStr)
                    bodyStr
                }
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

                val refreshed = fetchTicketByKey(issueKey, settings, filterSettings).getOrNull()
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
        settings: AppSettings
    ) {
        val encodedKey = URLEncoder.encode(issueKey, "UTF-8")
        val v2Payload = JsonObject().apply { addProperty("body", commentText) }
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
        settings: AppSettings
    ): String {
        val payload = JsonObject().apply {
            addProperty("jql", jql)
            addProperty("maxResults", maxResults)
            add("fields", JsonArray().apply { add("*all") })
            addProperty("expand", "names")
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
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return bodyStr
                    if (resp.code == 401 || resp.code == 403 || resp.code == 400) {
                        throw mapHttpError(resp.code, bodyStr)
                    }
                    lastError = mapHttpError(resp.code, bodyStr)
                }
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
        settings: AppSettings
    ): String {
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        val url = "$baseUrl/rest/api/2/search?jql=$encodedJql&maxResults=$maxResults&fields=*all&expand=names"
        val req = Request.Builder().url(url).get().applyAuth(settings).build()
        try {
            clientFor(settings).newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw mapHttpError(resp.code, bodyStr)
                return bodyStr
            }
        } catch (e: Exception) {
            throw wrapNetworkException(e, settings.trustSelfSignedSsl)
        }
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

    private fun wrapNetworkException(e: Exception, trustSelfSigned: Boolean): Exception {
        if (e is JiraApiException) return e
        val msg = e.message.orEmpty()
        if (e is javax.net.ssl.SSLException && !trustSelfSigned) {
            return JiraApiException(
                "Ошибка SSL-сертификата. Если используется внутренний корпоративный сервер, " +
                    "включите «Доверять самоподписанным SSL» в настройках."
            )
        }
        return JiraApiException("Ошибка сети: ${msg.ifBlank { e.javaClass.simpleName }}")
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

        private fun buildClient(trustAllSsl: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)

            if (trustAllSsl) {
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                builder.hostnameVerifier { _, _ -> true }
            }

            return builder.build()
        }
    }
}
