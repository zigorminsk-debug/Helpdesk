package com.helpdesk.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.data.ZabbixApiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ZabbixProblem
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DESKTOP_ZABBIX_THRESHOLDS = listOf(
    3 to "Ошибки и критические (3–5)",
    4 to "Высокие и аварии (4–5)",
    2 to "Предупреждения и выше (2–5)",
    5 to "Только аварии (5)"
)

@Composable
fun DesktopZabbixWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    val clipboard = LocalClipboardManager.current
    val settings = state.settings
    val snapshot = state.zabbixSnapshot

    var showAuthPanel by remember { mutableStateOf(!settings.isZabbixConfigured) }
    var urlDraft by remember(settings.zabbixUrl) {
        mutableStateOf(settings.zabbixUrl.ifBlank { AppSettings.DEFAULT_ZABBIX_URL })
    }
    var loginDraft by remember(settings.zabbixLogin) { mutableStateOf(settings.zabbixLogin) }
    var passwordDraft by remember(settings.zabbixPassword) { mutableStateOf(settings.zabbixPassword) }
    var enabledDraft by remember(settings.zabbixEnabled) { mutableStateOf(settings.zabbixEnabled) }
    var minSeverityDraft by remember(settings.zabbixMinAlertSeverity) {
        mutableStateOf(settings.normalizedZabbixMinSeverity)
    }
    var showPassword by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) } // 0 = All, 1 = Crit/Errors (3..5), 2 = Unacked
    var ackTarget by remember { mutableStateOf<ZabbixProblem?>(null) }
    var ackCommentDraft by remember { mutableStateOf("") }
    var activeDiagHost by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.isZabbixConfigured) {
        if (!settings.isZabbixConfigured) {
            showAuthPanel = true
        }
    }

    ackTarget?.let { prob ->
        AlertDialog(
            onDismissRequest = { ackTarget = null },
            title = { Text("Подтвердить проблему Zabbix", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${prob.hostWithIpLabel} — ${prob.name}", fontSize = 12.5.sp)
                    OutlinedTextField(
                        value = ackCommentDraft,
                        onValueChange = { ackCommentDraft = it },
                        label = { Text("Комментарий (необязательно)") },
                        placeholder = { Text("Принято в работу инженером…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        controller.acknowledgeZabbixProblem(prob, ackCommentDraft)
                        ackTarget = null
                        ackCommentDraft = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.StatusGreen)
                ) {
                    Text("Подтвердить (Ack)")
                }
            },
            dismissButton = {
                TextButton(onClick = { ackTarget = null }) { Text("Отмена") }
            }
        )
    }

    activeDiagHost?.let { hostLabel ->
        AlertDialog(
            onDismissRequest = { activeDiagHost = null },
            title = { Text("Диагностика узла $hostLabel", fontWeight = FontWeight.Bold) },
            text = {
                if (state.isRunningDiagnostics) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = DesktopColors.CslOrange)
                        Text("Проверка ICMP и основных портов…")
                    }
                } else {
                    val rep = state.diagnosticReport
                    if (rep == null) {
                        Text("Нет результатов проверки")
                    } else {
                        val openPorts = rep.portStatuses.filter { it.isOpen }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("IP: ${rep.resolvedIp ?: "не определён"}", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (rep.icmpReachable) {
                                    "🟢 ICMP Ping: доступен (${rep.icmpLatencyMs ?: 0} мс)"
                                } else {
                                    "🔴 ICMP Ping: нет ответа"
                                }
                            )
                            Text(
                                if (openPorts.isEmpty()) {
                                    "Открытые TCP-порты не обнаружены"
                                } else {
                                    "Открытые порты: " + openPorts.joinToString(", ") {
                                        "${it.port}/${it.serviceName} (${it.latencyMs ?: 0} мс)"
                                    }
                                },
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDiagHost = null }) { Text("Закрыть") }
            }
        )
    }

    val problems = snapshot?.problems.orEmpty()
    val filteredProblems = remember(problems, searchQuery, filterMode) {
        val q = searchQuery.trim().lowercase()
        problems.filter { p ->
            val passFilter = when (filterMode) {
                1 -> p.severityLevel >= 3
                2 -> !p.acknowledged
                else -> true
            }
            val passQuery = q.isEmpty() ||
                p.name.lowercase().contains(q) ||
                p.effectiveHostLabel.lowercase().contains(q) ||
                p.hostTechnicalName.lowercase().contains(q) ||
                p.hostIp.lowercase().contains(q) ||
                p.opData.lowercase().contains(q)
            passFilter && passQuery
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !settings.isZabbixConfigured -> Color(0xFF94A3B8)
                                    state.zabbixError != null -> DesktopColors.StatusRed
                                    else -> DesktopColors.StatusGreen
                                }
                            )
                    )
                    Column {
                        Text(
                            text = "Мониторинг Zabbix (${settings.zabbixUrl.ifBlank { AppSettings.DEFAULT_ZABBIX_URL }})",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                        val sub = if (snapshot != null) {
                            val timeStr = DateTimeFormatter.ofPattern("HH:mm:ss")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(snapshot.fetchedAtMillis))
                            buildString {
                                if (snapshot.serverVersion.isNotBlank()) append("Zabbix v${snapshot.serverVersion} · ")
                                if (snapshot.monitoredHostsCount > 0) append("Узлов под контролем: ${snapshot.monitoredHostsCount} · ")
                                append("Обновлено в $timeStr")
                            }
                        } else {
                            "Авторизация по логину и паролю · всплывающий баннер при ошибках и критических предупреждениях"
                        }
                        Text(sub, fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val webUrl = ZabbixApiClient.normalizeWebBaseUrl(settings.zabbixUrl)
                            runCatching { Desktop.getDesktop().browse(URI(webUrl)) }
                        }
                    ) {
                        Text("🌐 Открыть z.csl.by")
                    }
                    OutlinedButton(onClick = { controller.triggerTestZabbixBanner() }) {
                        Text("🔔 Тест баннера")
                    }
                    OutlinedButton(onClick = { showAuthPanel = !showAuthPanel }) {
                        Text(if (showAuthPanel) "Скрыть вход" else "⚙️ Логин / Настройки")
                    }
                    Button(
                        onClick = { controller.refreshZabbix(silent = false) },
                        enabled = !state.isLoadingZabbix,
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("🔄 Обновить")
                    }
                }
            }
        }

        if (state.isLoadingZabbix) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopColors.CslOrange)
        }

        // Login / Password Authorization Panel
        AnimatedVisibility(visible = showAuthPanel || !settings.isZabbixConfigured) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, DesktopColors.CslOrange.copy(alpha = 0.65f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Авторизация на сервере Zabbix (z.csl.by)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = DesktopColors.CslMidnight
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = urlDraft,
                            onValueChange = { urlDraft = it },
                            label = { Text("Адрес Zabbix") },
                            placeholder = { Text("http://z.csl.by") },
                            singleLine = true,
                            modifier = Modifier.weight(1.2f)
                        )
                        OutlinedTextField(
                            value = loginDraft,
                            onValueChange = { loginDraft = it },
                            label = { Text("Логин Zabbix") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = passwordDraft,
                            onValueChange = { passwordDraft = it },
                            label = { Text("Пароль Zabbix") },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "Скрыть" else "Показать", fontSize = 11.sp)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = enabledDraft,
                                onCheckedChange = { enabledDraft = it }
                            )
                            Text(
                                "Выводить срочный баннер и звук при появлении ошибки / критического предупреждения",
                                fontSize = 12.5.sp
                            )
                        }

                        if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    loginDraft = settings.usernameOrEmail
                                    passwordDraft = settings.passwordOrToken
                                }
                            ) {
                                Text("Подставить логин/пароль из Jira", fontSize = 12.sp)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Порог баннера:", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                        DESKTOP_ZABBIX_THRESHOLDS.forEach { (lvl, label) ->
                            FilterChip(
                                selected = minSeverityDraft == lvl,
                                onClick = { minSeverityDraft = lvl },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                controller.saveAndConnectZabbix(
                                    zabbixUrl = urlDraft,
                                    login = loginDraft,
                                    password = passwordDraft,
                                    enabled = enabledDraft,
                                    minSeverity = minSeverityDraft
                                )
                                if (loginDraft.isNotBlank() && passwordDraft.isNotBlank()) {
                                    showAuthPanel = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                        ) {
                            Text("Войти и загрузить данные")
                        }
                    }
                }
            }
        }

        if (!state.zabbixError.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                border = BorderStroke(1.dp, DesktopColors.StatusRed)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚠️ Ошибка Zabbix: ${state.zabbixError}",
                        color = DesktopColors.StatusRed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showAuthPanel = true }) {
                        Text("Проверить логин/пароль")
                    }
                }
            }
        }

        if (snapshot != null) {
            // KPI Strip + Search Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DesktopZabbixStatBadge("Аварии (5)", snapshot.disasterCount, Color(0xFFD93025))
                DesktopZabbixStatBadge("Критич. (4)", snapshot.highCount, Color(0xFFE65100))
                DesktopZabbixStatBadge("Ошибки (3)", snapshot.averageCount, DesktopColors.CslOrange)
                DesktopZabbixStatBadge("Предупр. (2)", snapshot.warningCount, DesktopColors.StatusAmber)

                Spacer(Modifier.width(8.dp))

                FilterChip(
                    selected = filterMode == 0,
                    onClick = { filterMode = 0 },
                    label = { Text("Все (${problems.size})") }
                )
                FilterChip(
                    selected = filterMode == 1,
                    onClick = { filterMode = 1 },
                    label = { Text("Ошибки и критич. (${snapshot.criticalAndErrorCount})") }
                )
                FilterChip(
                    selected = filterMode == 2,
                    onClick = { filterMode = 2 },
                    label = { Text("Неподтверждённые (${problems.count { !it.acknowledged }})") }
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по узлу, IP или проблеме…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (filteredProblems.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                    border = BorderStroke(1.dp, DesktopColors.StatusGreen.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (problems.isEmpty()) {
                            "✅ Активных проблем на z.csl.by нет — все контролируемые системы работают штатно."
                        } else {
                            "✅ По выбранному фильтру активных проблем нет."
                        },
                        modifier = Modifier.padding(18.dp),
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF166534)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredProblems, key = { it.alertKey }) { prob ->
                        val sevColor = when {
                            prob.severityLevel >= 5 -> Color(0xFFD93025)
                            prob.severityLevel == 4 -> Color(0xFFE65100)
                            prob.severityLevel == 3 -> DesktopColors.CslOrange
                            prob.severityLevel == 2 -> DesktopColors.StatusAmber
                            else -> Color(0xFF0284C7)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(
                                if (prob.severityLevel >= 4 && !prob.acknowledged) 1.8.dp else 1.dp,
                                sevColor.copy(alpha = if (prob.acknowledged) 0.35f else 0.85f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(color = sevColor, shape = RoundedCornerShape(6.dp)) {
                                    Text(
                                        text = prob.severity.shortBadge,
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = prob.hostWithIpLabel,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = DesktopColors.CslMidnight,
                                            modifier = Modifier.clickable {
                                                val addr = prob.hostIp.ifBlank { prob.hostTechnicalName.ifBlank { prob.effectiveHostLabel } }
                                                clipboard.setText(AnnotatedString(addr))
                                            }
                                        )
                                        Text(
                                            text = "Начало: ${prob.formattedStartTime()} · Длится: ${prob.formattedDuration()}",
                                            fontSize = 11.5.sp,
                                            color = Color(0xFF64748B)
                                        )
                                        if (prob.acknowledged) {
                                            Text(
                                                text = "✓ Подтверждено",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = DesktopColors.StatusGreen
                                            )
                                        }
                                    }

                                    Text(
                                        text = prob.name + (if (prob.opData.isNotBlank()) "  [${prob.opData}]" else ""),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (prob.ackMessage.isNotBlank()) {
                                        Text(
                                            text = "Комментарий: ${prob.ackMessage}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF475569)
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (prob.eventId.isNotBlank() && prob.eventId != "0") {
                                        OutlinedButton(
                                            onClick = {
                                                ackCommentDraft = ""
                                                ackTarget = prob
                                            }
                                        ) {
                                            Text(if (prob.acknowledged) "Комментарий" else "✓ Подтвердить")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val target = prob.hostIp.ifBlank { prob.hostTechnicalName.ifBlank { prob.effectiveHostLabel } }
                                            activeDiagHost = target
                                            controller.runHostDiagnostics(target)
                                        }
                                    ) {
                                        Text("🛠 Пинг")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val link = prob.toBannerDisplayTicket(settings.zabbixUrl).webUrl
                                            runCatching { Desktop.getDesktop().browse(URI(link)) }
                                        }
                                    ) {
                                        Text("🌐 В Zabbix")
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

@Composable
private fun DesktopZabbixStatBadge(label: String, count: Int, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (count > 0) accent.copy(alpha = 0.14f) else Color.White,
        border = BorderStroke(1.dp, if (count > 0) accent else Color(0xFFCBD5E1))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, fontSize = 12.sp, color = DesktopColors.CslMidnight)
            Text(
                text = count.toString(),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (count > 0) accent else Color(0xFF64748B)
            )
        }
    }
}
