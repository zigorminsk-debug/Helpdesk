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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helpdesk.jiraagent.data.InAppBannerAlert
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.ui.MainViewModel
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

    private fun handleNotificationIntent(intent: Intent?) {
        val ticketKey = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY)
        if (!ticketKey.isNullOrBlank()) {
            viewModel.openTicket(ticketKey)
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY)
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
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val selectedTicketKey by viewModel.selectedTicketKey.collectAsStateWithLifecycle()
    val isLoadingDetail by viewModel.isLoadingTicketDetail.collectAsStateWithLifecycle()
    val ticketActionState by viewModel.ticketActionState.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val latestInAppBanner by viewModel.latestInAppBanner.collectAsStateWithLifecycle()

    LaunchedEffect(latestInAppBanner?.timestampMillis) {
        if (latestInAppBanner != null) {
            delay(14_000L)
            viewModel.dismissInAppBanner()
        }
    }

    var currentTab by rememberSaveable { mutableStateOf(BottomDestination.TICKETS) }
    var hasNotifPermission by rememberSaveable {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
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
                appSettings = appSettings,
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
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = NavigationBarDefaults.Elevation
            ) {
                NavigationBarItem(
                    selected = currentTab == BottomDestination.TICKETS,
                    onClick = { currentTab = BottomDestination.TICKETS },
                    icon = { Icon(Icons.Default.SupportAgent, contentDescription = null) },
                    label = { Text(BottomDestination.TICKETS.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.FILTER,
                    onClick = { currentTab = BottomDestination.FILTER },
                    icon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                    label = { Text(BottomDestination.FILTER.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = navItemColors
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.SETTINGS,
                    onClick = { currentTab = BottomDestination.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(BottomDestination.SETTINGS.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

            when (currentTab) {
                BottomDestination.TICKETS -> TicketsScreen(
                    tickets = tickets,
                    postponedTickets = postponedTickets,
                    appSettings = appSettings,
                    syncStatus = syncStatus,
                    onTicketClick = { key -> viewModel.openTicket(key) },
                    onCancelPostponed = { id -> viewModel.cancelPostponedTicket(id) },
                    onRefresh = { viewModel.refreshNow() },
                    onOpenSettings = { currentTab = BottomDestination.SETTINGS },
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
                    onTestConnection = { candidate -> viewModel.testConnection(candidate) },
                    onSaveSettings = { updated -> viewModel.saveAppSettings(updated) },
                    onTriggerTestBanner = {
                        hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                        viewModel.triggerTestBannerNotification()
                    },
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onOpenTicket(ticket.key) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                    ) {
                        Text("Открыть заявку")
                    }

                    if (phone != null) {
                        Button(
                            onClick = {
                                AlertSoundPlayer.stopCurrent()
                                DialerLauncher.openDialer(context, phone.e164)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Позвонить")
                        }
                    }
                }
            }
        }
    }
}

