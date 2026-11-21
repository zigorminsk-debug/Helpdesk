package com.helpdesk.jiraagent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.ui.components.MessengerActionChip
import com.helpdesk.jiraagent.ui.components.MessengerCircleIconButton
import com.helpdesk.jiraagent.ui.components.SysadminEmptyStatePanel
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

private data class TabItemSpec(
    val mode: TicketTabMode,
    val title: String,
    val count: Int,
    val icon: ImageVector
)

@Composable
fun TicketsScreen(
    tickets: List<HelpdeskTicket>,
    postponedTickets: List<PostponedTicket>,
    appSettings: AppSettings,
    syncStatus: SyncStatus,
    onTicketClick: (String) -> Unit,
    onCancelPostponed: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tabMode by rememberSaveable { mutableStateOf(TicketTabMode.NEW) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val clientTickets = remember(tickets) { tickets.filter { it.isClientTicket } }
    val newTickets = remember(clientTickets) { clientTickets.filter { it.stage == TicketStage.NEW } }
    val inProgressTickets = remember(clientTickets) { clientTickets.filter { it.stage == TicketStage.IN_PROGRESS } }
    val closedTickets = remember(clientTickets) { clientTickets.filter { it.stage == TicketStage.CLOSED } }
    val serviceFilteredTickets = remember(tickets) { tickets.filter { !it.isClientTicket } }

    val baseList = when (tabMode) {
        TicketTabMode.NEW -> newTickets
        TicketTabMode.IN_PROGRESS -> inProgressTickets
        TicketTabMode.CLOSED -> closedTickets
        TicketTabMode.FILTERED_SERVICE -> serviceFilteredTickets
    }

    val displayedTickets = remember(baseList, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            baseList
        } else {
            baseList.filter { t ->
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

    Column(modifier = modifier.fillMaxSize()) {
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
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
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
                TabItemSpec(TicketTabMode.NEW, "Новые", newTickets.size, Icons.Default.FiberNew),
                TabItemSpec(TicketTabMode.IN_PROGRESS, "В работе", inProgressTickets.size, Icons.Default.Build),
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

        if (displayedTickets.isEmpty() && postponedTickets.isEmpty()) {
            EmptyTicketsPlaceholder(
                isConfigured = appSettings.isConfigured,
                tabMode = tabMode,
                hasSearch = searchQuery.isNotBlank(),
                onRefresh = onRefresh
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (postponedTickets.isNotEmpty()) {
                    item(key = "postponed_summary_card") {
                        PostponedTicketsQueueCard(
                            items = postponedTickets,
                            onCancel = onCancelPostponed
                        )
                    }
                }

                if (displayedTickets.isEmpty()) {
                    item(key = "empty_tickets_gif") {
                        EmptyTicketsPlaceholder(
                            isConfigured = appSettings.isConfigured,
                            tabMode = tabMode,
                            hasSearch = searchQuery.isNotBlank(),
                            onRefresh = onRefresh,
                            scrollable = false
                        )
                    }
                } else {
                    items(displayedTickets, key = { it.key }) { ticket ->
                        TicketListCard(
                            ticket = ticket,
                            onClick = { onTicketClick(ticket.key) }
                        )
                    }
                }
            }
        }
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.take(2).forEach { tab ->
                        CompactTabSegment(
                            tab = tab,
                            selected = selectedMode == tab.mode,
                            onClick = { onSelect(tab.mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.drop(2).forEach { tab ->
                        CompactTabSegment(
                            tab = tab,
                            selected = selectedMode == tab.mode,
                            onClick = { onSelect(tab.mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEach { tab ->
                    CompactTabSegment(
                        tab = tab,
                        selected = selectedMode == tab.mode,
                        onClick = { onSelect(tab.mode) },
                        modifier = Modifier.weight(1f)
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

    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        CslOrange
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
        border = BorderStroke(width = if (selected) 1.5.dp else 1.dp, color = borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
                    color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
                    maxLines = 1
                )
            }
            Text(
                text = tab.title,
                fontSize = titleTextSize,
                lineHeight = titleTextSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketListCard(
    ticket: HelpdeskTicket,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val formattedTime = remember(ticket.createdEpochMillis) {
        formatShortTime(ticket.createdEpochMillis)
    }
    val stage = ticket.stage

    val leftStripeColor: Color = when {
        !ticket.isClientTicket -> MaterialTheme.colorScheme.outline
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
                                    onClick = { DialerLauncher.openDialer(context, phone.e164) },
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
    isConfigured: Boolean,
    tabMode: TicketTabMode,
    hasSearch: Boolean,
    onRefresh: () -> Unit,
    scrollable: Boolean = true
) {
    val emptyMessage = when {
        hasSearch -> "По вашему поисковому запросу заявки не найдены"
        !isConfigured -> "Настройте подключение к Jira или отправьте тестовый баннер из раздела «Настройки»"
        tabMode == TicketTabMode.NEW -> "Новых необработанных заявок сейчас нет"
        tabMode == TicketTabMode.IN_PROGRESS -> "Заявок со статусом «В работе» сейчас нет"
        tabMode == TicketTabMode.CLOSED -> "Закрытых заявок клиентов в выборке пока нет"
        else -> "Отфильтрованных сервисных заявок (zabbix, боты) пока нет"
    }

    SysadminEmptyStatePanel(
        emptyStatusText = emptyMessage,
        showRefreshButton = isConfigured,
        onRefreshNow = onRefresh,
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
