package com.helpdesk.jiraagent.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.data.JiraAssignableUser
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.PostponeDateTimeParser
import com.helpdesk.jiraagent.domain.TicketReminder
import com.helpdesk.jiraagent.service.TicketReminderScheduler
import com.helpdesk.jiraagent.domain.SysadminEntityExtractor
import com.helpdesk.jiraagent.domain.TicketStopwatchEntry
import com.helpdesk.jiraagent.domain.TicketStopwatchTracker
import com.helpdesk.jiraagent.model.ClientTechPassport
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TicketTimingFormatter
import com.helpdesk.jiraagent.ui.components.AssignColleagueDialog
import com.helpdesk.jiraagent.ui.components.ClientDossierCard
import com.helpdesk.jiraagent.shared.ClientRequestAnalysisView
import com.helpdesk.jiraagent.ui.components.ClientTechPassportAndroidDialog
import com.helpdesk.jiraagent.ui.components.KnowledgeBaseArchiveAndroidDialog
import com.helpdesk.jiraagent.ui.components.ServiceActPdfDialog
import com.helpdesk.jiraagent.ui.components.WorklogAndPostCallDialog
import com.helpdesk.jiraagent.ui.components.rememberSafeBarcodeScanner
import com.helpdesk.jiraagent.util.ServiceActInput
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.helpdesk.jiraagent.domain.QuickReplyTemplates
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.TicketAttachment
import com.helpdesk.jiraagent.ui.ClientHistoryUiState
import com.helpdesk.jiraagent.ui.HostDiagnosticUiState
import com.helpdesk.jiraagent.ui.TicketActionUiState
import com.helpdesk.jiraagent.ui.components.ClickablePhoneText
import com.helpdesk.jiraagent.ui.components.ClientHistoryCard
import com.helpdesk.jiraagent.ui.components.HostDiagnosticDialog
import com.helpdesk.jiraagent.ui.components.MessengerActionChip
import com.helpdesk.jiraagent.ui.components.QuickReplyMacrosBar
import com.helpdesk.jiraagent.ui.components.SysadminEntitiesCard
import com.helpdesk.jiraagent.ui.components.TicketAttachmentsCard
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.util.DialerLauncher
import com.helpdesk.jiraagent.util.MessengerApp
import com.helpdesk.jiraagent.util.MessengerLauncher
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TicketDetailScreen(
    ticket: HelpdeskTicket?,
    confirmedSolutions: List<com.helpdesk.jiraagent.domain.ConfirmedSolution>,
    onAddSolution: (HelpdeskTicket, String, String) -> Boolean,
    onVoteSolution: (HelpdeskTicket, String, Int) -> Boolean,
    onDeleteSolution: (String) -> Boolean,
    ticketKey: String,
    isLoading: Boolean,
    actionState: TicketActionUiState,
    clientHistoryState: ClientHistoryUiState,
    analysisLoadedTickets: List<HelpdeskTicket> = emptyList(),
    hostDiagnosticState: HostDiagnosticUiState,
    appSettings: AppSettings,
    engineerDisplayName: String = "",
    clientDossierNote: String = "",
    assignableColleagues: List<JiraAssignableUser> = emptyList(),
    isLoadingColleagues: Boolean = false,
    onSaveClientDossier: (String, String) -> Unit = { _, _ -> },
    onLoadColleagues: () -> Unit = {},
    onAssignColleague: (JiraAssignableUser) -> Unit = {},
    onLogWorklog: (minutes: Int, comment: String, alsoComplete: Boolean) -> Unit = { _, _, _ -> },
    onGenerateServiceActPdf: (ServiceActInput) -> Unit = {},
    onScanSerialHistory: (String) -> Unit = {},
    onSendToRepair: (HelpdeskTicket, String) -> Unit = { _, _ -> },
    stopwatchEntry: TicketStopwatchEntry? = null,
    onToggleStopwatch: () -> Unit = {},
    onResetStopwatch: () -> Unit = {},
    clientPassport: ClientTechPassport? = null,
    onSaveClientPassport: (ClientTechPassport) -> Unit = {},
    onOpenClientInWiki: (String) -> Unit = {},
    knowledgeResults: List<HelpdeskTicket> = emptyList(),
    isSearchingKnowledge: Boolean = false,
    onSearchKnowledge: (String) -> Unit = {},
    loadAttachmentBytes: suspend (String) -> Result<ByteArray>,
    onUploadAttachmentUri: (Uri, String?) -> Unit,
    onOpenExternalAttachment: (TicketAttachment) -> Unit,
    onRunHostDiagnostics: (String) -> Unit,
    onDismissHostDiagnostics: () -> Unit,
    onDiscussInChat: (HelpdeskTicket) -> Unit,
    onOpenHistoricalTicket: (String) -> Unit,
    onExecuteAction: (TicketWorkflowAction, String?, String?) -> Unit,
    onPostponeTicket: (Long, String, String?) -> Unit,
    onAddComment: (String, Boolean) -> Unit,
    onDismissActionMessage: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var commentDialogAction by rememberSaveable { mutableStateOf<TicketWorkflowAction?>(null) }
    var commentDraft by rememberSaveable { mutableStateOf("") }
    var standaloneCommentDraft by rememberSaveable { mutableStateOf("") }
    var isInternalComment by rememberSaveable { mutableStateOf(false) }
    var showPostponeDialog by rememberSaveable { mutableStateOf(false) }
    var showWorklogDialog by rememberSaveable { mutableStateOf(false) }
    var showColleagueDialog by rememberSaveable { mutableStateOf(false) }
    var showServiceActDialog by rememberSaveable { mutableStateOf(false) }
    var showPassportDialog by rememberSaveable { mutableStateOf(false) }
    var showKbArchiveDialog by rememberSaveable { mutableStateOf(false) }
    var scannedSerialForActOrChoice by rememberSaveable { mutableStateOf("") }
    var showScannedSerialChoiceDialog by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var stopwatchNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticketKey, stopwatchEntry?.isRunning, lifecycleOwner) {
        if (stopwatchEntry?.isRunning == true) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    stopwatchNowMs = System.currentTimeMillis()
                    delay(1_000L)
                }
            }
        }
        stopwatchNowMs = System.currentTimeMillis()
    }
    val stopwatchDisplay = remember(stopwatchEntry, stopwatchNowMs) {
        TicketStopwatchTracker.formatTimerDisplay(stopwatchEntry, stopwatchNowMs)
    }

    val launchDetailScanner = rememberSafeBarcodeScanner(
        prompt = "Наведите камеру на штрихкод / S/N оборудования"
    ) { scanned ->
        scannedSerialForActOrChoice = scanned
        showScannedSerialChoiceDialog = true
    }

    if (showScannedSerialChoiceDialog && scannedSerialForActOrChoice.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showScannedSerialChoiceDialog = false },
            title = { Text("Распознан код: $scannedSerialForActOrChoice", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выберите действие с серийным / инвентарным номером:")
                    if (ticket != null) {
                        OutlinedButton(
                            onClick = {
                                val sn = scannedSerialForActOrChoice
                                showScannedSerialChoiceDialog = false
                                onSendToRepair(ticket, sn)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, CslOrange)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = CslOrange, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Занести во вкладку «В ремонт»")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showScannedSerialChoiceDialog = false
                        onScanSerialHistory(scannedSerialForActOrChoice)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Найти историю в Jira")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val addition = "Оборудование S/N: $scannedSerialForActOrChoice"
                        standaloneCommentDraft = if (standaloneCommentDraft.isBlank()) {
                            addition
                        } else {
                            "${standaloneCommentDraft.trimEnd()}\n$addition"
                        }
                        showScannedSerialChoiceDialog = false
                    }
                ) {
                    Text("В комментарий")
                }
            }
        )
    }

    if (showWorklogDialog && ticket != null) {
        WorklogAndPostCallDialog(
            ticketKey = ticket.key,
            clientName = ticket.reporterName,
            callSession = null,
            onConfirmWorklog = { mins, note, complete ->
                showWorklogDialog = false
                onLogWorklog(mins, note, complete)
            },
            onDismiss = { showWorklogDialog = false }
        )
    }

    if (showColleagueDialog) {
        AssignColleagueDialog(
            issueKey = ticketKey,
            colleagues = assignableColleagues,
            isLoading = isLoadingColleagues,
            onSelectColleague = { col ->
                showColleagueDialog = false
                onAssignColleague(col)
            },
            onDismiss = { showColleagueDialog = false }
        )
    }

    if (showServiceActDialog && ticket != null) {
        ServiceActPdfDialog(
            ticket = ticket,
            engineerName = engineerDisplayName,
            initialSerial = scannedSerialForActOrChoice,
            onGenerateAndAttach = { input ->
                showServiceActDialog = false
                onGenerateServiceActPdf(input)
            },
            onDismiss = { showServiceActDialog = false }
        )
    }

    if (showPassportDialog && ticket != null) {
        val initial = clientPassport ?: ClientTechPassport(
            clientKey = ticket.reporterName.trim().lowercase(),
            clientDisplayName = ticket.reporterName
        )
        ClientTechPassportAndroidDialog(
            initialPassport = initial,
            onSave = onSaveClientPassport,
            onOpenInWiki = {
                showPassportDialog = false
                onOpenClientInWiki(ticket.reporterName)
            },
            onDismiss = { showPassportDialog = false }
        )
    }

    if (showKbArchiveDialog) {
        KnowledgeBaseArchiveAndroidDialog(
            initialQuery = ticket?.summary.orEmpty(),
            results = knowledgeResults,
            isSearching = isSearchingKnowledge,
            onSearch = onSearchKnowledge,
            onOpenTicket = { key -> onOpenHistoricalTicket(key) },
            onInsertSolution = { sol ->
                standaloneCommentDraft = if (standaloneCommentDraft.isBlank()) sol else "$standaloneCommentDraft\n$sol"
            },
            onDismiss = { showKbArchiveDialog = false }
        )
    }

    HostDiagnosticDialog(
        state = hostDiagnosticState,
        onRetest = onRunHostDiagnostics,
        onDismiss = onDismissHostDiagnostics
    )

    // Optional comment dialog for "Завершить" or "Отклонить"
    val pendingDialogAction = commentDialogAction
    if (pendingDialogAction != null) {
        val isReject = pendingDialogAction == TicketWorkflowAction.REJECT
        AlertDialog(
            onDismissRequest = { commentDialogAction = null },
            title = {
                Text(
                    text = if (isReject) "Отклонить заявку $ticketKey?" else "Завершить заявку $ticketKey?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isReject) {
                            "Укажите или надиктуйте голосом причину отклонения (будет добавлена в комментарии Jira):"
                        } else {
                            "Добавьте или надиктуйте голосом комментарий о выполнении (необязательно):"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    QuickReplyMacrosBar(
                        templates = appSettings.quickReplyTemplates,
                        onSelectTemplate = { tpl ->
                            val expanded = QuickReplyTemplates.expand(
                                templateBody = tpl,
                                clientName = ticket?.reporterName,
                                ticketKey = ticketKey,
                                engineerName = engineerDisplayName
                            )
                            commentDraft = if (commentDraft.isBlank()) expanded else "${commentDraft.trimEnd()} $expanded"
                        }
                    )
                    VoiceCommentTextField(
                        value = commentDraft,
                        onValueChange = { commentDraft = it },
                        label = "Комментарий в Jira (нажмите 🎤 для голоса)"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val actionToRun = pendingDialogAction
                        val comment = commentDraft.trim().takeIf { it.isNotEmpty() }
                        commentDialogAction = null
                        commentDraft = ""
                        onExecuteAction(actionToRun, comment, null)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReject) CslDangerRed else CslSuccessGreen
                    )
                ) {
                    Text(if (isReject) "Отклонить" else "Завершить")
                }
            },
            dismissButton = {
                TextButton(onClick = { commentDialogAction = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Postpone ("Отложить на") Calendar + Time dialog
    if (showPostponeDialog) {
        PostponeTicketDialog(
            ticketKey = ticketKey,
            appSettings = appSettings,
            onDismiss = { showPostponeDialog = false },
            onConfirm = { epochMillis, formattedDisplay ->
                showPostponeDialog = false
                onPostponeTicket(epochMillis, formattedDisplay, null)
            }
        )
    }

    // Manual transition selector when Jira project uses custom transition names
    if (actionState.manualTransitionPrompt && actionState.availableTransitions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissActionMessage,
            title = {
                Text(
                    text = "Выберите переход статуса Jira",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = actionState.statusMessage
                            ?: "Выберите подходящий переход для заявки $ticketKey:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    actionState.availableTransitions.forEach { tr ->
                        OutlinedButton(
                            onClick = {
                                val postponeAt = actionState.pendingPostponeAtMillis
                                val postponeLabel = actionState.pendingPostponeDisplay
                                if (postponeAt != null && postponeLabel != null) {
                                    onPostponeTicket(postponeAt, postponeLabel, tr.id)
                                } else {
                                    val act = actionState.pendingAction ?: TicketWorkflowAction.TAKE_IN_WORK
                                    onExecuteAction(act, actionState.pendingComment, tr.id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "${tr.name} → ${tr.toStatusName}")
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CslMidnight,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(22.dp)
                                .background(CslOrange, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = ticketKey,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White
                            )
                            ticket?.status?.let { st ->
                                Text(
                                    text = st,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CslOrange
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (ticket != null) {
                        IconButton(onClick = { onDiscussInChat(ticket) }) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = "Обсудить в чате админов",
                                tint = CslOrange
                            )
                        }
                    }
                    val webUrl = ticket?.webUrl.orEmpty()
                    if (webUrl.isNotBlank()) {
                        IconButton(onClick = { DialerLauncher.openBrowser(context, webUrl) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Открыть в Jira",
                                tint = CslOrange
                            )
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
        ) {
            if (isLoading || actionState.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CslOrange
                )
            }

            if (ticket == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = CslOrange)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Загрузка заявки $ticketKey…")
                    } else {
                        Text("Данные заявки $ticketKey не найдены в локальном кэше.")
                    }
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.helpdesk.jiraagent.shared.LocalRecommendationsView(
                    ticket = ticket,
                    scope = com.helpdesk.jiraagent.domain.LocalRecommendations.scope(appSettings.serverUrl, appSettings.usernameOrEmail),
                    items = confirmedSolutions,
                    onAdd = { symptoms, solution -> onAddSolution(ticket, symptoms, solution) },
                    onVote = { id, vote -> onVoteSolution(ticket, id, vote) },
                    onDelete = onDeleteSolution
                )
                // 1. Workflow Action Buttons Card ("В работу", "Отложить на", "Завершить", "Отклонить")
                WorkflowActionsCard(
                    actionState = actionState,
                    onTakeInWork = {
                        onExecuteAction(TicketWorkflowAction.TAKE_IN_WORK, null, null)
                    },
                    onPostponeClick = {
                        showPostponeDialog = true
                    },
                    onComplete = {
                        commentDraft = ""
                        commentDialogAction = TicketWorkflowAction.COMPLETE
                    },
                    onReject = {
                        commentDraft = ""
                        commentDialogAction = TicketWorkflowAction.REJECT
                    }
                )

                // 2. Phone & Messengers Card (Dialer + Telegram + Viber + WhatsApp)
                PhoneAndMessengerSection(
                    phones = ticket.phoneNumbers,
                    telegramHandles = ticket.telegramHandles.orEmpty(),
                    onCall = { e164 ->
                        DialerLauncher.openSystemDialer(
                            context = context,
                            phoneInput = e164,
                            ticketKey = ticket.key,
                            clientName = ticket.reporterName
                        )
                    },
                    onCopy = { e164 -> copyToClipboard(context, e164) }
                )

                PersonalTicketReminderCard(ticket)

                // 2a. Field & Shift Engineer Action Bar (Colleague, Worklog, PDF Act, S/N Scanner, Chat)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Live Stopwatch Row
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (stopwatchEntry?.isRunning == true) CslOrange.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (stopwatchEntry?.isRunning == true) CslOrange else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⏱ Работа: $stopwatchDisplay",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (stopwatchEntry?.isRunning == true) CslOrange else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = onToggleStopwatch,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (stopwatchEntry?.isRunning == true) "⏸ Пауза" else "▶ Старт",
                                            color = CslOrange,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    if (stopwatchEntry != null) {
                                        TextButton(
                                            onClick = onResetStopwatch,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Text("Сброс", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }

                        // Client Tech Passport + Wiki + Knowledge Archive row
                        val detailBtnFontSize = with(LocalDensity.current) { 11.5.dp.toSp() }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showPassportDialog = true },
                                modifier = Modifier.weight(1.16f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    if (clientPassport != null && !clientPassport.isEmpty) "🏢 Паспорт ✓" else "🏢 Паспорт",
                                    fontSize = detailBtnFontSize,
                                    lineHeight = detailBtnFontSize,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { onOpenClientInWiki(ticket.reporterName) },
                                modifier = Modifier.weight(0.84f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "📘 Wiki",
                                    fontSize = detailBtnFontSize,
                                    lineHeight = detailBtnFontSize,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            OutlinedButton(
                                onClick = { showKbArchiveDialog = true },
                                modifier = Modifier.weight(1.0f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "📚 Решения",
                                    fontSize = detailBtnFontSize,
                                    lineHeight = detailBtnFontSize,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (clientPassport != null && !clientPassport.isEmpty) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = CslOrange.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().clickable { showPassportDialog = true }
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("🏢 Техпаспорт организации:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = CslOrange)
                                    listOf(
                                        clientPassport.addressesAndBranches.takeIf { it.isNotBlank() }?.let { "📍 $it" },
                                        clientPassport.gatewayAndProvider.takeIf { it.isNotBlank() }?.let { "🌐 $it" },
                                        clientPassport.vpnAndRemoteNotes.takeIf { it.isNotBlank() }?.let { "🔐 $it" },
                                        clientPassport.serversAndServices.takeIf { it.isNotBlank() }?.let { "🖥 $it" }
                                    ).filterNotNull().forEach { ln ->
                                        Text(ln, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onLoadColleagues()
                                    showColleagueDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = CslOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Коллеге", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = { showWorklogDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)
                            ) {
                                Icon(Icons.Default.Timer, contentDescription = null, tint = CslOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Worklog", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showServiceActDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = CslOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PDF-акт", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = { launchDetailScanner() },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = CslOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Скан S/N", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                            }
                        }
                        OutlinedButton(
                            onClick = { onSendToRepair(ticket, scannedSerialForActOrChoice) },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, CslOrange)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = CslOrange,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Передать технику во вкладку «Ремонт»",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = detailBtnFontSize,
                                lineHeight = detailBtnFontSize,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { onDiscussInChat(ticket) },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, CslOrange)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = null,
                                tint = CslOrange,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Обсудить заявку с админами в чате",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // 2c. Smart Sysadmin Entities: AnyDesk / RustDesk IDs + IP / Domain Ping & Port Check
                val extractedEntities = remember(ticket.key, ticket.summary, ticket.description, ticket.comments) {
                    SysadminEntityExtractor.extractFromTicket(ticket)
                }
                SysadminEntitiesCard(
                    entities = extractedEntities,
                    onRunDiagnostics = onRunHostDiagnostics
                )

                // 3. Summary & Metadata Card (Full non-truncated fields!)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = ticket.summary,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            HorizontalDivider()

                            MetadataRow("Клиент / Автор", ticket.reporterName)
                            if (ticket.reporterLoginOrEmail.isNotBlank()) {
                                MetadataRow("Контакт в Jira", ticket.reporterLoginOrEmail)
                            }
                            MetadataRow("Исполнитель", ticket.assigneeName ?: "Не назначен")
                            MetadataRow("Проект", "${ticket.projectName} (${ticket.projectKey})")
                            MetadataRow("Тип обращения", ticket.customerRequestType ?: ticket.issueType)
                            MetadataRow("Статус", ticket.status)
                            MetadataRow(
                                "Время поступления",
                                TicketTimingFormatter.formatFullMinsk(ticket.createdEpochMillis)
                            )
                            val closedAtMs = ticket.effectiveClosedEpochMillis
                            if (ticket.stage == TicketStage.CLOSED && closedAtMs > 0L) {
                                val durText = ticket.resolutionDurationMinutes?.let {
                                    " (решена за ${TicketTimingFormatter.formatDurationMinutes(it)})"
                                }.orEmpty()
                                MetadataRow(
                                    "Время закрытия",
                                    "${TicketTimingFormatter.formatFullMinsk(closedAtMs)}$durText"
                                )
                            } else {
                                MetadataRow("Время закрытия", "В работе / не закрыта")
                            }
                            if (ticket.priority.isNotBlank()) {
                                MetadataRow("Приоритет", ticket.priority)
                            }
                            if (ticket.labels.isNotEmpty()) {
                                MetadataRow("Метки", ticket.labels.joinToString(", "))
                            }
                        }
                    }
                }

                // 4. Description Card with inline clickable phone links
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    SelectionContainer {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Описание заявки",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = CslOrange
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
                                        DialerLauncher.openDialer(
                                            context = context,
                                            phoneInput = e164,
                                            ticketKey = ticket.key,
                                            clientName = ticket.reporterName
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // 4b. Jira Attachments & Camera Upload Card
                TicketAttachmentsCard(
                    attachments = ticket.attachments.orEmpty(),
                    isBusy = actionState.isBusy,
                    loadAttachmentBytes = loadAttachmentBytes,
                    onUploadUri = onUploadAttachmentUri,
                    onOpenExternal = onOpenExternalAttachment
                )

                // 4c. Local Client Dossier Card (provider, server room, on-site contact)
                ClientDossierCard(
                    clientName = ticket.reporterName,
                    savedNote = clientDossierNote,
                    onSaveNote = onSaveClientDossier
                )

                ClientRequestAnalysisView(
                    ticket = ticket,
                    loadedTickets = analysisLoadedTickets,
                    history = if (clientHistoryState.forTicketKey.equals(ticket.key, true)) clientHistoryState.items else emptyList(),
                    historyLoading = clientHistoryState.isLoading,
                    historyFailed = clientHistoryState.failed,
                    organizationHistories = clientHistoryState.organizationHistories,
                    onOpenTicket = onOpenHistoricalTicket
                )

                // 4d. Client Historical Tickets Card
                ClientHistoryCard(
                    clientName = ticket.reporterName,
                    historyState = clientHistoryState,
                    onOpenTicket = onOpenHistoricalTicket
                )

                // 5. Add Comment Card (with JSM Internal/Public toggle, Voice Input & Quick Macros)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Комментарий к заявке",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = CslOrange
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                onClick = { isInternalComment = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = if (!isInternalComment) CslOrange else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (!isInternalComment) CslOrange else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = null,
                                        tint = if (!isInternalComment) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Ответ клиенту",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!isInternalComment) Color.White else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            Surface(
                                onClick = { isInternalComment = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isInternalComment) CslMidnight else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (isInternalComment) CslOrange else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (isInternalComment) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Внутр. заметка",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isInternalComment) Color.White else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        QuickReplyMacrosBar(
                            templates = appSettings.quickReplyTemplates,
                            onSelectTemplate = { tpl ->
                                val expanded = QuickReplyTemplates.expand(
                                    templateBody = tpl,
                                    clientName = ticket?.reporterName,
                                    ticketKey = ticketKey,
                                    engineerName = engineerDisplayName
                                )
                                standaloneCommentDraft = if (standaloneCommentDraft.isBlank()) {
                                    expanded
                                } else {
                                    "${standaloneCommentDraft.trimEnd()} $expanded"
                                }
                            }
                        )

                        VoiceCommentTextField(
                            value = standaloneCommentDraft,
                            onValueChange = { standaloneCommentDraft = it },
                            label = if (isInternalComment) {
                                "🔒 Скрытая внутренняя заметка JSM (или нажмите 🎤)"
                            } else {
                                "Введите ответ клиенту или нажмите 🎤"
                            }
                        )

                        Button(
                            onClick = {
                                val textToSend = standaloneCommentDraft.trim()
                                if (textToSend.isNotEmpty()) {
                                    standaloneCommentDraft = ""
                                    onAddComment(textToSend, isInternalComment)
                                }
                            },
                            enabled = standaloneCommentDraft.isNotBlank() && !actionState.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isInternalComment) CslMidnight else CslOrange
                            )
                        ) {
                            Icon(
                                imageVector = if (isInternalComment) Icons.Default.Lock else Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isInternalComment) {
                                    "Сохранить внутреннюю заметку"
                                } else {
                                    "Отправить ответ клиенту"
                                }
                            )
                        }

                        if (ticket.comments.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "История комментариев (${ticket.comments.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            ticket.comments.forEachIndexed { idx, comment ->
                                if (idx > 0) HorizontalDivider()
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                                tint = CslOrange
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = comment.author,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        ClickablePhoneText(
                                            text = comment.body,
                                            region = appSettings.phoneRegion,
                                            customCountryCode = appSettings.customCountryCode,
                                            onPhoneClick = { e164 ->
                                                DialerLauncher.openDialer(
                                                    context = context,
                                                    phoneInput = e164,
                                                    ticketKey = ticket.key,
                                                    clientName = ticket.reporterName
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )
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
private fun VoiceCommentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                val combined = if (value.isBlank()) {
                    spoken
                } else {
                    "${value.trimEnd()} $spoken"
                }
                onValueChange(combined)
            }
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = 2,
        maxLines = 5,
        trailingIcon = {
            IconButton(
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите текст комментария…")
                    }
                    runCatching {
                        speechLauncher.launch(intent)
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "На устройстве не найден сервис голосового ввода Google/Android",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Голосовой ввод комментария",
                    tint = CslOrange
                )
            }
        }
    )
}

@Composable
private fun PersonalTicketReminderCard(ticket: HelpdeskTicket) {
    val context = LocalContext.current
    val reminders by remember { TicketReminderScheduler.observe(context.applicationContext) }.collectAsState()
    val current = reminders.firstOrNull { it.ticketKey.equals(ticket.key, true) }
    var show by remember(ticket.key) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (current == null) "⏰ Напомнить о заявке" else "⏰ Напоминание: ${PostponeDateTimeParser.formatEpochMillis(current.atMillis)}")
        }
        Text("Личное напоминание на этом устройстве; статус заявки не меняется.", style = MaterialTheme.typography.bodySmall)
    }
    if (show) {
        val initial = remember(current?.atMillis) { current?.atMillis ?: System.currentTimeMillis() + 60 * 60_000L }
        var date by remember(initial) { mutableStateOf(Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault()).toLocalDate().format(PostponeDateTimeParser.DISPLAY_DATE_FMT)) }
        var time by remember(initial) { mutableStateOf(Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault()).toLocalTime().format(PostponeDateTimeParser.DISPLAY_TIME_DASH_FMT)) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { show = false }, title = { Text("Напомнить · ${ticket.key}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Дата и время устройства. Напоминание не закрывает и не пересоздаёт заявку.")
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Дата ДД.ММ.ГГГГ") }, singleLine = true)
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Время ЧЧ:ММ") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (current != null) TextButton(onClick = {
                    runCatching { TicketReminderScheduler.cancel(context, ticket.key) }.onSuccess { show = false }.onFailure { error = it.message }
                }) { Text("Удалить напоминание") }
            }
        }, confirmButton = { TextButton(onClick = {
            val target = PostponeDateTimeParser.parseToEpochMillis(date, time)?.first
            if (target == null || target <= System.currentTimeMillis()) error = "Укажите корректные будущие дату и время"
            else runCatching { TicketReminderScheduler.set(context, TicketReminder(ticket.key, ticket.summary, target)) }
                .onSuccess { show = false }.onFailure { error = it.message ?: "Не удалось сохранить" }
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { show = false }) { Text("Отмена") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PostponeTicketDialog(
    ticketKey: String,
    appSettings: AppSettings,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit
) {
    val defaultTarget = remember {
        LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(12, 0))
    }
    var dateText by rememberSaveable {
        mutableStateOf(defaultTarget.toLocalDate().format(PostponeDateTimeParser.DISPLAY_DATE_FMT))
    }
    var timeText by rememberSaveable {
        mutableStateOf(defaultTarget.toLocalTime().format(PostponeDateTimeParser.DISPLAY_TIME_DASH_FMT))
    }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    var showCalendarPicker by rememberSaveable { mutableStateOf(false) }
    var showClockPicker by rememberSaveable { mutableStateOf(false) }

    // Visual Material3 DatePickerDialog
    if (showCalendarPicker) {
        val currentParsedDate = PostponeDateTimeParser.parseDate(dateText) ?: LocalDate.now().plusDays(1)
        val initialUtcMillis = currentParsedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)

        DatePickerDialog(
            onDismissRequest = { showCalendarPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val pickedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            dateText = pickedDate.format(PostponeDateTimeParser.DISPLAY_DATE_FMT)
                            validationError = null
                        }
                        showCalendarPicker = false
                    }
                ) {
                    Text("Выбрать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarPicker = false }) {
                    Text("Отмена")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Visual Material3 24h TimePicker Dialog
    if (showClockPicker) {
        val currentParsedTime = PostponeDateTimeParser.parseTime(timeText) ?: LocalTime.of(12, 0)
        val timePickerState = rememberTimePickerState(
            initialHour = currentParsedTime.hour,
            initialMinute = currentParsedTime.minute,
            is24Hour = true
        )

        AlertDialog(
            onDismissRequest = { showClockPicker = false },
            title = { Text("Выберите время пересоздания", fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = LocalTime.of(timePickerState.hour, timePickerState.minute)
                        timeText = t.format(PostponeDateTimeParser.DISPLAY_TIME_DASH_FMT)
                        validationError = null
                        showClockPicker = false
                    }
                ) {
                    Text("Готово")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClockPicker = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Отложить заявку $ticketKey",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Выберите в календаре или введите дату и время (например, 22.11.2026 и 12-00). Заявка будет закрыта сейчас, а в указанное время автоматически пересоздастся в Jira, выведет уведомление и появится во вкладке «Новые».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Quick presets
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {
                            val dt = LocalDateTime.now().plusHours(1)
                            dateText = dt.toLocalDate().format(PostponeDateTimeParser.DISPLAY_DATE_FMT)
                            timeText = dt.toLocalTime().format(PostponeDateTimeParser.DISPLAY_TIME_DASH_FMT)
                            validationError = null
                        },
                        label = { Text("+1 час") }
                    )
                    AssistChip(
                        onClick = {
                            val dt = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0))
                            dateText = dt.toLocalDate().format(PostponeDateTimeParser.DISPLAY_DATE_FMT)
                            timeText = "09-00"
                            validationError = null
                        },
                        label = { Text("Завтра 09-00") }
                    )
                    AssistChip(
                        onClick = {
                            val dt = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(12, 0))
                            dateText = dt.toLocalDate().format(PostponeDateTimeParser.DISPLAY_DATE_FMT)
                            timeText = "12-00"
                            validationError = null
                        },
                        label = { Text("Завтра 12-00") }
                    )
                    AssistChip(
                        onClick = {
                            var candidate = LocalDate.now().plusDays(1)
                            for (i in 0..14) {
                                val isWork = BelarusWorkCalendar.isWorkingDay(
                                    candidate,
                                    appSettings.extraWorkingDates,
                                    appSettings.extraHolidayDates
                                ).first
                                if (isWork) break
                                candidate = candidate.plusDays(1)
                            }
                            dateText = candidate.format(PostponeDateTimeParser.DISPLAY_DATE_FMT)
                            timeText = "09-00"
                            validationError = null
                        },
                        label = { Text("След. раб. день 09-00") }
                    )
                }

                // Date input + Calendar button
                OutlinedTextField(
                    value = dateText,
                    onValueChange = {
                        dateText = it
                        validationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Дата (ДД.ММ.ГГГГ)") },
                    placeholder = { Text("22.11.2026") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { showCalendarPicker = true }) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = "Открыть календарь",
                                tint = CslOrange
                            )
                        }
                    }
                )

                // Time input + Clock button (supports both 12-00 and 12:00)
                OutlinedTextField(
                    value = timeText,
                    onValueChange = {
                        timeText = it
                        validationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Время (ЧЧ-ММ или ЧЧ:ММ)") },
                    placeholder = { Text("12-00") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { showClockPicker = true }) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = "Выбрать время",
                                tint = CslOrange
                            )
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showCalendarPicker = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Календарь", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showClockPicker = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Часы", maxLines = 1)
                    }
                }

                val parsedPreview = remember(dateText, timeText) {
                    PostponeDateTimeParser.parseToEpochMillis(dateText, timeText)
                }
                if (parsedPreview != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Пересоздание назначено на: ${parsedPreview.second}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = PostponeDateTimeParser.parseToEpochMillis(dateText, timeText)
                    if (parsed == null) {
                        validationError = "Проверьте формат даты и времени (например: 22.11.2026 и 12-00)"
                        return@Button
                    }
                    if (parsed.first <= System.currentTimeMillis() + 5_000L) {
                        validationError = "Укажите дату и время в будущем"
                        return@Button
                    }
                    onConfirm(parsed.first, parsed.second)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Text("Отложить и закрыть")
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
private fun WorkflowActionsCard(
    actionState: TicketActionUiState,
    onTakeInWork: () -> Unit,
    onPostponeClick: () -> Unit,
    onComplete: () -> Unit,
    onReject: () -> Unit
) {
    val density = LocalDensity.current
    val btnTextSize = with(density) { 12.5.dp.toSp() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Действия по заявке",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CslOrange
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val singleColumn = maxWidth < 285.dp
                if (singleColumn) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InWorkButton(btnTextSize, actionState.isBusy, onTakeInWork, Modifier.fillMaxWidth())
                        PostponeButton(btnTextSize, actionState.isBusy, onPostponeClick, Modifier.fillMaxWidth())
                        CompleteButton(btnTextSize, actionState.isBusy, onComplete, Modifier.fillMaxWidth())
                        RejectButton(btnTextSize, actionState.isBusy, onReject, Modifier.fillMaxWidth())
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InWorkButton(btnTextSize, actionState.isBusy, onTakeInWork, Modifier.weight(1f))
                            PostponeButton(btnTextSize, actionState.isBusy, onPostponeClick, Modifier.weight(1f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompleteButton(btnTextSize, actionState.isBusy, onComplete, Modifier.weight(1f))
                            RejectButton(btnTextSize, actionState.isBusy, onReject, Modifier.weight(1f))
                        }
                    }
                }
            }

            Text(
                text = "«В работу» назначает вас исполнителем · «Отложить на» закрывает и пересоздаёт по таймеру",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            actionState.statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (actionState.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (actionState.isError) {
                                Icons.Default.ErrorOutline
                            } else {
                                Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (actionState.isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (actionState.isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InWorkButton(
    fontSize: androidx.compose.ui.unit.TextUnit,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.AssignmentInd, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text("В работу", fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PostponeButton(
    fontSize: androidx.compose.ui.unit.TextUnit,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text("Отложить на", fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CompleteButton(
    fontSize: androidx.compose.ui.unit.TextUnit,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text("Завершить", fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RejectButton(
    fontSize: androidx.compose.ui.unit.TextUnit,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier,
        border = BorderStroke(1.dp, CslDangerRed),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CslDangerRed),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text("Отклонить", fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneAndMessengerSection(
    phones: List<PhoneMatch>,
    telegramHandles: List<String>,
    onCall: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    val context = LocalContext.current

    if (phones.isEmpty() && telegramHandles.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneDisabled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Номера телефонов и никнеймы мессенджеров не обнаружены",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Связь с клиентом (звонок и мессенджеры)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CslOrange
            )

            phones.forEach { phone ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCall(phone.e164) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = phone.formattedDisplay,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CslSuccessGreen
                                )
                                Text(
                                    text = "${phone.source} · E.164: ${phone.e164}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(onClick = { onCopy(phone.e164) }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать номер",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Button(
                                onClick = { onCall(phone.e164) },
                                colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Позвонить", maxLines = 1)
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MessengerActionChip(
                                app = MessengerApp.TELEGRAM,
                                customLabel = "Telegram",
                                onClick = { MessengerLauncher.openTelegramByPhone(context, phone.e164) }
                            )
                            MessengerActionChip(
                                app = MessengerApp.VIBER,
                                customLabel = "Viber",
                                onClick = { MessengerLauncher.openViberByPhone(context, phone.e164) }
                            )
                            MessengerActionChip(
                                app = MessengerApp.WHATSAPP,
                                customLabel = "WhatsApp",
                                onClick = { MessengerLauncher.openWhatsAppByPhone(context, phone.e164) }
                            )
                        }
                    }
                }
            }

            if (telegramHandles.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    telegramHandles.forEach { handle ->
                        MessengerActionChip(
                            app = MessengerApp.TELEGRAM,
                            customLabel = "Telegram $handle",
                            onClick = { MessengerLauncher.openTelegramByUsername(context, handle) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(126.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun copyToClipboard(context: Context, phone: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Телефон клиента", phone))
    Toast.makeText(context, "Скопировано: $phone", Toast.LENGTH_SHORT).show()
}
