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
import com.helpdesk.jiraagent.model.ZabbixSeverity
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DESKTOP_ZABBIX_THRESHOLDS = listOf(
    5 to "Только Disaster (5)",
    4 to "High и Disaster (4–5)",
    3 to "Average и выше (3–5)",
    2 to "Warning и выше (2–5)"
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
    // 0 = Новые / активные, 1 = Ошибки и критические (3..5), 2 = Неподтверждённые, 3 = Скрытые и нерешаемые
    var filterMode by remember { mutableStateOf(0) }
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

    val mutedKeySet = remember(state.mutedZabbixRules) {
        state.mutedZabbixRules.flatMap { it.allMatchKeys() }.toSet()
    }
    val allServerProblems = snapshot?.problems.orEmpty()
    val activeProblems = remember(allServerProblems, state.dismissedZabbixKeys, mutedKeySet) {
        allServerProblems.filterNot { it.isHiddenFromActiveFeed(state.dismissedZabbixKeys, mutedKeySet) }
    }
    val dismissedActiveProblems = remember(allServerProblems, state.dismissedZabbixKeys, mutedKeySet) {
        allServerProblems.filter { !it.isMutedBy(mutedKeySet) && it.isDismissedBy(state.dismissedZabbixKeys) }
    }
    val hiddenTotalCount = dismissedActiveProblems.size + state.mutedZabbixRules.size

    val filteredActiveProblems = remember(activeProblems, searchQuery, filterMode) {
        val q = searchQuery.trim().lowercase()
        activeProblems.filter { p ->
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

    val activeDisasterCount = remember(activeProblems) { activeProblems.count { it.severityLevel >= 5 } }
    val activeHighCount = remember(activeProblems) { activeProblems.count { it.severityLevel == 4 } }
    val activeAverageCount = remember(activeProblems) { activeProblems.count { it.severityLevel == 3 } }
    val activeWarningCount = remember(activeProblems) { activeProblems.count { it.severityLevel == 2 } }
    val activeCritAndErrorCount = remember(activeProblems) { activeProblems.count { it.severityLevel >= 3 } }
    val activeUnackedCount = remember(activeProblems) { activeProblems.count { !it.acknowledged } }

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
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Мониторинг Zabbix (${settings.zabbixUrl.ifBlank { AppSettings.DEFAULT_ZABBIX_URL }})",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sub = if (snapshot != null) {
                                val timeStr = DateTimeFormatter.ofPattern("HH:mm:ss")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(snapshot.fetchedAtMillis))
                                buildString {
                                    if (snapshot.serverVersion.isNotBlank()) append("Zabbix v${snapshot.serverVersion} · ")
                                    if (snapshot.monitoredHostsCount > 0) append("Узлов под контролем: ${snapshot.monitoredHostsCount} · ")
                                    append("Обновлено в $timeStr")
                                    if (allServerProblems.size > activeProblems.size) {
                                        append(" · Скрыто старых/нерешаемых: ${allServerProblems.size - activeProblems.size}")
                                    }
                                }
                            } else {
                                "Авторизация по логину и паролю · всплывающий баннер и звук только при попадании в Disaster"
                            }
                            Text(sub, fontSize = 12.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (filterMode != 3 && filteredActiveProblems.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { controller.dismissAllCurrentZabbixProblems(filteredActiveProblems) },
                                border = BorderStroke(1.dp, DesktopColors.CslOrange)
                            ) {
                                Text("✓ Закрыть текущие (${filteredActiveProblems.size}) — только новые", maxLines = 1, softWrap = false)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val webUrl = ZabbixApiClient.normalizeWebBaseUrl(settings.zabbixUrl)
                                runCatching { Desktop.getDesktop().browse(URI(webUrl)) }
                            }
                        ) {
                            Text("🌐 Открыть z.csl.by", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = { controller.triggerTestZabbixBanner() }) {
                            Text("🔔 Тест баннера", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = { showAuthPanel = !showAuthPanel }) {
                            Text(if (showAuthPanel) "Скрыть вход" else "⚙️ Логин / Настройки", maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = { controller.refreshZabbix(silent = false) },
                            enabled = !state.isLoadingZabbix,
                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                        ) {
                            Text("🔄 Обновить", maxLines = 1, softWrap = false)
                        }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DesktopZabbixStatBadge("Аварии (5)", activeDisasterCount, Color(0xFFD93025))
                DesktopZabbixStatBadge("Критич. (4)", activeHighCount, Color(0xFFE65100))
                DesktopZabbixStatBadge("Ошибки (3)", activeAverageCount, DesktopColors.CslOrange)
                DesktopZabbixStatBadge("Предупр. (2)", activeWarningCount, DesktopColors.StatusAmber)

                Spacer(Modifier.width(4.dp))

                FilterChip(
                    selected = filterMode == 0,
                    onClick = { filterMode = 0 },
                    label = { Text("Новые / активные (${activeProblems.size})") }
                )
                FilterChip(
                    selected = filterMode == 1,
                    onClick = { filterMode = 1 },
                    label = { Text("Ошибки и критич. ($activeCritAndErrorCount)") }
                )
                FilterChip(
                    selected = filterMode == 2,
                    onClick = { filterMode = 2 },
                    label = { Text("Неподтв. ($activeUnackedCount)") }
                )
                FilterChip(
                    selected = filterMode == 3,
                    onClick = { filterMode = 3 },
                    label = { Text("🔕 Скрытые ($hiddenTotalCount)") }
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по узлу, IP или проблеме…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (filterMode == 3) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "desktop_muted_rules_card") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "🔕 Нерешаемые задачи (${state.mutedZabbixRules.size}) — уведомления отключены навсегда",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "По этим триггерам баннеры и звук выводиться больше не будут даже при новых срабатываниях.",
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                    if (state.mutedZabbixRules.isNotEmpty()) {
                                        OutlinedButton(onClick = { controller.clearAllMutedZabbixRules() }) {
                                            Text("Очистить список")
                                        }
                                    }
                                }

                                if (state.mutedZabbixRules.isEmpty()) {
                                    Text(
                                        text = "Список пуст. Нажмите «🔕 Не решаема» на любой карточке, чтобы навсегда отключить по ней уведомления.",
                                        fontSize = 12.5.sp,
                                        color = Color(0xFF64748B)
                                    )
                                } else {
                                    val q = searchQuery.trim().lowercase()
                                    state.mutedZabbixRules.filter { r ->
                                        q.isEmpty() ||
                                            r.hostLabel.lowercase().contains(q) ||
                                            r.problemName.lowercase().contains(q)
                                    }.forEach { rule ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFFF8FAFC),
                                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Surface(
                                                    color = desktopZabbixSevColor(rule.severityLevel),
                                                    shape = RoundedCornerShape(5.dp)
                                                ) {
                                                    Text(
                                                        text = ZabbixSeverity.fromLevel(rule.severityLevel).shortBadge,
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = rule.hostLabel.ifBlank { "Триггер Zabbix" },
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = rule.problemName,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF475569)
                                                    )
                                                }
                                                OutlinedButton(
                                                    onClick = { controller.unmuteZabbixRule(rule.ruleKey) }
                                                ) {
                                                    Text("Вернуть уведомления")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "desktop_dismissed_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Закрытые текущие карточки (${dismissedActiveProblems.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (dismissedActiveProblems.isNotEmpty()) {
                                OutlinedButton(onClick = { controller.restoreAllDismissedZabbixProblems() }) {
                                    Text("Вернуть все закрытые")
                                }
                            }
                        }
                    }

                    items(
                        items = dismissedActiveProblems.filter { p ->
                            val q = searchQuery.trim().lowercase()
                            q.isEmpty() ||
                                p.name.lowercase().contains(q) ||
                                p.effectiveHostLabel.lowercase().contains(q) ||
                                p.hostIp.lowercase().contains(q)
                        },
                        key = { "dismissed_" + it.alertKey }
                    ) { prob ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    color = desktopZabbixSevColor(prob.severityLevel),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = prob.severity.shortBadge,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(prob.hostWithIpLabel, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                    Text(prob.name, fontSize = 12.5.sp, color = Color(0xFF475569))
                                }
                                OutlinedButton(onClick = { controller.restoreDismissedZabbixProblem(prob) }) {
                                    Text("Показать снова")
                                }
                                OutlinedButton(onClick = { controller.muteZabbixProblemForever(prob) }) {
                                    Text("🔕 Не решаема")
                                }
                            }
                        }
                    }
                }
            } else if (filteredActiveProblems.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                    border = BorderStroke(1.dp, DesktopColors.StatusGreen.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when {
                                allServerProblems.isEmpty() ->
                                    "✅ Активных проблем на z.csl.by нет — все контролируемые системы работают штатно."
                                activeProblems.isEmpty() ->
                                    "✅ Все старые проблемы закрыты — здесь появятся только новые события."
                                else ->
                                    "✅ По выбранному фильтру активных проблем нет."
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF166534)
                        )
                        if (hiddenTotalCount > 0) {
                            TextButton(onClick = { filterMode = 3 }) {
                                Text("Открыть скрытые и нерешаемые ($hiddenTotalCount)")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredActiveProblems, key = { it.alertKey }) { prob ->
                        val sevColor = desktopZabbixSevColor(prob.severityLevel)
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
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(if (prob.acknowledged) "Коммент." else "✓ Подтв.", fontSize = 12.sp)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val target = prob.hostIp.ifBlank { prob.hostTechnicalName.ifBlank { prob.effectiveHostLabel } }
                                            activeDiagHost = target
                                            controller.runHostDiagnostics(target)
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("🛠 Пинг", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val link = prob.toBannerDisplayTicket(settings.zabbixUrl).webUrl
                                            runCatching { Desktop.getDesktop().browse(URI(link)) }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("🌐 Zabbix", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { controller.muteZabbixProblemForever(prob) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("🔕 Не решаема", fontSize = 12.sp, color = DesktopColors.StatusRed)
                                    }
                                    OutlinedButton(
                                        onClick = { controller.dismissZabbixProblem(prob) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("✕ Скрыть", fontSize = 12.sp)
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

private fun desktopZabbixSevColor(level: Int): Color {
    return when {
        level >= 5 -> Color(0xFFD93025)
        level == 4 -> Color(0xFFE65100)
        level == 3 -> DesktopColors.CslOrange
        level == 2 -> DesktopColors.StatusAmber
        else -> Color(0xFF0284C7)
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
