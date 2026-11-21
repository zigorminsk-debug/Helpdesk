package com.helpdesk.jiraagent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.ui.TicketActionUiState
import com.helpdesk.jiraagent.ui.components.ClickablePhoneText
import com.helpdesk.jiraagent.ui.components.MessengerActionChip
import com.helpdesk.jiraagent.util.DialerLauncher
import com.helpdesk.jiraagent.util.MessengerApp
import com.helpdesk.jiraagent.util.MessengerLauncher

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TicketDetailScreen(
    ticket: HelpdeskTicket?,
    ticketKey: String,
    isLoading: Boolean,
    actionState: TicketActionUiState,
    appSettings: AppSettings,
    onExecuteAction: (TicketWorkflowAction, String?, String?) -> Unit,
    onDismissActionMessage: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    var dialogAction by rememberSaveable { mutableStateOf<TicketWorkflowAction?>(null) }
    var dialogComment by rememberSaveable { mutableStateOf("") }

    if (dialogAction != null && ticket != null) {
        val currentDialogAction = dialogAction!!
        val isReject = currentDialogAction == TicketWorkflowAction.REJECT
        AlertDialog(
            onDismissRequest = {
                dialogAction = null
                dialogComment = ""
            },
            title = {
                Text(
                    text = if (isReject) "Отклонить заявку ${ticket.key}?" else "Завершить заявку ${ticket.key}?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isReject) {
                            "Заявка будет переведена в отклонённые / отменённые. При необходимости укажите причину:"
                        } else {
                            "Заявка будет переведена в выполненные / закрытые. При необходимости оставьте комментарий:"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = dialogComment,
                        onValueChange = { dialogComment = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                if (isReject) "Причина отклонения (необязательно)" else "Комментарий к решению (необязательно)"
                            )
                        },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val commentToSend = dialogComment.trim().takeIf { it.isNotEmpty() }
                        dialogAction = null
                        dialogComment = ""
                        onExecuteAction(currentDialogAction, commentToSend, null)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReject) MaterialTheme.colorScheme.error else Color(0xFF1B873F),
                        contentColor = Color.White
                    )
                ) {
                    Text(if (isReject) "Отклонить" else "Завершить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dialogAction = null
                        dialogComment = ""
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (actionState.manualTransitionPrompt &&
        actionState.availableTransitions.isNotEmpty() &&
        ticket != null
    ) {
        AlertDialog(
            onDismissRequest = onDismissActionMessage,
            title = {
                Text("Выберите переход статуса Jira", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = actionState.statusMessage ?: "Доступные переходы для ${ticket.key}:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    actionState.availableTransitions.forEach { tr ->
                        OutlinedButton(
                            onClick = {
                                val act = actionState.pendingAction ?: TicketWorkflowAction.TAKE_IN_WORK
                                onExecuteAction(act, null, tr.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${tr.name} → ${tr.toStatusName}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissActionMessage) {
                    Text("Закрыть")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ticket?.key ?: ticketKey,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (!ticket?.webUrl.isNullOrBlank()) {
                        IconButton(onClick = { DialerLauncher.openExternalUrl(context, ticket!!.webUrl) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в браузере")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLoading || actionState.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (ticket == null) {
                Text(
                    text = if (isLoading) {
                        "Загрузка данных заявки $ticketKey…"
                    } else {
                        "Не удалось найти данные заявки $ticketKey"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                return@Column
            }

            // 1. Header metadata card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetaBadge(ticket.status)
                        MetaBadge("Приоритет: ${ticket.priority}")
                        MetaBadge(ticket.customerRequestType ?: ticket.issueType)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = ticket.summary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Клиент: ${ticket.reporterName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Исполнитель: ${ticket.assigneeName ?: "не назначен"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (ticket.assigneeName != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Создана: ${formatTicketTime(ticket.createdEpochMillis, ticket.createdIso)} · Проект: ${ticket.projectName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. Workflow Action Buttons Card ("В работу" + assign logged-in user, "Завершить", "Отклонить")
            WorkflowActionsCard(
                actionState = actionState,
                onTakeInWork = { onExecuteAction(TicketWorkflowAction.TAKE_IN_WORK, null, null) },
                onComplete = { dialogAction = TicketWorkflowAction.COMPLETE },
                onReject = { dialogAction = TicketWorkflowAction.REJECT }
            )

            // 3. Clickable Phone + Messenger Icons Section (Telegram, Viber, WhatsApp)
            PhoneAndMessengerSection(
                phones = ticket.phoneNumbers,
                telegramHandles = ticket.telegramHandles.orEmpty(),
                onDial = { e164 ->
                    DialerLauncher.openSystemDialer(
                        context = context,
                        phoneInput = e164,
                        region = appSettings.phoneRegion,
                        customCountryCode = appSettings.customCountryCode
                    )
                },
                onCopy = { e164 -> copyToClipboard(context, e164) },
                onOpenMessenger = { app, e164 ->
                    MessengerLauncher.openChatByPhone(context, app, e164)
                },
                onOpenTelegramHandle = { handle ->
                    MessengerLauncher.openTelegramUsername(context, handle)
                }
            )

            // 4. Ticket Description
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Описание заявки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (ticket.description.isBlank()) {
                        Text(
                            text = "Описание отсутствует",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ClickablePhoneText(
                            text = ticket.description,
                            region = appSettings.phoneRegion,
                            customCountryCode = appSettings.customCountryCode,
                            onPhoneClick = { e164 ->
                                DialerLauncher.openSystemDialer(
                                    context = context,
                                    phoneInput = e164,
                                    region = appSettings.phoneRegion,
                                    customCountryCode = appSettings.customCountryCode
                                )
                            }
                        )
                    }
                }
            }

            // 5. Comments
            if (ticket.comments.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Комментарии (${ticket.comments.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ticket.comments.forEachIndexed { index, comment ->
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                            }
                            Text(
                                text = comment.author,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            ClickablePhoneText(
                                text = comment.body,
                                region = appSettings.phoneRegion,
                                customCountryCode = appSettings.customCountryCode,
                                onPhoneClick = { e164 ->
                                    DialerLauncher.openSystemDialer(
                                        context = context,
                                        phoneInput = e164,
                                        region = appSettings.phoneRegion,
                                        customCountryCode = appSettings.customCountryCode
                                    )
                                }
                            )
                        }
                    }
                }
            }

            if (ticket.webUrl.isNotBlank()) {
                OutlinedButton(
                    onClick = { DialerLauncher.openExternalUrl(context, ticket.webUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Открыть ${ticket.key} в браузере",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowActionsCard(
    actionState: TicketActionUiState,
    onTakeInWork: () -> Unit,
    onComplete: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Действия по заявке",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Primary action: Take in work & assign logged-in user
            Button(
                onClick = onTakeInWork,
                enabled = !actionState.isBusy,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (actionState.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.AssignmentInd, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "В работу (назначить меня)",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Secondary row: Complete & Reject — adapts automatically to ultra-narrow screens
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 280.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onComplete,
                            enabled = !actionState.isBusy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B873F),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Завершить",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onReject,
                            enabled = !actionState.isBusy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Отклонить",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onComplete,
                            enabled = !actionState.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B873F),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Завершить", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Button(
                            onClick = onReject,
                            enabled = !actionState.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отклонить", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }

            if (actionState.statusMessage != null && !actionState.manualTransitionPrompt) {
                Surface(
                    color = if (actionState.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (actionState.isError) {
                                Icons.Default.ErrorOutline
                            } else {
                                Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (actionState.isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = actionState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (actionState.isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneAndMessengerSection(
    phones: List<PhoneMatch>,
    telegramHandles: List<String>,
    onDial: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenMessenger: (MessengerApp, String) -> Unit,
    onOpenTelegramHandle: (String) -> Unit
) {
    if (phones.isEmpty() && telegramHandles.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PhoneDisabled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Контактный телефон и мессенджер в полях заявки не обнаружены",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Связь с клиентом (звонок и мессенджеры)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            phones.forEach { phone ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDial(phone.e164) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = phone.formattedDisplay,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = if (phone.raw.trim() != phone.e164 &&
                                    phone.raw.trim() != phone.formattedDisplay
                                ) {
                                    "Исходный: ${phone.raw} → ${phone.e164} · ${phone.source}"
                                } else {
                                    "${phone.e164} · ${phone.source}"
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(onClick = { onCopy(phone.e164) }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать номер"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { onDial(phone.e164) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Позвонить ${phone.formattedDisplay}",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Adaptive 3-messenger row: fits Telegram, Viber & WhatsApp cleanly on any screen
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            if (maxWidth >= 260.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    MessengerActionChip(
                                        app = MessengerApp.TELEGRAM,
                                        onClick = { onOpenMessenger(MessengerApp.TELEGRAM, phone.e164) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    MessengerActionChip(
                                        app = MessengerApp.VIBER,
                                        onClick = { onOpenMessenger(MessengerApp.VIBER, phone.e164) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    MessengerActionChip(
                                        app = MessengerApp.WHATSAPP,
                                        onClick = { onOpenMessenger(MessengerApp.WHATSAPP, phone.e164) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    MessengerActionChip(
                                        app = MessengerApp.TELEGRAM,
                                        onClick = { onOpenMessenger(MessengerApp.TELEGRAM, phone.e164) }
                                    )
                                    MessengerActionChip(
                                        app = MessengerApp.VIBER,
                                        onClick = { onOpenMessenger(MessengerApp.VIBER, phone.e164) }
                                    )
                                    MessengerActionChip(
                                        app = MessengerApp.WHATSAPP,
                                        onClick = { onOpenMessenger(MessengerApp.WHATSAPP, phone.e164) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (telegramHandles.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Telegram-контакт из текста заявки",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            telegramHandles.forEach { handle ->
                                MessengerActionChip(
                                    app = MessengerApp.TELEGRAM,
                                    customLabel = "Открыть $handle",
                                    onClick = { onOpenTelegramHandle(handle) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun copyToClipboard(context: Context, phoneE164: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Телефон клиента", phoneE164))
    Toast.makeText(context, "Скопировано: $phoneE164", Toast.LENGTH_SHORT).show()
}
