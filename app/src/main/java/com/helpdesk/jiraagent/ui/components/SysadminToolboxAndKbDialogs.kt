package com.helpdesk.jiraagent.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helpdesk.jiraagent.domain.SysadminToolboxEngine
import com.helpdesk.jiraagent.domain.WatchlistHostStatus
import com.helpdesk.jiraagent.model.ClientTechPassport
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.util.MessengerLauncher
import kotlinx.coroutines.launch

// =============================================================================
//  1. SYSADMIN TOOLBOX DIALOG (Wake-on-LAN, Subnet, Password Gen, Watchlist)
// =============================================================================

@Composable
fun SysadminToolboxAndroidDialog(
    watchlistHosts: List<String>,
    watchlistStatuses: List<WatchlistHostStatus>,
    isCheckingWatchlist: Boolean,
    onAddHost: (String) -> Unit,
    onRemoveHost: (String) -> Unit,
    onRefreshHosts: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("⚡ WOL", "🌐 Подсети", "🔐 Пароли", "🛡 SSL/Узлы")

    LaunchedEffect(tabIndex) {
        if (tabIndex == 3 && watchlistStatuses.isEmpty() && !isCheckingWatchlist) {
            onRefreshHosts()
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.86f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = CslMidnight, contentColor = Color.White) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🛠 Инструменты сисадмина", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        TextButton(onClick = onDismiss) {
                            Text("Закрыть", color = Color.White)
                        }
                    }
                }

                TabRow(selectedTabIndex = tabIndex, containerColor = CslMidnight) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = tabIndex == idx,
                            onClick = { tabIndex = idx },
                            text = {
                                Text(
                                    title,
                                    fontSize = 12.sp,
                                    color = if (tabIndex == idx) CslOrange else Color.White,
                                    fontWeight = if (tabIndex == idx) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
                    when (tabIndex) {
                        0 -> {
                            var mac by remember { mutableStateOf("") }
                            var broadcast by remember { mutableStateOf("255.255.255.255") }
                            var portText by remember { mutableStateOf("9") }
                            var status by remember { mutableStateOf<String?>(null) }

                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    "Отправка UDP Magic Packet в локальную/VPN-сеть для удалённого включения ПК",
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = mac,
                                    onValueChange = { mac = it },
                                    label = { Text("MAC-адрес (AA:BB:CC:DD:EE:FF)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = broadcast,
                                        onValueChange = { broadcast = it },
                                        label = { Text("Broadcast IP") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = portText,
                                        onValueChange = { portText = it.filter(Char::isDigit) },
                                        label = { Text("Порт") },
                                        singleLine = true,
                                        modifier = Modifier.width(90.dp)
                                    )
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            SysadminToolboxEngine.sendWakeOnLan(mac, broadcast, portText.toIntOrNull() ?: 9)
                                                .onSuccess { r ->
                                                    status = "✓ Magic Packet отправлен на ${r.normalizedMac} (${r.targetAddress}:${r.port})"
                                                }
                                                .onFailure { e ->
                                                    status = "⚠ ${e.message}"
                                                }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("⚡ Разбудить компьютер (Wake-on-LAN)")
                                }
                                status?.let { Text(it, fontSize = 12.5.sp, fontWeight = FontWeight.Medium) }
                            }
                        }

                        1 -> {
                            var ipInput by remember { mutableStateOf("192.168.1.10/24") }
                            val calc = remember(ipInput) { SysadminToolboxEngine.calculateSubnet(ipInput) }
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = ipInput,
                                    onValueChange = { ipInput = it },
                                    label = { Text("IPv4 / CIDR или маска (192.168.10.5/24)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                calc.onSuccess { info ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Сеть: ${info.networkAddress}/${info.cidrPrefix}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                            Text("Маска: ${info.subnetMask} (Wildcard: ${info.wildcardMask})", fontSize = 12.5.sp)
                                            Text("Первый хост (шлюз): ${info.firstUsableHost}", fontSize = 12.5.sp)
                                            Text("Последний хост: ${info.lastUsableHost}", fontSize = 12.5.sp)
                                            Text("Broadcast: ${info.broadcastAddress}", fontSize = 12.5.sp)
                                            Text("Рабочих адресов: ${info.usableHostCount}", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                            OutlinedButton(
                                                onClick = {
                                                    copyToClipboard(
                                                        "Subnet",
                                                        "Сеть ${info.networkAddress}/${info.cidrPrefix}, маска ${info.subnetMask}, пул ${info.firstUsableHost}–${info.lastUsableHost}"
                                                    )
                                                }
                                            ) { Text("📋 Скопировать расчёт") }
                                        }
                                    }
                                }.onFailure { err ->
                                    Text("⚠ ${err.message}", color = CslDangerRed, fontSize = 12.5.sp)
                                }
                            }
                        }

                        2 -> {
                            var service by remember { mutableStateOf("VPN / RDP") }
                            var login by remember { mutableStateOf("") }
                            var len by remember { mutableFloatStateOf(16f) }
                            var symbols by remember { mutableStateOf(true) }
                            var pwd by remember { mutableStateOf(SysadminToolboxEngine.generateStrongPassword(16, true)) }

                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = service,
                                    onValueChange = { service = it },
                                    label = { Text("Сервис (1С, VPN, Почта, RDP)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = login,
                                    onValueChange = { login = it },
                                    label = { Text("Логин пользователя") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Длина: ${len.toInt()}", fontSize = 12.sp)
                                    Slider(
                                        value = len,
                                        onValueChange = {
                                            len = it
                                            pwd = SysadminToolboxEngine.generateStrongPassword(it.toInt(), symbols)
                                        },
                                        valueRange = 10f..28f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                FilterChip(
                                    selected = symbols,
                                    onClick = {
                                        symbols = !symbols
                                        pwd = SysadminToolboxEngine.generateStrongPassword(len.toInt(), symbols)
                                    },
                                    label = { Text("Спецсимволы !@#$%") }
                                )
                                OutlinedTextField(
                                    value = pwd,
                                    onValueChange = { pwd = it },
                                    label = { Text("Пароль") },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { pwd = SysadminToolboxEngine.generateStrongPassword(len.toInt(), symbols) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("🎲 Новый") }
                                    Button(
                                        onClick = {
                                            copyToClipboard(
                                                "Доступ",
                                                SysadminToolboxEngine.formatCredentialCard(service, login, pwd)
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                        modifier = Modifier.weight(1.4f)
                                    ) { Text("📋 Карточка клиенту") }
                                }
                            }
                        }

                        3 -> {
                            var newHost by remember { mutableStateOf("") }
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = newHost,
                                        onValueChange = { newHost = it },
                                        label = { Text("Домен или IP") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            onAddHost(newHost)
                                            newHost = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                                    ) { Text("+") }
                                    OutlinedButton(onClick = onRefreshHosts, enabled = !isCheckingWatchlist) {
                                        Text("🔄")
                                    }
                                }
                                if (isCheckingWatchlist) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
                                }
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(watchlistHosts, key = { it }) { host ->
                                        val st = watchlistStatuses.firstOrNull { it.host.equals(host, ignoreCase = true) }
                                        val border = when {
                                            st == null -> MaterialTheme.colorScheme.outlineVariant
                                            !st.isOnline -> CslDangerRed
                                            st.isSslExpiringSoon -> Color(0xFFD97706)
                                            else -> CslSuccessGreen
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, border),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    val label = when {
                                                        st == null -> "⏳ Ожидает"
                                                        !st.isOnline -> "🔴 Недоступен"
                                                        st.isSslExpiringSoon -> "🟠 SSL: ${st.sslDaysRemaining} дн.!"
                                                        else -> "🟢 Онлайн"
                                                    }
                                                    Text("$host · $label", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    if (st != null) {
                                                        val meta = buildList {
                                                            st.latencyMs?.let { add("${it} мс") }
                                                            if (st.openPorts.isNotEmpty()) add("Порты: ${st.openPorts.joinToString(",")}")
                                                            st.sslDaysRemaining?.let { add("SSL: $it дн.") }
                                                        }.joinToString(" · ")
                                                        if (meta.isNotBlank()) {
                                                            Text(meta, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                }
                                                TextButton(onClick = { onRemoveHost(host) }) {
                                                    Text("✕", color = CslDangerRed)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
//  2. TEAM-SYNCED CLIENT TECH PASSPORT DIALOG
// =============================================================================

@Composable
fun ClientTechPassportAndroidDialog(
    initialPassport: ClientTechPassport,
    onSave: (ClientTechPassport) -> Unit,
    onOpenInWiki: () -> Unit,
    onDismiss: () -> Unit
) {
    var addresses by remember { mutableStateOf(initialPassport.addressesAndBranches) }
    var gateway by remember { mutableStateOf(initialPassport.gatewayAndProvider) }
    var vpn by remember { mutableStateOf(initialPassport.vpnAndRemoteNotes) }
    var servers by remember { mutableStateOf(initialPassport.serversAndServices) }
    var notes by remember { mutableStateOf(initialPassport.contactsAndNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("🏢 Техпаспорт клиента", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(initialPassport.clientDisplayName, fontSize = 13.sp, color = CslOrange)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onOpenInWiki, modifier = Modifier.fillMaxWidth()) {
                    Text("📘 Открыть карточку клиента в Wiki (w.csl.by)", fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = addresses,
                    onValueChange = { addresses = it },
                    label = { Text("📍 Адреса / филиалы") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gateway,
                    onValueChange = { gateway = it },
                    label = { Text("🌐 Шлюз / IP / Провайдер / Роутер") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vpn,
                    onValueChange = { vpn = it },
                    label = { Text("🔐 VPN / AnyDesk / RustDesk / RDP") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = servers,
                    onValueChange = { servers = it },
                    label = { Text("🖥 Серверы / 1С / AD / Принтеры") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("👤 Контакты на местах и заметки") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialPassport.copy(
                            addressesAndBranches = addresses.trim(),
                            gatewayAndProvider = gateway.trim(),
                            vpnAndRemoteNotes = vpn.trim(),
                            serversAndServices = servers.trim(),
                            contactsAndNotes = notes.trim()
                        )
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// =============================================================================
//  3. KNOWLEDGE BASE ARCHIVE SEARCH DIALOG
// =============================================================================

@Composable
fun KnowledgeBaseArchiveAndroidDialog(
    initialQuery: String,
    results: List<HelpdeskTicket>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onOpenTicket: (String) -> Unit,
    onInsertSolution: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) onSearch(initialQuery)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📚 База решений (закрытые заявки)", fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Ошибка, модель МФУ, 1С, ЭЦП…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSearch(query) },
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                    ) { Text("🔍") }
                }
                if (isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.key }) { t ->
                        val lastComment = t.comments.lastOrNull()
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${t.key} · ${t.summary}", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                Text(
                                    "${t.reporterName} · ${t.status} · ${t.createdIso.take(10)}",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (lastComment != null) {
                                    Text(
                                        "💡 ${lastComment.author}: ${lastComment.body.take(220)}",
                                        fontSize = 12.sp
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            onOpenTicket(t.key)
                                            onDismiss()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                                    ) { Text("Открыть ${t.key}", fontSize = 11.5.sp) }
                                    if (lastComment != null && onInsertSolution != null) {
                                        Button(
                                            onClick = {
                                                onInsertSolution(lastComment.body)
                                                onDismiss()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                                        ) { Text("Вставить решение", fontSize = 11.5.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
//  4. 1-TAP REPAIR READY NOTIFICATION DIALOG
// =============================================================================

@Composable
fun RepairReadyNotifyAndroidDialog(
    item: RepairEquipmentItem,
    onSendToJiraTicket: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var message by remember(item.id) { mutableStateOf(RepairEquipmentCodec.buildClientReadyMessage(item)) }
    val phone = item.clientPhone.trim()

    fun copyMsg() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Готовность", message))
        Toast.makeText(context, "Текст скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📣 Уведомить клиента (${item.intakeNumber})", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (phone.isNotBlank()) {
                        Button(
                            onClick = {
                                copyMsg()
                                MessengerLauncher.openViberByPhone(context, phone)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7360F2))
                        ) { Text("Viber", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                copyMsg()
                                MessengerLauncher.openTelegramByPhone(context, phone)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))
                        ) { Text("Telegram", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                copyMsg()
                                MessengerLauncher.openWhatsAppByPhone(context, phone)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                        ) { Text("WhatsApp", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                                        putExtra("sms_body", message)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(sms)
                                }
                            }
                        ) { Text("SMS", fontSize = 12.sp) }
                    }
                    if (item.linkedTicketKey.isNotBlank()) {
                        Button(
                            onClick = onSendToJiraTicket,
                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                        ) { Text("В ${item.linkedTicketKey}", fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    copyMsg()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) { Text("Скопировать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}
