package com.helpdesk.jiraagent.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.FilterSettings

@Composable
fun FilterSettingsScreen(
    currentFilter: FilterSettings,
    onSaveFilter: (FilterSettings) -> Unit,
    onSendTestBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(currentFilter) }

    LaunchedEffect(currentFilter) {
        draft = currentFilter
    }

    val generatedJql = remember(draft) { TicketFilterEngine.buildActiveJql(draft) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Test Banner Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Проверка всплывающего баннера",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Отправляет тестовую клиентскую заявку с номером 8 (029) 654-32-10 → +375 29 654-32-10 во всплывающий баннер на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onSendTestBanner) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Показать тестовый баннер")
                }
            }
        }

        // Main Filter Rules Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Отбор только клиентских заявок",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                SwitchRow(
                    title = "Фильтровать сервисные заявки",
                    subtitle = "В уведомления попадают только обращения клиентов; сервисные, внутренние и автозадачи игнорируются",
                    checked = draft.clientOnlyFilterEnabled,
                    onCheckedChange = { draft = draft.copy(clientOnlyFilterEnabled = it) }
                )

                SwitchRow(
                    title = "Игнорировать подзадачи (Sub-task)",
                    subtitle = "Не присылать баннеры по техническим подзадачам",
                    checked = draft.excludeSubtasks,
                    onCheckedChange = { draft = draft.copy(excludeSubtasks = it) }
                )

                SwitchRow(
                    title = "Только с клиентского портала JSM",
                    subtitle = "Включайте, если клиенты создают обращения исключительно через портал Jira Service Management",
                    checked = draft.onlyCustomerPortalRequests,
                    onCheckedChange = { draft = draft.copy(onlyCustomerPortalRequests = it) }
                )

                SwitchRow(
                    title = "Игнорировать собственные заявки",
                    subtitle = "Не уведомлять о заявках, созданных под вашим логином агента",
                    checked = draft.ignoreOwnTickets,
                    onCheckedChange = { draft = draft.copy(ignoreOwnTickets = it) }
                )
            }
        }

        // Detailed Criteria Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Правила классификации (через запятую)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = draft.projectKeys,
                    onValueChange = { draft = draft.copy(projectKeys = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ключи проектов Helpdesk (необязательно)") },
                    placeholder = { Text("Например: HD, SUP (пусто = все проекты)") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = draft.allowedIssueTypes,
                    onValueChange = { draft = draft.copy(allowedIssueTypes = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Разрешённые клиентские типы (опционально)") },
                    placeholder = { Text("Пусто = любые, кроме сервисных ниже") }
                )

                OutlinedTextField(
                    value = draft.excludedIssueTypes,
                    onValueChange = { draft = draft.copy(excludedIssueTypes = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Игнорируемые сервисные типы задач") },
                    minLines = 2
                )

                OutlinedTextField(
                    value = draft.excludedReporters,
                    onValueChange = { draft = draft.copy(excludedReporters = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Сервисные отправители (уходят в сервисные)") },
                    placeholder = { Text("Например: zabbix, prometheus, noreply") },
                    minLines = 2
                )

                OutlinedTextField(
                    value = draft.excludedLabelsAndKeywords,
                    onValueChange = { draft = draft.copy(excludedLabelsAndKeywords = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Игнорируемые метки и стоп-слова") },
                    minLines = 2
                )

                OutlinedTextField(
                    value = draft.customJqlCondition,
                    onValueChange = { draft = draft.copy(customJqlCondition = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Дополнительное условие JQL (необязательно)") },
                    placeholder = { Text("Например: component != \"Internal\"") }
                )

                Text(
                    text = "Итоговый JQL-запрос: $generatedJql",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { draft = FilterSettings() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сброс", maxLines = 1)
            }

            Button(
                onClick = {
                    onSaveFilter(draft)
                    Toast.makeText(context, "Фильтр заявок сохранён", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сохранить", maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
