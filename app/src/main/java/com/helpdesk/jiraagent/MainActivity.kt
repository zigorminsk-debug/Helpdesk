package com.helpdesk.jiraagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helpdesk.jiraagent.data.InAppBannerAlert
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.model.ClientTechPassportCodec
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.ui.AppUpdateUiState
import com.helpdesk.jiraagent.ui.MainViewModel
import com.helpdesk.jiraagent.ui.components.AppFullGuideDialog
import com.helpdesk.jiraagent.ui.components.KnowledgeBaseArchiveAndroidDialog
import com.helpdesk.jiraagent.ui.components.QrSettingsTransferDialog
import com.helpdesk.jiraagent.ui.components.SerialHistoryResultsDialog
import com.helpdesk.jiraagent.ui.components.ShiftAnalyticsDialog
import com.helpdesk.jiraagent.ui.components.SysadminToolboxAndroidDialog
import com.helpdesk.jiraagent.ui.components.WorklogAndPostCallDialog
import com.helpdesk.jiraagent.ui.screens.AdminChatScreen
import com.helpdesk.jiraagent.ui.screens.ConfluenceWikiScreen
import com.helpdesk.jiraagent.ui.screens.FilterSettingsScreen
import com.helpdesk.jiraagent.ui.screens.RepairRegistryScreen
import com.helpdesk.jiraagent.ui.screens.ServerSettingsScreen
import com.helpdesk.jiraagent.ui.screens.TicketDetailScreen
import com.helpdesk.jiraagent.ui.screens.TicketsScreen
import com.helpdesk.jiraagent.ui.screens.ZabbixMonitorScreen
import com.helpdesk.jiraagent.model.AppThemeMode
import com.helpdesk.jiraagent.model.ZabbixProblem
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.ui.theme.JiraHelpdeskTheme
import com.helpdesk.jiraagent.util.DialerLauncher
import kotlinx.coroutines.delay

private enum class BottomDestination(val label: String) {
    TICKETS("Заявки"),
    ZABBIX("Zabbix"),
    REPAIR("Ремонт"),
    WIKI("Wiki"),
    CHAT("Чат"),
    SETTINGS("Настройки")
}

