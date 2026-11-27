package com.helpdesk.jiraagent.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.DiagnosticScenario
import com.helpdesk.jiraagent.domain.DiagnosticScenarios
import com.helpdesk.jiraagent.domain.ScenarioStepResult
import com.helpdesk.jiraagent.domain.ScenarioStepStatus

private val stringsSaver = listSaver<List<String>, String>(save = { it }, restore = { it.toList() })

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticScenariosView(onCopy: (String, String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(DiagnosticScenarios.entries.first().id) }
    val holder = rememberSaveableStateHolder()
    val scenario = DiagnosticScenarios.entries.firstOrNull { it.id == selectedId } ?: DiagnosticScenarios.entries.first()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сценарии диагностики", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Проверки выполняет инженер вручную. Приложение не запускает команды, не отправляет отчёт в Jira и не подтверждает результаты автоматически.", style = MaterialTheme.typography.bodySmall)
        Text("Черновики временные: копируйте итог до закрытия окна. Не вводите пароли и секреты; проверьте заметки и буфер обмена перед отправкой.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticScenarios.entries.forEach { item ->
                FilterChip(selected = item.id == scenario.id, onClick = { selectedId = item.id }, label = { Text(item.title) })
            }
        }
        holder.SaveableStateProvider(scenario.id) {
            ScenarioDraft(scenario, onCopy)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioDraft(scenario: DiagnosticScenario, onCopy: (String, String) -> Unit) {
    var context by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    var statuses by rememberSaveable(stateSaver = stringsSaver) { mutableStateOf(List(scenario.steps.size) { ScenarioStepStatus.NOT_CHECKED.name }) }
    var notes by rememberSaveable(stateSaver = stringsSaver) { mutableStateOf(List(scenario.steps.size) { "" }) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    val safeStatuses = scenario.steps.indices.map { i ->
        ScenarioStepStatus.entries.firstOrNull { it.name == statuses.getOrNull(i) } ?: ScenarioStepStatus.NOT_CHECKED
    }
    val safeNotes = scenario.steps.indices.map { notes.getOrNull(it).orEmpty() }

    Text(scenario.introduction, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(context, { context = it.take(1000) }, label = { Text("Объект, симптомы, время и влияние") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
    scenario.steps.forEachIndexed { index, step ->
        key(step.id) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${index + 1}. ${step.title}", fontWeight = FontWeight.Bold)
                    Text(step.instruction, style = MaterialTheme.typography.bodySmall)
                    step.commands().forEach { command ->
                        HorizontalDivider()
                        Text("${command.platform.label} · ${command.title}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text("${command.description}\nКак применять: ${command.instructions}\nВажно: ${command.impact}", style = MaterialTheme.typography.bodySmall)
                        Text(command.command, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onCopy(command.title, command.command) }) { Text("📋 Копировать команду") }
                    }
                    Text("Результат шага", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ScenarioStepStatus.entries.forEach { status ->
                            FilterChip(selected = safeStatuses[index] == status, onClick = {
                                statuses = safeStatuses.mapIndexed { i, old -> if (i == index) status.name else old.name }
                            }, label = { Text(status.label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    OutlinedTextField(safeNotes[index], { value ->
                        notes = safeNotes.mapIndexed { i, old -> if (i == index) value.take(1000) else old }
                    }, label = { Text("Результат / примечание") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)
                }
            }
        }
    }
    OutlinedTextField(next, { next = it.take(1000) }, label = { Text("Дальнейшие действия / эскалация") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
    val report = DiagnosticScenarios.formatReport(scenario, context, scenario.steps.mapIndexed { i, step ->
        step.id to ScenarioStepResult(safeStatuses[i], safeNotes[i])
    }.toMap(), next)
    Text("Итог для заявки", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(report, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onCopy("Итог диагностики", report) }) { Text("📋 Копировать итог") }
        OutlinedButton(onClick = { showReset = true }) { Text("Очистить черновик") }
    }
    if (showReset) {
        AlertDialog(onDismissRequest = { showReset = false }, title = { Text("Очистить этот сценарий?") },
            text = { Text("Нескопированные заметки и отметки этого сценария будут потеряны.") },
            confirmButton = { TextButton(onClick = {
                context = ""; next = ""
                statuses = List(scenario.steps.size) { ScenarioStepStatus.NOT_CHECKED.name }
                notes = List(scenario.steps.size) { "" }
                showReset = false
            }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Отмена") } })
    }
}
