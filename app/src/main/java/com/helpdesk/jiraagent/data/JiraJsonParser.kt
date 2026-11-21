package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.TicketComment
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object JiraJsonParser {

    private val SYSTEM_SKIP_FIELDS = setOf(
        "summary", "description", "comment", "issuetype", "status", "priority",
        "project", "creator", "reporter", "assignee", "created", "updated",
        "resolutiondate", "duedate", "lastViewed", "watches", "votes",
        "worklog", "timetracking", "progress", "aggregateprogress",
        "issuelinks", "subtasks", "attachment", "components", "fixVersions",
        "versions", "security", "resolution", "labels", " statuscategorychangedate",
        "statuscategorychangedate", "workratio", "thumbnail"
    )

    private val PHONE_FIELD_HINTS = listOf(
        "phone", "телефон", "тел", "mobile", "мобил", "контакт", "номер", "callback", "звон"
    )

    // Jira timestamps often use +0300 without colon (e.g. 2026-11-21T10:15:30.000+0300)
    private val JIRA_OFFSET_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private val JIRA_OFFSET_NO_MS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

    fun parseSearchResponse(
        jsonString: String,
        appSettings: AppSettings,
        filterSettings: FilterSettings
    ): List<HelpdeskTicket> {
        val root = JsonParser.parseString(jsonString).asJsonObject
        val topLevelNames = root.getObjectOrNull("names")
        val issuesArray = root.getArrayOrNull("issues") ?: return emptyList()

        val result = mutableListOf<HelpdeskTicket>()
        for (element in issuesArray) {
            if (!element.isJsonObject) continue
            val issueObj = element.asJsonObject
            val namesObj = issueObj.getObjectOrNull("names") ?: topLevelNames
            parseIssueObject(issueObj, namesObj, appSettings, filterSettings)?.let {
                result.add(it)
            }
        }
        return result
    }

    fun parseSingleIssue(
        jsonString: String,
        appSettings: AppSettings,
        filterSettings: FilterSettings
    ): HelpdeskTicket? {
        val issueObj = JsonParser.parseString(jsonString).asJsonObject
        val namesObj = issueObj.getObjectOrNull("names")
        return parseIssueObject(issueObj, namesObj, appSettings, filterSettings)
    }

    private fun parseIssueObject(
        issueObj: JsonObject,
        namesObj: JsonObject?,
        appSettings: AppSettings,
        filterSettings: FilterSettings
    ): HelpdeskTicket? {
        val key = issueObj.getStringOrNull("key") ?: return null
        val id = issueObj.getStringOrNull("id") ?: key
        val fields = issueObj.getObjectOrNull("fields") ?: JsonObject()

        val summary = fields.getStringOrNull("summary")?.trim().orEmpty().ifEmpty { "(Без темы)" }
        val description = extractReadableText(fields.get("description")).trim()

        val issueTypeObj = fields.getObjectOrNull("issuetype")
        val issueType = issueTypeObj?.getStringOrNull("name") ?: "Task"
        val isSubtask = issueTypeObj?.getBooleanOrDefault("subtask", false) ?: false

        val statusObj = fields.getObjectOrNull("status")
        val statusName = statusObj?.getStringOrNull("name") ?: "Open"
        val statusCategoryKey = statusObj
            ?.getObjectOrNull("statusCategory")
            ?.getStringOrNull("key")
            .orEmpty()

        val priorityName = fields.getObjectOrNull("priority")?.getStringOrNull("name") ?: "Normal"

        val projectObj = fields.getObjectOrNull("project")
        val projectKey = projectObj?.getStringOrNull("key")
            ?: key.substringBefore('-', "")
        val projectName = projectObj?.getStringOrNull("name") ?: projectKey

        val reporterObj = fields.getObjectOrNull("reporter")
        val creatorObj = fields.getObjectOrNull("creator")
        val primaryAuthorObj = reporterObj ?: creatorObj

        val reporterName = primaryAuthorObj?.getStringOrNull("displayName")
            ?: primaryAuthorObj?.getStringOrNull("name")
            ?: primaryAuthorObj?.getStringOrNull("emailAddress")
            ?: "Клиент"

        // Collect all sender identifiers (login, email, displayName, key from both reporter & creator)
        // so sender rules like "zabbix" match regardless of which Jira field holds the account name.
        val reporterLoginOrEmail = listOfNotNull(
            reporterObj?.getStringOrNull("name"),
            reporterObj?.getStringOrNull("emailAddress"),
            reporterObj?.getStringOrNull("displayName"),
            reporterObj?.getStringOrNull("key"),
            creatorObj?.getStringOrNull("name"),
            creatorObj?.getStringOrNull("emailAddress"),
            creatorObj?.getStringOrNull("displayName")
        ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" | ")

        val assigneeName = fields.getObjectOrNull("assignee")?.getStringOrNull("displayName")

        val createdIso = fields.getStringOrNull("created").orEmpty()
        val updatedIso = fields.getStringOrNull("updated").orEmpty()
        val createdMillis = parseJiraTimestampMillis(createdIso)
        val updatedMillis = parseJiraTimestampMillis(updatedIso)

        val labels = fields.getArrayOrNull("labels")
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            .orEmpty()

        val comments = parseComments(fields.getObjectOrNull("comment"))

        // Detect Jira Service Management Customer Request Type & portal links
        var customerRequestType: String? = null
        var hasCustomerPortalInfo = false
        for ((fieldKey, fieldVal) in fields.entrySet()) {
            if (!fieldKey.startsWith("customfield_") || !fieldVal.isJsonObject) continue
            val obj = fieldVal.asJsonObject
            val reqTypeObj = obj.getObjectOrNull("requestType")
            if (reqTypeObj != null) {
                customerRequestType = reqTypeObj.getStringOrNull("name")
                hasCustomerPortalInfo = true
                break
            }
            if (obj.getObjectOrNull("_links")?.getStringOrNull("portal") != null) {
                hasCustomerPortalInfo = true
            }
        }

        // Extract and normalize phone numbers from custom fields, summary, description, comments
        val phones = extractAllPhones(
            fields = fields,
            namesObj = namesObj,
            summary = summary,
            description = description,
            comments = comments,
            appSettings = appSettings
        )

        val cleanBaseUrl = appSettings.serverUrl.trim().trimEnd('/')
        val webUrl = if (cleanBaseUrl.isNotEmpty()) "$cleanBaseUrl/browse/$key" else ""

        val rawTicket = HelpdeskTicket(
            id = id,
            key = key,
            summary = summary,
            description = description,
            issueType = issueType,
            isSubtask = isSubtask,
            customerRequestType = customerRequestType,
            hasCustomerPortalInfo = hasCustomerPortalInfo,
            status = statusName,
            statusCategoryKey = statusCategoryKey,
            priority = priorityName,
            projectKey = projectKey,
            projectName = projectName,
            reporterName = reporterName,
            reporterLoginOrEmail = reporterLoginOrEmail,
            assigneeName = assigneeName,
            createdIso = createdIso,
            createdEpochMillis = createdMillis,
            updatedIso = updatedIso,
            updatedEpochMillis = updatedMillis,
            labels = labels,
            phoneNumbers = phones,
            comments = comments,
            webUrl = webUrl
        )

        val eval = TicketFilterEngine.evaluate(
            ticket = rawTicket,
            filter = filterSettings,
            currentAgentLoginOrEmail = appSettings.usernameOrEmail
        )

        return rawTicket.copy(
            isClientTicket = eval.isClientTicket,
            filterReason = eval.reason
        )
    }

    private fun extractAllPhones(
        fields: JsonObject,
        namesObj: JsonObject?,
        summary: String,
        description: String,
        comments: List<TicketComment>,
        appSettings: AppSettings
    ): List<PhoneMatch> {
        val region = appSettings.phoneRegion
        val customCode = appSettings.customCountryCode
        val userPhoneFields = TicketFilterEngine.splitCsv(appSettings.customPhoneFieldNames)
            .map { it.lowercase() }

        val collected = mutableListOf<PhoneMatch>()

        // 1. Dedicated phone custom fields first (matched by field key or display name)
        for ((fieldKey, fieldValue) in fields.entrySet()) {
            if (fieldValue == null || fieldValue.isJsonNull) continue
            if (fieldKey in SYSTEM_SKIP_FIELDS) continue

            val displayName = namesObj?.getStringOrNull(fieldKey).orEmpty()
            val keyLower = fieldKey.lowercase()
            val nameLower = displayName.lowercase()

            val isDedicatedPhoneField =
                userPhoneFields.any { it == keyLower || (nameLower.isNotEmpty() && nameLower.contains(it)) } ||
                    PHONE_FIELD_HINTS.any { hint -> nameLower.contains(hint) }

            if (isDedicatedPhoneField) {
                val label = displayName.ifBlank { "Поле $fieldKey" }
                val textVal = extractCustomFieldStrings(fieldValue)
                for (candidate in textVal) {
                    // First try direct single-value normalization (less strict for dedicated phone field)
                    val direct = PhoneNumberNormalizer.normalizeSingle(
                        rawInput = candidate,
                        region = region,
                        customCountryCode = customCode,
                        strictForFreeText = false
                    )
                    if (direct != null) {
                        collected.add(
                            PhoneMatch(
                                raw = candidate.trim(),
                                e164 = direct.first,
                                formattedDisplay = direct.second,
                                source = label
                            )
                        )
                    } else {
                        collected.addAll(
                            PhoneNumberNormalizer.extractFromText(
                                text = candidate,
                                region = region,
                                customCountryCode = customCode,
                                sourceLabel = label
                            )
                        )
                    }
                }
            }
        }

        // 2. Summary
        collected.addAll(
            PhoneNumberNormalizer.extractFromText(
                text = summary,
                region = region,
                customCountryCode = customCode,
                sourceLabel = "Тема заявки"
            )
        )

        // 3. Description
        collected.addAll(
            PhoneNumberNormalizer.extractFromText(
                text = description,
                region = region,
                customCountryCode = customCode,
                sourceLabel = "Описание"
            )
        )

        // 4. Other custom fields (in case phone is stored in an unnamed custom field)
        for ((fieldKey, fieldValue) in fields.entrySet()) {
            if (fieldValue == null || fieldValue.isJsonNull) continue
            if (fieldKey in SYSTEM_SKIP_FIELDS) continue
            val displayName = namesObj?.getStringOrNull(fieldKey).orEmpty()
            val label = displayName.ifBlank { "Доп. поле" }
            for (str in extractCustomFieldStrings(fieldValue)) {
                collected.addAll(
                    PhoneNumberNormalizer.extractFromText(
                        text = str,
                        region = region,
                        customCountryCode = customCode,
                        sourceLabel = label
                    )
                )
            }
        }

        // 5. Comments
        for (comment in comments) {
            collected.addAll(
                PhoneNumberNormalizer.extractFromText(
                    text = comment.body,
                    region = region,
                    customCountryCode = customCode,
                    sourceLabel = "Комментарий (${comment.author})"
                )
            )
        }

        return collected.distinctBy { it.e164 }
    }

    private fun extractCustomFieldStrings(element: JsonElement, depth: Int = 0): List<String> {
        if (depth > 3 || element.isJsonNull) return emptyList()
        return when {
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                if (prim.isString) listOf(prim.asString) else emptyList()
            }
            element.isJsonArray -> {
                element.asJsonArray.flatMap { extractCustomFieldStrings(it, depth + 1) }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                // If this is an ADF document inside a rich-text custom field
                if (obj.getStringOrNull("type") == "doc") {
                    listOf(extractReadableText(obj))
                } else {
                    val result = mutableListOf<String>()
                    for (key in listOf("value", "name", "displayName", "phone", "phoneNumber")) {
                        obj.getStringOrNull(key)?.let { result.add(it) }
                    }
                    result
                }
            }
            else -> emptyList()
        }
    }

    private fun parseComments(commentContainer: JsonObject?): List<TicketComment> {
        val commentsArr = commentContainer?.getArrayOrNull("comments") ?: return emptyList()
        return commentsArr.mapNotNull { elem ->
            if (!elem.isJsonObject) return@mapNotNull null
            val obj = elem.asJsonObject
            val id = obj.getStringOrNull("id") ?: ""
            val author = obj.getObjectOrNull("author")?.getStringOrNull("displayName")
                ?: obj.getObjectOrNull("author")?.getStringOrNull("name")
                ?: "Пользователь"
            val body = extractReadableText(obj.get("body")).trim()
            val created = obj.getStringOrNull("created").orEmpty()
            if (body.isEmpty()) null else TicketComment(id, author, body, created)
        }
    }

    /**
     * Converts either a plain/Wiki String (Jira Server/DC v2) or an Atlassian Document Format
     * JSON tree (Jira Cloud v3) into clean readable text.
     */
    fun extractReadableText(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) {
            return if (element.asJsonPrimitive.isString) element.asString else ""
        }
        if (!element.isJsonObject) return ""

        val sb = StringBuilder()
        appendAdfNode(element.asJsonObject, sb)
        return sb.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun appendAdfNode(node: JsonObject, sb: StringBuilder) {
        when (node.getStringOrNull("type")) {
            "text" -> {
                val text = node.getStringOrNull("text").orEmpty()
                sb.append(text)
            }
            "hardBreak" -> sb.append('\n')
            "mention" -> {
                val mentionText = node.getObjectOrNull("attrs")?.getStringOrNull("text") ?: "@user"
                sb.append(mentionText)
            }
            "inlineCard" -> {
                val url = node.getObjectOrNull("attrs")?.getStringOrNull("url").orEmpty()
                if (url.isNotEmpty()) sb.append(url)
            }
            "listItem" -> {
                sb.append("• ")
                node.getArrayOrNull("content")?.forEach { child ->
                    if (child.isJsonObject) appendAdfNode(child.asJsonObject, sb)
                }
                if (!sb.endsWith("\n")) sb.append('\n')
            }
            "paragraph", "heading", "blockquote", "codeBlock", "panel" -> {
                node.getArrayOrNull("content")?.forEach { child ->
                    if (child.isJsonObject) appendAdfNode(child.asJsonObject, sb)
                }
                sb.append('\n')
            }
            else -> {
                node.getArrayOrNull("content")?.forEach { child ->
                    if (child.isJsonObject) appendAdfNode(child.asJsonObject, sb)
                }
            }
        }
    }

    fun parseJiraTimestampMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso, JIRA_OFFSET_FORMATTER).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso, JIRA_OFFSET_NO_MS_FORMATTER).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive && !el.isJsonNull) el.asString else null
    }

    private fun JsonObject.getBooleanOrDefault(key: String, default: Boolean): Boolean {
        val el = get(key) ?: return default
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) el.asBoolean else default
    }

    private fun JsonObject.getObjectOrNull(key: String): JsonObject? {
        val el = get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    private fun JsonObject.getArrayOrNull(key: String): JsonArray? {
        val el = get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }
}