/** Compact navigation keeps all destinations and counters available on short phone windows. */
@Composable
private fun CompactBottomNavigation(
    currentTab: BottomDestination,
    zabbixCount: Int,
    repairCount: Int,
    chatCount: Int,
    onSelect: (BottomDestination) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            BottomDestination.entries.forEach { destination ->
                val selected = destination == currentTab
                val count = when (destination) {
                    BottomDestination.ZABBIX -> zabbixCount
                    BottomDestination.REPAIR -> repairCount
                    BottomDestination.CHAT -> chatCount
                    else -> 0
                }
                val icon = when (destination) {
                    BottomDestination.TICKETS -> Icons.Default.SupportAgent
                    BottomDestination.ZABBIX -> Icons.Default.Dns
                    BottomDestination.REPAIR -> Icons.Default.Build
                    BottomDestination.WIKI -> Icons.Default.LibraryBooks
                    BottomDestination.CHAT -> Icons.Default.Forum
                    BottomDestination.SETTINGS -> Icons.Default.Settings
                }
                val label = destination.label + if (count > 0) " ${if (count > 99) "99+" else count}" else ""
                Row(
                    modifier = Modifier.weight(1f).height(48.dp).clickable { onSelect(destination) }
                        .background(if (selected) CslOrange.copy(alpha = 0.16f) else Color.Transparent),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = label, tint = if (selected) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        fontSize = with(LocalDensity.current) { 10.dp.toSp() },
                        color = if (selected) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

        setContent {
            val currentAppSettings by viewModel.appSettings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (currentAppSettings.themeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> systemDark
            }
            JiraHelpdeskTheme(
                darkTheme = useDark,
                darkPreset = currentAppSettings.darkThemePreset,
                lightPreset = currentAppSettings.lightThemePreset
            ) {
                HelpdeskMainRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val updateState = viewModel.appUpdateState.value
        if (updateState.needsInstallPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls()
        ) {
            viewModel.installReadyUpdateApk()
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val ticketKey = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY)
        val autoCallPhone = intent?.getStringExtra(NotificationHelper.EXTRA_AUTO_CALL_PHONE_E164)
        val autoCallClient = intent?.getStringExtra(NotificationHelper.EXTRA_AUTO_CALL_CLIENT_NAME)
        if (!ticketKey.isNullOrBlank()) {
            viewModel.openTicket(ticketKey)
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY)
        }
        if (!autoCallPhone.isNullOrBlank()) {
            intent?.removeExtra(NotificationHelper.EXTRA_AUTO_CALL_PHONE_E164)
            intent?.removeExtra(NotificationHelper.EXTRA_AUTO_CALL_CLIENT_NAME)
            DialerLauncher.openSystemDialer(
                context = this,
                phoneInput = autoCallPhone,
                ticketKey = ticketKey,
                clientName = autoCallClient
            )
        }
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_TRIGGER_APP_UPDATE, false) == true) {
            intent.removeExtra(NotificationHelper.EXTRA_TRIGGER_APP_UPDATE)
            viewModel.checkForAppUpdate(manual = true, autoStartDownload = true)
        }
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_ADMIN_CHAT, false) == true) {
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_ADMIN_CHAT)
            viewModel.requestOpenAdminChatTab()
        }
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_WATCHLIST_MONITOR, false) == true) {
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_WATCHLIST_MONITOR)
            viewModel.requestOpenWatchlistMonitor()
        }
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_ZABBIX_TAB, false) == true) {
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_ZABBIX_TAB)
            viewModel.requestOpenZabbixTab()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpdeskMainRoot(viewModel: MainViewModel) {
    val context = LocalContext.current

    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val filterSettings by viewModel.filterSettings.collectAsStateWithLifecycle()
    val tickets by viewModel.tickets.collectAsStateWithLifecycle()
    val postponedTickets by viewModel.postponedTickets.collectAsStateWithLifecycle()
    val offlineQueuedActions by viewModel.offlineQueuedActions.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val selectedTicketKey by viewModel.selectedTicketKey.collectAsStateWithLifecycle()
    val isLoadingDetail by viewModel.isLoadingTicketDetail.collectAsStateWithLifecycle()
    val ticketActionState by viewModel.ticketActionState.collectAsStateWithLifecycle()
    val clientHistoryState by viewModel.clientHistoryState.collectAsStateWithLifecycle()
    val hostDiagnosticState by viewModel.hostDiagnosticState.collectAsStateWithLifecycle()
    val isCreatingTicket by viewModel.isCreatingTicket.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val latestInAppBanner by viewModel.latestInAppBanner.collectAsStateWithLifecycle()
    val returnToMainScreenStamp by viewModel.returnToMainScreenStamp.collectAsStateWithLifecycle()
    val openChatTabStamp by viewModel.openChatTabStamp.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val unreadChatCount by viewModel.unreadChatCount.collectAsStateWithLifecycle()
    val resolvedChatRoomKey by viewModel.resolvedChatRoomKey.collectAsStateWithLifecycle()
    val pinnedShiftNote by viewModel.pinnedShiftNote.collectAsStateWithLifecycle()
    val adminChatUiState by viewModel.adminChatUiState.collectAsStateWithLifecycle()
    val clientDossierNotes by viewModel.clientDossierNotes.collectAsStateWithLifecycle()
    val pendingCallSession by viewModel.pendingCallSession.collectAsStateWithLifecycle()
    val assignableColleagues by viewModel.assignableColleagues.collectAsStateWithLifecycle()
    val isLoadingColleagues by viewModel.isLoadingColleagues.collectAsStateWithLifecycle()
    val serialSearchState by viewModel.serialSearchState.collectAsStateWithLifecycle()
    val isBatchBusy by viewModel.isBatchBusy.collectAsStateWithLifecycle()
    val repairItems by viewModel.repairItems.collectAsStateWithLifecycle()
    val clientDirectory by viewModel.clientDirectory.collectAsStateWithLifecycle()
    val isLoadingClientDirectory by viewModel.isLoadingClientDirectory.collectAsStateWithLifecycle()
    val isSyncingRepair by viewModel.isSyncingRepair.collectAsStateWithLifecycle()
    val repairSyncError by viewModel.repairSyncError.collectAsStateWithLifecycle()
    val pendingRepairDraft by viewModel.pendingRepairDraftFromTicket.collectAsStateWithLifecycle()
    val openRepairTabStamp by viewModel.openRepairTabStamp.collectAsStateWithLifecycle()
    val wikiUiState by viewModel.wikiUiState.collectAsStateWithLifecycle()
    val openWikiTabStamp by viewModel.openWikiTabStamp.collectAsStateWithLifecycle()
    val openZabbixTabStamp by viewModel.openZabbixTabStamp.collectAsStateWithLifecycle()
    val zabbixSnapshot by viewModel.zabbixSnapshot.collectAsStateWithLifecycle()
    val zabbixLastError by viewModel.zabbixLastError.collectAsStateWithLifecycle()
    val dismissedZabbixKeys by viewModel.dismissedZabbixKeys.collectAsStateWithLifecycle()
    val mutedZabbixRules by viewModel.mutedZabbixRules.collectAsStateWithLifecycle()
    val isLoadingZabbix by viewModel.isLoadingZabbix.collectAsStateWithLifecycle()
    val zabbixStatusFeedback by viewModel.zabbixStatusFeedback.collectAsStateWithLifecycle()
    val clientPassports by viewModel.clientPassports.collectAsStateWithLifecycle()
    val ticketStopwatches by viewModel.ticketStopwatches.collectAsStateWithLifecycle()
    val watchlistHosts by viewModel.watchlistHosts.collectAsStateWithLifecycle()
    val watchlistLabels by viewModel.watchlistLabels.collectAsStateWithLifecycle()
    val watchlistStatuses by viewModel.watchlistStatuses.collectAsStateWithLifecycle()
    val isCheckingWatchlist by viewModel.isCheckingWatchlist.collectAsStateWithLifecycle()
    val openWatchlistStamp by viewModel.openWatchlistStamp.collectAsStateWithLifecycle()
    val securityWipeNotice by viewModel.securityWipeNotice.collectAsStateWithLifecycle()
    val knowledgeResults by viewModel.knowledgeArchiveResults.collectAsStateWithLifecycle()
    val isSearchingKnowledge by viewModel.isSearchingKnowledge.collectAsStateWithLifecycle()
    val kpiSampleTickets by viewModel.kpiSampleTickets.collectAsStateWithLifecycle()
    val isLoadingKpi by viewModel.isLoadingKpi.collectAsStateWithLifecycle()
    val isCleaningSystemRooms by viewModel.isCleaningSystemRooms.collectAsStateWithLifecycle()
    val systemRoomsCleanupMessage by viewModel.systemRoomsCleanupMessage.collectAsStateWithLifecycle()

    val activeWorkshopCount = remember(repairItems) {
        repairItems.count { !it.isDeleted && it.status.isActiveInWorkshop }
    }
    val mutedZabbixKeySet = remember(mutedZabbixRules) {
        mutedZabbixRules.flatMap { it.allMatchKeys() }.toSet()
    }
    val activeZabbixAlertsCount = remember(
        zabbixSnapshot,
        appSettings.zabbixMinAlertSeverity,
        dismissedZabbixKeys,
        mutedZabbixKeySet
    ) {
        val minSev = appSettings.normalizedZabbixMinSeverity
        zabbixSnapshot?.problems?.count {
            !it.acknowledged &&
                !it.isHiddenFromActiveFeed(dismissedZabbixKeys, mutedZabbixKeySet) &&
                it.shouldTriggerBanner(minSev)
        } ?: 0
    }

    var currentTab by rememberSaveable { mutableStateOf(BottomDestination.TICKETS) }
    var resetTicketsToFreshNonce by rememberSaveable { mutableLongStateOf(0L) }
    var settingsSubTab by rememberSaveable { mutableStateOf(0) }
    var hasNotifPermission by rememberSaveable {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }
    var showShiftAnalytics by rememberSaveable { mutableStateOf(false) }
    var showToolboxDialog by rememberSaveable { mutableStateOf(false) }
    var toolboxInitialTab by rememberSaveable { mutableStateOf(0) }
    var showKbArchiveDialog by rememberSaveable { mutableStateOf(false) }
    var showFullGuide by rememberSaveable { mutableStateOf(false) }
    var showQrTransfer by rememberSaveable { mutableStateOf(false) }
    var showPostCallWorklogPrompt by rememberSaveable { mutableStateOf(false) }
    var externalSearchFilter by rememberSaveable { mutableStateOf("") }

    // Biometric lock state
    var isAppUnlocked by rememberSaveable { mutableStateOf(false) }
    var lastBackgroundAt by rememberSaveable { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? FragmentActivity

    val triggerBiometricUnlock = remember(activity) {
        {
            if (activity == null) {
                isAppUnlocked = true
            } else {
                val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                val canAuth = BiometricManager.from(activity).canAuthenticate(authenticators)
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    isAppUnlocked = true
                } else {
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                isAppUnlocked = true
                            }
                        }
                    )
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Вход в Help-CSL")
                        .setSubtitle("Подтвердите отпечаток пальца, лицо или PIN экрана")
                        .setAllowedAuthenticators(authenticators)
                        .build()
                    runCatching { prompt.authenticate(info) }.onFailure { isAppUnlocked = true }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, appSettings.biometricLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    lastBackgroundAt = System.currentTimeMillis()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (appSettings.biometricLockEnabled &&
                        lastBackgroundAt > 0L &&
                        System.currentTimeMillis() - lastBackgroundAt > 120_000L
                    ) {
                        isAppUnlocked = false
                        triggerBiometricUnlock()
                    }
                    val callSess = viewModel.pendingCallSession.value
                    if (callSess != null && System.currentTimeMillis() - callSess.startedAtMillis >= 4_000L) {
                        showPostCallWorklogPrompt = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(appSettings.biometricLockEnabled) {
        if (!appSettings.biometricLockEnabled) {
            isAppUnlocked = true
        } else if (!isAppUnlocked) {
            triggerBiometricUnlock()
        }
    }

    if (appSettings.biometricLockEnabled && !isAppUnlocked) {
        Surface(modifier = Modifier.fillMaxSize(), color = CslMidnight, contentColor = Color.White) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = CslOrange, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Help-CSL защищён", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Подтвердите личность для доступа к заявкам и чату", color = Color.White.copy(alpha = 0.75f))
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { triggerBiometricUnlock() },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Разблокировать")
                }
            }
        }
        return
    }

    if (showPostCallWorklogPrompt && pendingCallSession != null) {
        val sess = pendingCallSession!!
        WorklogAndPostCallDialog(
            ticketKey = sess.ticketKey,
            clientName = sess.clientName,
            callSession = sess,
            onConfirmWorklog = { mins, note, complete ->
                showPostCallWorklogPrompt = false
                viewModel.logTicketWorklog(sess.ticketKey, mins, note, complete)
            },
            onDismiss = {
                showPostCallWorklogPrompt = false
                viewModel.dismissPendingCallSession()
            }
        )
    }

    SerialHistoryResultsDialog(
        state = serialSearchState,
        onOpenTicket = { key -> viewModel.openTicket(key) },
        onDismiss = { viewModel.clearSerialSearch() }
    )

    if (showShiftAnalytics) {
        ShiftAnalyticsDialog(
            tickets = tickets,
            kpiSampleTickets = kpiSampleTickets,
            isLoadingKpi = isLoadingKpi,
            isJiraConfigured = appSettings.isConfigured,
            stopwatches = ticketStopwatches,
            connectedUserDisplayName = syncStatus.connectedUser,
            usernameOrEmail = appSettings.usernameOrEmail,
            onRefreshKpi = { viewModel.refreshEmployeeKpi(30) },
            onOpenTicket = { key ->
                showShiftAnalytics = false
                viewModel.openTicket(key)
            },
            onSelectClientFilter = { client ->
                currentTab = BottomDestination.TICKETS
                externalSearchFilter = client
            },
            onExportPdf = { sum -> viewModel.shareShiftAnalyticsPdf(sum) },
            onExportCsv = { sum -> viewModel.shareShiftAnalyticsCsv(sum) },
            onDismiss = { showShiftAnalytics = false }
        )
    }

    if (showToolboxDialog) {
        SysadminToolboxAndroidDialog(
            watchlistHosts = watchlistHosts,
            watchlistLabels = watchlistLabels,
            watchlistStatuses = watchlistStatuses,
            isCheckingWatchlist = isCheckingWatchlist,
            initialTabIndex = toolboxInitialTab,
            onAddHost = { host, label -> viewModel.addWatchlistHost(host, label) },
            onUpdateHostLabel = { host, label -> viewModel.updateWatchlistHostLabel(host, label) },
            onRemoveHost = { viewModel.removeWatchlistHost(it) },
            onRefreshHosts = { viewModel.refreshWatchlistHosts() },
            onDismiss = {
                showToolboxDialog = false
                toolboxInitialTab = 0
            }
        )
    }

    securityWipeNotice?.let { wipeMsg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissSecurityWipeNotice() },
            title = { Text("🔒 База и записи очищены", fontWeight = FontWeight.Bold) },
            text = { Text(wipeMsg) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissSecurityWipeNotice()
                        currentTab = BottomDestination.SETTINGS
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Понятно")
                }
            }
        )
    }

    if (showKbArchiveDialog) {
        KnowledgeBaseArchiveAndroidDialog(
            initialQuery = externalSearchFilter,
            results = knowledgeResults,
            isSearching = isSearchingKnowledge,
            onSearch = { q -> viewModel.searchKnowledgeArchive(q) },
            onOpenTicket = { key -> viewModel.openTicket(key) },
            onDismiss = { showKbArchiveDialog = false }
        )
    }

    if (showFullGuide) {
        AppFullGuideDialog(onDismiss = { showFullGuide = false })
    }

    if (showQrTransfer) {
        QrSettingsTransferDialog(
            appSettings = appSettings,
            filterSettings = filterSettings,
            onImportPayload = { code, pin -> viewModel.importSettingsBundle(code, pin) },
            onDismiss = { showQrTransfer = false }
        )
    }

    LaunchedEffect(returnToMainScreenStamp) {
        if (returnToMainScreenStamp > 0L) {
            currentTab = BottomDestination.TICKETS
            resetTicketsToFreshNonce++
            viewModel.consumeReturnToMainScreenStamp()
        }
    }

    // Pressing Back on any non-Tickets bottom tab returns directly to the primary «Свежие» screen
    BackHandler(enabled = selectedTicketKey == null && currentTab != BottomDestination.TICKETS) {
        currentTab = BottomDestination.TICKETS
        resetTicketsToFreshNonce++
    }

    LaunchedEffect(openChatTabStamp) {
        if (openChatTabStamp > 0L) {
            currentTab = BottomDestination.CHAT
            viewModel.consumeOpenChatTabStamp()
        }
    }

    LaunchedEffect(openRepairTabStamp) {
        if (openRepairTabStamp > 0L) {
            currentTab = BottomDestination.REPAIR
            viewModel.consumeOpenRepairTabStamp()
        }
    }

    LaunchedEffect(openWikiTabStamp) {
        if (openWikiTabStamp > 0L) {
            currentTab = BottomDestination.WIKI
            viewModel.consumeOpenWikiTabStamp()
        }
    }

    LaunchedEffect(openZabbixTabStamp) {
        if (openZabbixTabStamp > 0L) {
            currentTab = BottomDestination.ZABBIX
            viewModel.consumeOpenZabbixTabStamp()
        }
    }

    LaunchedEffect(openWatchlistStamp) {
        if (openWatchlistStamp > 0L) {
            toolboxInitialTab = 3
            showToolboxDialog = true
            viewModel.consumeOpenWatchlistStamp()
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == BottomDestination.REPAIR && appSettings.isConfigured) {
            viewModel.syncRepairRegistry()
        }
    }

    LaunchedEffect(latestInAppBanner?.timestampMillis) {
        if (latestInAppBanner != null) {
            delay(14_000L)
            viewModel.dismissInAppBanner()
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // If a ticket is opened (from list or from tapping the Heads-Up notification banner), show detail screen
    val activeKey = selectedTicketKey
    if (activeKey != null) {
        val activeTicket = tickets.firstOrNull { it.key.equals(activeKey, ignoreCase = true) }
        val clientDossier = activeTicket?.let {
            clientDossierNotes[it.reporterName.trim().lowercase()] ?: ""
        }.orEmpty()
        val engineerName = EngineerNameResolver.resolve(
            rawEngineer = null,
            connectedUserDisplayName = syncStatus.connectedUser,
            currentUsernameOrEmail = appSettings.usernameOrEmail,
            knownTickets = tickets
        )
        Box(modifier = Modifier.fillMaxSize()) {
            TicketDetailScreen(
                ticket = activeTicket,
                ticketKey = activeKey,
                isLoading = isLoadingDetail,
                actionState = ticketActionState,
                clientHistoryState = clientHistoryState,
                analysisLoadedTickets = tickets,
                hostDiagnosticState = hostDiagnosticState,
                appSettings = appSettings,
                engineerDisplayName = engineerName,
                clientDossierNote = clientDossier,
                assignableColleagues = assignableColleagues,
                isLoadingColleagues = isLoadingColleagues,
                onSaveClientDossier = { client, note -> viewModel.saveClientDossierNote(client, note) },
                onLoadColleagues = { viewModel.loadAssignableColleagues(activeKey) },
                onAssignColleague = { col -> viewModel.assignTicketToColleague(activeKey, col) },
                onLogWorklog = { mins, note, complete ->
                    viewModel.logTicketWorklog(activeKey, mins, note, complete)
                },
                onGenerateServiceActPdf = { input -> viewModel.generateAndAttachServiceAct(input) },
                onScanSerialHistory = { sn -> viewModel.searchByHardwareSerial(sn) },
                onSendToRepair = { t, sn -> viewModel.sendTicketToRepairIntake(t, sn) },
                stopwatchEntry = ticketStopwatches[activeKey],
                onToggleStopwatch = { viewModel.toggleTicketStopwatch(activeKey) },
                onResetStopwatch = { viewModel.resetTicketStopwatch(activeKey) },
                clientPassport = activeTicket?.let {
                    clientPassports[ClientTechPassportCodec.normalizeKey(it.reporterName)]
                },
                onSaveClientPassport = { p -> viewModel.saveClientTechPassport(p) },
                onOpenClientInWiki = { client -> viewModel.openClientInWiki(client) },
                knowledgeResults = knowledgeResults,
                isSearchingKnowledge = isSearchingKnowledge,
                onSearchKnowledge = { q -> viewModel.searchKnowledgeArchive(q, activeKey) },
                loadAttachmentBytes = { url -> viewModel.loadAttachmentBytes(url) },
                onUploadAttachmentUri = { uri, name ->
                    viewModel.uploadAttachmentFromUri(activeKey, uri, name)
                },
                onOpenExternalAttachment = { att ->
                    viewModel.openExternalAttachment(att)
                },
                onRunHostDiagnostics = { host ->
                    viewModel.runHostDiagnostics(host)
                },
                onDismissHostDiagnostics = {
                    viewModel.dismissHostDiagnostics()
                },
                onDiscussInChat = { t ->
                    viewModel.discussTicketInChat(t)
                },
                onOpenHistoricalTicket = { key ->
                    viewModel.openTicket(key)
                },
                onExecuteAction = { action, comment, transitionId ->
                    viewModel.executeTicketAction(activeKey, action, comment, transitionId)
                },
                onPostponeTicket = { epochMillis, formattedDisplay, transitionId ->
                    viewModel.postponeTicket(activeKey, epochMillis, formattedDisplay, transitionId)
                },
                onAddComment = { commentText, internalOnly ->
                    viewModel.addTicketComment(activeKey, commentText, internalOnly)
                },
                onDismissActionMessage = { viewModel.dismissTicketActionFeedback() },
                onBack = { viewModel.closeTicketDetail() }
            )

            InAppFloatingBannerOverlay(
                bannerAlert = latestInAppBanner,
                onOpenTicket = { key ->
                    AlertSoundPlayer.stopCurrent()
                    viewModel.dismissInAppBanner()
                    viewModel.openTicket(key)
                },
                onTakeInWork = { key ->
                    AlertSoundPlayer.stopCurrent()
                    viewModel.quickTakeTicketInWork(key)
                },
                onOpenZabbix = {
                    AlertSoundPlayer.stopCurrent()
                    viewModel.dismissInAppBanner()
                    viewModel.requestOpenZabbixTab()
                },
                onAckZabbix = { prob ->
                    AlertSoundPlayer.stopCurrent()
                    viewModel.acknowledgeZabbixProblem(prob)
                },
                onMuteZabbix = { prob ->
                    AlertSoundPlayer.stopCurrent()
                    viewModel.muteZabbixProblemForever(prob)
                },
                onDismiss = {
                    AlertSoundPlayer.stopCurrent()
                    viewModel.dismissInAppBanner()
                }
            )
        }
        return
    }

    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = CslOrange,
        selectedTextColor = CslOrange,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CslMidnight,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Signature csl.by 4dp orange vertical accent bar (#F7631B)
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(22.dp)
                                .background(CslOrange, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val dotColor = when {
                            !appSettings.isConfigured -> Color(0xFF97A0AF)
                            syncStatus.lastError != null -> Color(0xFFF44A40)
                            syncStatus.isServiceRunning || appSettings.pollingEnabled -> CslSuccessGreen
                            else -> Color(0xFFFFAB00)
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Help-CSL",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showKbArchiveDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "База решений",
                            tint = Color.White
                        )
                    }
                    val systemDarkNow = isSystemInDarkTheme()
                    val isCurrentlyDark = when (appSettings.themeMode) {
                        AppThemeMode.DARK -> true
                        AppThemeMode.LIGHT -> false
                        AppThemeMode.SYSTEM -> systemDarkNow
                    }
                    IconButton(
                        onClick = {
                            val nextMode = if (isCurrentlyDark) AppThemeMode.LIGHT else AppThemeMode.DARK
                            viewModel.saveAppSettings(appSettings.copy(themeMode = nextMode))
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isCurrentlyDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Переключить тему",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { showToolboxDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Handyman,
                            contentDescription = "Инструменты сисадмина",
                            tint = Color.White
                        )
                    }
                    Surface(
                        onClick = { showShiftAnalytics = true },
                        shape = RoundedCornerShape(8.dp),
                        color = CslOrange.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, CslOrange),
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = "KPI по сотрудникам",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "KPI",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    IconButton(
                        onClick = { showFullGuide = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "Полный гайд",
                            tint = Color.White
                        )
                    }
                    if (appSettings.isConfigured || appSettings.isZabbixConfigured || postponedTickets.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                when (currentTab) {
                                    BottomDestination.ZABBIX -> viewModel.refreshZabbix(manual = true)
                                    BottomDestination.REPAIR -> viewModel.syncRepairRegistry()
                                    BottomDestination.WIKI -> viewModel.refreshWikiSpacesAndPages()
                                    BottomDestination.CHAT -> viewModel.refreshAdminChat()
                                    else -> viewModel.refreshNow()
                                }
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить",
                                tint = CslOrange
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (LocalConfiguration.current.screenHeightDp < 480) {
                CompactBottomNavigation(
                    currentTab = currentTab,
                    zabbixCount = activeZabbixAlertsCount,
                    repairCount = activeWorkshopCount,
                    chatCount = unreadChatCount,
                    onSelect = { destination ->
                        currentTab = destination
                        if (destination == BottomDestination.TICKETS) resetTicketsToFreshNonce++
                    }
                )
            } else {
            val navLabelSize = with(LocalDensity.current) { 9.5.dp.toSp() }
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = NavigationBarDefaults.Elevation
            ) {
                NavigationBarItem(
                    selected = currentTab == BottomDestination.TICKETS,
                    onClick = {
                        currentTab = BottomDestination.TICKETS
                        resetTicketsToFreshNonce++
                    },
                    icon = { Icon(Icons.Default.SupportAgent, contentDescription = null) },
                    label = {
                        Text(
                            text = BottomDestination.TICKETS.label,
                            fontSize = navLabelSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.ZABBIX,
                    onClick = { currentTab = BottomDestination.ZABBIX },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (activeZabbixAlertsCount > 0) {
                                    Badge(
                                        containerColor = Color(0xFFD93025),
                                        contentColor = Color.White
                                    ) {
                                        Text(
                                            text = if (activeZabbixAlertsCount > 99) "99+" else activeZabbixAlertsCount.toString()
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null)
                        }
                    },
                    label = {
                        Text(
                            text = BottomDestination.ZABBIX.label,
                            fontSize = navLabelSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.REPAIR,
                    onClick = { currentTab = BottomDestination.REPAIR },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (activeWorkshopCount > 0) {
                                    Badge(
                                        containerColor = CslOrange,
                                        contentColor = Color.White
                                    ) {
                                        Text(
                                            text = if (activeWorkshopCount > 99) "99+" else activeWorkshopCount.toString()
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null)
                        }
                    },
                    label = {
                        Text(
                            text = BottomDestination.REPAIR.label,
                            fontSize = navLabelSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.WIKI,
                    onClick = { currentTab = BottomDestination.WIKI },
                    icon = { Icon(Icons.Default.LibraryBooks, contentDescription = null) },
                    label = {
                        Text(
                            text = BottomDestination.WIKI.label,
                            fontSize = navLabelSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.CHAT,
                    onClick = { currentTab = BottomDestination.CHAT },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (unreadChatCount > 0) {
                                    Badge(
                                        containerColor = CslOrange,
                                        contentColor = Color.White
                                    ) {
                                        Text(
                                            text = if (unreadChatCount > 99) "99+" else unreadChatCount.toString()
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Forum, contentDescription = null)
                        }
                    },
                    label = {
                        Text(
                            text = BottomDestination.CHAT.label,
                            fontSize = navLabelSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.SETTINGS,
                    onClick = { currentTab = BottomDestination.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = {
                        Text(
                            text = BottomDestination.SETTINGS.label,
                            fontSize = navLabelSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = navItemColors
                )
            }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasNotifPermission) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Разрешите уведомления для показа всплывающих баннеров заявок",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        ) {
                            Text("Включить")
                        }
                    }
                }
            }

            if (appUpdateState.availableUpdate != null && !appUpdateState.bannerDismissed) {
                AppUpdateTopBanner(
                    state = appUpdateState,
                    onDownloadAndInstall = { viewModel.downloadAndInstallAppUpdate() },
                    onInstallReady = { viewModel.installReadyUpdateApk() },
                    onDismiss = { viewModel.dismissAppUpdateBanner() }
                )
            }

            when (currentTab) {
                BottomDestination.TICKETS -> TicketsScreen(
                    tickets = tickets,
                    postponedTickets = postponedTickets,
                    offlineQueuedActions = offlineQueuedActions,
                    appSettings = appSettings,
                    filterSettings = filterSettings,
                    syncStatus = syncStatus,
                    isCreatingTicket = isCreatingTicket,
                    isBatchBusy = isBatchBusy,
                    resetToFreshHomeNonce = resetTicketsToFreshNonce,
                    externalSearchFilter = externalSearchFilter,
                    onConsumeExternalSearch = { externalSearchFilter = "" },
                    onScanSerialHistory = { sn -> viewModel.searchByHardwareSerial(sn) },
                    onBatchAction = { keys, act, comment, done ->
                        viewModel.performBatchTicketsAction(keys, act, comment, done)
                    },
                    onTicketClick = { key -> viewModel.openTicket(key) },
                    onQuickTakeInWork = { key -> viewModel.quickTakeTicketInWork(key) },
                    onDiscussTicketInChat = { t -> viewModel.discussTicketInChat(t) },
                    onCreateTicket = { proj, type, sum, desc, selfAssign ->
                        viewModel.createNewTicket(proj, type, sum, desc, selfAssign) { newKey ->
                            viewModel.openTicket(newKey)
                        }
                    },
                    onFlushOfflineQueue = { viewModel.flushOfflineQueueNow() },
                    onCancelOfflineAction = { id -> viewModel.cancelOfflineAction(id) },
                    onCancelPostponed = { id -> viewModel.cancelPostponedTicket(id) },
                    onRefresh = { viewModel.refreshNow() },
                    onOpenSettings = { currentTab = BottomDestination.SETTINGS },
                    modifier = Modifier.weight(1f)
                )

                BottomDestination.ZABBIX -> ZabbixMonitorScreen(
                    settings = appSettings,
                    snapshot = zabbixSnapshot,
                    dismissedKeys = dismissedZabbixKeys,
                    mutedRules = mutedZabbixRules,
                    lastError = zabbixLastError,
                    statusFeedback = zabbixStatusFeedback,
                    isLoading = isLoadingZabbix,
                    hostDiagnosticState = hostDiagnosticState,
                    onSaveAndConnect = { url, login, pwd, enabled, minSev ->
                        viewModel.saveAndConnectZabbix(url, login, pwd, enabled, minSev)
                    },
                    onRefresh = { viewModel.refreshZabbix(manual = true) },
                    onAcknowledge = { prob, comment -> viewModel.acknowledgeZabbixProblem(prob, comment) },
                    onDismissProblem = { prob -> viewModel.dismissZabbixProblem(prob) },
                    onDismissAllCurrent = { list -> viewModel.dismissAllCurrentZabbixProblems(list) },
                    onRestoreProblem = { prob -> viewModel.restoreDismissedZabbixProblem(prob) },
                    onRestoreAllDismissed = { viewModel.restoreAllDismissedZabbixProblems() },
                    onMuteForever = { prob -> viewModel.muteZabbixProblemForever(prob) },
                    onUnmuteRule = { key -> viewModel.unmuteZabbixRule(key) },
                    onClearAllMuted = { viewModel.clearAllMutedZabbixRules() },
                    onTestZabbixBanner = {
                        hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                        viewModel.triggerTestZabbixBanner()
                    },
                    onRunHostDiagnostics = { host -> viewModel.runHostDiagnostics(host) },
                    onDismissHostDiagnostics = { viewModel.dismissHostDiagnostics() },
                    modifier = Modifier.weight(1f)
                )

                BottomDestination.REPAIR -> {
                    val engineerName = EngineerNameResolver.resolve(
                        rawEngineer = null,
                        connectedUserDisplayName = syncStatus.connectedUser,
                        currentUsernameOrEmail = appSettings.usernameOrEmail,
                        knownTickets = tickets
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        RepairRegistryScreen(
                            items = repairItems,
                            clientDirectory = clientDirectory,
                            isLoadingClientDirectory = isLoadingClientDirectory,
                            appSettings = appSettings,
                            engineerDisplayName = engineerName,
                            isSyncing = isSyncingRepair,
                            syncErrorMessage = repairSyncError,
                            prefillDraftFromTicket = pendingRepairDraft,
                            onConsumePrefillDraft = { viewModel.consumePendingRepairDraft() },
                            onSaveItem = { item, postNote ->
                                viewModel.saveRepairEquipmentItem(item, postNote)
                            },
                            onDeleteItem = { item, reason ->
                                viewModel.deleteRepairEquipmentItem(item, reason)
                            },
                            onRestoreItem = { item ->
                                viewModel.restoreRepairEquipmentItem(item)
                            },
                            onSyncNow = { viewModel.syncRepairRegistry() },
                            onRefreshClients = { viewModel.refreshClientsDirectory(manual = true) },
                            onOpenLinkedTicket = { key -> viewModel.openTicket(key) },
                            onAttachPdfToTicket = { key, bytes, name, note ->
                                viewModel.attachRepairPdfToTicket(key, bytes, name, note)
                            },
                            onPrintQrSticker = { item -> viewModel.shareRepairQrStickerPdf(item) },
                            onPostReadyToTicket = { item -> viewModel.postRepairReadyToLinkedTicket(item) },
                            onExportCsv = { viewModel.shareRepairRegistryCsv() }
                        )
                    }
                }

                BottomDestination.WIKI -> Box(modifier = Modifier.weight(1f)) {
                    ConfluenceWikiScreen(
                        wikiState = wikiUiState,
                        settings = appSettings,
                        viewModel = viewModel
                    )
                }

                BottomDestination.CHAT -> AdminChatScreen(
                    messages = chatMessages,
                    chatUiState = adminChatUiState,
                    resolvedRoomKey = resolvedChatRoomKey,
                    pinnedShiftNote = pinnedShiftNote,
                    appSettings = appSettings,
                    activeTickets = tickets,
                    onChatVisibilityChanged = { visible -> viewModel.setChatTabActive(visible) },
                    onRefresh = { viewModel.refreshAdminChat() },
                    onDraftChange = { txt -> viewModel.updateChatDraft(txt) },
                    onSelectReply = { msg -> viewModel.setChatReplyTarget(msg) },
                    onSendMessage = { viewModel.sendAdminChatMessage() },
                    onDeleteMessage = { id -> viewModel.deleteAdminChatMessage(id) },
                    onSavePinnedNote = { note -> viewModel.savePinnedShiftNote(note) },
                    onSaveRoomSettings = { key, notif -> viewModel.saveChatRoomSettings(key, notif) },
                    onOpenTicket = { key -> viewModel.openTicket(key) },
                    onOpenServerSettings = { currentTab = BottomDestination.SETTINGS },
                    onSendAttachmentUri = { uri -> viewModel.sendChatAttachmentFromUri(uri) },
                    loadAttachmentBytes = { url -> viewModel.loadAttachmentBytes(url) },
                    connectedUserDisplayName = syncStatus.connectedUser,
                    modifier = Modifier.weight(1f)
                )

                BottomDestination.SETTINGS -> Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = settingsSubTab == 0,
                                onClick = { settingsSubTab = 0 },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("Серверы и уведомления", maxLines = 1) }
                            )
                            FilterChip(
                                selected = settingsSubTab == 1,
                                onClick = { settingsSubTab = 1 },
                                leadingIcon = {
                                    Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("Фильтр заявок", maxLines = 1) }
                            )
                        }
                    }

                    if (settingsSubTab == 1) {
                        FilterSettingsScreen(
                            currentFilter = filterSettings,
                            onSaveFilter = { updated -> viewModel.saveFilterSettings(updated) },
                            onSendTestBanner = {
                                hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                                viewModel.triggerTestBannerNotification()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ServerSettingsScreen(
                            currentSettings = appSettings,
                            connectionTest = connectionTest,
                            appUpdateState = appUpdateState,
                            isCleaningSystemRooms = isCleaningSystemRooms,
                            systemRoomsCleanupMessage = systemRoomsCleanupMessage,
                            onCleanupSystemRooms = { viewModel.cleanupSystemRoomsInJira(manual = true) },
                            onTestConnection = { candidate -> viewModel.testConnection(candidate) },
                            onSaveSettings = { updated -> viewModel.saveAppSettings(updated) },
                            onTriggerTestBanner = {
                                hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                                viewModel.triggerTestBannerNotification()
                            },
                            onOpenGuide = { showFullGuide = true },
                            onOpenQrTransfer = { showQrTransfer = true },
                            onCheckForUpdate = { viewModel.checkForAppUpdate(manual = true) },
                            onDownloadAndInstallUpdate = { viewModel.downloadAndInstallAppUpdate() },
                            onInstallReadyUpdate = { viewModel.installReadyUpdateApk() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    InAppFloatingBannerOverlay(
        bannerAlert = latestInAppBanner,
        onOpenTicket = { key ->
            AlertSoundPlayer.stopCurrent()
            viewModel.dismissInAppBanner()
            viewModel.openTicket(key)
        },
        onTakeInWork = { key ->
            AlertSoundPlayer.stopCurrent()
            viewModel.quickTakeTicketInWork(key)
        },
        onOpenZabbix = {
            AlertSoundPlayer.stopCurrent()
            viewModel.dismissInAppBanner()
            currentTab = BottomDestination.ZABBIX
        },
        onAckZabbix = { prob ->
            AlertSoundPlayer.stopCurrent()
            viewModel.acknowledgeZabbixProblem(prob)
        },
        onMuteZabbix = { prob ->
            AlertSoundPlayer.stopCurrent()
            viewModel.muteZabbixProblemForever(prob)
        },
        onDismiss = {
            AlertSoundPlayer.stopCurrent()
            viewModel.dismissInAppBanner()
        }
    )
    }
}

@Composable
private fun InAppFloatingBannerOverlay(
    bannerAlert: InAppBannerAlert?,
    onOpenTicket: (String) -> Unit,
    onTakeInWork: (String) -> Unit,
    onOpenZabbix: () -> Unit,
    onAckZabbix: (ZabbixProblem) -> Unit,
    onMuteZabbix: (ZabbixProblem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = bannerAlert != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        val alert = bannerAlert ?: return@AnimatedVisibility
        val zbxProblem = alert.zabbixProblem
        val ticket = alert.ticket
        val phone = ticket.phoneNumbers.firstOrNull()
        val accentBorder = if (zbxProblem != null && zbxProblem.severityLevel >= 4) {
            Color(0xFFD93025)
        } else {
            CslOrange
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (zbxProblem != null) onOpenZabbix() else onOpenTicket(ticket.key)
                },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CslMidnight),
            border = BorderStroke(2.dp, accentBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (zbxProblem != null) Icons.Default.Dns else Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = accentBorder,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (zbxProblem != null) {
                            "🚨 Zabbix · ${zbxProblem.effectiveHostLabel} · ${alert.titleSuffix}"
                        } else {
                            "${ticket.reporterName.ifBlank { "Клиент" }} · ${alert.titleSuffix}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentBorder,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть баннер",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = ticket.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Text(
                    text = if (zbxProblem != null) {
                        "Узел: ${zbxProblem.hostWithIpLabel} · ${zbxProblem.severity.ruTitle}"
                    } else {
                        "№ ${ticket.key}" + (phone?.let { " · ${it.formattedDisplay}" } ?: "")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD0D5DD)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (zbxProblem != null) {
                        if (zbxProblem.eventId.isNotBlank() && zbxProblem.eventId != "0") {
                            Button(
                                onClick = { onAckZabbix(zbxProblem) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CslSuccessGreen,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TaskAlt,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Подтв.", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                        Button(
                            onClick = { onMuteZabbix(zbxProblem) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF475569),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Не решаема", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = onOpenZabbix,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentBorder,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Открыть", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, softWrap = false)
                        }
                    } else {
                        Button(
                            onClick = { onTakeInWork(ticket.key) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CslSuccessGreen,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.AssignmentInd,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("В работу", color = Color.White, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = { onOpenTicket(ticket.key) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CslOrange,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Открыть", color = Color.White, maxLines = 1, softWrap = false)
                        }

                        if (phone != null) {
                            Button(
                                onClick = {
                                    AlertSoundPlayer.stopCurrent()
                                    DialerLauncher.openDialer(
                                        context = context,
                                        phoneInput = phone.e164,
                                        ticketKey = ticket.key,
                                        clientName = ticket.reporterName
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1D4ED8),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Позвонить",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUpdateTopBanner(
    state: AppUpdateUiState,
    onDownloadAndInstall: () -> Unit,
    onInstallReady: () -> Unit,
    onDismiss: () -> Unit
) {
    val update = state.availableUpdate ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CslMidnight),
        border = BorderStroke(1.dp, CslOrange)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = CslOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Новая версия Help-CSL v${update.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.statusMessage ?: "Доступно обновление на GitHub",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD0D5E2),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!state.isDownloading) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (state.readyApkPath != null) {
                                onInstallReady()
                            } else {
                                onDownloadAndInstall()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CslOrange,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (state.readyApkPath != null) "Установить" else "Обновить",
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Скрыть",
                            tint = Color(0xFFB0B8CC),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (state.isDownloading) {
                val progress = if (state.totalBytes > 0L) {
                    (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = CslOrange
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CslOrange
                    )
                }
            }
        }
    }
}


