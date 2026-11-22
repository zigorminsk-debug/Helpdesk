package com.helpdesk.jiraagent.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AdminChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.ui.AdminChatUiState
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QUICK_CHAT_PHRASES = listOf(
    "👀 Смотрю",
    "📞 Звоню клиенту",
    "🚗 На выезде",
    "✅ Сделал",
    "🤝 Перехватите, пожалуйста"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminChatScreen(
    messages: List<AdminChatMessage>,
    chatUiState: AdminChatUiState,
    resolvedRoomKey: String,
    pinnedShiftNote: String,
    appSettings: AppSettings,
    activeTickets: List<HelpdeskTicket>,
    onChatVisibilityChanged: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSelectReply: (AdminChatMessage?) -> Unit,
    onSendMessage: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onSavePinnedNote: (String) -> Unit,
    onSaveRoomSettings: (String, Boolean) -> Unit,
    onOpenTicket: (String) -> Unit,
    onOpenServerSettings: () -> Unit,
    onSendAttachmentUri: (Uri) -> Unit = {},
    loadAttachmentBytes: (suspend (String) -> Result<ByteArray>)? = null,
    connectedUserDisplayName: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            onSendAttachmentUri(pickedUri)
        }
    }
    val colleagueHandles = remember(messages) {
        messages
            .filterNot { it.isOwnMessage }
            .map { it.authorDisplayName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)
    }

    var showRoomSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showPinnedNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showTicketPickerDialog by rememberSaveable { mutableStateOf(false) }
    var deleteCandidateId by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onChatVisibilityChanged(true)
        onDispose {
            onChatVisibilityChanged(false)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                val cur = chatUiState.draftText
                val next = if (cur.isBlank()) spoken else "${cur.trimEnd()} $spoken"
                onDraftChange(next)
            }
        }
    }

    if (showRoomSettingsDialog) {
        ChatRoomSettingsDialog(
            currentManualKey = appSettings.adminChatIssueKey,
            resolvedKey = resolvedRoomKey,
            notificationsEnabled = appSettings.adminChatNotificationsEnabled,
            onDismiss = { showRoomSettingsDialog = false },
            onSave = { manualKey, notifEnabled ->
                showRoomSettingsDialog = false
                onSaveRoomSettings(manualKey, notifEnabled)
            }
        )
    }

    if (showPinnedNoteDialog) {
        EditPinnedShiftNoteDialog(
            initialNote = pinnedShiftNote,
            isSaving = chatUiState.isSavingNote,
            onDismiss = { showPinnedNoteDialog = false },
            onSave = { updated ->
                showPinnedNoteDialog = false
                onSavePinnedNote(updated)
            }
        )
    }

    if (showTicketPickerDialog) {
        val pickableTickets = remember(activeTickets) {
            activeTickets
                .filter { it.isClientTicket && it.stage != TicketStage.CLOSED }
                .take(25)
        }
        AlertDialog(
            onDismissRequest = { showTicketPickerDialog = false },
            title = {
                Text("Упомянуть заявку в чате", fontWeight = FontWeight.Bold)
            },
            text = {
                if (pickableTickets.isEmpty()) {
                    Text(
                        text = "Сейчас нет активных клиентских заявок. Вы можете написать номер вручную, например CSL-37368.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(pickableTickets, key = { it.key }) { t ->
                            Surface(
                                onClick = {
                                    val insertion = "#${t.key} (${t.reporterName}) "
                                    val current = chatUiState.draftText
                                    val updated = if (current.isBlank()) insertion else "${current.trimEnd()} $insertion"
                                    onDraftChange(updated)
                                    showTicketPickerDialog = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "${t.reporterName} · ${t.key}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = CslOrange
                                    )
                                    Text(
                                        text = t.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTicketPickerDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    val pendingDeleteId = deleteCandidateId
    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidateId = null },
            title = { Text("Удалить сообщение?", fontWeight = FontWeight.Bold) },
            text = { Text("Сообщение будет удалено из общего чата админов в Jira.") },
            confirmButton = {
                Button(
                    onClick = {
                        val id = pendingDeleteId
                        deleteCandidateId = null
                        onDeleteMessage(id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslDangerRed)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidateId = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        if (chatUiState.isLoading || chatUiState.isSending || chatUiState.isSavingNote) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = CslOrange
            )
        }

        if (!appSettings.isConfigured) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, CslOrange)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Внутренний чат инженеров Help-CSL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Подключите сервер Jira во вкладке «Настройки», чтобы общаться с коллегами по смене в защищённом внутреннем канале без доступа клиентов.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onOpenServerSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                    ) {
                        Text("Открыть настройки Jira")
                    }
                }
            }
            return@Column
        }

        // Compact room header + Pinned shift handover note
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = null,
                        tint = CslOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Чат техподдержки (внутренний)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (resolvedRoomKey.isNotBlank()) {
                                "Комната Jira: $resolvedRoomKey · клиентам не видна"
                            } else {
                                "Подключение к общей комнате…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showPinnedNoteDialog = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Дежурная заметка смены",
                            tint = if (pinnedShiftNote.isNotBlank()) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить чат",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showRoomSettingsDialog = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки чата",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Pinned shift handover banner
                Surface(
                    onClick = { showPinnedNoteDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    color = if (pinnedShiftNote.isNotBlank()) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (pinnedShiftNote.isNotBlank()) CslOrange.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = CslOrange,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (pinnedShiftNote.isNotBlank()) {
                                "Смена: $pinnedShiftNote"
                            } else {
                                "Закрепить дежурную заметку смены (кто дежурит / техработы)…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pinnedShiftNote.isNotBlank()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                chatUiState.errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        HorizontalDivider()

        // Messages area
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (chatUiState.isLoading) {
                    CircularProgressIndicator(color = CslOrange)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = CslOrange,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = "В комнате пока нет сообщений",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Напишите коллегам или нажмите # ниже, чтобы прикрепить ссылку на заявку (например CSL-37368).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        mentionsMe = !msg.isOwnMessage && AdminChatCodec.isUserMentioned(
                            mentionedHandles = msg.mentionedHandles,
                            displayName = connectedUserDisplayName,
                            usernameOrEmail = appSettings.usernameOrEmail
                        ),
                        loadAttachmentBytes = loadAttachmentBytes,
                        onReply = { onSelectReply(msg) },
                        onDelete = if (msg.isOwnMessage) {
                            { deleteCandidateId = msg.id }
                        } else {
                            null
                        },
                        onTicketClick = onOpenTicket
                    )
                }
            }
        }

        HorizontalDivider()

        // Bottom composer: reply banner + quick status chips + input bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chatUiState.replyTo?.let { reply ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = CslOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ответ для ${reply.authorDisplayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CslOrange
                                )
                                Text(
                                    text = reply.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onSelectReply(null) },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Отменить ответ",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Quick status chips row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { showTicketPickerDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        color = CslMidnight,
                        border = BorderStroke(1.dp, CslOrange)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tag,
                                contentDescription = null,
                                tint = CslOrange,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Заявка",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    colleagueHandles.forEach { fullName ->
                        val handle = "@" + fullName.substringBefore(' ').trim()
                        Surface(
                            onClick = {
                                val cur = chatUiState.draftText
                                val next = if (cur.isBlank()) "$handle " else "${cur.trimEnd()} $handle "
                                onDraftChange(next)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = CslOrange.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = handle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = CslOrange,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    QUICK_CHAT_PHRASES.forEach { phrase ->
                        Surface(
                            onClick = {
                                val cur = chatUiState.draftText
                                val next = if (cur.isBlank()) phrase else "${cur.trimEnd()} $phrase"
                                onDraftChange(next)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = phrase,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Input row with Photo 📸, Voice 🎤 and Send button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        enabled = !chatUiState.isSending,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Прикрепить скриншот / фото",
                            tint = CslOrange
                        )
                    }
                    OutlinedTextField(
                        value = chatUiState.draftText,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = "Сообщение коллегам…",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Надиктуйте сообщение в чат…")
                                    }
                                    runCatching { voiceLauncher.launch(intent) }.onFailure {
                                        Toast.makeText(
                                            context,
                                            "Голосовой ввод недоступен на этом устройстве",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Голосовой ввод",
                                    tint = CslOrange
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CslOrange
                        )
                    )

                    Surface(
                        onClick = onSendMessage,
                        enabled = chatUiState.draftText.isNotBlank() && !chatUiState.isSending,
                        shape = CircleShape,
                        color = if (chatUiState.draftText.isNotBlank() && !chatUiState.isSending) {
                            CslOrange
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить",
                                tint = if (chatUiState.draftText.isNotBlank() && !chatUiState.isSending) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: AdminChatMessage,
    mentionsMe: Boolean = false,
    loadAttachmentBytes: (suspend (String) -> Result<ByteArray>)? = null,
    onReply: () -> Unit,
    onDelete: (() -> Unit)?,
    onTicketClick: (String) -> Unit
) {
    val isMine = message.isOwnMessage
    val timeLabel = remember(message.createdEpochMillis) {
        formatChatTime(message.createdEpochMillis)
    }

    val bubbleBg = when {
        mentionsMe -> MaterialTheme.colorScheme.primaryContainer
        isMine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val bubbleBorder = when {
        mentionsMe -> CslOrange
        isMine -> CslOrange.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isMine) 12.dp else 3.dp,
                bottomEnd = if (isMine) 3.dp else 12.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleBg),
            border = BorderStroke(1.dp, bubbleBorder)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isMine) "Вы (${message.authorDisplayName})" else message.authorDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!message.replyToAuthor.isNullOrBlank() && !message.replyToPreview.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                RoundedCornerShape(6.dp)
                            )
                            .drawBehind {
                                drawRect(
                                    color = CslOrange,
                                    topLeft = Offset.Zero,
                                    size = Size(3.dp.toPx(), size.height)
                                )
                            }
                            .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Column {
                            Text(
                                text = message.replyToAuthor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = CslOrange
                            )
                            Text(
                                text = message.replyToPreview,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (!message.attachmentUrl.isNullOrBlank() && loadAttachmentBytes != null) {
                    val attName = message.attachmentName ?: "скриншот"
                    var bitmap by remember(message.attachmentUrl) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(message.attachmentUrl) {
                        val bytes = loadAttachmentBytes(message.attachmentUrl).getOrNull() ?: return@LaunchedEffect
                        val decoded = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                        bitmap = decoded
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = attName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Text(
                            text = "📎 $attName",
                            style = MaterialTheme.typography.labelSmall,
                            color = CslOrange
                        )
                    }
                }

                if (message.mentionedTicketKeys.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        message.mentionedTicketKeys.forEach { key ->
                            Surface(
                                onClick = { onTicketClick(key) },
                                shape = RoundedCornerShape(6.dp),
                                color = CslMidnight,
                                border = BorderStroke(1.dp, CslOrange)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ConfirmationNumber,
                                        contentDescription = null,
                                        tint = CslOrange,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Открыть $key",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ответить",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CslOrange,
                        modifier = Modifier
                            .clickable(onClick = onReply)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    if (onDelete != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onDelete)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditPinnedShiftNoteDialog(
    initialNote: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var draft by rememberSaveable(initialNote) { mutableStateOf(initialNote) }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                draft = if (draft.isBlank()) spoken else "${draft.trimEnd()} $spoken"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Дежурная заметка смены", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Эта заметка закреплена вверху чата и видна всем админам (кто дежурит, плановые работы, важные сбои).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Текст заметки") },
                    minLines = 3,
                    maxLines = 6,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                }
                                runCatching { voiceLauncher.launch(intent) }.onFailure {
                                    Toast.makeText(context, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосом", tint = CslOrange)
                        }
                    }
                )
                if (draft.isNotBlank()) {
                    TextButton(onClick = { draft = "" }) {
                        Text("Очистить заметку")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun ChatRoomSettingsDialog(
    currentManualKey: String,
    resolvedKey: String,
    notificationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var manualKey by rememberSaveable { mutableStateOf(currentManualKey) }
    var notifOn by rememberSaveable { mutableStateOf(notificationsEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки внутреннего чата", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "По умолчанию Help-CSL автоматически находит или создаёт единую закрытую задачу-комнату в Jira (сейчас: ${resolvedKey.ifBlank { "автопоиск" }}). При желании можно указать ключ задачи вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = manualKey,
                    onValueChange = { manualKey = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ключ комнаты Jira (пусто = авто)") },
                    placeholder = { Text("Например: CSL-100") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Уведомления о сообщениях",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Показывать пуш при новых сообщениях коллег",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notifOn,
                        onCheckedChange = { notifOn = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CslOrange)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(manualKey, notifOn) },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Text("Применить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun formatChatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    val pattern = if (zdt.toLocalDate() == today) "HH:mm" else "dd.MM HH:mm"
    return zdt.format(DateTimeFormatter.ofPattern(pattern))
}
