package com.helpdesk.jiraagent.model

import java.util.Locale

data class AdminChatMessage(
    val id: String,
    val authorDisplayName: String,
    val authorIdentifier: String,
    val isOwnMessage: Boolean,
    val text: String,
    val replyToAuthor: String? = null,
    val replyToPreview: String? = null,
    val mentionedTicketKeys: List<String> = emptyList(),
    val mentionedHandles: List<String> = emptyList(),
    val attachmentName: String? = null,
    val attachmentUrl: String? = null,
    val createdIso: String = "",
    val createdEpochMillis: Long = 0L
)

data class AdminChatRoomSnapshot(
    val roomIssueKey: String,
    val currentUserDisplayName: String,
    val currentUserIdentifiers: Set<String>,
    val messages: List<AdminChatMessage>,
    val pinnedNote: String = ""
)

object AdminChatCodec {

    const val ADMIN_CHAT_MARKER = "HELPCSL_ADMIN_CHAT_ROOM"
    const val ADMIN_CHAT_LABEL = "helpcsl_admin_chat"
    const val ADMIN_CHAT_ROOM_SUMMARY =
        "[HELPCSL_ADMIN_CHAT_ROOM] Внутренний чат админов техподдержки"
    const val ADMIN_CHAT_ROOM_DESCRIPTION =
        "Служебная комната внутреннего чата инженеров техподдержки Help-CSL. Не удаляйте эту задачу."
    private const val PINNED_NOTE_DELIMITER = "--- PINNED_SHIFT_NOTE ---"

    private val REPLY_HEADER_REGEX = Regex(
        "^>>\\s*\\[([^\\]\\r\\n]{1,60})]:\\s*([^\\r\\n]{1,180})\\r?\\n([\\s\\S]*)$"
    )
    private val ATTACHMENT_TAG_REGEX = Regex(
        "\\[ATTACHMENT:\\s*([^|\\]\\r\\n]{1,140})\\s*\\|\\s*([^\\]\\r\\n]{4,600})\\s*]",
        RegexOption.IGNORE_CASE
    )
    private val TICKET_KEY_REGEX = Regex("\\b([A-Z][A-Z0-9_]{1,9}-\\d{1,7})\\b")
    private val MENTION_REGEX = Regex("(?<!\\S)@([А-Яа-яЁёA-Za-z0-9._-]{2,36})")

    data class DecodedChatBody(
        val text: String,
        val replyToAuthor: String?,
        val replyToPreview: String?,
        val mentionedTicketKeys: List<String>,
        val mentionedHandles: List<String> = emptyList(),
        val attachmentName: String? = null,
        val attachmentUrl: String? = null
    )

