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
    private val TICKET_KEY_REGEX = Regex("\\b([A-Z][A-Z0-9_]{1,9}-\\d{1,7})\\b")

    data class DecodedChatBody(
        val text: String,
        val replyToAuthor: String?,
        val replyToPreview: String?,
        val mentionedTicketKeys: List<String>
    )

    fun encodeMessage(
        text: String,
        replyToAuthor: String? = null,
        replyToPreview: String? = null
    ): String {
        val cleanText = text.trim()
        if (replyToAuthor.isNullOrBlank() || replyToPreview.isNullOrBlank()) {
            return cleanText
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
        return ">> [$safeAuthor]: $safePreview\n$cleanText"
    }

    fun decodeMessage(rawBody: String, chatRoomIssueKey: String = ""): DecodedChatBody {
        val normalized = rawBody.trim()
        val match = REPLY_HEADER_REGEX.matchEntire(normalized)
        val replyAuthor: String?
        val replyPreview: String?
        val mainText: String

        if (match != null) {
            replyAuthor = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            replyPreview = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            mainText = match.groupValues[3].trim()
        } else {
            replyAuthor = null
            replyPreview = null
            mainText = normalized
        }

        val roomUpper = chatRoomIssueKey.trim().uppercase(Locale.ROOT)
        val tickets = TICKET_KEY_REGEX.findAll(mainText)
            .map { it.groupValues[1].uppercase(Locale.ROOT) }
            .filter { it != roomUpper }
            .distinct()
            .toList()

        return DecodedChatBody(
            text = mainText,
            replyToAuthor = replyAuthor,
            replyToPreview = replyPreview,
            mentionedTicketKeys = tickets
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
        if (summary.contains(ADMIN_CHAT_MARKER, ignoreCase = true)) {
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
