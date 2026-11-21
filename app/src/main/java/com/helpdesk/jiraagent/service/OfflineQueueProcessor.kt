package com.helpdesk.jiraagent.service

import android.content.Context
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.QueuedActionType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OfflineQueueProcessor {

    private val flushMutex = Mutex()

    /**
     * Flushes any offline-queued actions against Jira when network connectivity is available.
     * Returns the number of actions successfully synced.
     */
    suspend fun flushPending(
        context: Context,
        apiClient: JiraApiClient
    ): Int = flushMutex.withLock {
        val repo = SettingsRepository.getInstance(context)
        val settings = repo.appSettings.value
        if (!settings.isConfigured) return 0
        val filter = repo.filterSettings.value

        val snapshot = repo.offlineQueuedActions.value
        if (snapshot.isEmpty()) return 0

        var completedCount = 0
        for (item in snapshot) {
            val result = executeQueuedItem(repo, apiClient, item)
            if (result.isSuccess) {
                repo.removeOfflineAction(item.id)
                completedCount++
            } else {
                val err = result.exceptionOrNull()
                val msg = err?.message ?: "Ошибка синхронизации"
                repo.updateOfflineActionError(item.id, msg)
                val isNetErr = (err as? JiraApiException)?.isNetworkError == true
                if (isNetErr) {
                    // Still offline — pause remaining items until connectivity returns
                    break
                }
            }
        }
        completedCount
    }

    private suspend fun executeQueuedItem(
        repo: SettingsRepository,
        apiClient: JiraApiClient,
        item: OfflineQueuedAction
    ): Result<Unit> {
        val settings = repo.appSettings.value
        val filter = repo.filterSettings.value

        return when (item.type) {
            QueuedActionType.WORKFLOW_ACTION -> {
                val action = runCatching {
                    TicketWorkflowAction.valueOf(item.workflowActionName.orEmpty())
                }.getOrDefault(TicketWorkflowAction.TAKE_IN_WORK)

                apiClient.performWorkflowAction(
                    issueKey = item.ticketKey,
                    action = action,
                    commentText = item.commentText,
                    explicitTransitionId = item.explicitTransitionId,
                    settings = settings,
                    filterSettings = filter
                ).map { outcome ->
                    outcome.updatedTicket?.let { repo.upsertSingleTicket(it) }
                }
            }

            QueuedActionType.ADD_COMMENT -> {
                val text = item.commentText?.trim().orEmpty()
                if (text.isEmpty()) {
                    Result.success(Unit)
                } else {
                    apiClient.postIssueComment(
                        issueKey = item.ticketKey,
                        commentText = text,
                        settings = settings,
                        filterSettings = filter
                    ).map { refreshed ->
                        refreshed?.let { repo.upsertSingleTicket(it) }
                    }
                }
            }

            QueuedActionType.CHAT_MESSAGE -> {
                val text = item.commentText?.trim().orEmpty()
                if (text.isEmpty()) {
                    Result.success(Unit)
                } else {
                    val roomRes = apiClient.resolveOrCreateAdminChatRoom(
                        settings = settings,
                        filterSettings = filter,
                        cachedRoomKey = repo.resolvedChatRoomKey.value
                    )
                    val roomKey = roomRes.getOrElse { return Result.failure(it) }
                    repo.setResolvedChatRoomKey(roomKey)
                    apiClient.sendAdminChatMessage(
                        roomIssueKey = roomKey,
                        rawText = text,
                        replyToAuthor = item.chatReplyAuthor,
                        replyToPreview = item.chatReplyPreview,
                        settings = settings
                    ).map { sentMsg ->
                        repo.appendSentChatMessage(sentMsg)
                    }
                }
            }
        }
    }
}