    fun encodeMessage(
        text: String,
        replyToAuthor: String? = null,
        replyToPreview: String? = null,
        attachmentName: String? = null,
        attachmentUrl: String? = null
    ): String {
        val cleanText = text.trim()
        val attSuffix = if (!attachmentName.isNullOrBlank() && !attachmentUrl.isNullOrBlank()) {
            val safeName = attachmentName.replace('|', '_').replace(']', ')').trim()
            val safeUrl = attachmentUrl.trim()
            "[ATTACHMENT: $safeName | $safeUrl]"
        } else {
            null
        }
        val bodyWithAttachment = when {
            cleanText.isNotEmpty() && attSuffix != null -> "$cleanText\n$attSuffix"
            attSuffix != null -> "📎 Вложение: ${attachmentName?.trim()}\n$attSuffix"
            else -> cleanText
        }

        if (replyToAuthor.isNullOrBlank() || replyToPreview.isNullOrBlank()) {
            return bodyWithAttachment
        }
        val safeAuthor = replyToAuthor
            .replace('[', '(')
            .replace(']', ')')
            .replace('\n', ' ')
            .trim()
            .take(50)
        val safePreview = replyToPreview
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > 90) it.take(90).trimEnd() + "…" else it }
        return ">> [$safeAuthor]: $safePreview\n$bodyWithAttachment"
    }

    fun decodeMessage(rawBody: String, chatRoomIssueKey: String = ""): DecodedChatBody {
        val normalized = rawBody.trim()
        val match = REPLY_HEADER_REGEX.matchEntire(normalized)
        val replyAuthor: String?
        val replyPreview: String?
        val rawMainText: String

        if (match != null) {
            replyAuthor = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            replyPreview = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            rawMainText = match.groupValues[3].trim()
        } else {
            replyAuthor = null
            replyPreview = null
            rawMainText = normalized
        }

        val attMatch = ATTACHMENT_TAG_REGEX.find(rawMainText)
        val attName = attMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val attUrl = attMatch?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        val mainText = if (attMatch != null) {
            rawMainText.removeRange(attMatch.range).trim()
        } else {
            rawMainText
        }

        val roomUpper = chatRoomIssueKey.trim().uppercase(Locale.ROOT)
        val tickets = TICKET_KEY_REGEX.findAll(mainText)
            .map { it.groupValues[1].uppercase(Locale.ROOT) }
            .filter { it != roomUpper }
            .distinct()
            .toList()

        val mentions = MENTION_REGEX.findAll(mainText)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        return DecodedChatBody(
            text = mainText,
            replyToAuthor = replyAuthor,
            replyToPreview = replyPreview,
            mentionedTicketKeys = tickets,
            mentionedHandles = mentions,
            attachmentName = attName,
            attachmentUrl = attUrl
        )
    }

    fun isUserMentioned(
        mentionedHandles: List<String>,
        displayName: String?,
        usernameOrEmail: String?
    ): Boolean {
        if (mentionedHandles.isEmpty()) return false
        val nameTokens = buildSet {
            add("все")
            add("all")
            displayName?.trim()?.lowercase(Locale.ROOT)?.split(Regex("\\s+"))
                ?.filter { it.length >= 2 }
                ?.forEach {
                    add(it)
                    add(it.replace('ё', 'е'))
                }
            displayName?.trim()?.replace(" ", "_")?.lowercase(Locale.ROOT)
                ?.takeIf { it.length >= 2 }
                ?.let { add(it) }
            usernameOrEmail?.trim()?.lowercase(Locale.ROOT)?.let { login ->
                if (login.isNotEmpty()) {
                    add(login)
                    add(login.substringBefore('@'))
                }
            }
        }
        return mentionedHandles.any { handle ->
            val low = handle.lowercase(Locale.ROOT).replace('ё', 'е')
            low in nameTokens
        }
    }

    fun isUserMentioned(
        message: AdminChatMessage,
        currentDisplayName: String?,
        currentUsernameOrEmail: String?
    ): Boolean {
        if (message.isOwnMessage) return false
        return isUserMentioned(
            mentionedHandles = message.mentionedHandles,
            displayName = currentDisplayName,
            usernameOrEmail = currentUsernameOrEmail
        )
    }

    fun isAdminChatStorageIssue(
        issueKey: String,
        summary: String,
        labels: List<String>,
        configuredChatKey: String = ""
    ): Boolean {
        if (configuredChatKey.isNotBlank() && issueKey.equals(configuredChatKey.trim(), ignoreCase = true)) {
            return true
        }
        if (summary.contains(ADMIN_CHAT_MARKER, ignoreCase = true) ||
            RepairEquipmentCodec.isRepairRegistryStorageIssue(summary, labels)
        ) {
            return true
        }
        return labels.any { it.equals(ADMIN_CHAT_LABEL, ignoreCase = true) }
    }

    fun encodeRoomDescriptionWithPinnedNote(pinnedNote: String): String {
        val clean = pinnedNote.trim()
        return if (clean.isEmpty()) {
            ADMIN_CHAT_ROOM_DESCRIPTION
        } else {
            "$ADMIN_CHAT_ROOM_DESCRIPTION\n$PINNED_NOTE_DELIMITER\n$clean"
        }
    }

    fun extractPinnedNoteFromDescription(description: String): String {
        val idx = description.indexOf(PINNED_NOTE_DELIMITER)
        if (idx < 0) return ""
        return description.substring(idx + PINNED_NOTE_DELIMITER.length).trim()
    }
}
