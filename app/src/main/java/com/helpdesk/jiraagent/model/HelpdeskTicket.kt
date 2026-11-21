package com.helpdesk.jiraagent.model

data class PhoneMatch(
    val raw: String,
    val e164: String,
    val formattedDisplay: String,
    val source: String
)

data class TicketComment(
    val id: String,
    val author: String,
    val body: String,
    val createdIso: String
)

data class HelpdeskTicket(
    val id: String,
    val key: String,
    val summary: String,
    val description: String,
    val issueType: String,
    val isSubtask: Boolean,
    val customerRequestType: String?,
    val hasCustomerPortalInfo: Boolean,
    val status: String,
    val statusCategoryKey: String,
    val priority: String,
    val projectKey: String,
    val projectName: String,
    val reporterName: String,
    val reporterLoginOrEmail: String,
    val assigneeName: String?,
    val createdIso: String,
    val createdEpochMillis: Long,
    val updatedIso: String,
    val labels: List<String>,
    val phoneNumbers: List<PhoneMatch>,
    val comments: List<TicketComment>,
    val webUrl: String,
    val isClientTicket: Boolean = true,
    val filterReason: String? = null
)

data class SyncStatus(
    val isServiceRunning: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncEpochMillis: Long? = null,
    val lastError: String? = null,
    val connectedUser: String? = null
)
