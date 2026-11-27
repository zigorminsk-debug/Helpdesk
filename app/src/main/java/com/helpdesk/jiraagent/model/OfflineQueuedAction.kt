package com.helpdesk.jiraagent.model

import java.util.UUID

enum class QueuedActionType {
    WORKFLOW_ACTION,
    ADD_COMMENT,
    ADD_WORKLOG,
    CHAT_MESSAGE
}

data class OfflineQueuedAction(
    val id: String = UUID.randomUUID().toString(),
    val type: QueuedActionType,
    val ticketKey: String = "",
    val workflowActionName: String? = null,
    val commentText: String? = null,
    val internalComment: Boolean = false,
    val worklogSeconds: Int = 0,
    val explicitTransitionId: String? = null,
    val chatReplyAuthor: String? = null,
    val chatReplyPreview: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null
) {
    val summaryLabel: String
        get() = when (type) {
            QueuedActionType.WORKFLOW_ACTION -> {
                val actTitle = when (workflowActionName) {
                    "TAKE_IN_WORK" -> "В работу"
                    "COMPLETE" -> "Завершить"
                    "REJECT" -> "Отклонить"
                    else -> workflowActionName ?: "Действие"
                }
                "$ticketKey · $actTitle"
            }
            QueuedActionType.ADD_COMMENT -> "$ticketKey · ${if (internalComment) "Внутр. заметка" else "Комментарий"}"
            QueuedActionType.ADD_WORKLOG -> "$ticketKey · Worklog (${(worklogSeconds / 60).coerceAtLeast(1)} мин)"
            QueuedActionType.CHAT_MESSAGE -> "Чат админов · Сообщение"
        }
}
