package com.helpdesk.jiraagent.model

import java.util.UUID

enum class QueuedActionType {
    WORKFLOW_ACTION,
    ADD_COMMENT,
    CHAT_MESSAGE
}

data class OfflineQueuedAction(
    val id: String = UUID.randomUUID().toString(),
    val type: QueuedActionType,
    val ticketKey: String = "",
    val workflowActionName: String? = null,
    val commentText: String? = null,
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
            QueuedActionType.ADD_COMMENT -> "$ticketKey · Комментарий"
            QueuedActionType.CHAT_MESSAGE -> "Чат админов · Сообщение"
        }
}
