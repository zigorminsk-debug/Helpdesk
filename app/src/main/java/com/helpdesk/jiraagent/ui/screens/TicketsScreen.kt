package com.helpdesk.jiraagent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.util.DialerLauncher
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TicketTabMode {
    CLIENT_ONLY,
    WITH_PHONE,
    FILTERED_SERVICE
}

@OptIn(ExperimentalLayoutApi::class)
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
    var selectedMode by rememberSaveable { mutableStateOf(TicketTabMode.CLIENT_ONLY) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val clientTickets = remember(tickets) { tickets.filter { it.isClientTicket } }
    val withPhoneTickets = remember(clientTickets) { clientTickets.filter { it.phoneNumbers.isNotEmpty() } }
    val serviceTickets = remember(tickets) { tickets.filter { !it.isClientTicket } }

    val baseList = when (selectedMode) {
        TicketTabMode.CLIENT_ONLY -> clientTickets
        TicketTabMode.WITH_PHONE -> withPhoneTickets
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
                    }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (syncStatus.isSyncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!appSettings.isConfigured) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Сервер Jira ещё не настроен",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Укажите адрес сервера Jira и учётные данные во вкладке «Настройки», чтобы агент начал принимать заявки клиентов и выводить баннеры уведомлений.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onGoToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть настройки")
                    }
                }
            }
        }

        if (syncStatus.lastError != null && appSettings.isConfigured) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text("Поиск по номеру, теме, клиенту или телефону") },
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

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedMode == TicketTabMode.CLIENT_ONLY,
                onClick = { selectedMode = TicketTabMode.CLIENT_ONLY },
                label = { Text("Клиентские (${clientTickets.size})") }
            )
            FilterChip(
                selected = selectedMode == TicketTabMode.WITH_PHONE,
                onClick = { selectedMode = TicketTabMode.WITH_PHONE },
                label = { Text("С телефоном (${withPhoneTickets.size})") }
            )
            FilterChip(
                selected = selectedMode == TicketTabMode.FILTERED_SERVICE,
                onClick = { selectedMode = TicketTabMode.FILTERED_SERVICE },
                label = { Text("Сервисные скрытые (${serviceTickets.size})") }
            )
        }

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
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
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when (selectedMode) {
                            TicketTabMode.CLIENT_ONLY -> "Клиентских заявок пока нет"
                            TicketTabMode.WITH_PHONE -> "Заявок с номером телефона не найдено"
                            TicketTabMode.FILTERED_SERVICE -> "Отсеянных сервисных заявок нет"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketListCard(
    ticket: HelpdeskTicket,
    onOpen: () -> Unit,
    onCallPhone: (String) -> Unit
) {
    val formattedDate = remember(ticket.createdEpochMillis, ticket.createdIso) {
        formatTicketTime(ticket.createdEpochMillis, ticket.createdIso)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = ticket.key,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ticket.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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

            if (ticket.filterReason != null) {
                Spacer(modifier = Modifier.height(8.dp))
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

            if (ticket.phoneNumbers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val primary = ticket.phoneNumbers.first()
                AssistChip(
                    onClick = { onCallPhone(primary.e164) },
                    label = {
                        Text(
                            text = primary.formattedDisplay,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Позвонить",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
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
