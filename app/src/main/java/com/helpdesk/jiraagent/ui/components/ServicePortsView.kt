package com.helpdesk.jiraagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.domain.ServicePortReference

@Composable
fun ServicePortsAndroidView(onCopy: (String, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val ports = remember(query) { ServicePortReference.search(query) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "ports_search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Справочник портов", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Работает офлайн. Это справка, а не сканирование или рекомендация открыть порт в брандмауэре.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск: 443, DNS, домен…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Найдено: ${ports.size}", fontSize = 12.sp)
            }
        }
        items(ports, key = { it.id }) { entry ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${entry.service} · ${entry.port}", fontWeight = FontWeight.Bold)
                    Text(entry.use, fontSize = 13.sp)
                    Text(entry.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onCopy(entry.service, "${entry.service}: ${entry.port}") }) { Text("📋 Скопировать") }
                }
            }
        }
    }
}
