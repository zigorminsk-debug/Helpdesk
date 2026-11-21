package com.helpdesk.jiraagent.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.BuildConfig
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.ui.AppUpdateUiState
import com.helpdesk.jiraagent.ui.ConnectionTestState
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.util.DialerLauncher

private val POLL_INTERVAL_OPTIONS = listOf(
    15 to "15 сек",
    30 to "30 сек",
    60 to "1 мин",
    120 to "2 мин",
    300 to "5 мин"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerSettingsScreen(
    currentSettings: AppSettings,
    connectionTest: ConnectionTestState,
    appUpdateState: AppUpdateUiState = AppUpdateUiState(),
    onTestConnection: (AppSettings) -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onTriggerTestBanner: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onDownloadAndInstallUpdate: () -> Unit = {},
    onInstallReadyUpdate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(currentSettings) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var samplePhoneInput by rememberSaveable { mutableStateOf("8 (029) 654-32-10") }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val pickedUri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (pickedUri != null) {
                val title = runCatching {
                    RingtoneManager.getRingtone(context, pickedUri)?.getTitle(context)
                }.getOrNull().orEmpty().ifBlank { "Системная мелодия" }
                val updated = draft.copy(
                    alertSoundPreset = AlertSoundPreset.CUSTOM_URI,
                    customAlertSoundUri = pickedUri.toString(),
                    customAlertSoundTitle = title
                )
                draft = updated
                onSaveSettings(updated)
                AlertSoundPlayer.playTicketAlert(context, updated, forcePreview = true)
            }
        }
    }

    val audioDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val fileName = runCatching {
                context.contentResolver.query(pickedUri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
                }
            }.getOrNull().orEmpty().ifBlank { "Аудиофайл с телефона" }

            val updated = draft.copy(
                alertSoundPreset = AlertSoundPreset.CUSTOM_URI,
                customAlertSoundUri = pickedUri.toString(),
                customAlertSoundTitle = fileName
            )
            draft = updated
            onSaveSettings(updated)
            AlertSoundPlayer.playTicketAlert(context, updated, forcePreview = true)
        }
    }

    LaunchedEffect(currentSettings) {
        draft = currentSettings
    }

    val sampleNormalized = remember(samplePhoneInput, draft.phoneRegion, draft.customCountryCode) {
        PhoneNumberNormalizer.normalizeSingle(
            rawInput = samplePhoneInput,
            region = draft.phoneRegion,
            customCountryCode = draft.customCountryCode,
            strictForFreeText = false
        )
    }

    val rbCalendarStatus = remember(
        draft.workScheduleEnabled,
        draft.workStartTime,
        draft.workEndTime,
        draft.extraWorkingDates,
        draft.extraHolidayDates
    ) {
        BelarusWorkCalendar.evaluate(draft)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Jira Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Подключение к серверу Jira",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = draft.serverUrl,
                    onValueChange = { draft = draft.copy(serverUrl = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL сервера Jira") },
                    placeholder = { Text("https://jira.company.by или https://org.atlassian.net") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                Text(
                    text = "Тип сервера",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                JiraServerType.entries.forEach { serverType ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = draft.serverType == serverType,
                            onClick = { draft = draft.copy(serverType = serverType) }
                        )
                        Column {
                            Text(serverType.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                serverType.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "Способ авторизации",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                AuthType.entries.forEach { authType ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = draft.authType == authType,
                            onClick = { draft = draft.copy(authType = authType) }
                        )
                        Text(authType.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OutlinedTextField(
                    value = draft.usernameOrEmail,
                    onValueChange = { draft = draft.copy(usernameOrEmail = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(draft.authType.loginLabel) },
                    singleLine = true
                )

                OutlinedTextField(
                    value = draft.passwordOrToken,
                    onValueChange = { draft = draft.copy(passwordOrToken = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(draft.authType.secretLabel) },
                    singleLine = true,
                    visualTransformation = if (showSecret) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Показать/скрыть"
                            )
                        }
                    }
                )

                SwitchRow(
                    title = "Доверять самоподписанным SSL",
                    subtitle = "Для внутренних корпоративных серверов Jira в локальной сети или VPN",
                    checked = draft.trustSelfSignedSsl,
                    onCheckedChange = { draft = draft.copy(trustSelfSignedSsl = it) }
                )

                OutlinedButton(
                    onClick = { onTestConnection(draft) },
                    enabled = !connectionTest.isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (connectionTest.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Проверить подключение")
                }

                if (connectionTest.message != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (connectionTest.isSuccess == true) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.ErrorOutline
                            },
                            contentDescription = null,
                            tint = if (connectionTest.isSuccess == true) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = connectionTest.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectionTest.isSuccess == true) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }

        // 2. Background Monitoring Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Фоновый мониторинг и баннеры",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                SwitchRow(
                    title = "Активный фоновый агент",
                    subtitle = "Поддерживать постоянное подключение и выводить баннер при новой заявке от клиента",
                    checked = draft.pollingEnabled,
                    onCheckedChange = { draft = draft.copy(pollingEnabled = it) }
                )

                Text(
                    text = "Интервал проверки новых заявок",
                    style = MaterialTheme.typography.labelLarge
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    POLL_INTERVAL_OPTIONS.forEach { (sec, label) ->
                        FilterChip(
                            selected = draft.pollIntervalSeconds == sec,
                            onClick = { draft = draft.copy(pollIntervalSeconds = sec) },
                            label = { Text(label, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                SwitchRow(
                    title = "Автозапуск при включении телефона",
                    subtitle = "Автоматически запускать агент после перезагрузки или обновления APK поверх",
                    checked = draft.startOnBoot,
                    onCheckedChange = { draft = draft.copy(startOnBoot = it) }
                )
            }
        }

        // 2a. Выбор мелодии звукового сигнала и всплывающие баннеры
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Мелодия сигнала и всплывающий баннер",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CslOrange
                )

                Text(
                    text = "Выберите мелодию оповещения о новой заявке (звучит автоматически при поступлении заявки и при срабатывании отложенной заявки):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AlertSoundPreset.entries.forEach { preset ->
                    if (preset == AlertSoundPreset.CUSTOM_URI && draft.customAlertSoundUri.isBlank()) {
                        return@forEach
                    }
                    val isSelected = draft.alertSoundPreset == preset
                    val subtitleText = if (preset == AlertSoundPreset.CUSTOM_URI) {
                        "Файл: ${draft.customAlertSoundTitle.ifBlank { "Выбранная мелодия" }}"
                    } else {
                        preset.subtitle
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = draft.copy(alertSoundPreset = preset)
                                draft = updated
                                onSaveSettings(updated)
                                AlertSoundPlayer.playTicketAlert(context, updated, forcePreview = true)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                val updated = draft.copy(alertSoundPreset = preset)
                                draft = updated
                                onSaveSettings(updated)
                                AlertSoundPlayer.playTicketAlert(context, updated, forcePreview = true)
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val existingUri = draft.customAlertSoundUri
                                .takeIf { it.isNotBlank() }
                                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                                    RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_ALARM
                                )
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Выберите мелодию сигнала Help-CSL")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
                            }
                            runCatching { ringtonePickerLauncher.launch(intent) }
                                .onFailure {
                                    Toast.makeText(context, "Не удалось открыть список рингтонов", Toast.LENGTH_SHORT).show()
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Рингтон телефона", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            runCatching { audioDocumentLauncher.launch(arrayOf("audio/*")) }
                                .onFailure {
                                    Toast.makeText(context, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Свой MP3/файл", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            AlertSoundPlayer.playTicketAlert(context, draft, forcePreview = true)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Прослушать")
                    }

                    OutlinedButton(
                        onClick = { AlertSoundPlayer.stopCurrent() }
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Стоп")
                    }
                }

                Button(
                    onClick = {
                        onSaveSettings(draft)
                        onTriggerTestBanner()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CslMidnight)
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тест всплывающего баннера и звука")
                }

                OutlinedButton(
                    onClick = { NotificationHelper.openBannerChannelSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.SettingsApplications, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Включить всплывающие баннеры в MIUI / Android")
                }
            }
        }

        // 2b. Рабочие дни (календарь РБ) + кнопка уведомлений в нерабочее время
        val allowOffHours = !draft.workScheduleEnabled
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Рабочие дни (календарь РБ)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CslOrange
                )

                Text(
                    text = if (allowOffHours) {
                        "Сейчас уведомления в нерабочее время ВКЛЮЧЕНЫ: баннеры и звук приходят круглосуточно (24/7), включая вечера, выходные и праздники."
                    } else {
                        "Сейчас уведомления в нерабочее время ОТКЛЮЧЕНЫ: баннеры и звук работают строго в рабочие дни РБ (${draft.workStartTime}–${draft.workEndTime})."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Dedicated prominent button requested by user to toggle non-working hours notifications
                Button(
                    onClick = {
                        val updated = draft.copy(workScheduleEnabled = allowOffHours)
                        draft = updated
                        onSaveSettings(updated)
                        val msg = if (!updated.workScheduleEnabled) {
                            "Уведомления в нерабочее время включены (24/7)"
                        } else {
                            "Уведомления в нерабочее время отключены (только рабочие дни РБ)"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allowOffHours) CslMidnight else CslOrange
                    )
                ) {
                    Icon(
                        imageVector = if (allowOffHours) {
                            Icons.Default.NotificationsOff
                        } else {
                            Icons.Default.NotificationsActive
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allowOffHours) {
                            "Отключить уведомления в нерабочее время"
                        } else {
                            "Включить уведомления в нерабочее время"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                SwitchRow(
                    title = "Уведомления в нерабочее время",
                    subtitle = "Показывать баннеры и подавать звуковой сигнал также вечером, ночью и в выходные дни",
                    checked = allowOffHours,
                    onCheckedChange = { enabledOffHours ->
                        val updated = draft.copy(workScheduleEnabled = !enabledOffHours)
                        draft = updated
                        onSaveSettings(updated)
                    }
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (rbCalendarStatus.isAllowedNow) {
                            CslSuccessGreen.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = rbCalendarStatus.statusTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (rbCalendarStatus.isAllowedNow) {
                                CslSuccessGreen
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = rbCalendarStatus.detailMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (draft.workScheduleEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = draft.workStartTime,
                            onValueChange = { draft = draft.copy(workStartTime = it) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Начало", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { Text("09:00") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draft.workEndTime,
                            onValueChange = { draft = draft.copy(workEndTime = it) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Окончание", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { Text("17:30") },
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = draft.extraWorkingDates,
                        onValueChange = { draft = draft.copy(extraWorkingDates = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Доп. рабочие субботы (необязательно)") },
                        placeholder = { Text("ДД.ММ.ГГГГ через запятую (основные переносы РБ уже встроены)") }
                    )

                    OutlinedTextField(
                        value = draft.extraHolidayDates,
                        onValueChange = { draft = draft.copy(extraHolidayDates = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Доп. выходные дни (необязательно)") },
                        placeholder = { Text("ДД.ММ.ГГГГ через запятую") }
                    )
                }
            }
        }

        // 3. International Phone Formatting Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Международный формат телефонов",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PhoneRegion.entries.forEach { region ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = draft.phoneRegion == region,
                            onClick = { draft = draft.copy(phoneRegion = region) }
                        )
                        Column {
                            Text(region.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                region.example,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (draft.phoneRegion == PhoneRegion.CUSTOM) {
                    OutlinedTextField(
                        value = draft.customCountryCode,
                        onValueChange = { draft = draft.copy(customCountryCode = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Международный код страны") },
                        placeholder = { Text("+375") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                }

                OutlinedTextField(
                    value = draft.customPhoneFieldNames,
                    onValueChange = { draft = draft.copy(customPhoneFieldNames = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Названия полей телефона в Jira") },
                    placeholder = { Text("Телефон, Phone, customfield_10100") }
                )

                // Interactive tester for international normalization + default dialer
                OutlinedTextField(
                    value = samplePhoneInput,
                    onValueChange = { samplePhoneInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Тест приведения номера к международному формату") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    trailingIcon = {
                        if (sampleNormalized != null) {
                            IconButton(
                                onClick = {
                                    DialerLauncher.openSystemDialer(
                                        context = context,
                                        phoneInput = sampleNormalized.first,
                                        region = draft.phoneRegion,
                                        customCountryCode = draft.customCountryCode
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Проверить звонилку")
                            }
                        }
                    }
                )

                Text(
                    text = if (sampleNormalized != null) {
                        "Результат: ${sampleNormalized.second} (набор: tel:${sampleNormalized.first})"
                    } else {
                        "Введите номер для проверки конвертации"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (sampleNormalized != null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Button(
            onClick = {
                onSaveSettings(draft)
                Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сохранить настройки", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // 4. GitHub Auto-Update & Version card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = CslOrange
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Автообновление с GitHub",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Установлена: v${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Автоматически проверять новые версии",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "При запуске и в фоне проверять релизы на GitHub и предлагать обновление",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draft.autoCheckUpdates,
                        onCheckedChange = { enabled ->
                            val updated = draft.copy(autoCheckUpdates = enabled)
                            draft = updated
                            onSaveSettings(updated)
                        }
                    )
                }

                val availableUpdate = appUpdateState.availableUpdate
                if (availableUpdate != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Доступна новая версия: Help-CSL v${availableUpdate.versionName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )

                            if (appUpdateState.isDownloading) {
                                val progress = if (appUpdateState.totalBytes > 0L) {
                                    (appUpdateState.downloadedBytes.toFloat() / appUpdateState.totalBytes.toFloat())
                                        .coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                if (progress != null) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    val doneMb = appUpdateState.downloadedBytes / (1024f * 1024f)
                                    val totalMb = appUpdateState.totalBytes / (1024f * 1024f)
                                    Text(
                                        text = String.format(
                                            java.util.Locale.US,
                                            "Скачано %.1f / %.1f МБ (%d%%)",
                                            doneMb,
                                            totalMb,
                                            (progress * 100).toInt()
                                        ),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (appUpdateState.readyApkPath != null) {
                                            onInstallReadyUpdate()
                                        } else {
                                            onDownloadAndInstallUpdate()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (appUpdateState.readyApkPath != null) {
                                            "Установить скачанный APK (v${availableUpdate.versionName})"
                                        } else {
                                            "Скачать и обновить до v${availableUpdate.versionName}"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                appUpdateState.statusMessage?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (appUpdateState.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                OutlinedButton(
                    onClick = onCheckForUpdate,
                    enabled = !appUpdateState.isChecking && !appUpdateState.isDownloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (appUpdateState.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверка GitHub…")
                    } else {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверить наличие новой версии")
                    }
                }

                Text(
                    text = "Пакет: ${BuildConfig.APPLICATION_ID} · Обновление ставится поверх текущей версии с сохранением всех настроек.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
