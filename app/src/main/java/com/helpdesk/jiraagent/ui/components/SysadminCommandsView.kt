package com.helpdesk.jiraagent.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.domain.CommandPlatform
import com.helpdesk.jiraagent.domain.SysadminCommandCatalog

@Composable
fun SysadminCommandsAndroidView(onCopy: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf<CommandPlatform?>(null) }
    val entries = remember(query, platform) { SysadminCommandCatalog.search(query, platform) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Команды и скрипты", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Только копирование: приложение ничего не запускает. Перед выполнением проверьте адреса, права и предупреждения.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск: DNS, диск, служба…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = platform == null, onClick = { platform = null }, label = { Text("Все", maxLines = 1) })
            CommandPlatform.entries.forEach { item ->
                FilterChip(selected = platform == item, onClick = { platform = item }, label = { Text(item.label, maxLines = 1, softWrap = false) })
            }
        }
        if (entries.isEmpty()) Text("Ничего не найдено. Измените запрос или платформу.")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(entries, key = { it.id }) { entry ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${entry.platform.label} · ${entry.title}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(entry.description, fontSize = 13.sp)
                        Text("Как применять: ${entry.instructions}", fontSize = 12.5.sp)
                        Text("Важно: ${entry.impact}", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                        SelectionContainer {
                            Text(entry.command, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp)).padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        Button(onClick = { onCopy(entry.title, entry.command) }) { Text("📋 Скопировать команду") }
                    }
                }
            }
        }
    }
}
