package com.helpdesk.jiraagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helpdesk.jiraagent.data.InAppBannerAlert
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.ui.AppUpdateUiState
import com.helpdesk.jiraagent.ui.MainViewModel
import com.helpdesk.jiraagent.ui.screens.AdminChatScreen
import com.helpdesk.jiraagent.ui.screens.FilterSettingsScreen
import com.helpdesk.jiraagent.ui.screens.ServerSettingsScreen
import com.helpdesk.jiraagent.ui.screens.TicketDetailScreen
import com.helpdesk.jiraagent.ui.screens.TicketsScreen
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.ui.theme.JiraHelpdeskTheme
import com.helpdesk.jiraagent.util.DialerLauncher
import kotlinx.coroutines.delay

private enum class BottomDestination(val label: String) {
    TICKETS("Заявки"),
    CHAT("Чат"),
    FILTER("Фильтр"),
    SETTINGS("Настройки")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

        setContent {
            JiraHelpdeskTheme {
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
        if (!ticketKey.isNullOrBlank()) {
            viewModel.openTicket(ticketKey)
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY)
        }
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_TRIGGER_APP_UPDATE, false) == true) {
            intent.removeExtra(NotificationHelper.EXTRA_TRIGGER_APP_UPDATE)
            viewModel.checkForAppUpdate(manual = true, autoStartDownload = true)
        }
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_ADMIN_CHAT, false) == true) {
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_ADMIN_CHAT)
            viewModel.requestOpenAdminChatTab()
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

    var currentTab by rememberSaveable { mutableStateOf(BottomDestination.TICKETS) }
    var hasNotifPermission by rememberSaveable {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }

    LaunchedEffect(returnToMainScreenStamp) {
        if (returnToMainScreenStamp > 0L) {
            currentTab = BottomDestination.TICKETS
        }
    }

    LaunchedEffect(openChatTabStamp) {
        if (openChatTabStamp > 0L) {
            currentTab = BottomDestination.CHAT
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
        Box(modifier = Modifier.fillMaxSize()) {
            TicketDetailScreen(
                ticket = activeTicket,
                ticketKey = activeKey,
                isLoading = isLoadingDetail,
                actionState = ticketActionState,
                clientHistoryState = clientHistoryState,
                hostDiagnosticState = hostDiagnosticState,
                appSettings = appSettings,
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
                onAddComment = { commentText ->
                    viewModel.addTicketComment(activeKey, commentText)
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
                        Spacer(modifier = Modifier.width(8.dp))
                        val dotColor = when {
                            !appSettings.isConfigured -> Color(0xFF97A0AF)
                            syncStatus.lastError != null -> Color(0xFFF44A40)
                            syncStatus.isServiceRunning || appSettings.pollingEnabled -> CslSuccessGreen
                            else -> Color(0xFFFFAB00)
                        }
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Help-CSL",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (appSettings.isConfigured || postponedTickets.isNotEmpty()) {
                        IconButton(onClick = { viewModel.refreshNow() }) {
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
            val navLabelSize = with(LocalDensity.current) { 11.dp.toSp() }
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = NavigationBarDefaults.Elevation
            ) {
                NavigationBarItem(
                    selected = currentTab == BottomDestination.TICKETS,
                    onClick = { currentTab = BottomDestination.TICKETS },
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
                    selected = currentTab == BottomDestination.FILTER,
                    onClick = { currentTab = BottomDestination.FILTER },
                    icon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                    label = {
                        Text(
                            text = BottomDestination.FILTER.label,
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
                    modifier = Modifier.weight(1f)
                )

                BottomDestination.FILTER -> FilterSettingsScreen(
                    currentFilter = filterSettings,
                    onSaveFilter = { updated -> viewModel.saveFilterSettings(updated) },
                    onSendTestBanner = {
                        hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                        viewModel.triggerTestBannerNotification()
                    },
                    modifier = Modifier.weight(1f)
                )

                BottomDestination.SETTINGS -> ServerSettingsScreen(
                    currentSettings = appSettings,
                    connectionTest = connectionTest,
                    appUpdateState = appUpdateState,
                    onTestConnection = { candidate -> viewModel.testConnection(candidate) },
                    onSaveSettings = { updated -> viewModel.saveAppSettings(updated) },
                    onTriggerTestBanner = {
                        hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                        viewModel.triggerTestBannerNotification()
                    },
                    onCheckForUpdate = { viewModel.checkForAppUpdate(manual = true) },
                    onDownloadAndInstallUpdate = { viewModel.downloadAndInstallAppUpdate() },
                    onInstallReadyUpdate = { viewModel.installReadyUpdateApk() },
                    modifier = Modifier.weight(1f)
                )
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
        val ticket = alert.ticket
        val phone = ticket.phoneNumbers.firstOrNull()

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenTicket(ticket.key) },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CslMidnight),
            border = BorderStroke(2.dp, CslOrange),
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
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = CslOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${ticket.reporterName.ifBlank { "Клиент" }} · ${alert.titleSuffix}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = CslOrange,
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
                    text = "№ ${ticket.key}" +
                        (phone?.let { " · ${it.formattedDisplay}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD0D5DD)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onTakeInWork(ticket.key) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AssignmentInd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("В работу", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = { onOpenTicket(ticket.key) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                    ) {
                        Text("Открыть", maxLines = 1, softWrap = false)
                    }

                    if (phone != null) {
                        Button(
                            onClick = {
                                AlertSoundPlayer.stopCurrent()
                                DialerLauncher.openDialer(context, phone.e164)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Позвонить",
                                modifier = Modifier.size(16.dp)
                            )
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


