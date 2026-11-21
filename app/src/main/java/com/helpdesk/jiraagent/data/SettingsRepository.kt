package com.helpdesk.jiraagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.model.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val prefs: SharedPreferences = createSecurePrefsWithFallback(appContext).also {
        migrateLegacyFilterDefaults(it)
    }
    private val cachePrefs: SharedPreferences =
        appContext.getSharedPreferences("helpdesk_ticket_cache", Context.MODE_PRIVATE)

    private val _appSettings = MutableStateFlow(loadAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    private val _filterSettings = MutableStateFlow(loadFilterSettings())
    val filterSettings: StateFlow<FilterSettings> = _filterSettings.asStateFlow()

    private val _tickets = MutableStateFlow(
        loadCachedTickets().map { ticket ->
            val eval = TicketFilterEngine.evaluate(
                ticket = ticket,
                filter = _filterSettings.value,
                currentAgentLoginOrEmail = _appSettings.value.usernameOrEmail
            )
            ticket.copy(isClientTicket = eval.isClientTicket, filterReason = eval.reason)
        }
    )
    val tickets: StateFlow<List<HelpdeskTicket>> = _tickets.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

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
        val updated = _tickets.value.map { ticket ->
            val eval = TicketFilterEngine.evaluate(ticket, newFilter, currentAgent)
            ticket.copy(isClientTicket = eval.isClientTicket, filterReason = eval.reason)
        }
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
    fun processPolledTicketsAndFindNewClientOnes(polled: List<HelpdeskTicket>): List<HelpdeskTicket> {
        updateTicketsCache(polled)

        val baselineDone = cachePrefs.getBoolean(KEY_BASELINE_INITIALIZED, false)
        val seenKeys = cachePrefs.getStringSet(KEY_SEEN_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (!baselineDone) {
            polled.forEach { seenKeys.add(it.key) }
            saveSeenKeys(seenKeys, baselineInitialized = true)
            return emptyList()
        }

        val newClientTickets = mutableListOf<HelpdeskTicket>()
        // Sort ascending by creation time so notifications arrive in chronological order
        for (ticket in polled.sortedBy { it.createdEpochMillis }) {
            val isUnseen = seenKeys.add(ticket.key)
            if (isUnseen && ticket.isClientTicket) {
                newClientTickets.add(ticket)
            }
        }

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

        private fun migrateLegacyFilterDefaults(prefs: SharedPreferences) {
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

            editor.putInt(KEY_FILTER_SCHEMA_VERSION, CURRENT_FILTER_SCHEMA_VERSION).apply()
        }

        private const val CURRENT_FILTER_SCHEMA_VERSION = 3
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
        private const val KEY_BASELINE_INITIALIZED = "baseline_initialized"
        private const val KEY_CACHED_TICKETS_JSON = "cached_tickets_json"
    }
}
