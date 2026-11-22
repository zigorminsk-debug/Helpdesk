package com.helpdesk.jiraagent.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    watchlistLabels: Map<String, String> = emptyMap(),
    watchlistStatuses: List<WatchlistHostStatus>,
    isCheckingWatchlist: Boolean,
    onAddHost: (host: String, label: String) -> Unit,
    onUpdateHostLabel: (host: String, label: String) -> Unit = { _, _ -> },
    onRemoveHost: (String) -> Unit,
    onRefreshHosts: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("⚡ WOL", "🌐 Подсети", "🔐 Пароли", "🛡 SSL / Узлы")

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
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: single-line title with weight(1f) + compact Close X icon button
                Surface(color = CslMidnight, contentColor = Color.White) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🛠 Инструменты сисадмина",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.5.sp,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = Color.White
                            )
                        }
                    }
                }

                // ScrollableTabRow prevents word splitting ("Подсет / и") on any font scale
                ScrollableTabRow(
                    selectedTabIndex = tabIndex,
                    edgePadding = 8.dp,
                    containerColor = CslMidnight,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        if (tabIndex in tabPositions.indices) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                                color = CslOrange
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = tabIndex == idx,
                            onClick = { tabIndex = idx },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (tabIndex == idx) CslOrange else Color.White,
                                    fontWeight = if (tabIndex == idx) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }

                when (tabIndex) {
                    0 -> {
                        var mac by remember { mutableStateOf("") }
                        var broadcast by remember { mutableStateOf("255.255.255.255") }
                        var portText by remember { mutableStateOf("9") }
                        var status by remember { mutableStateOf<String?>(null) }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Отправка UDP Magic Packet в локальную или VPN-сеть для удалённого включения ПК",
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MAC-адрес сетевой карты", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = mac,
                                    onValueChange = { mac = it },
                                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Broadcast IP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    OutlinedTextField(
                                        value = broadcast,
                                        onValueChange = { broadcast = it },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Column(
                                    modifier = Modifier.width(96.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Порт", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    OutlinedTextField(
                                        value = portText,
                                        onValueChange = { portText = it.filter(Char::isDigit) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
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
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚡ Разбудить ПК (Wake-on-LAN)",
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            status?.let {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = it,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        var ipInput by remember { mutableStateOf("192.168.1.10/24") }
                        val calc = remember(ipInput) { SysadminToolboxEngine.calculateSubnet(ipInput) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "IPv4 / CIDR или маска (напр. 192.168.10.5/24)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = ipInput,
                                    onValueChange = { ipInput = it },
                                    placeholder = { Text("192.168.10.5/24 или 10.8.0.5 255.255.252.0") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("📋 Скопировать расчёт", maxLines = 1, softWrap = false)
                                        }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Сервис (1С, VPN, Почта, RDP)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = service,
                                    onValueChange = { service = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Логин пользователя", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = login,
                                    onValueChange = { login = it },
                                    placeholder = { Text("ivanov") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Длина: ${len.toInt()}", fontSize = 12.5.sp, maxLines = 1, softWrap = false)
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
                                label = { Text("Спецсимволы !@#$%", maxLines = 1, softWrap = false) }
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Сгенерированный пароль", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = pwd,
                                    onValueChange = { pwd = it },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { pwd = SysadminToolboxEngine.generateStrongPassword(len.toInt(), symbols) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("🎲 Новый", maxLines = 1, softWrap = false)
                                }
                                Button(
                                    onClick = {
                                        copyToClipboard(
                                            "Доступ",
                                            SysadminToolboxEngine.formatCredentialCard(service, login, pwd)
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                                    modifier = Modifier.weight(1.5f)
                                ) {
                                    Text("📋 Скопировать", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }

                    3 -> {
                        var newHost by remember { mutableStateOf("") }
                        var newLabel by remember { mutableStateOf("") }
                        var selectedFullScreenHost by remember { mutableStateOf<String?>(null) }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = newHost,
                                            onValueChange = { newHost = it },
                                            placeholder = { Text("IP или домен", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedIconButton(
                                            onClick = onRefreshHosts,
                                            enabled = !isCheckingWatchlist
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Обновить все")
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = newLabel,
                                            onValueChange = { newLabel = it },
                                            placeholder = { Text("Подпись (чей IP)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                if (newHost.isNotBlank()) {
                                                    onAddHost(newHost, newLabel)
                                                    newHost = ""
                                                    newLabel = ""
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                                        ) {
                                            Text("+ Узел", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }

                            if (isCheckingWatchlist) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
                            }

                            Text(
                                text = "Нажмите на карточку узла — она откроется на весь экран со всеми портами",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(watchlistHosts, key = { it }) { host ->
                                    val normKey = SysadminToolboxEngine.normalizeHostKey(host)
                                    val savedLabel = watchlistLabels[normKey].orEmpty()
                                    val st = watchlistStatuses.firstOrNull {
                                        SysadminToolboxEngine.normalizeHostKey(it.host) == normKey ||
                                            it.host.equals(host, ignoreCase = true)
                                    }
                                    val border = when {
                                        st == null -> MaterialTheme.colorScheme.outlineVariant
                                        !st.isOnline -> CslDangerRed
                                        st.isSslExpiringSoon -> Color(0xFFD97706)
                                        else -> CslSuccessGreen
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, border),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedFullScreenHost = host
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                if (savedLabel.isNotBlank()) {
                                                    Text(
                                                        text = "🏷 $savedLabel",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = CslOrange
                                                    )
                                                }
                                                val statusBadge = when {
                                                    st == null -> "⏳ Ожидает"
                                                    !st.isOnline -> "🔴 Недоступен"
                                                    st.isSslExpiringSoon -> "🟠 SSL: ${st.sslDaysRemaining} дн.!"
                                                    else -> "🟢 Онлайн"
                                                }
                                                Text(
                                                    text = "$host · $statusBadge",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.5.sp
                                                )
                                                if (st != null) {
                                                    val totalPorts = st.portStatuses.size
                                                    val openCount = st.openPorts.size
                                                    val meta = buildList {
                                                        if (st.resolvedIp != null && !st.resolvedIp.equals(host, ignoreCase = true)) {
                                                            add("IP: ${st.resolvedIp}")
                                                        }
                                                        st.latencyMs?.let { add("${it} мс") }
                                                        if (totalPorts > 0) {
                                                            val preview = if (openCount > 0) {
                                                                st.openPorts.take(5).joinToString(",") +
                                                                    if (openCount > 5) "…" else ""
                                                            } else "нет открытых"
                                                            add("Порты $openCount/$totalPorts ($preview)")
                                                        }
                                                        st.sslDaysRemaining?.let { add("SSL: $it дн.") }
                                                    }.joinToString(" · ")
                                                    if (meta.isNotBlank()) {
                                                        Text(
                                                            text = meta,
                                                            fontSize = 11.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = "Открыть карточку узла",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        selectedFullScreenHost?.let { activeHost ->
                            val normKey = SysadminToolboxEngine.normalizeHostKey(activeHost)
                            val savedLabel = watchlistLabels[normKey].orEmpty()
                            val st = watchlistStatuses.firstOrNull {
                                SysadminToolboxEngine.normalizeHostKey(it.host) == normKey ||
                                    it.host.equals(activeHost, ignoreCase = true)
                            }
                            WatchlistHostFullScreenAndroidDialog(
                                host = activeHost,
                                savedLabel = savedLabel,
                                status = st,
                                isCheckingWatchlist = isCheckingWatchlist,
                                onUpdateLabel = { newLbl ->
                                    onUpdateHostLabel(activeHost, newLbl)
                                    Toast.makeText(context, "Подпись сохранена", Toast.LENGTH_SHORT).show()
                                },
                                onRefresh = onRefreshHosts,
                                onConfirmDelete = {
                                    onRemoveHost(activeHost)
                                    selectedFullScreenHost = null
                                },
                                onDismiss = { selectedFullScreenHost = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistHostFullScreenAndroidDialog(
    host: String,
    savedLabel: String,
    status: WatchlistHostStatus?,
    isCheckingWatchlist: Boolean,
    onUpdateLabel: (String) -> Unit,
    onRefresh: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var labelDraft by remember(host, savedLabel) { mutableStateOf(savedLabel) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Full-screen Host Card Header
                Surface(color = CslMidnight, contentColor = Color.White) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            if (savedLabel.isNotBlank()) {
                                Text(
                                    text = "🏷 $savedLabel",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = CslOrange,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = host,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (savedLabel.isNotBlank()) 12.5.sp else 15.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onRefresh, enabled = !isCheckingWatchlist) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = Color.White
                            )
                        }
                    }
                }

                if (isCheckingWatchlist) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Status summary banner
                    val bannerColor = when {
                        status == null -> MaterialTheme.colorScheme.outlineVariant
                        !status.isOnline -> CslDangerRed
                        status.isSslExpiringSoon -> Color(0xFFD97706)
                        else -> CslSuccessGreen
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bannerColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, bannerColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val badge = when {
                                status == null -> "⏳ Ожидает проверки"
                                !status.isOnline -> "🔴 Узел недоступен"
                                status.isSslExpiringSoon -> "🟠 SSL истекает через ${status.sslDaysRemaining} дн.!"
                                else -> "🟢 Узел онлайн"
                            }
                            Text(badge, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                            if (status != null) {
                                val line = buildList {
                                    status.resolvedIp?.let { add("IP: $it") }
                                    status.latencyMs?.let { add("Пинг: $it мс") }
                                    add("Проверено: ${status.checkedAtDisplay}")
                                }.joinToString(" · ")
                                Text(line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                status.httpServerSummary?.let {
                                    Text("🌐 Веб-ответ: $it", fontSize = 12.sp)
                                }
                                if (status.sslSubject != null) {
                                    Text(
                                        text = "🔒 SSL CN: ${status.sslSubject} · до ${status.sslExpiresFormatted ?: "—"} (${status.sslDaysRemaining ?: 0} дн.)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    status.sslIssuer?.takeIf { it.isNotBlank() }?.let {
                                        Text("УЦ: $it", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // IP Signature / Label Editor
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "🏷 Подпись узла (чей IP / клиент / роутер):",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = CslOrange
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = labelDraft,
                                    onValueChange = { labelDraft = it },
                                    placeholder = { Text("ООО Ромашка — MikroTik", maxLines = 1) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { onUpdateLabel(labelDraft) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Сохранить", tint = Color.White)
                                }
                            }
                        }
                    }

                    // Full Port List
                    if (status != null) {
                        Text(
                            text = "🔌 Все проверенные TCP-порты (открыто ${status.openPorts.size} из ${status.portStatuses.size}):",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (status.portStatuses.isEmpty()) {
                            Text(
                                text = "Порты не проверены (${status.statusSummary})",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                status.portStatuses.forEach { p ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (p.isOpen) {
                                            CslSuccessGreen.copy(alpha = 0.14f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        border = if (p.isOpen) BorderStroke(1.dp, CslSuccessGreen.copy(alpha = 0.5f)) else null,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${if (p.isOpen) "🟢" else "⚪"} ${p.port} · ${p.serviceName}",
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (p.isOpen) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = if (p.isOpen) {
                                                    "ОТКРЫТ${p.latencyMs?.let { " · $it мс" }.orEmpty()}"
                                                } else {
                                                    "закрыт"
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = if (p.isOpen) FontWeight.Bold else FontWeight.Normal,
                                                color = if (p.isOpen) CslSuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Проверка ещё не выполнялась — нажмите кнопку обновления 🔄 в верхней шапке.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

                    // Delete button tucked safely inside the full-screen card + confirmation dialog
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        border = BorderStroke(1.dp, CslDangerRed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CslDangerRed),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = CslDangerRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Удалить узел из мониторинга",
                            fontWeight = FontWeight.SemiBold,
                            color = CslDangerRed
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val displayTitle = if (savedLabel.isNotBlank()) "«$host» ($savedLabel)" else "«$host»"
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить узел?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Вы действительно хотите удалить узел $displayTitle из списка мониторинга SSL / Узлы?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onConfirmDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslDangerRed)
                ) {
                    Text("Удалить", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
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
                    Text("📘 Открыть в Wiki (w.csl.by)", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                OutlinedTextField(
                    value = addresses,
                    onValueChange = { addresses = it },
                    label = { Text("📍 Адреса", maxLines = 1, softWrap = false) },
                    placeholder = { Text("Офис, склад, филиалы…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gateway,
                    onValueChange = { gateway = it },
                    label = { Text("🌐 Шлюз / IP", maxLines = 1, softWrap = false) },
                    placeholder = { Text("Внешний IP, провайдер, роутер…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vpn,
                    onValueChange = { vpn = it },
                    label = { Text("🔐 VPN / RDP", maxLines = 1, softWrap = false) },
                    placeholder = { Text("OpenVPN, AnyDesk, RustDesk, RDP…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = servers,
                    onValueChange = { servers = it },
                    label = { Text("🖥 Серверы / 1С", maxLines = 1, softWrap = false) },
                    placeholder = { Text("1С, AD, Файловый, МФУ…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("👤 Контакты", maxLines = 1, softWrap = false) },
                    placeholder = { Text("Ответственные на местах, заметки…") },
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
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.84f)
                .imePadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = CslMidnight, contentColor = Color.White) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📚 База решений",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.5.sp,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = Color.White
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Поиск: ошибка, МФУ, 1С, ЭЦП…", maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onSearch(query) },
                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Найти", tint = Color.White)
                        }
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
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) { Text("Открыть ${t.key}", fontSize = 11.5.sp, maxLines = 1, softWrap = false) }
                                        if (lastComment != null && onInsertSolution != null) {
                                            Button(
                                                onClick = {
                                                    onInsertSolution(lastComment.body)
                                                    onDismiss()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) { Text("Вставить решение", fontSize = 11.5.sp, maxLines = 1, softWrap = false) }
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
