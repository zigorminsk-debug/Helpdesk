package com.helpdesk.jiraagent.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.ClientRequestAnalysis
import com.helpdesk.jiraagent.domain.OrganizationHistory
import com.helpdesk.jiraagent.model.HelpdeskTicket

/** Embedded in the ticket detail scroll container on both platforms. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientRequestAnalysisView(
    ticket: HelpdeskTicket,
    loadedTickets: List<HelpdeskTicket>,
    history: List<HelpdeskTicket>,
    historyLoading: Boolean,
    historyFailed: Boolean,
    organizationHistories: Map<String, OrganizationHistory>,
    onOpenTicket: (String) -> Unit
) {
    var expanded by rememberSaveable(ticket.key) { mutableStateOf(false) }
    var scope by rememberSaveable(ticket.key) { mutableStateOf("author") }
    var selectedTopic by rememberSaveable(ticket.key, scope) { mutableStateOf("") }
    var visibleCount by rememberSaveable(ticket.key, scope, selectedTopic) { mutableIntStateOf(10) }
    val orgs = ticket.organizations.orEmpty()
    val org = orgs.firstOrNull { "${it.fieldId}:${it.id}" == scope }
    val orgHistory = organizationHistories[scope]
    val sample = remember(ticket, loadedTickets, history, scope, orgHistory) {
        val candidates = if (scope == "author") loadedTickets + history else loadedTickets + orgHistory?.tickets.orEmpty()
        (listOf(ticket) + candidates.filter { candidate ->
            candidate.isClientTicket && if (scope == "author") ClientRequestAnalysis.sameReporter(ticket, candidate)
            else org != null && ClientRequestAnalysis.sameOrganization(org, candidate)
        }).distinctBy { it.key.uppercase() }
    }
    val groups = remember(sample) { ClientRequestAnalysis.groups(sample) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "▾ Анализ обращений" else "▸ Анализ обращений", fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = scope == "author", onClick = { scope = "author" }, label = { Text("Автор") })
                    orgs.forEach { item ->
                        val id = "${item.fieldId}:${item.id}"
                        FilterChip(selected = scope == id, onClick = { scope = id }, label = { Text(item.name) })
                    }
                }
                if (orgs.isEmpty()) Text("Организация не определена по полю JSM Organizations этой заявки. Анализ по организации недоступен; имя автора не используется как организация.", style = MaterialTheme.typography.bodySmall)
                if (scope == "author") {
                    Text("Автор: ${ticket.reporterName}. Выборка: текущая заявка, загруженные заявки и найденная история. Совпадение по имени при отсутствии ID приблизительное; возможны пропуски и однофамильцы. Поиск Jira ограничен проектами, правами и 500 результатами; история может быть неполной.", style = MaterialTheme.typography.bodySmall)
                    if (historyLoading) Text("Загрузка истории Jira…", style = MaterialTheme.typography.bodySmall)
                    if (historyFailed) Text("Историю Jira загрузить не удалось; показаны только доступные заявки.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Организация: ${org?.name.orEmpty()}. Учитываются загруженные заявки и поиск Jira по ID в поле JSM Organizations, не более 500 результатов поиска. Права, проекты, фильтры, заполнение поля и лимит влияют на выборку; история может быть неполной.", style = MaterialTheme.typography.bodySmall)
                    if (orgHistory == null) Text("Доступны только загруженные заявки: поиск в Jira не выполнялся.", style = MaterialTheme.typography.bodySmall)
                    if (orgHistory?.loading == true) Text("Поиск обращений организации в Jira…", style = MaterialTheme.typography.bodySmall)
                    if (orgHistory?.failed == true) Text("Не удалось загрузить историю организации из Jira; показаны только доступные заявки.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text("Заявок в выборке: ${sample.size}. Темы определяются по ключевым словам в названиях, одна заявка может попасть в несколько тем. Это не анализ текста или причин; сверяйте с источником.", style = MaterialTheme.typography.bodySmall)
                groups.forEach { (topic, items) ->
                    TextButton(onClick = { selectedTopic = if (selectedTopic == topic) "" else topic }) {
                        Text("${if (selectedTopic == topic) "▾" else "▸"} $topic (${items.size})")
                    }
                    if (selectedTopic == topic) {
                        items.take(visibleCount).forEach { item ->
                            TextButton(onClick = { onOpenTicket(item.key) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${item.key} · ${item.summary} · ${item.status}", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (items.size > visibleCount) TextButton(onClick = { visibleCount += 10 }) { Text("Показать ещё (${items.size - visibleCount})") }
                    }
                }
            }
        }
    }
}
