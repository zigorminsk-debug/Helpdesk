package com.helpdesk.jiraagent.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.ui.components.MessengerActionChip
import com.helpdesk.jiraagent.ui.components.MessengerCircleIconButton
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
    appSettings: AppSettings,
    syncStatus: SyncStatus,
    onTicketClick: (String) -> Unit,
    onGoToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedMode by rememberSaveable { mutableStateOf(TicketTabMode.NEW) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val newTickets = remember(tickets) {
        tickets
            .filter { it.isClientTicket && it.stage == TicketStage.NEW }
            .sortedByDescending { it.createdEpochMillis }
    }
    val inProgressTickets = remember(tickets) {
        tickets
            .filter { it.isClientTicket && it.stage == TicketStage.IN_PROGRESS }
            .sortedByDescending { maxOf(it.updatedEpochMillis, it.createdEpochMillis) }
    }
    val closedTickets = remember(tickets) {
        tickets
            .filter { it.isClientTicket && it.stage == TicketStage.CLOSED }
            .sortedByDescending { maxOf(it.updatedEpochMillis, it.createdEpochMillis) }
    }
    val serviceTickets = remember(tickets) {
        tickets
            .filter { !it.isClientTicket }
            .sortedByDescending { it.createdEpochMillis }
    }

    val baseList = when (selectedMode) {
        TicketTabMode.NEW -> newTickets
        TicketTabMode.IN_PROGRESS -> inProgressTickets
        TicketTabMode.CLOSED -> closedTickets
        TicketTabMode.FILTERED_SERVICE -> serviceTickets
    }

    val filteredList = remember(baseList, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            baseList
        } else {
            baseList.filter { t ->
                t.key.lowercase().contains(q) ||
                    t.summary.lowercase().contains(q) ||
                    t.reporterName.lowercase().contains(q) ||
                    t.phoneNumbers.any { p ->
                        p.e164.contains(q) || p.formattedDisplay.lowercase().contains(q) || p.raw.contains(q)
                    } ||
                    t.telegramHandles.orEmpty().any { it.lowercase().contains(q) }
            }
        }
    }

    val tabs = remember(newTickets.size, inProgressTickets.size, closedTickets.size, serviceTickets.size) {
        listOf(
            TabItemSpec(TicketTabMode.NEW, "Новые", newTickets.size, Icons.Default.FiberNew),
            TabItemSpec(TicketTabMode.IN_PROGRESS, "В работе", inProgressTickets.size, Icons.Default.Build),
            TabItemSpec(TicketTabMode.CLOSED, "Закрытые", closedTickets.size, Icons.Default.CheckCircleOutline),
            TabItemSpec(TicketTabMode.FILTERED_SERVICE, "Сервисные", serviceTickets.size, Icons.Default.SmartToy)
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (syncStatus.isSyncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!appSettings.isConfigured) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Сервер Jira ещё не настроен",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Укажите адрес сервера Jira и учётные данные во вкладке «Настройки».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = onGoToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть настройки", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (syncStatus.lastError != null && appSettings.isConfigured) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = syncStatus.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = {
                Text(
                    text = "Поиск: номер, тема, клиент, телефон",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        // Adaptive 4-tab selector: fits 100% within any screen width & system font scale without cutoff
        AdaptiveTicketTabsBar(
            tabs = tabs,
            selectedMode = selectedMode,
            onSelectMode = { selectedMode = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (selectedMode == TicketTabMode.FILTERED_SERVICE) {
                            Icons.Default.FilterAltOff
                        } else {
                            Icons.Default.Inbox
                        },
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = when (selectedMode) {
                            TicketTabMode.NEW -> "Новых заявок пока нет"
                            TicketTabMode.IN_PROGRESS -> "Заявок в работе пока нет"
                            TicketTabMode.CLOSED -> "Закрытых заявок не найдено"
                            TicketTabMode.FILTERED_SERVICE -> "Отсеянных сервисных заявок нет"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredList, key = { it.key }) { ticket ->
                    TicketListCard(
                        ticket = ticket,
                        onOpen = { onTicketClick(ticket.key) },
                        onCallPhone = { e164 ->
                            DialerLauncher.openSystemDialer(
                                context = context,
                                phoneInput = e164,
                                region = appSettings.phoneRegion,
                                customCountryCode = appSettings.customCountryCode
                            )
                        },
                        onOpenMessenger = { app, e164 ->
                            MessengerLauncher.openChatByPhone(context, app, e164)
                        },
                        onOpenTelegramHandle = { handle ->
                            MessengerLauncher.openTelegramUsername(context, handle)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AdaptiveTicketTabsBar(
    tabs: List<TabItemSpec>,
    selectedMode: TicketTabMode,
    onSelectMode: (TicketTabMode) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= 300.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                tabs.forEach { tab ->
                    CompactSegmentTab(
                        spec = tab,
                        selected = selectedMode == tab.mode,
                        onClick = { onSelectMode(tab.mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            // Ultra-narrow screen fallback: clean 2x2 grid
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    tabs.take(2).forEach { tab ->
                        CompactSegmentTab(
                            spec = tab,
                            selected = selectedMode == tab.mode,
                            onClick = { onSelectMode(tab.mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    tabs.drop(2).forEach { tab ->
                        CompactSegmentTab(
                            spec = tab,
                            selected = selectedMode == tab.mode,
                            onClick = { onSelectMode(tab.mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSegmentTab(
    spec: TabItemSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // Density-bounded font sizes guarantee all 4 tabs & icons stay completely inside screen bounds
    // even when system accessibility fontScale is set to 130–150%.
    val countFontSize = with(density) { 13.dp.toSp() }
    val labelFontSize = with(density) { 10.5.dp.toSp() }

    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = spec.count.toString(),
                    fontSize = countFontSize,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = spec.title,
                fontSize = labelFontSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
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
    onOpen: () -> Unit,
    onCallPhone: (String) -> Unit,
    onOpenMessenger: (MessengerApp, String) -> Unit,
    onOpenTelegramHandle: (String) -> Unit
) {
    val displayMillis = if (ticket.stage == TicketStage.CLOSED && ticket.updatedEpochMillis > 0L) {
        ticket.updatedEpochMillis
    } else {
        ticket.createdEpochMillis
    }
    val displayIso = if (ticket.stage == TicketStage.CLOSED && ticket.updatedIso.isNotBlank()) {
        ticket.updatedIso
    } else {
        ticket.createdIso
    }
    val formattedDate = remember(displayMillis, displayIso) {
        formatTicketTime(displayMillis, displayIso)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = ticket.key,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ticket.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = ticket.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${ticket.reporterName} · ${ticket.customerRequestType ?: ticket.issueType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!ticket.assigneeName.isNullOrBlank() && ticket.stage != TicketStage.NEW) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Исполнитель: ${ticket.assigneeName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (ticket.filterReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Без уведомления: ${ticket.filterReason}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val tgHandles = ticket.telegramHandles.orEmpty()
            if (ticket.phoneNumbers.isNotEmpty() || tgHandles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (ticket.phoneNumbers.isNotEmpty()) {
                        val primary = ticket.phoneNumbers.first()
                        AssistChip(
                            onClick = { onCallPhone(primary.e164) },
                            label = {
                                Text(
                                    text = primary.formattedDisplay,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Call,
                                    contentDescription = "Позвонить",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            MessengerCircleIconButton(
                                app = MessengerApp.TELEGRAM,
                                onClick = { onOpenMessenger(MessengerApp.TELEGRAM, primary.e164) }
                            )
                            MessengerCircleIconButton(
                                app = MessengerApp.VIBER,
                                onClick = { onOpenMessenger(MessengerApp.VIBER, primary.e164) }
                            )
                            MessengerCircleIconButton(
                                app = MessengerApp.WHATSAPP,
                                onClick = { onOpenMessenger(MessengerApp.WHATSAPP, primary.e164) }
                            )
                        }
                    }

                    tgHandles.forEach { handle ->
                        MessengerActionChip(
                            app = MessengerApp.TELEGRAM,
                            customLabel = handle,
                            onClick = { onOpenTelegramHandle(handle) }
                        )
                    }
                }
            }
        }
    }
}

private val CARD_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm")

fun formatTicketTime(epochMillis: Long, rawIso: String): String {
    if (epochMillis > 0L) {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(CARD_DATE_FORMATTER)
    }
    return rawIso.take(16).replace('T', ' ')
}
