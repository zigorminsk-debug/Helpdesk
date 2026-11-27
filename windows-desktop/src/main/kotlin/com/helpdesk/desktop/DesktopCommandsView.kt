package com.helpdesk.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.domain.CommandPlatform
import com.helpdesk.jiraagent.domain.CommandTopic
import com.helpdesk.jiraagent.domain.SysadminCommandCatalog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun DesktopCommandsView() {
    var query by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf<CommandPlatform?>(null) }
    var copiedId by remember { mutableStateOf<String?>(null) }
    var clipboardError by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf<CommandTopic?>(null) }
    fun copyText(id: String, text: String) {
        val result = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        clipboardError = result.isFailure
        copiedId = if (result.isSuccess) id else null
    }
    val entries = remember(query, platform, topic) { SysadminCommandCatalog.search(query, platform, topic) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Команды и скрипты для ПК и серверов", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Только копирование: приложение ничего не запускает. Перед выполнением проверьте адреса, права и предупреждения.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск: DNS, диск, служба…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = platform == null, onClick = { platform = null }, label = { Text("Все") })
            CommandPlatform.entries.forEach { item ->
                FilterChip(selected = platform == item, onClick = { platform = item }, label = { Text(item.label) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = topic == null, onClick = { topic = null }, label = { Text("Все темы", maxLines = 1, softWrap = false) })
            CommandTopic.entries.filter { it != CommandTopic.GENERAL }.forEach { item ->
                FilterChip(selected = topic == item, onClick = { topic = item }, label = { Text(item.label, maxLines = 1, softWrap = false) })
            }
        }
        Text("Найдено: ${entries.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entries.isEmpty()) Text("Ничего не найдено. Измените запрос, платформу или тему.")
        if (clipboardError) Text("Не удалось скопировать: буфер обмена недоступен. Выделите текст команды вручную.", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(entries, key = { it.id }) { entry ->
                var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${entry.platform.label} · ${entry.title}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(entry.description, fontSize = 13.sp)
                        Text(if (entry.changesSystem) "⚠ Изменяет систему · ${entry.topic.label}" else "Диагностика / справка · ${entry.topic.label}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (entry.changesSystem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (expanded) "▲ Скрыть инструкцию и команду" else "▼ Показать инструкцию и команду")
                        }
                        if (expanded) {
                            Text("Как применять: ${entry.instructions}", fontSize = 12.5.sp)
                            Text("Важно: ${entry.impact}", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                            SelectionContainer {
                                Text(entry.command, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp)).padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                        if (expanded) {
                            Button(onClick = { copyText("${entry.id}:command", entry.command) }) {
                                Text(if (copiedId == "${entry.id}:command") "✓ Команда скопирована" else "📋 Скопировать команду")
                            }
                            OutlinedButton(onClick = { copyText("${entry.id}:guide", SysadminCommandCatalog.formatInstructions(entry)) }) {
                                Text(if (copiedId == "${entry.id}:guide") "✓ Инструкция скопирована" else "📋 Скопировать инструкцию для коллеги")
                            }
                        }
                    }
                }
            }
        }
    }
}
