package com.helpdesk.jiraagent.model

/**
 * Persisted record of a postponed ("Отложить на") Jira ticket.
 * When postponed, the original ticket is closed immediately; at [scheduledAtMillis],
 * Help-CSL automatically recreates the ticket in Jira, places it in "Новые",
 * and triggers a heads-up notification with sound.
 */
data class PostponedTicket(
    val id: String,
    val originalKey: String,
    val projectKey: String,
    val projectId: String? = null,
    val issueTypeId: String? = null,
    val issueTypeName: String = "Task",
    val priorityId: String? = null,
    val priorityName: String = "",
    val summary: String,
    val description: String,
    val reporterDisplayName: String = "",
    val reporterUsername: String? = null,
    val reporterAccountId: String? = null,
    val reporterEmail: String? = null,
    val serviceDeskId: String? = null,
    val requestTypeId: String? = null,
    val customerRequestTypeName: String? = null,
    val customFieldsJson: Map<String, String>? = emptyMap(),
    val labels: List<String>? = emptyList(),
    val phoneNumbers: List<PhoneMatch>? = emptyList(),
    val telegramHandles: List<String>? = emptyList(),
    val scheduledAtMillis: Long,
    val scheduledDisplay: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastError: String? = null
)
