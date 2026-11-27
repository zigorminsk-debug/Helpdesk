package com.helpdesk.jiraagent.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.DomainDiagnosticRole
import com.helpdesk.jiraagent.domain.DomainDiagnostics
import com.helpdesk.jiraagent.domain.SysadminCommandCatalog

private val repairChecks = listOf(
    "Подтверждена роль: это рабочая станция или рядовой сервер, не контроллер домена.",
    "Проверены корпоративная сеть/VPN, DNS, доступность контроллера и время; результат теста защищённого канала изучен.",
    "Обеспечен проверенный локальный административный доступ и возможность восстановления доступа через консоль.",
    "Согласованы работы и последствия для пользователей и служб; проверены резервная копия и план отката.",
    "Есть доменная учётная запись с делегированным правом сброса пароля учётной записи этого компьютера. Пароль не будет вставлен в заметки или скрипт."
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DomainDiagnosticCardView(onCopy: (String, String) -> Unit) {
    // Role is a user selection, not a detection. Approvals are deliberately never saved.
    var roleName by rememberSaveable { mutableStateOf("") }
    val role = DomainDiagnosticRole.entries.firstOrNull { it.name == roleName }
    var confirmations by remember { mutableStateOf(List(repairChecks.size) { false }) }
    var showConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Диагностика домена AD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Выберите роль компьютера, на котором будут выполняться команды. Приложение не определяет роль автоматически, ничего не запускает и не подключается к AD.", style = MaterialTheme.typography.bodySmall)
        Text("Копирование с Android помещает текст в буфер телефона, а не на удалённый компьютер. Не копируйте пароли и проверьте содержимое буфера перед передачей.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DomainDiagnosticRole.entries.forEach { item ->
                FilterChip(selected = item == role, onClick = {
                    if (roleName != item.name) {
                        roleName = item.name
                        confirmations = List(repairChecks.size) { false }
                        showConfirm = false
                    }
                }, label = { Text(item.label) })
            }
        }
        if (role == null) {
            Text("Выберите роль, чтобы увидеть подходящие проверки. Если роль не известна, сначала выясните её у администратора AD.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Точка запуска: ${role.location}", fontWeight = FontWeight.SemiBold)
            Text(role.warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("Безопасные проверки по порядку", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            DomainDiagnostics.checks(role).forEachIndexed { index, check ->
                val command = check.command
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${index + 1}. ${command.title}", fontWeight = FontWeight.Bold)
                        Text("Где: ${check.location}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(check.guidance, style = MaterialTheme.typography.bodySmall)
                        Text("${command.platform.label}: ${command.description}\nКак применять: ${command.instructions}\nВажно: ${command.impact}", style = MaterialTheme.typography.bodySmall)
                        Text(command.command, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onCopy(command.title, command.command) }) { Text("📋 Команда") }
                            OutlinedButton(onClick = { onCopy("Инструкция: ${command.title}", SysadminCommandCatalog.formatInstructions(command)) }) { Text("📋 Инструкция") }
                        }
                    }
                }
            }
            if (role == DomainDiagnosticRole.MEMBER) {
                val repair = DomainDiagnostics.repairFor(role)!!
                HorizontalDivider()
                Text("Отдельно: восстановление доверия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("Изменяет пароль учётной записи компьютера и защищённый канал в AD. Это не универсальное исправление; при сомнениях, сбоях репликации или повторении ошибки передайте задачу специалисту AD. Не применяется к DC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("${repair.instructions}\nВажно: ${repair.impact}", style = MaterialTheme.typography.bodySmall)
                Text("Скрипт для проверки перед копированием:", fontWeight = FontWeight.SemiBold)
                Text(repair.command, style = MaterialTheme.typography.bodySmall)
                Text("Перед копированием подтвердите каждый пункт. Отметки не проверяются приложением и не заменяют оценку инженера.", style = MaterialTheme.typography.bodySmall)
                repairChecks.forEachIndexed { index, text ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Checkbox(checked = confirmations[index], onCheckedChange = { checked ->
                            confirmations = confirmations.mapIndexed { i, old -> if (i == index) checked else old }
                        })
                        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    }
                }
                Button(enabled = confirmations.all { it }, onClick = { showConfirm = true }) { Text("Подготовить копирование скрипта") }
                Text("Это только копирование. На целевом компьютере скрипт повторно проверит роль и запросит YES и учётные данные; приложение их не получает.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Ремонт защищённого канала участника домена в этом режиме недоступен. При проблемах DC обратитесь к ответственному специалисту AD.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showConfirm && role == DomainDiagnosticRole.MEMBER) {
        AlertDialog(
            onDismissRequest = { confirmations = List(repairChecks.size) { false }; showConfirm = false },
            title = { Text("Скопировать скрипт восстановления?") },
            text = { Text("Скрипт предназначен только для подтверждённого члена домена. Его ручной запуск изменит защищённый канал и пароль учётной записи компьютера. Сейчас будет только копирование в буфер, не запуск.") },
            confirmButton = { TextButton(onClick = {
                if (role == DomainDiagnosticRole.MEMBER && confirmations.all { it }) {
                    DomainDiagnostics.repairFor(role)?.let { onCopy(it.title, it.command) }
                }
                confirmations = List(repairChecks.size) { false }
                showConfirm = false
            }) { Text("Только скопировать") } },
            dismissButton = { TextButton(onClick = { confirmations = List(repairChecks.size) { false }; showConfirm = false }) { Text("Отмена") } }
        )
    }
}
