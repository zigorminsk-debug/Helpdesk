package com.helpdesk.jiraagent.ui.components

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.data.JiraAssignableUser
import com.helpdesk.jiraagent.data.PendingCallSession
import com.helpdesk.jiraagent.domain.SettingsTransferCodec
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.ui.SerialSearchUiState
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.util.PdfServiceActGenerator
import com.helpdesk.jiraagent.util.ServiceActInput
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Crash-safe camera barcode / QR scanner launcher that checks `CAMERA` permission up front
 * (required on MIUI/HyperOS & Android 6+) and immediately opens the scanner upon grant.
 */
@Composable
fun rememberSafeBarcodeScanner(
    prompt: String,
    onScanned: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotEmpty()) {
            onScanned(contents)
        }
    }
    val launchScanNow = {
        runCatching {
            scanLauncher.launch(
                ScanOptions()
                    .setPrompt(prompt)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Не удалось открыть сканер камеры: ${err.localizedMessage ?: "ошибка"}",
                Toast.LENGTH_SHORT
            ).show()
        }
        Unit
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchScanNow()
        } else {
            Toast.makeText(
                context,
                "Разрешите доступ к камере для сканирования штрихкодов и QR",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    return {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            launchScanNow()
        } else {
            runCatching { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
                .onFailure { launchScanNow() }
        }
    }
}

/**
 * Local persistent technical dossier card for each client organization inside TicketDetailScreen.
 */
@Composable
fun ClientDossierCard(
    clientName: String,
    savedNote: String,
    onSaveNote: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by rememberSaveable(clientName) { mutableStateOf(false) }
    var draft by rememberSaveable(clientName, savedNote) { mutableStateOf(savedNote) }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("📋 Досье клиента: $clientName", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 7,
                    label = { Text("Провайдер, шлюз, серверная, ответственный на месте") },
                    placeholder = { Text("Например: Белтелеком статика, MikroTik в каб. 204, главбух Елена доб. 105") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveNote(clientName, draft)
                        editing = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Отмена") }
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Badge,
                    contentDescription = null,
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Досье клиента ($clientName)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = CslOrange,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        draft = savedNote
                        editing = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Редактировать досье клиента",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Text(
                text = savedNote.ifBlank {
                    "Локальная заметка пуста — нажмите ✏️, чтобы сохранить провайдера, расположение серверной или контакт на месте."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (savedNote.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/**
 * Post-Call Wrap-up & Manual Jira Worklog dialog (`Списание времени в Worklog`).
 */
@Composable
fun WorklogAndPostCallDialog(
    ticketKey: String,
    clientName: String,
    callSession: PendingCallSession?,
    onConfirmWorklog: (minutes: Int, comment: String, alsoComplete: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val initialMinutes = remember(callSession) {
        if (callSession != null) {
            val elapsedMin = ((System.currentTimeMillis() - callSession.startedAtMillis) / 60_000L).toInt()
            when {
                elapsedMin <= 5 -> 5
                elapsedMin <= 10 -> 10
                elapsedMin <= 15 -> 15
                elapsedMin <= 30 -> 30
                else -> elapsedMin.coerceAtMost(240)
            }
        } else 10
    }
    var minutes by rememberSaveable(ticketKey) { mutableIntStateOf(initialMinutes) }
    var noteDraft by rememberSaveable(ticketKey) { mutableStateOf("") }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                noteDraft = if (noteDraft.isBlank()) spoken else "${noteDraft.trimEnd()} $spoken"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (callSession != null) {
                    "📞 Итог звонка · $ticketKey"
                } else {
                    "⏱ Списать время (Worklog) · $ticketKey"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Клиент: $clientName" + (callSession?.let { " (${it.phoneNumber})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(5, 10, 15, 30, 60, 120).forEach { preset ->
                        val selected = minutes == preset
                        Surface(
                            onClick = { minutes = preset },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) CslOrange else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (selected) CslOrange else MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = if (preset >= 60) "${preset / 60} ч" else "$preset мин",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Итог разговора / комментарий к Worklog") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                            }
                            runCatching { speechLauncher.launch(intent) }
                        }) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод", tint = CslOrange)
                        }
                    }
                )
                OutlinedButton(
                    onClick = { onConfirmWorklog(minutes, noteDraft, true) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, CslSuccessGreen)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = CslSuccessGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Списать $minutes мин и Завершить заявку", color = CslSuccessGreen, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmWorklog(minutes, noteDraft, false) },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("Списать $minutes мин")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Пропустить") }
        }
    )
}

/**
 * Searchable Jira colleague handoff dialog (`👤 Передать коллеге`).
 */
@Composable
fun AssignColleagueDialog(
    issueKey: String,
    colleagues: List<JiraAssignableUser>,
    isLoading: Boolean,
    onSelectColleague: (JiraAssignableUser) -> Unit,
    onDismiss: () -> Unit
) {
    var filterQuery by rememberSaveable { mutableStateOf("") }
    val filtered = remember(colleagues, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) colleagues
        else colleagues.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                (it.username?.contains(q, ignoreCase = true) == true) ||
                (it.emailAddress?.contains(q, ignoreCase = true) == true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("👤 Передать $issueKey коллеге", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск инженера") }
                )
                if (isLoading) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = CslOrange)
                        Text("Загрузка сотрудников проекта из Jira…")
                    }
                } else if (filtered.isEmpty()) {
                    Text(
                        text = "Доступные исполнители не найдены",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        filtered.forEach { user ->
                            Surface(
                                onClick = { onSelectColleague(user) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        tint = CslOrange,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = user.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        val sub = user.emailAddress ?: user.username
                                        if (!sub.isNullOrBlank()) {
                                            Text(
                                                text = sub,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

/**
 * Field Engineer Service Act Generator Dialog with Checklist, Barcode S/N & Finger Signature Pad.
 */
@Composable
fun ServiceActPdfDialog(
    ticket: HelpdeskTicket,
    engineerName: String,
    initialSerial: String = "",
    onGenerateAndAttach: (ServiceActInput) -> Unit,
    onDismiss: () -> Unit
) {
    val allItems = PdfServiceActGenerator.DEFAULT_CHECKLIST_ITEMS
    val checkedStates = remember { mutableStateListOf(true, true, false, false, true) }
    var clientRep by rememberSaveable { mutableStateOf(ticket.reporterName) }
    var serialDraft by rememberSaveable { mutableStateOf(initialSerial) }
    var extraNotes by rememberSaveable { mutableStateOf("") }

    // Finger signature strokes: each stroke is a list of normalized/pixel points
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padWidthPx by remember { mutableIntStateOf(600) }
    var padHeightPx by remember { mutableIntStateOf(220) }

    val launchActBarcodeScan = rememberSafeBarcodeScanner(
        prompt = "Наведите камеру на штрихкод / S/N оборудования"
    ) { contents ->
        serialDraft = contents
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("✍️ PDF-акт выполненных работ (${ticket.key})", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = clientRep,
                    onValueChange = { clientRep = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Представитель Заказчика (ФИО / должность)") }
                )

                OutlinedTextField(
                    value = serialDraft,
                    onValueChange = { serialDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("S/N или инвентарный номер (необязательно)") },
                    trailingIcon = {
                        IconButton(onClick = { launchActBarcodeScan() }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать S/N", tint = CslOrange)
                        }
                    }
                )

                Text(
                    text = "Выполненные работы:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CslOrange
                )

                allItems.forEachIndexed { idx, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checkedStates[idx] = !checkedStates[idx] },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checkedStates[idx],
                            onCheckedChange = { checkedStates[idx] = it }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedTextField(
                    value = extraNotes,
                    onValueChange = { extraNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                    label = { Text("Техническое заключение / примечание") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Подпись клиента пальцем:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = {
                        strokes.clear()
                        activeStroke = emptyList()
                    }) {
                        Text("Очистить подпись")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(125.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.5.dp, CslOrange, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            padWidthPx = size.width.coerceAtLeast(200)
                            padHeightPx = size.height.coerceAtLeast(100)
                            detectDragGestures(
                                onDragStart = { start -> activeStroke = listOf(start) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    activeStroke = activeStroke + change.position
                                },
                                onDragEnd = {
                                    if (activeStroke.size > 1) {
                                        strokes.add(activeStroke)
                                    }
                                    activeStroke = emptyList()
                                },
                                onDragCancel = { activeStroke = emptyList() }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val allToDraw = if (activeStroke.size > 1) strokes + listOf(activeStroke) else strokes
                        allToDraw.forEach { pts ->
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) {
                                    lineTo(pts[i].x, pts[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = CslMidnight,
                                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                    if (strokes.isEmpty() && activeStroke.isEmpty()) {
                        Text(
                            text = "Распишитесь здесь",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sigBitmap = renderSignatureBitmap(strokes.toList(), padWidthPx, padHeightPx)
                    val selectedItems = allItems.filterIndexed { idx, _ -> checkedStates[idx] }
                    onGenerateAndAttach(
                        ServiceActInput(
                            ticket = ticket,
                            engineerName = engineerName,
                            clientRepresentative = clientRep,
                            checkedItems = selectedItems,
                            extraNotes = extraNotes,
                            equipmentSerial = serialDraft,
                            clientSignatureBitmap = sigBitmap
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Text("Сформировать PDF в Jira")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

private fun renderSignatureBitmap(
    strokes: List<List<Offset>>,
    widthPx: Int,
    heightPx: Int
): Bitmap? {
    if (strokes.isEmpty()) return null
    val w = widthPx.coerceAtLeast(240)
    val h = heightPx.coerceAtLeast(110)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#04091E")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (pts in strokes) {
        if (pts.size < 2) continue
        val path = android.graphics.Path()
        path.moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
        }
        canvas.drawPath(path, paint)
    }
    return bmp
}

/**
 * Hardware Serial / Barcode repair history search results dialog.
 */
@Composable
fun SerialHistoryResultsDialog(
    state: SerialSearchUiState,
    onOpenTicket: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.isSearching && state.serialQuery.isBlank()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("🔍 История по S/N: ${state.serialQuery}", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isSearching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = CslOrange)
                        Text("Поиск ремонтов и обращений в Jira…")
                    }
                } else if (state.results.isEmpty()) {
                    Text(
                        text = "В Jira пока нет прошлых заявок с упоминанием «${state.serialQuery}».",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "Найдено обращений: ${state.results.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CslOrange,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.results.forEach { item ->
                            Surface(
                                onClick = {
                                    onDismiss()
                                    onOpenTicket(item.key)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "${item.reporterName} · ${item.key}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CslOrange
                                    )
                                    Text(
                                        text = item.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Статус: ${item.status}" +
                                            (item.assigneeName?.let { " · Инженер: $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

/**
 * Encrypted QR Code & String Settings Export / Import Dialog for 3-second colleague onboarding.
 */
@Composable
fun QrSettingsTransferDialog(
    appSettings: AppSettings,
    filterSettings: FilterSettings,
    onImportPayload: (encoded: String, pin: String) -> Result<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var modeExport by rememberSaveable { mutableStateOf(true) }
    var includeCredentials by rememberSaveable { mutableStateOf(false) }
    var pinCode by rememberSaveable { mutableStateOf("") }
    var importText by rememberSaveable { mutableStateOf("") }
    var feedbackMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var feedbackError by rememberSaveable { mutableStateOf(false) }

    val launchQrConfigScan = rememberSafeBarcodeScanner(
        prompt = "Наведите камеру на QR-код настроек Help-CSL на экране коллеги"
    ) { scanned ->
        importText = scanned
        val res = onImportPayload(scanned, pinCode)
        res.onSuccess { msg ->
            feedbackError = false
            feedbackMessage = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.onFailure { err ->
            feedbackError = true
            feedbackMessage = err.message ?: "Ошибка импорта QR"
        }
    }

    val exportPayload = remember(appSettings, filterSettings, includeCredentials, pinCode) {
        SettingsTransferCodec.encode(
            appSettings = appSettings,
            filterSettings = filterSettings,
            includeCredentials = includeCredentials,
            pinCode = pinCode
        )
    }
    val qrBitmap = remember(exportPayload) {
        SettingsTransferCodec.generateQrBitmap(exportPayload, 640)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCode2, contentDescription = null, tint = CslOrange)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Перенос настроек коллеге (QR)", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { modeExport = true; feedbackMessage = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (modeExport) CslOrange else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (modeExport) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    ) { Text("Показать QR") }

                    Button(
                        onClick = { modeExport = false; feedbackMessage = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!modeExport) CslOrange else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (!modeExport) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    ) { Text("Считать QR") }
                }

                OutlinedTextField(
                    value = pinCode,
                    onValueChange = { pinCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("PIN-код шифрования (необязательно)") },
                    placeholder = { Text("Оставьте пустым для стандартного переноса") }
                )

                if (modeExport) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Включить мой логин и пароль/токен", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("Выключите при передаче другому сотруднику", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = includeCredentials,
                            onCheckedChange = { includeCredentials = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = CslOrange)
                        )
                    }

                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR-код настроек Help-CSL",
                                modifier = Modifier.size(210.dp)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Help-CSL Config", exportPayload))
                            Toast.makeText(context, "Зашифрованный код настроек скопирован", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Скопировать код текстом")
                    }
                } else {
                    Button(
                        onClick = { launchQrConfigScan() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("📷 Сканировать QR-код камерой")
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("Или вставьте код HELPCSL1:… вручную") }
                    )

                    OutlinedButton(
                        onClick = {
                            val res = onImportPayload(importText, pinCode)
                            res.onSuccess { msg ->
                                feedbackError = false
                                feedbackMessage = msg
                            }.onFailure { err ->
                                feedbackError = true
                                feedbackMessage = err.message ?: "Ошибка импорта"
                            }
                        },
                        enabled = importText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Применить вставленный код")
                    }
                }

                if (!feedbackMessage.isNullOrBlank()) {
                    Text(
                        text = feedbackMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (feedbackError) CslDangerRed else CslSuccessGreen
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        }
    )
}
