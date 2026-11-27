package com.helpdesk.jiraagent.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.DnsLookupResult
import com.helpdesk.jiraagent.domain.DnsLookupTool
import com.helpdesk.jiraagent.domain.DnsLookupType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


@Composable
fun DnsLookupAndroidView(onCopy: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var secondServer by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(DnsLookupType.A) }
    var consent by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DnsLookupResult>>(emptyList()) }
    var validationError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun edited() { consent = false; results = emptyList(); validationError = "" }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Проверка DNS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("Только указанные серверы, UDP/TCP порт 53; настройки устройства не изменяются. Разные ответы могут быть нормой при split DNS.", style = MaterialTheme.typography.bodySmall)
        Text("DNS-имя будет отправлено каждому указанному серверу. Не отправляйте внутренние имена публичным DNS без разрешения. Запросы не шифруются.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it; edited() }, label = { Text(if (type == DnsLookupType.AD_SRV) "Домен AD (например, corp.example)" else "DNS-имя (например, example.com)") }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(server, { server = it; edited() }, label = { Text("DNS-сервер 1 · IPv4") }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secondServer, { secondServer = it; edited() }, label = { Text("DNS-сервер 2 · IPv4 (необязательно)") }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DnsLookupType.entries.forEach { item ->
                FilterChip(selected = type == item, onClick = { type = item; edited() }, enabled = !running, label = { Text(item.label) })
            }
        }
        if (type == DnsLookupType.AD_SRV) Text("AD SRV: _ldap._tcp.dc._msdcs.<домен> — наличие записи не гарантирует исправность контроллера.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !running)
            Text("Подтверждаю отправку имени указанным DNS-серверам", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        Button(enabled = consent && !running && name.isNotBlank() && server.isNotBlank(), onClick = {
            val inputName = name
            val first = server
            val second = secondServer.trim()
            val selectedType = type
            consent = false
            results = emptyList()
            val validation = runCatching {
                DnsLookupTool.queryName(inputName, selectedType)
                DnsLookupTool.parseServer(first)
                if (second.isNotEmpty()) DnsLookupTool.parseServer(second)
            }
            validationError = validation.exceptionOrNull()?.message.orEmpty()
            if (validation.isSuccess) {
                running = true
                scope.launch {
                try {
                    results = coroutineScope {
                        val one = async { DnsLookupTool.lookup(inputName, first, selectedType) }
                        val two = if (second.isNotEmpty()) async { DnsLookupTool.lookup(inputName, second, selectedType) } else null
                        listOfNotNull(one.await(), two?.await())
                    }
                } finally { running = false }
                }
            }
        }) { Text(if (running) "Проверка…" else "Запросить DNS") }
        if (validationError.isNotEmpty()) Text(validationError, color = MaterialTheme.colorScheme.error)
        results.forEach { result ->
            HorizontalDivider()
            Text(result.asText(), style = MaterialTheme.typography.bodySmall)
        }
        if (results.isNotEmpty()) {
            Text("NXDOMAIN — имя не найдено; пустой NOERROR — записей этого типа нет. Ошибка сети не означает отсутствия записи.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onCopy(results.joinToString("\n\n") { it.asText() }) }) { Text("📋 Скопировать результаты") }

        }
    }
}
