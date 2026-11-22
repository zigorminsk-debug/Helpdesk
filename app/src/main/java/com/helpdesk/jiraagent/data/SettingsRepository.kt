package com.helpdesk.jiraagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TtsReadoutMode
import com.helpdesk.jiraagent.widget.HelpCslAppWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class PendingCallSession(
    val ticketKey: String,
    val clientName: String,
    val phoneNumber: String,
    val startedAtMillis: Long
)

data class InAppBannerAlert(
    val ticket: HelpdeskTicket,
    val titleSuffix: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val cachePrefs: SharedPreferences =
        appContext.getSharedPreferences("helpdesk_ticket_cache", Context.MODE_PRIVATE)
    private val prefs: SharedPreferences = createSecurePrefsWithFallback(appContext).also {
        migrateLegacyFilterDefaults(it, cachePrefs)
    }

    private val _appSettings = MutableStateFlow(loadAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    private val _filterSettings = MutableStateFlow(loadFilterSettings())
    val filterSettings: StateFlow<FilterSettings> = _filterSettings.asStateFlow()

    private val _tickets = MutableStateFlow(
        applyRecreatedOverrides(
            loadCachedTickets().map { ticket ->
                val eval = TicketFilterEngine.evaluate(
                    ticket = ticket,
                    filter = _filterSettings.value,
                    currentAgentLoginOrEmail = _appSettings.value.usernameOrEmail
                )
                ticket.copy(isClientTicket = eval.isClientTicket, filterReason = eval.reason)
            }
        )
    )
    val tickets: StateFlow<List<HelpdeskTicket>> = _tickets.asStateFlow()

    private val _postponedTickets = MutableStateFlow(loadPostponedTickets())
    val postponedTickets: StateFlow<List<PostponedTicket>> = _postponedTickets.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _latestInAppBanner = MutableStateFlow<InAppBannerAlert?>(null)
    val latestInAppBanner: StateFlow<InAppBannerAlert?> = _latestInAppBanner.asStateFlow()

    private val _resolvedChatRoomKey = MutableStateFlow(
        _appSettings.value.adminChatIssueKey.trim().ifBlank {
            cachePrefs.getString(KEY_RESOLVED_CHAT_ROOM_KEY, "").orEmpty()
        }
    )
    val resolvedChatRoomKey: StateFlow<String> = _resolvedChatRoomKey.asStateFlow()

    private val _chatMessages = MutableStateFlow(loadCachedChatMessages())
    val chatMessages: StateFlow<List<AdminChatMessage>> = _chatMessages.asStateFlow()

    private val _unreadChatCount = MutableStateFlow(cachePrefs.getInt(KEY_UNREAD_CHAT_COUNT, 0))
    val unreadChatCount: StateFlow<Int> = _unreadChatCount.asStateFlow()

    private val _pinnedShiftNote = MutableStateFlow(cachePrefs.getString(KEY_PINNED_SHIFT_NOTE, "").orEmpty())
    val pinnedShiftNote: StateFlow<String> = _pinnedShiftNote.asStateFlow()

    private val _offlineQueuedActions = MutableStateFlow(loadOfflineQueuedActions())
    val offlineQueuedActions: StateFlow<List<OfflineQueuedAction>> = _offlineQueuedActions.asStateFlow()

    private val _clientDossierNotes = MutableStateFlow(loadClientDossierNotes())
    val clientDossierNotes: StateFlow<Map<String, String>> = _clientDossierNotes.asStateFlow()

    private val _pendingCallSession = MutableStateFlow<PendingCallSession?>(null)
    val pendingCallSession: StateFlow<PendingCallSession?> = _pendingCallSession.asStateFlow()

    private val _repairItems = MutableStateFlow(loadRepairItems())
    val repairItems: StateFlow<List<RepairEquipmentItem>> = _repairItems.asStateFlow()

    private val _resolvedRepairRoomKey = MutableStateFlow(
        cachePrefs.getString(KEY_RESOLVED_REPAIR_ROOM_KEY, "").orEmpty()
    )
    val resolvedRepairRoomKey: StateFlow<String> = _resolvedRepairRoomKey.asStateFlow()

    @Synchronized
    fun setResolvedRepairRoomKey(issueKey: String) {
        val clean = issueKey.trim().uppercase(Locale.ROOT)
        _resolvedRepairRoomKey.value = clean
        cachePrefs.edit().putString(KEY_RESOLVED_REPAIR_ROOM_KEY, clean).apply()
    }

    @Synchronized
    fun upsertRepairItem(item: RepairEquipmentItem): RepairEquipmentItem {
        val current = _repairItems.value
        val assignedNumber = item.intakeNumber.trim().ifEmpty {
            RepairEquipmentCodec.generateNextIntakeNumber(current)
        }
        val now = System.currentTimeMillis()
        val stamped = item.copy(
            intakeNumber = assignedNumber,
            updatedAtEpochMs = now.coerceAtLeast(item.updatedAtEpochMs),
            issuedAtEpochMs = if (item.status == RepairStatus.ISSUED) {
                item.issuedAtEpochMs ?: now
            } else {
                null
            },
            pendingRemoteSync = true
        )
        val merged = (current.filterNot { it.id == stamped.id } + stamped)
            .sortedByDescending { it.updatedAtEpochMs }
        persistRepairItems(merged)
        return stamped
    }

    @Synchronized
    fun markRepairItemSynced(itemId: String, syncedUpdatedAtEpochMs: Long) {
        val updated = _repairItems.value.map { existing ->
            if (existing.id == itemId && existing.updatedAtEpochMs <= syncedUpdatedAtEpochMs) {
                existing.copy(pendingRemoteSync = false)
            } else {
                existing
            }
        }
        persistRepairItems(updated)
    }

    @Synchronized
    fun mergeRemoteRepairEvents(
        roomIssueKey: String,
        remoteEvents: List<RepairEquipmentItem>
    ): List<RepairEquipmentItem> {
        if (roomIssueKey.isNotBlank()) {
            setResolvedRepairRoomKey(roomIssueKey)
        }
        val merged = RepairEquipmentCodec.mergeItems(_repairItems.value, remoteEvents)
        persistRepairItems(merged)
        return merged.filter { it.pendingRemoteSync }
    }

    private fun persistRepairItems(items: List<RepairEquipmentItem>) {
        // Keep up to 400 recent items including tombstones so cross-device deletes converge cleanly
        val bounded = items.take(400)
        _repairItems.value = bounded
        prefs.edit().putString(KEY_REPAIR_ITEMS_JSON, gson.toJson(bounded)).apply()
    }

    private fun loadRepairItems(): List<RepairEquipmentItem> {
        val raw = prefs.getString(KEY_REPAIR_ITEMS_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<RepairEquipmentItem>>() {}.type
            val parsed = gson.fromJson<List<RepairEquipmentItem>>(raw, type).orEmpty()
            parsed.filter { it.id.isNotBlank() }
                .map { it.copy(status = it.status ?: RepairStatus.RECEIVED) }
                .sortedByDescending { it.updatedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    @Volatile
    var isChatTabVisible: Boolean = false

    fun recordOutgoingTicketCall(ticketKey: String, clientName: String, phoneNumber: String) {
        if (!_appSettings.value.postCallWorklogPromptEnabled) return
        _pendingCallSession.value = PendingCallSession(
            ticketKey = ticketKey,
            clientName = clientName,
            phoneNumber = phoneNumber,
            startedAtMillis = System.currentTimeMillis()
        )
    }

    fun clearPendingCallSession() {
        _pendingCallSession.value = null
    }

    @Synchronized
    fun saveClientDossierNote(clientName: String, noteText: String) {
        val normKey = normalizeClientKey(clientName)
        if (normKey.isEmpty()) return
        val current = _clientDossierNotes.value.toMutableMap()
        val trimmed = noteText.trim()
        if (trimmed.isEmpty()) {
            current.remove(normKey)
        } else {
            current[normKey] = trimmed
        }
        prefs.edit().putString(KEY_CLIENT_DOSSIER_NOTES_JSON, gson.toJson(current)).apply()
        _clientDossierNotes.value = current
    }

    fun getClientDossierNote(clientName: String): String {
        val normKey = normalizeClientKey(clientName)
        return _clientDossierNotes.value[normKey].orEmpty()
    }

    private fun normalizeClientKey(clientName: String): String =
        clientName.trim().lowercase(Locale.ROOT)

    fun emitInAppBanner(ticket: HelpdeskTicket, titleSuffix: String) {
        _latestInAppBanner.value = InAppBannerAlert(
            ticket = ticket,
            titleSuffix = titleSuffix,
            timestampMillis = System.currentTimeMillis()
        )
    }

    fun dismissInAppBanner() {
        _latestInAppBanner.value = null
    }

    fun saveAppSettings(newSettings: AppSettings) {
        val serverChanged = newSettings.serverUrl.trim() != _appSettings.value.serverUrl.trim()
        val manualRoomChanged = newSettings.adminChatIssueKey.trim() != _appSettings.value.adminChatIssueKey.trim()
        prefs.edit()
            .putString(KEY_SERVER_URL, newSettings.serverUrl.trim())
            .putString(KEY_SERVER_TYPE, newSettings.serverType.name)
            .putString(KEY_AUTH_TYPE, newSettings.authType.name)
            .putString(KEY_USERNAME, newSettings.usernameOrEmail.trim())
            .putString(KEY_PASSWORD, newSettings.passwordOrToken)
            .putBoolean(KEY_TRUST_SSL, newSettings.trustSelfSignedSsl)
            .putBoolean(KEY_POLLING_ENABLED, newSettings.pollingEnabled)
            .putInt(KEY_POLL_INTERVAL, newSettings.pollIntervalSeconds)
            .putBoolean(KEY_START_ON_BOOT, newSettings.startOnBoot)
            .putBoolean(KEY_AUTO_CHECK_UPDATES, newSettings.autoCheckUpdates)
            .putBoolean(KEY_WORK_SCHEDULE_ENABLED, newSettings.workScheduleEnabled)
            .putString(KEY_WORK_START_TIME, newSettings.workStartTime.trim().ifEmpty { "09:00" })
            .putString(KEY_WORK_END_TIME, newSettings.workEndTime.trim().ifEmpty { "17:30" })
            .putString(KEY_EXTRA_WORKING_DATES, newSettings.extraWorkingDates.trim())
            .putString(KEY_EXTRA_HOLIDAY_DATES, newSettings.extraHolidayDates.trim())
            .putString(KEY_ALERT_SOUND_PRESET, newSettings.alertSoundPreset.name)
            .putString(KEY_CUSTOM_ALERT_SOUND_URI, newSettings.customAlertSoundUri.trim())
            .putString(KEY_CUSTOM_ALERT_SOUND_TITLE, newSettings.customAlertSoundTitle.trim())
            .putString(KEY_ADMIN_CHAT_ISSUE_KEY, newSettings.adminChatIssueKey.trim().uppercase())
            .putBoolean(KEY_ADMIN_CHAT_NOTIFICATIONS, newSettings.adminChatNotificationsEnabled)
            .putBoolean(KEY_STALE_REMINDER_ENABLED, newSettings.staleTicketReminderEnabled)
            .putInt(KEY_STALE_REMINDER_MINUTES, newSettings.staleTicketReminderMinutes.coerceIn(5, 240))
            .putString(KEY_QUICK_REPLY_TEMPLATES_JSON, gson.toJson(newSettings.quickReplyTemplates))
            .putString(KEY_TTS_READOUT_MODE, newSettings.ttsReadoutMode.name)
            .putBoolean(KEY_STORM_PROTECTION_ENABLED, newSettings.stormProtectionEnabled)
            .putBoolean(KEY_CALLER_ID_ENABLED, newSettings.callerIdEnabled)
            .putBoolean(KEY_POST_CALL_WORKLOG_ENABLED, newSettings.postCallWorklogPromptEnabled)
            .putBoolean(KEY_BIOMETRIC_LOCK_ENABLED, newSettings.biometricLockEnabled)
            .putString(KEY_PHONE_REGION, newSettings.phoneRegion.name)
            .putString(KEY_CUSTOM_COUNTRY_CODE, newSettings.customCountryCode.trim())
            .putString(KEY_CUSTOM_PHONE_FIELDS, newSettings.customPhoneFieldNames)
            .apply()

        if (serverChanged) {
            resetSeenBaseline()
            resetAdminChatCache()
        } else if (manualRoomChanged) {
            resetAdminChatCache()
            val manual = newSettings.adminChatIssueKey.trim().uppercase()
            if (manual.isNotBlank()) {
                setResolvedChatRoomKey(manual)
            }
        }
        _appSettings.value = newSettings.copy(adminChatIssueKey = newSettings.adminChatIssueKey.trim().uppercase())
        runCatching { HelpCslAppWidgetProvider.requestUpdateAll(appContext) }
    }

    fun saveFilterSettings(newFilter: FilterSettings) {
        prefs.edit()
            .putInt(KEY_FILTER_SCHEMA_VERSION, CURRENT_FILTER_SCHEMA_VERSION)
            .putBoolean(KEY_CLIENT_ONLY, newFilter.clientOnlyFilterEnabled)
            .putBoolean(KEY_EXCLUDE_SUBTASKS, newFilter.excludeSubtasks)
            .putBoolean(KEY_ONLY_PORTAL, newFilter.onlyCustomerPortalRequests)
            .putBoolean(KEY_EXCLUDE_DONE, newFilter.excludeResolvedClosed)
            .putBoolean(KEY_IGNORE_OWN, newFilter.ignoreOwnTickets)
            .putString(KEY_PROJECT_KEYS, newFilter.projectKeys)
            .putString(KEY_ALLOWED_TYPES, newFilter.allowedIssueTypes)
            .putString(KEY_EXCLUDED_TYPES, newFilter.excludedIssueTypes)
            .putString(KEY_EXCLUDED_REPORTERS, newFilter.excludedReporters)
            .putString(KEY_EXCLUDED_KEYWORDS, newFilter.excludedLabelsAndKeywords)
            .putString(KEY_CUSTOM_JQL, newFilter.customJqlCondition)
            .apply()

        _filterSettings.value = newFilter

        // Re-evaluate cached tickets immediately so UI reflects new filter rules without waiting
        val currentAgent = _appSettings.value.usernameOrEmail
        val updated = applyRecreatedOverrides(
            _tickets.value.map { ticket ->
                val eval = TicketFilterEngine.evaluate(ticket, newFilter, currentAgent)
                ticket.copy(isClientTicket = eval.isClientTicket, filterReason = eval.reason)
            }
        )
        updateTicketsCache(updated)
    }

    /**
     * Records newly polled tickets and returns ONLY brand-new client tickets that should trigger
     * a Heads-Up Notification Banner on the phone.
     *
     * On the very first sync for a server (`!isBaselineInitialized`), existing historical tickets
     * are recorded as baseline so the user is not flooded with banners for old tickets; every
     * client ticket arriving after baseline triggers a banner.
     */
    @Synchronized
    fun processPolledTicketsAndFindNewClientOnes(
        polled: List<HelpdeskTicket>,
        isNotificationWindowOpen: Boolean = BelarusWorkCalendar.isWorkingTimeNow(_appSettings.value)
    ): List<HelpdeskTicket> {
        val chatRoomKey = _resolvedChatRoomKey.value
        val filteredWithoutChatRoom = polled.filterNot { t ->
            AdminChatCodec.isAdminChatStorageIssue(
                issueKey = t.key,
                summary = t.summary,
                labels = t.labels,
                configuredChatKey = chatRoomKey
            )
        }
        val adjustedPolled = applyRecreatedOverrides(filteredWithoutChatRoom)
        updateTicketsCache(adjustedPolled)

        val baselineDone = cachePrefs.getBoolean(KEY_BASELINE_INITIALIZED, false)
        val silentReseed = cachePrefs.getBoolean(KEY_SILENT_RESEED_ONCE, false)
        val seenKeys = cachePrefs.getStringSet(KEY_SEEN_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val deferredKeys = cachePrefs.getStringSet(KEY_DEFERRED_ALERT_KEYS, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        if (!baselineDone || silentReseed) {
            adjustedPolled.forEach { seenKeys.add(it.key) }
            cachePrefs.edit()
                .putBoolean(KEY_SILENT_RESEED_ONCE, false)
                .remove(KEY_DEFERRED_ALERT_KEYS)
                .apply()
            saveSeenKeys(seenKeys, baselineInitialized = true)
            return emptyList()
        }

        val sortedChronologically = adjustedPolled.sortedBy { it.createdEpochMillis }

        // Outside RB working days / hours (09:00–17:30): silence notifications & sound,
        // and defer brand-new client tickets until the next RB working window opens.
        if (!isNotificationWindowOpen) {
            for (ticket in sortedChronologically) {
                val isUnseen = seenKeys.add(ticket.key)
                if (isUnseen && ticket.isClientTicket && ticket.stage == TicketStage.NEW) {
                    deferredKeys.add(ticket.key)
                }
            }
            cachePrefs.edit().putStringSet(KEY_DEFERRED_ALERT_KEYS, deferredKeys).apply()
            saveSeenKeys(seenKeys, baselineInitialized = true)
            return emptyList()
        }

        val newClientTickets = mutableListOf<HelpdeskTicket>()
        for (ticket in sortedChronologically) {
            val wasDeferred = deferredKeys.remove(ticket.key)
            val isUnseen = seenKeys.add(ticket.key)
            if (ticket.isClientTicket && ticket.stage != TicketStage.CLOSED) {
                if (isUnseen || (wasDeferred && ticket.stage == TicketStage.NEW)) {
                    newClientTickets.add(ticket)
                }
            }
        }

        cachePrefs.edit().remove(KEY_DEFERRED_ALERT_KEYS).apply()
        saveSeenKeys(seenKeys, baselineInitialized = true)
        return newClientTickets
    }

    @Synchronized
    fun upsertSingleTicket(ticket: HelpdeskTicket) {
        val current = _tickets.value.toMutableList()
        val index = current.indexOfFirst { it.key.equals(ticket.key, ignoreCase = true) }
        if (index >= 0) {
            current[index] = ticket
        } else {
            current.add(0, ticket)
        }
        updateTicketsCache(current)
    }

    @Synchronized
    fun addPostponedTicket(item: PostponedTicket) {
        val updated = (_postponedTickets.value.filterNot { it.id == item.id } + item)
            .sortedBy { it.scheduledAtMillis }
        savePostponedTickets(updated)
    }

    @Synchronized
    fun removePostponedTicket(id: String) {
        val updated = _postponedTickets.value.filterNot { it.id == id }
        savePostponedTickets(updated)
    }

    @Synchronized
    fun updatePostponedTicketError(id: String, error: String) {
        val updated = _postponedTickets.value.map {
            if (it.id == id) it.copy(lastError = error) else it
        }
        savePostponedTickets(updated)
    }

    /**
     * Marks a recreated postponed ticket as seen & guaranteed-client, and places it at the top of "Новые".
     */
    @Synchronized
    fun registerRecreatedPostponedTicket(ticket: HelpdeskTicket) {
        val recreatedKeys = cachePrefs.getStringSet(KEY_RECREATED_CLIENT_KEYS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        recreatedKeys.add(ticket.key.uppercase())
        val boundedRecreated = if (recreatedKeys.size > 300) {
            recreatedKeys.toList().takeLast(300).toSet()
        } else {
            recreatedKeys
        }

        val seenKeys = cachePrefs.getStringSet(KEY_SEEN_KEYS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        seenKeys.add(ticket.key)

        cachePrefs.edit()
            .putStringSet(KEY_RECREATED_CLIENT_KEYS, boundedRecreated)
            .apply()
        saveSeenKeys(seenKeys, baselineInitialized = cachePrefs.getBoolean(KEY_BASELINE_INITIALIZED, true))

        val clientTicket = ticket.copy(isClientTicket = true, filterReason = null)
        upsertSingleTicket(clientTicket)
    }

    private fun applyRecreatedOverrides(list: List<HelpdeskTicket>): List<HelpdeskTicket> {
        val recreatedKeys = cachePrefs.getStringSet(KEY_RECREATED_CLIENT_KEYS, emptySet()).orEmpty()
        if (recreatedKeys.isEmpty()) return list
        return list.map { t ->
            if (t.key.uppercase() in recreatedKeys) {
                t.copy(isClientTicket = true, filterReason = null)
            } else {
                t
            }
        }
    }

    private fun savePostponedTickets(list: List<PostponedTicket>) {
        _postponedTickets.value = list
        runCatching {
            cachePrefs.edit().putString(KEY_POSTPONED_TICKETS_JSON, gson.toJson(list)).apply()
        }
    }

    private fun loadPostponedTickets(): List<PostponedTicket> {
        val json = cachePrefs.getString(KEY_POSTPONED_TICKETS_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PostponedTicket>>() {}.type
            (gson.fromJson<List<PostponedTicket>>(json, type) ?: emptyList())
                .sortedBy { it.scheduledAtMillis }
        }.getOrDefault(emptyList())
    }

    fun resetSeenBaseline() {
        cachePrefs.edit()
            .remove(KEY_SEEN_KEYS)
            .putBoolean(KEY_BASELINE_INITIALIZED, false)
            .apply()
    }

    fun updateSyncStatus(transform: (SyncStatus) -> SyncStatus) {
        _syncStatus.update(transform)
    }

    private fun updateTicketsCache(list: List<HelpdeskTicket>) {
        _tickets.value = list
        runCatching {
            val trimmed = list.take(60)
            cachePrefs.edit().putString(KEY_CACHED_TICKETS_JSON, gson.toJson(trimmed)).apply()
        }
        runCatching { HelpCslAppWidgetProvider.requestUpdateAll(appContext) }
    }

    private fun saveSeenKeys(keys: Set<String>, baselineInitialized: Boolean) {
        // Keep bounded set of most recent 1000 keys
        val bounded = if (keys.size > 1000) keys.toList().takeLast(1000).toSet() else keys
        cachePrefs.edit()
            .putStringSet(KEY_SEEN_KEYS, bounded)
            .putBoolean(KEY_BASELINE_INITIALIZED, baselineInitialized)
            .apply()
    }

    private fun loadCachedTickets(): List<HelpdeskTicket> {
        val json = cachePrefs.getString(KEY_CACHED_TICKETS_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<HelpdeskTicket>>() {}.type
            gson.fromJson<List<HelpdeskTicket>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun setResolvedChatRoomKey(issueKey: String) {
        val clean = issueKey.trim().uppercase()
        if (clean.isEmpty() || clean == _resolvedChatRoomKey.value) return
        _resolvedChatRoomKey.value = clean
        cachePrefs.edit().putString(KEY_RESOLVED_CHAT_ROOM_KEY, clean).apply()
        // Ensure chat room issue is immediately hidden if it was ever in tickets cache
        val filtered = _tickets.value.filterNot { it.key.equals(clean, ignoreCase = true) }
        if (filtered.size != _tickets.value.size) {
            updateTicketsCache(filtered)
        }
    }

    @Synchronized
    fun markAdminChatRead() {
        val latestIds = _chatMessages.value.map { it.id }.toSet()
        val seenIds = (cachePrefs.getStringSet(KEY_SEEN_CHAT_IDS, emptySet()).orEmpty() + latestIds)
            .toList()
            .takeLast(500)
            .toSet()
        _unreadChatCount.value = 0
        cachePrefs.edit()
            .putInt(KEY_UNREAD_CHAT_COUNT, 0)
            .putStringSet(KEY_SEEN_CHAT_IDS, seenIds)
            .putBoolean(KEY_CHAT_BASELINE_INITIALIZED, true)
            .apply()
    }

    /**
     * Updates cached chat messages, maintains unread counter, and returns brand-new messages
     * authored by OTHER admins (for optional background notification).
     */
    @Synchronized
    fun recordPolledChatMessages(
        roomIssueKey: String,
        messages: List<AdminChatMessage>,
        pinnedNote: String? = null
    ): List<AdminChatMessage> {
        setResolvedChatRoomKey(roomIssueKey)
        if (pinnedNote != null && pinnedNote != _pinnedShiftNote.value) {
            _pinnedShiftNote.value = pinnedNote
            cachePrefs.edit().putString(KEY_PINNED_SHIFT_NOTE, pinnedNote).apply()
        }
        _chatMessages.value = messages
        runCatching {
            val trimmed = messages.takeLast(80)
            cachePrefs.edit().putString(KEY_CACHED_CHAT_MESSAGES_JSON, gson.toJson(trimmed)).apply()
        }

        val chatBaselineDone = cachePrefs.getBoolean(KEY_CHAT_BASELINE_INITIALIZED, false)
        val seenIds = cachePrefs.getStringSet(KEY_SEEN_CHAT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (!chatBaselineDone) {
            messages.forEach { seenIds.add(it.id) }
            val bounded = if (seenIds.size > 500) seenIds.toList().takeLast(500).toSet() else seenIds
            cachePrefs.edit()
                .putStringSet(KEY_SEEN_CHAT_IDS, bounded)
                .putBoolean(KEY_CHAT_BASELINE_INITIALIZED, true)
                .putInt(KEY_UNREAD_CHAT_COUNT, 0)
                .apply()
            _unreadChatCount.value = 0
            return emptyList()
        }

        val newColleagueMessages = mutableListOf<AdminChatMessage>()
        for (msg in messages) {
            val isUnseen = seenIds.add(msg.id)
            if (isUnseen && !msg.isOwnMessage) {
                newColleagueMessages.add(msg)
            }
        }

        val bounded = if (seenIds.size > 500) seenIds.toList().takeLast(500).toSet() else seenIds
        val updatedUnread = if (isChatTabVisible) {
            0
        } else {
            (_unreadChatCount.value + newColleagueMessages.size).coerceAtMost(99)
        }
        _unreadChatCount.value = updatedUnread
        cachePrefs.edit()
            .putStringSet(KEY_SEEN_CHAT_IDS, bounded)
            .putInt(KEY_UNREAD_CHAT_COUNT, updatedUnread)
            .apply()

        return if (isChatTabVisible) emptyList() else newColleagueMessages
    }

    @Synchronized
    fun appendSentChatMessage(msg: AdminChatMessage) {
        val merged = (_chatMessages.value.filterNot { it.id == msg.id } + msg)
            .sortedBy { it.createdEpochMillis }
        _chatMessages.value = merged
        val seenIds = (cachePrefs.getStringSet(KEY_SEEN_CHAT_IDS, emptySet()).orEmpty() + msg.id)
            .toList()
            .takeLast(500)
            .toSet()
        cachePrefs.edit()
            .putStringSet(KEY_SEEN_CHAT_IDS, seenIds)
            .putBoolean(KEY_CHAT_BASELINE_INITIALIZED, true)
            .putString(KEY_CACHED_CHAT_MESSAGES_JSON, gson.toJson(merged.takeLast(80)))
            .apply()
    }

    @Synchronized
    fun removeDeletedChatMessage(commentId: String) {
        val filtered = _chatMessages.value.filterNot { it.id == commentId }
        _chatMessages.value = filtered
        cachePrefs.edit()
            .putString(KEY_CACHED_CHAT_MESSAGES_JSON, gson.toJson(filtered.takeLast(80)))
            .apply()
    }

    fun resetAdminChatCache() {
        _resolvedChatRoomKey.value = ""
        _chatMessages.value = emptyList()
        _unreadChatCount.value = 0
        _pinnedShiftNote.value = ""
        cachePrefs.edit()
            .remove(KEY_RESOLVED_CHAT_ROOM_KEY)
            .remove(KEY_CACHED_CHAT_MESSAGES_JSON)
            .remove(KEY_SEEN_CHAT_IDS)
            .remove(KEY_PINNED_SHIFT_NOTE)
            .putBoolean(KEY_CHAT_BASELINE_INITIALIZED, false)
            .putInt(KEY_UNREAD_CHAT_COUNT, 0)
            .apply()
    }

    @Synchronized
    fun updatePinnedShiftNote(note: String) {
        val clean = note.trim()
        _pinnedShiftNote.value = clean
        cachePrefs.edit().putString(KEY_PINNED_SHIFT_NOTE, clean).apply()
    }

    @Synchronized
    fun enqueueOfflineAction(action: OfflineQueuedAction) {
        val updated = (_offlineQueuedActions.value.filterNot { it.id == action.id } + action)
            .takeLast(50)
        _offlineQueuedActions.value = updated
        persistOfflineActions(updated)
    }

    @Synchronized
    fun removeOfflineAction(actionId: String) {
        val updated = _offlineQueuedActions.value.filterNot { it.id == actionId }
        _offlineQueuedActions.value = updated
        persistOfflineActions(updated)
    }

    @Synchronized
    fun updateOfflineActionError(actionId: String, errorMsg: String) {
        val updated = _offlineQueuedActions.value.map { item ->
            if (item.id == actionId) {
                item.copy(attemptCount = item.attemptCount + 1, lastError = errorMsg)
            } else {
                item
            }
        }
        _offlineQueuedActions.value = updated
        persistOfflineActions(updated)
    }

    private fun persistOfflineActions(items: List<OfflineQueuedAction>) {
        runCatching {
            cachePrefs.edit().putString(KEY_OFFLINE_QUEUE_JSON, gson.toJson(items)).apply()
        }
    }

    private fun loadOfflineQueuedActions(): List<OfflineQueuedAction> {
        val json = cachePrefs.getString(KEY_OFFLINE_QUEUE_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<OfflineQueuedAction>>() {}.type
            gson.fromJson<List<OfflineQueuedAction>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Returns NEW unassigned client tickets that have waited longer than [reminderMinutes]
     * and have not yet triggered a stale escalation reminder.
     */
    @Synchronized
    fun findDueStaleUnassignedTickets(
        polled: List<HelpdeskTicket>,
        reminderMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<HelpdeskTicket> {
        val thresholdMs = reminderMinutes.coerceAtLeast(5) * 60_000L
        val maxLookbackMs = 12L * 3600_000L
        val reminded = cachePrefs.getStringSet(KEY_STALE_REMINDED_KEYS, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        val due = mutableListOf<HelpdeskTicket>()
        for (ticket in polled) {
            if (!ticket.isClientTicket) continue
            if (ticket.stage != TicketStage.NEW) continue
            if (!ticket.assigneeName.isNullOrBlank()) continue
            if (ticket.createdEpochMillis <= 0L) continue
            val ageMs = nowMillis - ticket.createdEpochMillis
            if (ageMs in thresholdMs..maxLookbackMs && reminded.add(ticket.key)) {
                due.add(ticket)
            }
        }

        if (due.isNotEmpty()) {
            val bounded = if (reminded.size > 500) reminded.toList().takeLast(500).toSet() else reminded
            cachePrefs.edit().putStringSet(KEY_STALE_REMINDED_KEYS, bounded).apply()
        }
        return due
    }

    private fun loadCachedChatMessages(): List<AdminChatMessage> {
        val json = cachePrefs.getString(KEY_CACHED_CHAT_MESSAGES_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<AdminChatMessage>>() {}.type
            gson.fromJson<List<AdminChatMessage>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun loadClientDossierNotes(): Map<String, String> {
        val json = prefs.getString(KEY_CLIENT_DOSSIER_NOTES_JSON, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun loadAppSettings(): AppSettings {
        return AppSettings(
            serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
            serverType = enumOrDefault(prefs.getString(KEY_SERVER_TYPE, null), JiraServerType.AUTO),
            authType = enumOrDefault(prefs.getString(KEY_AUTH_TYPE, null), AuthType.BASIC_LOGIN_PASSWORD),
            usernameOrEmail = prefs.getString(KEY_USERNAME, "").orEmpty(),
            passwordOrToken = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            trustSelfSignedSsl = prefs.getBoolean(KEY_TRUST_SSL, false),
            pollingEnabled = prefs.getBoolean(KEY_POLLING_ENABLED, true),
            pollIntervalSeconds = prefs.getInt(KEY_POLL_INTERVAL, 30),
            startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true),
            autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
            workScheduleEnabled = prefs.getBoolean(KEY_WORK_SCHEDULE_ENABLED, true),
            workStartTime = prefs.getString(KEY_WORK_START_TIME, "09:00").orEmpty().ifBlank { "09:00" },
            workEndTime = prefs.getString(KEY_WORK_END_TIME, "17:30").orEmpty().ifBlank { "17:30" },
            extraWorkingDates = prefs.getString(KEY_EXTRA_WORKING_DATES, "").orEmpty(),
            extraHolidayDates = prefs.getString(KEY_EXTRA_HOLIDAY_DATES, "").orEmpty(),
            alertSoundPreset = enumOrDefault(
                prefs.getString(KEY_ALERT_SOUND_PRESET, null),
                AlertSoundPreset.CSL_CHIME
            ),
            customAlertSoundUri = prefs.getString(KEY_CUSTOM_ALERT_SOUND_URI, "").orEmpty(),
            customAlertSoundTitle = prefs.getString(KEY_CUSTOM_ALERT_SOUND_TITLE, "").orEmpty(),
            adminChatIssueKey = prefs.getString(KEY_ADMIN_CHAT_ISSUE_KEY, "").orEmpty(),
            adminChatNotificationsEnabled = prefs.getBoolean(KEY_ADMIN_CHAT_NOTIFICATIONS, true),
            staleTicketReminderEnabled = prefs.getBoolean(KEY_STALE_REMINDER_ENABLED, true),
            staleTicketReminderMinutes = prefs.getInt(KEY_STALE_REMINDER_MINUTES, 15).coerceIn(5, 240),
            quickReplyTemplates = loadQuickReplyTemplates(),
            ttsReadoutMode = enumOrDefault(prefs.getString(KEY_TTS_READOUT_MODE, null), TtsReadoutMode.HEADSET_ONLY),
            stormProtectionEnabled = prefs.getBoolean(KEY_STORM_PROTECTION_ENABLED, true),
            callerIdEnabled = prefs.getBoolean(KEY_CALLER_ID_ENABLED, true),
            postCallWorklogPromptEnabled = prefs.getBoolean(KEY_POST_CALL_WORKLOG_ENABLED, true),
            biometricLockEnabled = prefs.getBoolean(KEY_BIOMETRIC_LOCK_ENABLED, false),
            phoneRegion = enumOrDefault(prefs.getString(KEY_PHONE_REGION, null), PhoneRegion.AUTO_BY_RU),
            customCountryCode = prefs.getString(KEY_CUSTOM_COUNTRY_CODE, "+375").orEmpty().ifBlank { "+375" },
            customPhoneFieldNames = prefs.getString(
                KEY_CUSTOM_PHONE_FIELDS,
                "Телефон, Phone, Номер телефона, Контактный телефон, Mobile"
            ).orEmpty()
        )
    }

    private fun loadFilterSettings(): FilterSettings {
        return FilterSettings(
            clientOnlyFilterEnabled = prefs.getBoolean(KEY_CLIENT_ONLY, true),
            excludeSubtasks = prefs.getBoolean(KEY_EXCLUDE_SUBTASKS, true),
            onlyCustomerPortalRequests = prefs.getBoolean(KEY_ONLY_PORTAL, false),
            excludeResolvedClosed = prefs.getBoolean(KEY_EXCLUDE_DONE, true),
            ignoreOwnTickets = prefs.getBoolean(KEY_IGNORE_OWN, false),
            projectKeys = prefs.getString(KEY_PROJECT_KEYS, "").orEmpty(),
            allowedIssueTypes = prefs.getString(KEY_ALLOWED_TYPES, "").orEmpty(),
            excludedIssueTypes = prefs.getString(
                KEY_EXCLUDED_TYPES,
                FilterSettings.DEFAULT_EXCLUDED_ISSUE_TYPES
            ).orEmpty(),
            excludedReporters = prefs.getString(
                KEY_EXCLUDED_REPORTERS,
                FilterSettings.DEFAULT_EXCLUDED_REPORTERS
            ).orEmpty(),
            excludedLabelsAndKeywords = prefs.getString(
                KEY_EXCLUDED_KEYWORDS,
                FilterSettings.DEFAULT_EXCLUDED_LABELS_AND_KEYWORDS
            ).orEmpty(),
            customJqlCondition = prefs.getString(KEY_CUSTOM_JQL, "").orEmpty()
        )
    }

    private fun loadQuickReplyTemplates(): List<String> {
        val raw = prefs.getString(KEY_QUICK_REPLY_TEMPLATES_JSON, null)
            ?: return AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(raw, type)
            parsed?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?: AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES
        }.getOrDefault(AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES)
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T {
            if (name.isNullOrBlank()) return default
            return runCatching { enumValueOf<T>(name) }.getOrDefault(default)
        }

        private fun createSecurePrefsWithFallback(context: Context): SharedPreferences {
            return runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "helpdesk_secure_settings",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse {
                context.getSharedPreferences("helpdesk_fallback_settings", Context.MODE_PRIVATE)
            }
        }

        private fun migrateLegacyFilterDefaults(
            prefs: SharedPreferences,
            cachePrefs: SharedPreferences
        ) {
            val savedVersion = prefs.getInt(KEY_FILTER_SCHEMA_VERSION, 1)
            if (savedVersion >= CURRENT_FILTER_SCHEMA_VERSION) return

            val editor = prefs.edit()
            val savedTypes = prefs.getString(KEY_EXCLUDED_TYPES, null)
            if (savedTypes != null && (
                    savedTypes == FilterSettings.LEGACY_V1_EXCLUDED_ISSUE_TYPES ||
                        savedTypes.contains("Service", ignoreCase = true) ||
                        savedTypes.contains("Сервисн", ignoreCase = true) ||
                        savedTypes.contains("Проблем", ignoreCase = true)
                )
            ) {
                editor.putString(KEY_EXCLUDED_TYPES, FilterSettings.DEFAULT_EXCLUDED_ISSUE_TYPES)
            }

            val savedReporters = prefs.getString(KEY_EXCLUDED_REPORTERS, null)
            if (savedReporters == null || savedReporters == FilterSettings.LEGACY_V1_EXCLUDED_REPORTERS) {
                editor.putString(KEY_EXCLUDED_REPORTERS, FilterSettings.DEFAULT_EXCLUDED_REPORTERS)
            } else if (!savedReporters.contains("zabbix", ignoreCase = true)) {
                editor.putString(KEY_EXCLUDED_REPORTERS, "zabbix, $savedReporters")
            }

            val savedKeywords = prefs.getString(KEY_EXCLUDED_KEYWORDS, null)
            if (savedKeywords == FilterSettings.LEGACY_V1_EXCLUDED_LABELS_AND_KEYWORDS) {
                editor.putString(KEY_EXCLUDED_KEYWORDS, FilterSettings.DEFAULT_EXCLUDED_LABELS_AND_KEYWORDS)
            }

            if (cachePrefs.getBoolean(KEY_BASELINE_INITIALIZED, false)) {
                cachePrefs.edit().putBoolean(KEY_SILENT_RESEED_ONCE, true).apply()
            }

            editor.putInt(KEY_FILTER_SCHEMA_VERSION, CURRENT_FILTER_SCHEMA_VERSION).apply()
        }

        private const val CURRENT_FILTER_SCHEMA_VERSION = 4
        private const val KEY_FILTER_SCHEMA_VERSION = "filter_schema_version"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_TYPE = "server_type"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRUST_SSL = "trust_ssl"
        private const val KEY_POLLING_ENABLED = "polling_enabled"
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_WORK_SCHEDULE_ENABLED = "work_schedule_enabled"
        private const val KEY_WORK_START_TIME = "work_start_time"
        private const val KEY_WORK_END_TIME = "work_end_time"
        private const val KEY_EXTRA_WORKING_DATES = "extra_working_dates"
        private const val KEY_EXTRA_HOLIDAY_DATES = "extra_holiday_dates"
        private const val KEY_ALERT_SOUND_PRESET = "alert_sound_preset"
        private const val KEY_CUSTOM_ALERT_SOUND_URI = "custom_alert_sound_uri"
        private const val KEY_CUSTOM_ALERT_SOUND_TITLE = "custom_alert_sound_title"
        private const val KEY_ADMIN_CHAT_ISSUE_KEY = "admin_chat_issue_key"
        private const val KEY_ADMIN_CHAT_NOTIFICATIONS = "admin_chat_notifications"
        private const val KEY_STALE_REMINDER_ENABLED = "stale_reminder_enabled"
        private const val KEY_STALE_REMINDER_MINUTES = "stale_reminder_minutes"
        private const val KEY_QUICK_REPLY_TEMPLATES_JSON = "quick_reply_templates_json"
        private const val KEY_TTS_READOUT_MODE = "tts_readout_mode"
        private const val KEY_STORM_PROTECTION_ENABLED = "storm_protection_enabled"
        private const val KEY_CALLER_ID_ENABLED = "caller_id_enabled"
        private const val KEY_POST_CALL_WORKLOG_ENABLED = "post_call_worklog_enabled"
        private const val KEY_BIOMETRIC_LOCK_ENABLED = "biometric_lock_enabled"
        private const val KEY_CLIENT_DOSSIER_NOTES_JSON = "client_dossier_notes_json"
        private const val KEY_PHONE_REGION = "phone_region"
        private const val KEY_CUSTOM_COUNTRY_CODE = "custom_country_code"
        private const val KEY_CUSTOM_PHONE_FIELDS = "custom_phone_fields"

        private const val KEY_CLIENT_ONLY = "filter_client_only"
        private const val KEY_EXCLUDE_SUBTASKS = "filter_exclude_subtasks"
        private const val KEY_ONLY_PORTAL = "filter_only_portal"
        private const val KEY_EXCLUDE_DONE = "filter_exclude_done"
        private const val KEY_IGNORE_OWN = "filter_ignore_own"
        private const val KEY_PROJECT_KEYS = "filter_project_keys"
        private const val KEY_ALLOWED_TYPES = "filter_allowed_types"
        private const val KEY_EXCLUDED_TYPES = "filter_excluded_types"
        private const val KEY_EXCLUDED_REPORTERS = "filter_excluded_reporters"
        private const val KEY_EXCLUDED_KEYWORDS = "filter_excluded_keywords"
        private const val KEY_CUSTOM_JQL = "filter_custom_jql"

        private const val KEY_SEEN_KEYS = "seen_ticket_keys"
        private const val KEY_DEFERRED_ALERT_KEYS = "deferred_alert_keys"
        private const val KEY_RECREATED_CLIENT_KEYS = "recreated_client_keys"
        private const val KEY_POSTPONED_TICKETS_JSON = "postponed_tickets_json"
        private const val KEY_BASELINE_INITIALIZED = "baseline_initialized"
        private const val KEY_SILENT_RESEED_ONCE = "silent_reseed_once"
        private const val KEY_CACHED_TICKETS_JSON = "cached_tickets_json"
        private const val KEY_RESOLVED_CHAT_ROOM_KEY = "resolved_chat_room_key"
        private const val KEY_CACHED_CHAT_MESSAGES_JSON = "cached_chat_messages_json"
        private const val KEY_SEEN_CHAT_IDS = "seen_chat_comment_ids"
        private const val KEY_CHAT_BASELINE_INITIALIZED = "chat_baseline_initialized"
        private const val KEY_UNREAD_CHAT_COUNT = "unread_chat_count"
        private const val KEY_PINNED_SHIFT_NOTE = "pinned_shift_note"
        private const val KEY_OFFLINE_QUEUE_JSON = "offline_queue_json"
        private const val KEY_STALE_REMINDED_KEYS = "stale_reminded_ticket_keys"
        private const val KEY_REPAIR_ITEMS_JSON = "repair_equipment_items_json"
        private const val KEY_RESOLVED_REPAIR_ROOM_KEY = "resolved_repair_room_key"
    }
}
