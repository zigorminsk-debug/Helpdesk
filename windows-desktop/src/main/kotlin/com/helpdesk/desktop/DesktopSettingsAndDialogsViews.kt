package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.model.TtsReadoutMode
import javax.swing.JFileChooser

// =============================================================================
//  1. FILTER SETTINGS WORKSPACE
// =============================================================================

@Composable
fun DesktopFilterWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    var draft by remember(state.filterSettings) { mutableStateOf(state.filterSettings) }
    val activeJqlPreview = remember(draft) { TicketFilterEngine.buildActiveJql(draft) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "🎯 Фильтрация клиентских заявок JSM и отсев автоматики (zabbix)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Клиентские обращения портала Jira Service Management (Service Request, Проблемы с ПК/Ноутбуком, Сейлзмодерн, ЖКХ и др.) попадают в рабочие очереди с уведомлениями, а алерты мониторинга (zabbix, prometheus, боты) направляются во вкладку «Сервисные» без звуковых баннеров.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = draft.projectKeys,
                    onValueChange = { draft = draft.copy(projectKeys = it) },
                    label = { Text("Ключи проектов через запятую (пусто = все доступные, напр. CSL, HD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.excludedReporters,
                    onValueChange = { draft = draft.copy(excludedReporters = it) },
                    label = { Text("Сервисные отправители (уходят в «Сервисные» без звука)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.excludedLabelsAndKeywords,
                    onValueChange = { draft = draft.copy(excludedLabelsAndKeywords = it) },
                    label = { Text("Сервисные метки и ключевые слова") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.excludedIssueTypes,
                    onValueChange = { draft = draft.copy(excludedIssueTypes = it) },
                    label = { Text("Исключённые системные типы задач (по умолчанию только Sub-task / Подзадача)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.customJqlCondition,
                    onValueChange = { draft = draft.copy(customJqlCondition = it) },
                    label = { Text("Дополнительное условие JQL (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.clientOnlyFilterEnabled,
                            onCheckedChange = { draft = draft.copy(clientOnlyFilterEnabled = it) }
                        )
                        Text("Отделять сервисные/бот-заявки от клиентских", fontSize = 12.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.excludeSubtasks,
                            onCheckedChange = { draft = draft.copy(excludeSubtasks = it) }
                        )
                        Text("Скрывать технические подзадачи", fontSize = 12.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.ignoreOwnTickets,
                            onCheckedChange = { draft = draft.copy(ignoreOwnTickets = it) }
                        )
                        Text("Не считать мои собственные заявки клиентскими", fontSize = 12.5.sp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Итоговый JQL активных очередей:", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        Text(activeJqlPreview, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { controller.saveFilterSettings(draft) },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("💾 Сохранить правила фильтра", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { draft = com.helpdesk.jiraagent.model.FilterSettings() }) {
                        Text("Сбросить по умолчанию")
                    }
                }
            }
        }
    }
}

// =============================================================================
//  2. SETTINGS WORKSPACE (JIRA, RB CALENDAR, SOUND, TRAY, AUTO-UPDATE)
// =============================================================================

@Composable
fun DesktopSettingsWorkspace(
    state: DesktopUiState,
    controller: DesktopController,
    onOpenQrDialog: () -> Unit
) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var showSecret by remember { mutableStateOf(false) }
    val scheduleStatus = remember(draft) { BelarusWorkCalendar.evaluate(draft) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Jira Connection
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🌐 Подключение к Jira Server / Data Center и Jira Cloud", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    OutlinedButton(onClick = onOpenQrDialog) {
                        Text("📲 Перенос настроек по QR-коду (Android ↔ Windows)", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = draft.serverUrl,
                    onValueChange = { draft = draft.copy(serverUrl = it) },
                    label = { Text("Адрес сервера Jira (напр. https://jira.company.by или https://company.atlassian.net)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JiraServerType.entries.forEach { st ->
                        FilterChip(
                            selected = draft.serverType == st,
                            onClick = { draft = draft.copy(serverType = st) },
                            label = { Text(st.title, fontSize = 12.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthType.entries.forEach { at ->
                        FilterChip(
                            selected = draft.authType == at,
                            onClick = { draft = draft.copy(authType = at) },
                            label = { Text(at.title, fontSize = 12.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.usernameOrEmail,
                        onValueChange = { draft = draft.copy(usernameOrEmail = it) },
                        label = { Text(draft.authType.loginLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.passwordOrToken,
                        onValueChange = { draft = draft.copy(passwordOrToken = it) },
                        label = { Text(draft.authType.secretLabel) },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showSecret = !showSecret }) {
                                Text(if (showSecret) "Скрыть" else "Показать", fontSize = 11.sp)
                            }
                        },
                        modifier = Modifier.weight(1.2f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = draft.trustSelfSignedSsl,
                        onCheckedChange = { draft = draft.copy(trustSelfSignedSsl = it) }
                    )
                    Text("Доверять корпоративному / самоподписанному SSL-сертификату", fontSize = 12.5.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { controller.saveSettings(draft) },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("💾 Сохранить и синхронизировать", fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { controller.testConnection(draft) },
                        enabled = !state.isPerformingAction
                    ) {
                        Text("🔌 Проверить подключение")
                    }
                }
            }
        }

        // Card 2: RB Working Calendar, Sound & Windows System Tray
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔔 Расписание РБ (09:00–17:30), звук уведомлений и системный трей Windows", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = draft.pollingEnabled,
                            onCheckedChange = { draft = draft.copy(pollingEnabled = it) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Фоновый мониторинг заявок", fontSize = 13.sp)
                    }
                    OutlinedTextField(
                        value = draft.pollIntervalSeconds.toString(),
                        onValueChange = { v ->
                            v.filter(Char::isDigit).toIntOrNull()?.let { sec ->
                                draft = draft.copy(pollIntervalSeconds = sec)
                            }
                        },
                        label = { Text("Интервал опроса (сек)") },
                        singleLine = true,
                        modifier = Modifier.width(190.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.minimizeToTray,
                            onCheckedChange = { controller.setMinimizeToTray(it) }
                        )
                        Text("Сворачивать в системный трей Windows при закрытии окна (✕)", fontSize = 12.5.sp)
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(
                        checked = draft.workScheduleEnabled,
                        onCheckedChange = { draft = draft.copy(workScheduleEnabled = it) }
                    )
                    Column {
                        Text("Рабочие дни Республики Беларусь", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text("${scheduleStatus.statusTitle} — ${scheduleStatus.detailMessage}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.workStartTime,
                        onValueChange = { draft = draft.copy(workStartTime = it) },
                        label = { Text("Начало смены (Минск)") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp)
                    )
                    OutlinedTextField(
                        value = draft.workEndTime,
                        onValueChange = { draft = draft.copy(workEndTime = it) },
                        label = { Text("Окончание смены (Минск)") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp)
                    )
                    OutlinedTextField(
                        value = draft.extraWorkingDates,
                        onValueChange = { draft = draft.copy(extraWorkingDates = it) },
                        label = { Text("Доп. рабочие даты (ДД.ММ.ГГГГ)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.extraHolidayDates,
                        onValueChange = { draft = draft.copy(extraHolidayDates = it) },
                        label = { Text("Доп. выходные (ДД.ММ.ГГГГ)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider()

                Text("Мелодия звукового оповещения Windows:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        AlertSoundPreset.CSL_CHIME,
                        AlertSoundPreset.DISPATCH_PULSE,
                        AlertSoundPreset.HARMONIC_CHORD,
                        AlertSoundPreset.URGENT_ALARM,
                        AlertSoundPreset.CUSTOM_URI
                    ).forEach { preset ->
                        FilterChip(
                            selected = draft.alertSoundPreset == preset,
                            onClick = {
                                draft = draft.copy(alertSoundPreset = preset)
                                DesktopSoundPlayer.playAlert(preset, draft.customAlertSoundUri)
                            },
                            label = { Text(preset.title.take(24), fontSize = 11.5.sp) }
                        )
                    }
                    OutlinedButton(onClick = { DesktopSoundPlayer.playAlert(draft.alertSoundPreset, draft.customAlertSoundUri) }) {
                        Text("🔊 Тест", fontSize = 12.sp)
                    }
                }

                if (draft.alertSoundPreset == AlertSoundPreset.CUSTOM_URI) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft.customAlertSoundUri,
                            onValueChange = { draft = draft.copy(customAlertSoundUri = it) },
                            label = { Text("Путь к собственному WAV-файлу") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            val chooser = JFileChooser()
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                draft = draft.copy(
                                    customAlertSoundUri = chooser.selectedFile.absolutePath,
                                    customAlertSoundTitle = chooser.selectedFile.name
                                )
                            }
                        }) { Text("Выбрать WAV...") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.stormProtectionEnabled,
                            onCheckedChange = { draft = draft.copy(stormProtectionEnabled = it) }
                        )
                        Text("Анти-шторм (группировать пачку ≥4 заявок в одно сводное окно)", fontSize = 12.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.ttsReadoutMode == TtsReadoutMode.ALWAYS,
                            onCheckedChange = {
                                draft = draft.copy(ttsReadoutMode = if (it) TtsReadoutMode.ALWAYS else TtsReadoutMode.OFF)
                            }
                        )
                        Text("Озвучивать новые заявки голосом Windows TTS", fontSize = 12.5.sp)
                    }
                }

                Text("Регион нормализации телефонов клиентов:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneRegion.entries.forEach { reg ->
                        FilterChip(
                            selected = draft.phoneRegion == reg,
                            onClick = { draft = draft.copy(phoneRegion = reg) },
                            label = { Text(reg.title, fontSize = 11.5.sp) }
                        )
                    }
                }

                Button(
                    onClick = { controller.saveSettings(draft) },
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                ) {
                    Text("💾 Сохранить все настройки", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Card 3: GitHub Self-Update (`setup.exe`)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "🚀 Обновление Help-CSL для Windows (текущая версия v${controller.currentVersion})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = state.updateInfo?.let { "Доступен новый установщик setup.exe: версия v${it.versionName}" }
                            ?: "Проверка обновлений выполняется напрямую через GitHub Releases без лимитов API 403.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.checkUpdates(manual = true) },
                        enabled = !state.isCheckingUpdate
                    ) {
                        Text(if (state.isCheckingUpdate) "Проверка..." else "🔄 Проверить обновления")
                    }
                    state.updateInfo?.let { upd ->
                        Button(
                            onClick = { controller.downloadAndRunWindowsSetup(upd) },
                            enabled = state.updateDownloadProgress == null,
                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                        ) {
                            val label = state.updateDownloadProgress?.let { "Скачивание $it%..." }
                                ?: "⬇ Скачать и запустить setup.exe v${upd.versionName}"
                            Text(label, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
//  3. SHIFT ANALYTICS, QR ONBOARDING & FULL PROGRAM GUIDE DIALOGS
// =============================================================================

@Composable
fun DesktopShiftAnalyticsDialog(
    state: DesktopUiState,
    onDismiss: () -> Unit
) {
    val report = remember(state.tickets, state.connectedUserName, state.settings.usernameOrEmail) {
        ShiftAnalyticsCalculator.compute(
            tickets = state.tickets,
            connectedUserDisplayName = state.connectedUserName,
            usernameOrEmail = state.settings.usernameOrEmail
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📊 Статистика и аналитика смены Help-CSL", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(620.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiBox("Новые сейчас", report.activeNewCount.toString(), Modifier.weight(1f))
                    KpiBox("В работе", report.activeInProgressCount.toString(), Modifier.weight(1f))
                    KpiBox("Закрыто мной сегодня", report.closedByMeToday.toString(), Modifier.weight(1f))
                    KpiBox("SLA < 15 мин", "${report.slaGreenPercent}%", Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiBox("Поступило сегодня", report.createdToday.toString(), Modifier.weight(1f))
                    KpiBox("Закрыто сегодня (все)", report.closedToday.toString(), Modifier.weight(1f))
                    KpiBox("За 7 дней поступило", report.createdLast7Days.toString(), Modifier.weight(1f))
                    KpiBox("Ср. ожидание", "${report.avgQueueWaitMinutes} мин", Modifier.weight(1f))
                }

                if (report.engineerStats.isNotEmpty()) {
                    Text("👨‍💻 Выработка инженеров:", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    report.engineerStats.take(8).forEach { eng ->
                        Text(
                            "• ${eng.engineerName}: в работе ${eng.inProgressCount}, закрыто ${eng.closedCount}",
                            fontSize = 12.5.sp
                        )
                    }
                }
                if (report.topClients.isNotEmpty()) {
                    Text("🏢 Топ клиентов по числу обращений:", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    report.topClients.take(8).forEach { cl ->
                        Text(
                            "• ${cl.clientName}: всего ${cl.totalCount} (открыто ${cl.openCount})",
                            fontSize = 12.5.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Закрыть") }
        }
    )
}

@Composable
private fun KpiBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = DesktopColors.CslOrange)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DesktopQrTransferDialog(
    state: DesktopUiState,
    controller: DesktopController,
    onDismiss: () -> Unit
) {
    var includeCredentials by remember { mutableStateOf(true) }
    var pinCode by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    val encodedPayload = remember(state.settings, state.filterSettings, includeCredentials, pinCode) {
        DesktopQrTransferCodec.encode(
            appSettings = state.settings,
            filterSettings = state.filterSettings,
            includeCredentials = includeCredentials,
            pinCode = pinCode
        )
    }
    val qrBitmap = remember(encodedPayload) {
        DesktopQrTransferCodec.renderQrImageBitmap(encodedPayload, sizePx = 280)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📲 QR-перенос настроек Help-CSL (Windows ↔ Android)", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(560.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Наведите камеру телефона в приложении Help-CSL (Настройки → Сканировать QR) на экран монитора, чтобы за 3 секунды перенести адрес сервера, фильтр и токен.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                qrBitmap?.let { bmp ->
                    Image(bitmap = bmp, contentDescription = "QR Help-CSL", modifier = Modifier.size(240.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeCredentials, onCheckedChange = { includeCredentials = it })
                        Text("Включить логин и пароль/токен", fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = { pinCode = it },
                        label = { Text("PIN-код шифрования (необяз.)") },
                        singleLine = true,
                        modifier = Modifier.width(210.dp)
                    )
                    OutlinedButton(onClick = { WindowsExternalLaunchers.copyToClipboard(encodedPayload) }) {
                        Text("📋 Скопировать код")
                    }
                }

                HorizontalDivider()

                Text("Или вставьте текстовый код HELPCSL1:... со смартфона или от коллеги:", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("HELPCSL1:...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            controller.importQrPayload(importText, pinCode)
                            onDismiss()
                        },
                        enabled = importText.startsWith("HELPCSL1:"),
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) { Text("Импортировать") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun DesktopFullGuideDialog(
    version: String,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val sections = remember(version) {
        listOf(
            "1. Назначение и архитектура (Windows setup.exe + Android APK)" to
                "Help-CSL v$version — единое корпоративное рабочее место системного администратора и сервисного инженера в стиле csl.by. Версия для Windows устанавливается самодостаточным инсталлятором setup.exe (не требует прав администратора и не требует установки Java), работает в системном трее Windows и на 100% синхронизируется с мобильной Android-версией через ваш сервер Jira Server / Data Center или Jira Cloud.",
            "2. Вкладка «Заявки» и сплит-панель инспектора" to
                "Слева отображаются 4 компактные вкладки: «Новые», «В работе» (Все/Мои), «Закрытые» (Все/Мои) и «Сервисные» (куда без звуковых баннеров уходят автоматические алерты zabbix/мониторинга). В карточке крупно выделено Имя клиента вверху, а номер CSL-XXXXX компактно расположен ниже. Справа открывается полный инспектор: кнопки «В работу», «Завершить» (с возвратом в очередь), «Отложить на...» (закрывает сейчас → автоматически пересоздаёт в назначенный час → уведомляет → возвращает в «Новые»), «Отклонить» и «Коллеге».",
            "3. Суперспособность Windows: быстрые подключения и скриншоты Win+Shift+S" to
                "Инспектор автоматически распознаёт в тексте и комментариях заявки:\n• ID AnyDesk / RustDesk — кнопка 1-клик запускает установленный AnyDesk.exe / rustdesk.exe с мгновенным подключением;\n• IP-адреса и домены — кнопки запуска RDP (mstsc.exe /v:host), SSH-терминала и встроенной диагностики портов/SSL/DNS;\n• Телефоны +375 и +7 — 1-клик вызов корпоративного SIP-софтфона (MicroSIP, 3CX, PhonerLite по протоколу tel:) и чаты Telegram, Viber, WhatsApp;\n• Кнопка «📸 Вставить скриншот (Win+Shift+S)» забирает снимок экрана прямо из буфера обмена Windows и прикрепляет PNG в Jira без сохранения временных файлов!",
            "4. Вкладка «В ремонт», справочник клиентов Helpdesk и аудит «Удалённые»" to
                "Предназначена для учёта техники в мастерской (REM-1001+). Поле «Клиент / Организация» автоматически подгружает реальных клиентов и организации из Helpdesk и само пополняется при вводе новых заказчиков. Поддерживается ввод серийных номеров ручным USB-сканером штрихкодов и печать актов/квитанций A4 PDF с кириллицей. При нажатии «В удалённые» позиция НЕ стирается бесследно, а переходит во вкладку «🗑 Удалённые» с записью ФИО/логина инженера, времени и кнопкой «↩ Восстановить».",
            "5. Внутренний «Чат» инженеров, пустая очередь с GIF и Habr.com" to
                "Вкладка «Чат» синхронизирует закреп дежурной смены, ответы и кликабельные ссылки CSL-XXXXX между всеми ПК и смартфонами инженеров (отправка по Ctrl+Enter). Когда в очереди пусто, отображаются плавные анимации Роя и Мосса (GIF #1 и #7) с цитатой и живой лентой статей Habr.com.",
            "6. Производственный календарь РБ (09:00–17:30), трей и автообновление setup.exe" to
                "Встроенный календарь Республики Беларусь учитывает праздники РБ (включая Радуницу) и переносы Совмина РБ: вне рабочих часов (09:00–17:30 Минск) приложение тихо обновляет очередь в системном трее без звуков и всплывающих окон. Проверка обновлений скачивает и запускает свежий setup.exe напрямую с GitHub Releases."
        )
    }

    val filtered = remember(sections, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) sections
        else sections.filter { (t, b) -> t.lowercase().contains(q) || b.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📖 Полное руководство по программе Help-CSL v$version", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(680.dp).heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Поиск по руководству (AnyDesk, В ремонт, Удалённые, Win+Shift+S, РБ)...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    filtered.forEach { (title, body) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = DesktopColors.CslOrange)
                                Text(body, fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Понятно") }
        }
    )
}
