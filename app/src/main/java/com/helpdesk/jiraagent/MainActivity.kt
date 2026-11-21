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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.ui.MainViewModel
import com.helpdesk.jiraagent.ui.screens.FilterSettingsScreen
import com.helpdesk.jiraagent.ui.screens.ServerSettingsScreen
import com.helpdesk.jiraagent.ui.screens.TicketDetailScreen
import com.helpdesk.jiraagent.ui.screens.TicketsScreen
import com.helpdesk.jiraagent.ui.theme.JiraHelpdeskTheme

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
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val selectedTicketKey by viewModel.selectedTicketKey.collectAsStateWithLifecycle()
    val isLoadingDetail by viewModel.isLoadingTicketDetail.collectAsStateWithLifecycle()
    val ticketActionState by viewModel.ticketActionState.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()

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
        TicketDetailScreen(
            ticket = activeTicket,
            ticketKey = activeKey,
            isLoading = isLoadingDetail,
            actionState = ticketActionState,
            appSettings = appSettings,
            onExecuteAction = { action, comment, transitionId ->
                viewModel.executeTicketAction(activeKey, action, comment, transitionId)
            },
            onDismissActionMessage = { viewModel.dismissTicketActionFeedback() },
            onBack = { viewModel.closeTicketDetail() }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = when {
                            !appSettings.isConfigured -> Color(0xFF97A0AF)
                            syncStatus.lastError != null -> MaterialTheme.colorScheme.error
                            syncStatus.isServiceRunning || appSettings.pollingEnabled -> Color(0xFF1B873F)
                            else -> Color(0xFFFFAB00)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Jira Helpdesk Агент",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (appSettings.isConfigured) {
                        IconButton(onClick = { viewModel.refreshNow() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == BottomDestination.TICKETS,
                    onClick = { currentTab = BottomDestination.TICKETS },
                    icon = { Icon(Icons.Default.SupportAgent, contentDescription = null) },
                    label = { Text(BottomDestination.TICKETS.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.FILTER,
                    onClick = { currentTab = BottomDestination.FILTER },
                    icon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                    label = { Text(BottomDestination.FILTER.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomDestination.SETTINGS,
                    onClick = { currentTab = BottomDestination.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(BottomDestination.SETTINGS.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                    appSettings = appSettings,
                    syncStatus = syncStatus,
                    onTicketClick = { key -> viewModel.openTicket(key) },
                    onGoToSettings = { currentTab = BottomDestination.SETTINGS },
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
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
