package com.helpdesk.jiraagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.SlaCalculator
import com.helpdesk.jiraagent.model.TicketTimingFormatter
import com.helpdesk.jiraagent.domain.SlaUrgency
import com.helpdesk.jiraagent.domain.TicketAgeFilter
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.domain.TicketFreshnessClassifier
import com.helpdesk.jiraagent.domain.TicketHangingSeverity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.ui.components.CreateTicketDialog
import com.helpdesk.jiraagent.ui.components.MessengerActionChip
import com.helpdesk.jiraagent.ui.components.MessengerCircleIconButton
import com.helpdesk.jiraagent.ui.components.SysadminEmptyStatePanel
import com.helpdesk.jiraagent.ui.components.rememberSafeBarcodeScanner
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslInfoBlue
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.util.DialerLauncher
import com.helpdesk.jiraagent.util.MessengerApp
import com.helpdesk.jiraagent.util.MessengerLauncher
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TicketTabMode {
    NEW,
    IN_PROGRESS,
    CLOSED,
    FILTERED_SERVICE
}

private enum class AssigneeScopeFilter {
    ALL,
    MINE,
    UNASSIGNED
}

private data class TabItemSpec(
    val mode: TicketTabMode,
    val title: String,
    val count: Int,
    val icon: ImageVector,
    val freshSubLabel: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen(
    tickets: List<HelpdeskTicket>,
    postponedTickets: List<PostponedTicket>,
    offlineQueuedActions: List<OfflineQueuedAction>,
    appSettings: AppSettings,
    filterSettings: FilterSettings,
    syncStatus: SyncStatus,
    isCreatingTicket: Boolean,
    isBatchBusy: Boolean = false,
    resetToFreshHomeNonce: Long = 0L,
    externalSearchFilter: String = "",
    onConsumeExternalSearch: () -> Unit = {},
    onScanSerialHistory: (String) -> Unit = {},
    onBatchAction: (Set<String>, TicketWorkflowAction?, String?, () -> Unit) -> Unit = { _, _, _, done -> done() },
    onTicketClick: (String) -> Unit,
    onQuickTakeInWork: (String) -> Unit,
    onDiscussTicketInChat: (HelpdeskTicket) -> Unit,
    onCreateTicket: (String, String, String, String, Boolean) -> Unit,
    onFlushOfflineQueue: () -> Unit,
    onCancelOfflineAction: (String) -> Unit,
    onCancelPostponed: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var tabMode by rememberSaveable { mutableStateOf(TicketTabMode.NEW) }
    var assigneeScope by rememberSaveable { mutableStateOf(AssigneeScopeFilter.ALL) }
    var ageFilter by rememberSaveable { mutableStateOf(TicketAgeFilter.FRESH_TODAY) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var showBatchCommentDialog by rememberSaveable { mutableStateOf(false) }
    var batchCommentDraft by rememberSaveable { mutableStateOf("") }
    var handledResetNonce by rememberSaveable { mutableLongStateOf(resetToFreshHomeNonce) }

    LaunchedEffect(resetToFreshHomeNonce) {
        if (resetToFreshHomeNonce != handledResetNonce) {
            handledResetNonce = resetToFreshHomeNonce
            multiSelectMode = false
            selectedKeys = emptySet()
            tabMode = TicketTabMode.NEW
            ageFilter = TicketAgeFilter.FRESH_TODAY
            assigneeScope = AssigneeScopeFilter.ALL
            searchQuery = ""
        }
    }

    val isAtFreshHome = !multiSelectMode &&
        searchQuery.isEmpty() &&
        tabMode == TicketTabMode.NEW &&
        ageFilter == TicketAgeFilter.FRESH_TODAY &&
        assigneeScope == AssigneeScopeFilter.ALL

    BackHandler(enabled = !isAtFreshHome) {
        if (multiSelectMode) {
            multiSelectMode = false
            selectedKeys = emptySet()
        } else {
            tabMode = TicketTabMode.NEW
            ageFilter = TicketAgeFilter.FRESH_TODAY
            assigneeScope = AssigneeScopeFilter.ALL
            searchQuery = ""
        }
    }

    LaunchedEffect(externalSearchFilter) {
        if (externalSearchFilter.isNotBlank()) {
            searchQuery = externalSearchFilter
            onConsumeExternalSearch()
        }
    }

    val launchSerialScanner = rememberSafeBarcodeScanner(
        prompt = "Наведите камеру на S/N или QR оборудования для поиска ремонтов в Jira"
    ) { scanned ->
        onScanSerialHistory(scanned)
    }

    if (showBatchCommentDialog) {
        AlertDialog(
            onDismissRequest = { showBatchCommentDialog = false },
            title = { Text("Комментарий для ${selectedKeys.size} заявок", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = batchCommentDraft,
                    onValueChange = { batchCommentDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Общий комментарий клиентам") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchCommentDialog = false
                        onBatchAction(selectedKeys, null, batchCommentDraft) {
                            selectedKeys = emptySet()
                            multiSelectMode = false
                            batchCommentDraft = ""
                        }
                    },
                    enabled = batchCommentDraft.isNotBlank() && !isBatchBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) { Text("Отправить всем") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchCommentDialog = false }) { Text("Отмена") }
            }
        )
    }

    val defaultProjectKey = remember(filterSettings.projectKeys, tickets) {
        TicketFilterEngine.splitCsv(filterSettings.projectKeys).firstOrNull()?.uppercase()
            ?: tickets.firstOrNull { it.isClientTicket }?.projectKey?.uppercase()
            ?: ""
    }

    if (showCreateDialog) {
        CreateTicketDialog(
            defaultProjectKey = defaultProjectKey,
            isCreating = isCreatingTicket,
            onDismiss = { showCreateDialog = false },
            onCreate = { proj, type, sum, desc, selfAssign ->
                showCreateDialog = false
                onCreateTicket(proj, type, sum, desc, selfAssign)
            }
        )
    }

    val clientTickets = remember(tickets) { tickets.filter { it.isClientTicket } }
    val newTickets = remember(clientTickets) { clientTickets.filter { it.stage == TicketStage.NEW } }
    val inProgressTickets = remember(clientTickets) { clientTickets.filter { it.stage == TicketStage.IN_PROGRESS } }
    val closedTickets = remember(clientTickets) { clientTickets.filter { it.stage == TicketStage.CLOSED } }
    val serviceFilteredTickets = remember(tickets) { tickets.filter { !it.isClientTicket } }

    val nowMillis = remember(tickets, syncStatus.lastSyncEpochMillis) { System.currentTimeMillis() }
    val (newFreshAll, newPastAll) = remember(newTickets, nowMillis) {
        TicketFreshnessClassifier.partitionFreshAndPast(newTickets, nowMillis)
    }
    val (inProgressFreshAll, inProgressPastAll) = remember(inProgressTickets, nowMillis) {
        TicketFreshnessClassifier.partitionFreshAndPast(inProgressTickets, nowMillis)
    }

    val baseList = when (tabMode) {
        TicketTabMode.NEW -> newTickets
        TicketTabMode.IN_PROGRESS -> inProgressTickets
        TicketTabMode.CLOSED -> closedTickets
        TicketTabMode.FILTERED_SERVICE -> serviceFilteredTickets
    }

    val mineCountInTab = remember(baseList, syncStatus.connectedUser, appSettings.usernameOrEmail) {
        baseList.count { it.isAssignedToAgent(syncStatus.connectedUser, appSettings.usernameOrEmail) }
    }
    val freeCountInTab = remember(baseList) {
        baseList.count { it.assigneeName.isNullOrBlank() }
    }

    val scopeFilteredList = remember(baseList, assigneeScope, syncStatus.connectedUser, appSettings.usernameOrEmail) {
        when (assigneeScope) {
            AssigneeScopeFilter.ALL -> baseList
            AssigneeScopeFilter.MINE -> baseList.filter {
                it.isAssignedToAgent(syncStatus.connectedUser, appSettings.usernameOrEmail)
            }
            AssigneeScopeFilter.UNASSIGNED -> baseList.filter { it.assigneeName.isNullOrBlank() }
        }
    }

    val searchFilteredTickets = remember(scopeFilteredList, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            scopeFilteredList
        } else {
            scopeFilteredList.filter { t ->
                t.key.lowercase().contains(q) ||
                    t.summary.lowercase().contains(q) ||
                    t.reporterName.lowercase().contains(q) ||
                    t.status.lowercase().contains(q) ||
                    (t.customerRequestType?.lowercase()?.contains(q) == true) ||
                    (t.assigneeName?.lowercase()?.contains(q) == true) ||
                    t.description.lowercase().contains(q) ||
                    t.phoneNumbers.any { it.e164.contains(q) || it.formattedDisplay.lowercase().contains(q) }
            }
        }
    }

    val isFreshPastStage = tabMode == TicketTabMode.NEW || tabMode == TicketTabMode.IN_PROGRESS
    val (freshDisplayed, pastDisplayed) = remember(searchFilteredTickets, isFreshPastStage, nowMillis) {
        if (isFreshPastStage) {
            TicketFreshnessClassifier.partitionFreshAndPast(searchFilteredTickets, nowMillis)
        } else {
            emptyList<HelpdeskTicket>() to emptyList()
        }
    }

    val displayedTickets = remember(searchFilteredTickets, isFreshPastStage, ageFilter, freshDisplayed, pastDisplayed) {
        if (!isFreshPastStage) {
            searchFilteredTickets
        } else {
            when (ageFilter) {
                TicketAgeFilter.ALL -> freshDisplayed + pastDisplayed
                TicketAgeFilter.FRESH_TODAY -> freshDisplayed
                TicketAgeFilter.PAST_HANGING -> pastDisplayed
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (syncStatus.isSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CslOrange
                )
            }

            if (!appSettings.isConfigured) {
                UnconfiguredBanner(onOpenSettings = onOpenSettings)
            } else {
                syncStatus.lastError?.let { err ->
                    ErrorBanner(errorMessage = err, onRetry = onRefresh)
                }
            }

            // Concise single-line search field styled in csl.by palette
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = {
                    Text(
                        text = "Поиск заявки…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = CslOrange
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(
                            onClick = { launchSerialScanner() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Сканер S/N в Jira",
                                tint = CslOrange,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                multiSelectMode = !multiSelectMode
                                if (!multiSelectMode) selectedKeys = emptySet()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Checklist,
                                contentDescription = "Массовый выбор",
                                tint = if (multiSelectMode) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CslOrange,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            val tabSpecs = remember(
                newTickets.size,
                inProgressTickets.size,
                closedTickets.size,
                serviceFilteredTickets.size
            ) {
                listOf(
                    TabItemSpec(
                        mode = TicketTabMode.NEW,
                        title = "Новые",
                        count = newTickets.size,
                        icon = Icons.Default.FiberNew
                    ),
                    TabItemSpec(
                        mode = TicketTabMode.IN_PROGRESS,
                        title = "В работе",
                        count = inProgressTickets.size,
                        icon = Icons.Default.Build
                    ),
                    TabItemSpec(TicketTabMode.CLOSED, "Закрытые", closedTickets.size, Icons.Default.CheckCircleOutline),
                    TabItemSpec(TicketTabMode.FILTERED_SERVICE, "Сервисные", serviceFilteredTickets.size, Icons.Default.SmartToy)
                )
            }

            AdaptiveFourTabsBar(
                tabs = tabSpecs,
                selectedMode = tabMode,
                onSelect = { tabMode = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            if (isFreshPastStage) {
                FreshAndPastFilterRow(
                    selected = ageFilter,
                    allCount = searchFilteredTickets.size,
                    freshCount = freshDisplayed.size,
                    pastCount = pastDisplayed.size,
                    onSelect = { ageFilter = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            AssigneeScopeRow(
                selected = assigneeScope,
                allCount = baseList.size,
                mineCount = mineCountInTab,
                freeCount = freeCountInTab,
                onSelect = { assigneeScope = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )

            if (multiSelectMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = CslMidnight,
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Выбрано: ${selectedKeys.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = CslOrange,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            onClick = {
                                if (selectedKeys.isNotEmpty() && !isBatchBusy) {
                                    onBatchAction(selectedKeys, TicketWorkflowAction.TAKE_IN_WORK, null) {
                                        selectedKeys = emptySet()
                                        multiSelectMode = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(7.dp),
                            color = CslOrange
                        ) {
                            Text(
                                text = "В работу",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                        Surface(
                            onClick = {
                                if (selectedKeys.isNotEmpty() && !isBatchBusy) {
                                    showBatchCommentDialog = true
                                }
                            },
                            shape = RoundedCornerShape(7.dp),
                            color = Color.White.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "Коммент.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                        Surface(
                            onClick = {
                                if (selectedKeys.isNotEmpty() && !isBatchBusy) {
                                    onBatchAction(selectedKeys, TicketWorkflowAction.COMPLETE, null) {
                                        selectedKeys = emptySet()
                                        multiSelectMode = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(7.dp),
                            color = CslSuccessGreen
                        ) {
                            Text(
                                text = "Закрыть",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                selectedKeys = emptySet()
                                multiSelectMode = false
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Отмена", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = syncStatus.isSyncing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                val hasActiveFilters = searchQuery.isNotBlank() ||
                    assigneeScope != AssigneeScopeFilter.ALL ||
                    (isFreshPastStage && ageFilter != TicketAgeFilter.ALL)

                @Composable
                fun RenderTicketItemRow(ticket: HelpdeskTicket) {
                    val isSelectedInBatch = ticket.key in selectedKeys
                    if (multiSelectMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    selectedKeys = if (isSelectedInBatch) {
                                        selectedKeys - ticket.key
                                    } else {
                                        selectedKeys + ticket.key
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSelectedInBatch) {
                                        Icons.Default.CheckBox
                                    } else {
                                        Icons.Default.CheckBoxOutlineBlank
                                    },
                                    contentDescription = null,
                                    tint = if (isSelectedInBatch) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TicketListCard(
                                ticket = ticket,
                                appSettings = appSettings,
                                nowMillis = nowMillis,
                                onClick = {
                                    selectedKeys = if (isSelectedInBatch) {
                                        selectedKeys - ticket.key
                                    } else {
                                        selectedKeys + ticket.key
                                    }
                                }
                            )
                        }
                    } else {
                        SwipeableTicketListItem(
                            ticket = ticket,
                            appSettings = appSettings,
                            nowMillis = nowMillis,
                            onClick = { onTicketClick(ticket.key) },
                            onSwipeTakeInWork = { onQuickTakeInWork(ticket.key) },
                            onSwipeLeftAction = {
                                val primaryPhone = ticket.phoneNumbers.firstOrNull()
                                if (primaryPhone != null) {
                                    DialerLauncher.openSystemDialer(
                                        context = context,
                                        phoneInput = primaryPhone.e164,
                                        ticketKey = ticket.key,
                                        clientName = ticket.reporterName
                                    )
                                } else {
                                    onDiscussTicketInChat(ticket)
                                }
                            }
                        )
                    }
                }

                if (displayedTickets.isEmpty() && postponedTickets.isEmpty() && offlineQueuedActions.isEmpty()) {
                    EmptyTicketsPlaceholder(
                        appSettings = appSettings,
                        isConfigured = appSettings.isConfigured,
                        tabMode = tabMode,
                        ageFilter = if (isFreshPastStage) ageFilter else TicketAgeFilter.ALL,
                        hasSearch = searchQuery.isNotBlank() || assigneeScope != AssigneeScopeFilter.ALL,
                        onRefresh = onRefresh
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 76.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (offlineQueuedActions.isNotEmpty()) {
                            item(key = "offline_queue_card") {
                                OfflineActionsQueueCard(
                                    items = offlineQueuedActions,
                                    onFlushNow = onFlushOfflineQueue,
                                    onCancel = onCancelOfflineAction
                                )
                            }
                        }

                        if (postponedTickets.isNotEmpty()) {
                            item(key = "postponed_summary_card") {
                                PostponedTicketsQueueCard(
                                    items = postponedTickets,
                                    onCancel = onCancelPostponed
                                )
                            }
                        }

                        if (displayedTickets.isEmpty()) {
                            if (isFreshPastStage && ageFilter == TicketAgeFilter.FRESH_TODAY && pastDisplayed.isNotEmpty()) {
                                item(key = "past_available_hint") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { ageFilter = TicketAgeFilter.PAST_HANGING }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE65100))
                                        )
                                        Text(
                                            text = "В прошлых заявках: ${pastDisplayed.size}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            thickness = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                        Text(
                                            text = "Показать →",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE65100)
                                        )
                                    }
                                }
                            }
                            item(key = "empty_tickets_gif") {
                                EmptyTicketsPlaceholder(
                                    appSettings = appSettings,
                                    isConfigured = appSettings.isConfigured,
                                    tabMode = tabMode,
                                    ageFilter = if (isFreshPastStage) ageFilter else TicketAgeFilter.ALL,
                                    hasSearch = searchQuery.isNotBlank() || assigneeScope != AssigneeScopeFilter.ALL,
                                    onRefresh = onRefresh,
                                    scrollable = false
                                )
                            }
                        } else if (isFreshPastStage) {
                            when (ageFilter) {
                                TicketAgeFilter.ALL -> {
                                    if (freshDisplayed.isNotEmpty()) {
                                        item(key = "section_header_fresh") {
                                            TicketAgeSectionHeader(
                                                isFresh = true,
                                                count = freshDisplayed.size,
                                                rightHint = "за сегодня"
                                            )
                                        }
                                        items(freshDisplayed, key = { it.key }) { ticket ->
                                            RenderTicketItemRow(ticket)
                                        }
                                    }

                                    if (pastDisplayed.isNotEmpty()) {
                                        item(key = "section_header_past") {
                                            TicketAgeSectionHeader(
                                                isFresh = false,
                                                count = pastDisplayed.size,
                                                rightHint = if (freshDisplayed.isEmpty()) "сегодня: 0" else "с прошлых дней"
                                            )
                                        }
                                        items(pastDisplayed, key = { it.key }) { ticket ->
                                            RenderTicketItemRow(ticket)
                                        }
                                    }
                                }
                                TicketAgeFilter.FRESH_TODAY -> {
                                    item(key = "section_header_fresh_only") {
                                        TicketAgeSectionHeader(
                                            isFresh = true,
                                            count = freshDisplayed.size,
                                            rightHint = "поступили сегодня"
                                        )
                                    }
                                    items(freshDisplayed, key = { it.key }) { ticket ->
                                        RenderTicketItemRow(ticket)
                                    }
                                }
                                TicketAgeFilter.PAST_HANGING -> {
                                    item(key = "section_header_past_only") {
                                        TicketAgeSectionHeader(
                                            isFresh = false,
                                            count = pastDisplayed.size,
                                            rightHint = "с прошлых дней"
                                        )
                                    }
                                    items(pastDisplayed, key = { it.key }) { ticket ->
                                        RenderTicketItemRow(ticket)
                                    }
                                }
                            }
                        } else {
                            items(displayedTickets, key = { it.key }) { ticket ->
                                RenderTicketItemRow(ticket)
                            }
                        }
                    }
                }
            }
        }

        if (appSettings.isConfigured) {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = CslOrange,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Новая заявка"
                )
            }
        }
    }
}

@Composable
private fun FreshAndPastFilterRow(
    selected: TicketAgeFilter,
    allCount: Int,
    freshCount: Int,
    pastCount: Int,
    onSelect: (TicketAgeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val pillFontSize = with(density) { 11.5.dp.toSp() }

    data class AgeOption(
        val filter: TicketAgeFilter,
        val label: String,
        val accent: Color,
        val weight: Float
    )
    val options = listOf(
        AgeOption(TicketAgeFilter.ALL, "Все ($allCount)", CslOrange, 0.74f),
        AgeOption(TicketAgeFilter.FRESH_TODAY, "Свежие ($freshCount)", CslSuccessGreen, 1.11f),
        AgeOption(TicketAgeFilter.PAST_HANGING, "Прошлые ($pastCount)", Color(0xFFE65100), 1.15f)
    )
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        options.forEach { opt ->
            val isSelected = selected == opt.filter
            Surface(
                onClick = { onSelect(opt.filter) },
                modifier = Modifier
                    .weight(opt.weight)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) opt.accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) opt.accent else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 5.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (opt.filter) {
                        TicketAgeFilter.FRESH_TODAY -> {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(CslSuccessGreen, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        TicketAgeFilter.PAST_HANGING -> {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = opt.accent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        TicketAgeFilter.ALL -> Unit
                    }
                    Text(
                        text = opt.label,
                        fontSize = pillFontSize,
                        lineHeight = pillFontSize,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketAgeSectionHeader(
    isFresh: Boolean,
    count: Int,
    rightHint: String
) {
    val accent = if (isFresh) CslSuccessGreen else Color(0xFFE65100)
    val title = if (isFresh) {
        "Свежие заявки ($count)"
    } else {
        "Прошлые заявки ($count)"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            softWrap = false
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        if (rightHint.isNotBlank()) {
            Text(
                text = rightHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun AssigneeScopeRow(
    selected: AssigneeScopeFilter,
    allCount: Int,
    mineCount: Int,
    freeCount: Int,
    onSelect: (AssigneeScopeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val pillFontSize = with(density) { 11.5.dp.toSp() }

    data class ScopeOption(
        val mode: AssigneeScopeFilter,
        val label: String,
        val weight: Float
    )
    val options = listOf(
        ScopeOption(AssigneeScopeFilter.ALL, "Все ($allCount)", 0.78f),
        ScopeOption(AssigneeScopeFilter.MINE, "Мои ($mineCount)", 0.82f),
        ScopeOption(AssigneeScopeFilter.UNASSIGNED, "Свободные ($freeCount)", 1.40f)
    )
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        options.forEach { opt ->
            val isSelected = selected == opt.mode
            Surface(
                onClick = { onSelect(opt.mode) },
                modifier = Modifier
                    .weight(opt.weight)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) CslMidnight else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) CslOrange else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 5.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = opt.label,
                        fontSize = pillFontSize,
                        lineHeight = pillFontSize,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineActionsQueueCard(
    items: List<OfflineQueuedAction>,
    onFlushNow: () -> Unit,
    onCancel: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, CslOrange)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Офлайн-очередь (${items.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = onFlushNow,
                    shape = RoundedCornerShape(6.dp),
                    color = CslOrange
                ) {
                    Text(
                        text = "Отправить",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.summaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onCancel(item.id) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Удалить из очереди",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTicketListItem(
    ticket: HelpdeskTicket,
    appSettings: AppSettings? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onClick: () -> Unit,
    onSwipeTakeInWork: () -> Unit,
    onSwipeLeftAction: () -> Unit
) {
    val hasPhone = ticket.phoneNumbers.isNotEmpty()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeTakeInWork()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeLeftAction()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val bgColor = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> CslSuccessGreen.copy(alpha = 0.88f)
                SwipeToDismissBoxValue.EndToStart -> CslMidnight.copy(alpha = 0.92f)
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp)
            ) {
                if (dir == SwipeToDismissBoxValue.StartToEnd) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AssignmentInd, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "В работу",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else if (dir == SwipeToDismissBoxValue.EndToStart) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hasPhone) "Позвонить" else "В чат",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (hasPhone) Icons.Default.Call else Icons.Default.Forum,
                            contentDescription = null,
                            tint = CslOrange
                        )
                    }
                }
            }
        }
    ) {
        TicketListCard(
            ticket = ticket,
            appSettings = appSettings,
            nowMillis = nowMillis,
            onClick = onClick
        )
    }
}

@Composable
private fun PostponedTicketsQueueCard(
    items: List<PostponedTicket>,
    onCancel: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Отложенные к пересозданию (${items.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            items.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.originalKey} → ${item.scheduledDisplay}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = CslOrange
                            )
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            item.lastError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(
                            onClick = { onCancel(item.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Отменить пересоздание",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveFourTabsBar(
    tabs: List<TabItemSpec>,
    selectedMode: TicketTabMode,
    onSelect: (TicketTabMode) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val useTwoRows = maxWidth < 310.dp
        if (useTwoRows) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.take(2).forEach { tab ->
                        CompactTabSegment(
                            tab = tab,
                            selected = selectedMode == tab.mode,
                            onClick = { onSelect(tab.mode) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.drop(2).forEach { tab ->
                        CompactTabSegment(
                            tab = tab,
                            selected = selectedMode == tab.mode,
                            onClick = { onSelect(tab.mode) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEach { tab ->
                    CompactTabSegment(
                        tab = tab,
                        selected = selectedMode == tab.mode,
                        onClick = { onSelect(tab.mode) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactTabSegment(
    tab: TabItemSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val countTextSize = with(density) { 12.dp.toSp() }
    val titleTextSize = with(density) { 11.dp.toSp() }
    val subTextSize = with(density) { 8.8.dp.toSp() }

    val containerColor = if (selected) {
        CslOrange
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        CslOrange
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(width = if (selected) 1.5.dp else 1.dp, color = borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = tab.count.toString(),
                    fontSize = countTextSize,
                    lineHeight = countTextSize,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Text(
                text = tab.title,
                fontSize = titleTextSize,
                lineHeight = titleTextSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (tab.freshSubLabel != null) {
                Text(
                    text = tab.freshSubLabel,
                    fontSize = subTextSize,
                    lineHeight = subTextSize,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketListCard(
    ticket: HelpdeskTicket,
    appSettings: AppSettings? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val formattedTime = remember(ticket.createdEpochMillis) {
        formatShortTime(ticket.createdEpochMillis)
    }
    val stage = ticket.stage
    val freshnessInfo = remember(ticket.key, ticket.createdEpochMillis, stage, ticket.isClientTicket, nowMillis) {
        if (ticket.isClientTicket && (stage == TicketStage.NEW || stage == TicketStage.IN_PROGRESS)) {
            TicketFreshnessClassifier.classify(ticket, appSettings, nowMillis)
        } else null
    }

    val leftStripeColor: Color = when {
        !ticket.isClientTicket -> MaterialTheme.colorScheme.outline
        freshnessInfo?.severity == TicketHangingSeverity.PAST_LONG -> CslDangerRed
        stage == TicketStage.NEW -> CslOrange
        stage == TicketStage.IN_PROGRESS -> CslInfoBlue
        else -> CslSuccessGreen
    }

    val statusBadgeBg = when {
        !ticket.isClientTicket -> MaterialTheme.colorScheme.surfaceVariant
        stage == TicketStage.NEW -> MaterialTheme.colorScheme.primaryContainer
        stage == TicketStage.IN_PROGRESS -> CslInfoBlue.copy(alpha = 0.14f)
        else -> CslSuccessGreen.copy(alpha = 0.14f)
    }
    val statusBadgeFg = when {
        !ticket.isClientTicket -> MaterialTheme.colorScheme.onSurfaceVariant
        stage == TicketStage.NEW -> CslOrange
        stage == TicketStage.IN_PROGRESS -> CslInfoBlue
        else -> CslSuccessGreen
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .drawBehind {
                    drawRect(
                        color = leftStripeColor,
                        size = Size(width = 4.dp.toPx(), height = size.height)
                    )
                }
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. Header row: Prominent Client Name on top-left + Created Time on top-right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        modifier = Modifier.weight(1f, fill = false),
                        color = CslMidnight,
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = CslOrange
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = ticket.reporterName.ifBlank { "Клиент" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formattedTime,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 2. Full Status & Request Type row (wraps cleanly, NO ellipsis cutoff!)
                val typeText = ticket.customerRequestType?.takeIf { it.isNotBlank() } ?: ticket.issueType
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = statusBadgeBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = ticket.status,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusBadgeFg
                        )
                    }

                    if (typeText.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = typeText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (freshnessInfo != null) {
                        val badgeFontSize = with(LocalDensity.current) { 11.5.dp.toSp() }
                        val (freshBg, freshFg, freshIcon) = when (freshnessInfo.severity) {
                            TicketHangingSeverity.FRESH_TODAY -> Triple(
                                CslSuccessGreen.copy(alpha = 0.15f),
                                CslSuccessGreen,
                                "●"
                            )
                            TicketHangingSeverity.PAST_RECENT -> Triple(
                                Color(0xFFE65100).copy(alpha = 0.16f),
                                Color(0xFFE65100),
                                "⏳"
                            )
                            TicketHangingSeverity.PAST_LONG -> Triple(
                                CslDangerRed.copy(alpha = 0.16f),
                                CslDangerRed,
                                "🔥"
                            )
                        }
                        Surface(
                            color = freshBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "$freshIcon ${freshnessInfo.fullBadgeLabel}",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                fontSize = badgeFontSize,
                                lineHeight = badgeFontSize,
                                fontWeight = FontWeight.Bold,
                                color = freshFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (ticket.isClientTicket && stage == TicketStage.NEW) {
                        val slaBadge = SlaCalculator.computeWaitBadge(ticket.createdEpochMillis)
                        if (slaBadge != null) {
                            val (slaBg, slaFg) = when (slaBadge.urgency) {
                                SlaUrgency.FRESH -> CslSuccessGreen.copy(alpha = 0.14f) to CslSuccessGreen
                                SlaUrgency.WARNING -> CslOrange.copy(alpha = 0.16f) to CslOrange
                                SlaUrgency.OVERDUE -> CslDangerRed.copy(alpha = 0.16f) to CslDangerRed
                            }
                            Surface(
                                color = slaBg,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = slaBadge.label,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = slaFg,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // 3. Full Summary (wraps naturally inside card so multi-line titles are readable)
                Text(
                    text = ticket.summary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 4. Ticket Key moved down in smaller font (+ Assignee if present)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "№ ${ticket.key}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!ticket.assigneeName.isNullOrBlank()) {
                        Text(
                            text = "• Исполнитель: ${ticket.assigneeName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CslInfoBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    val attCount = ticket.attachments?.size ?: 0
                    if (attCount > 0) {
                        Text(
                            text = "• 📎 $attCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = CslOrange,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 4b. Arrival & Closure Timestamps fixed on every ticket card
                if (ticket.createdEpochMillis > 0L || ticket.effectiveClosedEpochMillis > 0L) {
                    Text(
                        text = TicketTimingFormatter.buildCardTimingSummary(ticket, nowMillis),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (stage == TicketStage.CLOSED) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (stage == TicketStage.CLOSED) CslSuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!ticket.isClientTicket && ticket.filterReason != null) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.FilterAltOff,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = ticket.filterReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // 5. Clickable Phone Numbers + Messenger Icons (Telegram / Viber / WhatsApp)
                if (ticket.phoneNumbers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ticket.phoneNumbers.take(3).forEach { phone ->
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AssistChip(
                                    onClick = {
                                        DialerLauncher.openSystemDialer(
                                            context = context,
                                            phoneInput = phone.e164,
                                            ticketKey = ticket.key,
                                            clientName = ticket.reporterName
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = phone.formattedDisplay,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = "Позвонить",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                ) {
                                    MessengerCircleIconButton(
                                        app = MessengerApp.TELEGRAM,
                                        size = 34.dp,
                                        onClick = { MessengerLauncher.openTelegramByPhone(context, phone.e164) }
                                    )
                                    MessengerCircleIconButton(
                                        app = MessengerApp.VIBER,
                                        size = 34.dp,
                                        onClick = { MessengerLauncher.openViberByPhone(context, phone.e164) }
                                    )
                                    MessengerCircleIconButton(
                                        app = MessengerApp.WHATSAPP,
                                        size = 34.dp,
                                        onClick = { MessengerLauncher.openWhatsAppByPhone(context, phone.e164) }
                                    )
                                }
                            }
                        }
                    }
                } else if (!ticket.telegramHandles.isNullOrEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ticket.telegramHandles.take(2).forEach { handle ->
                            MessengerActionChip(
                                app = MessengerApp.TELEGRAM,
                                customLabel = handle,
                                onClick = { MessengerLauncher.openTelegramByUsername(context, handle) }
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun UnconfiguredBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Подключите сервер Jira в Help-CSL",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Укажите адрес сервера Jira, логин и пароль или токен во вкладке «Настройки», чтобы получать заявки в реальном времени.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Открыть настройки")
            }
        }
    }
}

@Composable
private fun ErrorBanner(errorMessage: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRetry) {
                Text("Повтор", maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyTicketsPlaceholder(
    appSettings: AppSettings,
    isConfigured: Boolean,
    tabMode: TicketTabMode,
    ageFilter: TicketAgeFilter = TicketAgeFilter.ALL,
    hasSearch: Boolean,
    onRefresh: () -> Unit,
    scrollable: Boolean = true
) {
    val emptyMessage = when {
        hasSearch -> "По вашему поисковому запросу заявки не найдены"
        !isConfigured -> "Настройте подключение к Jira или отправьте тестовый баннер из раздела «Настройки»"
        (tabMode == TicketTabMode.NEW || tabMode == TicketTabMode.IN_PROGRESS) && ageFilter == TicketAgeFilter.FRESH_TODAY ->
            "Сегодня свежих заявок пока не поступало"
        (tabMode == TicketTabMode.NEW || tabMode == TicketTabMode.IN_PROGRESS) && ageFilter == TicketAgeFilter.PAST_HANGING ->
            "Зависших заявок с прошлых дней нет"
        tabMode == TicketTabMode.NEW -> "Новых необработанных заявок сейчас нет"
        tabMode == TicketTabMode.IN_PROGRESS -> "Заявок со статусом «В работе» сейчас нет"
        tabMode == TicketTabMode.CLOSED -> "Закрытых заявок клиентов в выборке пока нет"
        else -> "Отфильтрованных сервисных заявок (zabbix, боты) пока нет"
    }

    SysadminEmptyStatePanel(
        emptyStatusText = emptyMessage,
        showRefreshButton = isConfigured,
        onRefreshNow = onRefresh,
        settings = appSettings,
        scrollable = scrollable
    )
}

private val cardDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun formatShortTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(cardDateTimeFormatter)
    }.getOrDefault("")
}
