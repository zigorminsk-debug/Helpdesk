package com.helpdesk.jiraagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
            .putBoolean(KEY_WORK_SCHEDULE_ENABLED, newSettings.workScheduleEnabled)
            .putString(KEY_WORK_START_TIME, newSettings.workStartTime.trim().ifEmpty { "09:00" })
            .putString(KEY_WORK_END_TIME, newSettings.workEndTime.trim().ifEmpty { "17:30" })
            .putString(KEY_EXTRA_WORKING_DATES, newSettings.extraWorkingDates.trim())
            .putString(KEY_EXTRA_HOLIDAY_DATES, newSettings.extraHolidayDates.trim())
            .putString(KEY_ALERT_SOUND_PRESET, newSettings.alertSoundPreset.name)
            .putString(KEY_CUSTOM_ALERT_SOUND_URI, newSettings.customAlertSoundUri.trim())
            .putString(KEY_CUSTOM_ALERT_SOUND_TITLE, newSettings.customAlertSoundTitle.trim())
            .putString(KEY_PHONE_REGION, newSettings.phoneRegion.name)
            .putString(KEY_CUSTOM_COUNTRY_CODE, newSettings.customCountryCode.trim())
            .putString(KEY_CUSTOM_PHONE_FIELDS, newSettings.customPhoneFieldNames)
            .apply()

        if (serverChanged) {
            resetSeenBaseline()
        }
        _appSettings.value = newSettings
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
        val adjustedPolled = applyRecreatedOverrides(polled)
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
        private const val KEY_WORK_SCHEDULE_ENABLED = "work_schedule_enabled"
        private const val KEY_WORK_START_TIME = "work_start_time"
        private const val KEY_WORK_END_TIME = "work_end_time"
        private const val KEY_EXTRA_WORKING_DATES = "extra_working_dates"
        private const val KEY_EXTRA_HOLIDAY_DATES = "extra_holiday_dates"
        private const val KEY_ALERT_SOUND_PRESET = "alert_sound_preset"
        private const val KEY_CUSTOM_ALERT_SOUND_URI = "custom_alert_sound_uri"
        private const val KEY_CUSTOM_ALERT_SOUND_TITLE = "custom_alert_sound_title"
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
    }
}
