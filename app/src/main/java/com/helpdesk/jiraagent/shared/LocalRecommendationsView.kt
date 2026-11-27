package com.helpdesk.jiraagent.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.ConfirmedSolution
import com.helpdesk.jiraagent.domain.LocalRecommendations
import com.helpdesk.jiraagent.model.HelpdeskTicket

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalRecommendationsView(ticket: HelpdeskTicket, scope: String, items: List<ConfirmedSolution>,
    onAdd: (String, String) -> Boolean, onVote: (String, Int) -> Boolean, onDelete: (String) -> Boolean) {
    var editing by remember(ticket.key, scope) { mutableStateOf(false) }
    var manage by remember(ticket.key, scope) { mutableStateOf(false) }
    var symptoms by remember(ticket.key, scope) { mutableStateOf("") }
    var solution by remember(ticket.key, scope) { mutableStateOf("") }
    var error by remember(ticket.key, scope) { mutableStateOf("") }
    var pendingDelete by remember(ticket.key, scope) { mutableStateOf("") }
    val examples = remember(items, scope) { items.filter { it.scope == scope } }
    val suggestions = remember(items, scope, ticket.key, ticket.summary, ticket.description) {
        LocalRecommendations.suggest(items, scope, ticket.key, ticket.summary + " " + ticket.description)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Рекомендации по решению", fontWeight = FontWeight.Bold)
            Text("Локальный подбор по подтверждённым примерам, не нейросеть. Проверьте применимость перед выполнением. Данные не синхронизируются между устройствами.", style = MaterialTheme.typography.bodySmall)
            if (scope.isBlank()) Text("Настройте адрес Jira и учётную запись для использования рекомендаций.")
            else {
                if (suggestions.isEmpty()) Text(if (examples.isEmpty()) "Пока нет подтверждённых решений. Добавьте первый пример." else "Подходящих примеров не найдено.")
                suggestions.forEach { suggestion ->
                    val entry = suggestion.example
                    HorizontalDivider()
                    Text("Источник: ${entry.ticketKey} · совпало: ${suggestion.matchingTerms.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Text(entry.solution)
                    val vote = entry.votes[ticket.key.trim().uppercase(java.util.Locale.ROOT)] ?: 0
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { error = if (onVote(entry.id, if (vote == 1) 0 else 1)) "" else "Не удалось сохранить оценку." }) { Text(if (vote == 1) "✓ Помогло" else "Помогло") }
                        OutlinedButton(onClick = { error = if (onVote(entry.id, if (vote == -1) 0 else -1)) "" else "Не удалось сохранить оценку." }) { Text(if (vote == -1) "✓ Не помогло" else "Не помогло") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Отмена" else "Добавить решение") }
                    TextButton(onClick = { manage = !manage }) { Text(if (manage) "Скрыть базу" else "Управлять базой (${examples.size})") }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (editing) {
                    Text("Вносите только проверенное решение. Не добавляйте пароли, токены, персональные данные и другие секреты. Текст хранится на этом устройстве.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = symptoms, onValueChange = { if (it.length <= LocalRecommendations.MAX_SYMPTOMS) symptoms = it }, label = { Text("Признаки проблемы (без секретов)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = solution, onValueChange = { if (it.length <= LocalRecommendations.MAX_SOLUTION) solution = it }, label = { Text("Проверенное решение") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (onAdd(symptoms, solution)) { editing = false; symptoms = ""; solution = ""; error = "" }
                        else error = "Не удалось сохранить: проверьте поля (не менее 3 символов), лимит базы 300 записей и доступность хранилища."
                    }) { Text("Подтверждаю и сохраняю") }
                }
                if (manage) {
                    Text("Подтверждённые решения этой учётной записи:", fontWeight = FontWeight.SemiBold)
                    examples.forEach { entry ->
                        HorizontalDivider()
                        Text("${entry.ticketKey}: ${entry.symptoms}")
                        Text(entry.solution, style = MaterialTheme.typography.bodySmall)
                        if (pendingDelete == entry.id) {
                            FlowRow {
                                TextButton(onClick = { if (onDelete(entry.id)) { pendingDelete = ""; error = "" } else error = "Не удалось удалить запись." }) { Text("Подтвердить удаление") }
                                TextButton(onClick = { pendingDelete = "" }) { Text("Отмена") }
                            }
                        } else TextButton(onClick = { pendingDelete = entry.id }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}
