package com.helpdesk.jiraagent.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.RepairSubTab
import com.helpdesk.jiraagent.ui.components.MessengerCircleIconButton
import com.helpdesk.jiraagent.ui.components.rememberSafeBarcodeScanner
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import com.helpdesk.jiraagent.util.DialerLauncher
import com.helpdesk.jiraagent.util.MessengerApp
import com.helpdesk.jiraagent.util.MessengerLauncher
import com.helpdesk.jiraagent.util.PdfServiceActGenerator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REPAIR_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun formatRepairTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.of("Europe/Minsk"))
            .format(REPAIR_DATE_FMT)
    }.getOrDefault("")
}

private fun statusAccentColor(status: RepairStatus): Color = when (status) {
    RepairStatus.RECEIVED -> CslOrange
    RepairStatus.DIAGNOSTICS -> Color(0xFF2563EB)
    RepairStatus.WAITING_PARTS -> Color(0xFFD97706)
    RepairStatus.IN_REPAIR -> Color(0xFF7C3AED)
    RepairStatus.READY -> CslSuccessGreen
    RepairStatus.ISSUED -> Color(0xFF64748B)
}

private val RepairDeletedRed = Color(0xFFDC2626)

@Composable
fun RepairRegistryScreen(
    items: List<RepairEquipmentItem>,
    appSettings: AppSettings,
    engineerDisplayName: String,
    isSyncing: Boolean,
    syncErrorMessage: String?,
    prefillDraftFromTicket: RepairEquipmentItem?,
    onConsumePrefillDraft: () -> Unit,
    onSaveItem: (RepairEquipmentItem, postNoteToLinkedTicket: Boolean) -> Unit,
    onDeleteItem: (RepairEquipmentItem, reason: String) -> Unit,
    onRestoreItem: (RepairEquipmentItem) -> Unit,
    onSyncNow: () -> Unit,
    onOpenLinkedTicket: (String) -> Unit,
    onAttachPdfToTicket: (ticketKey: String, pdfBytes: ByteArray, fileName: String, note: String) -> Unit
) {
    val context = LocalContext.current

    var selectedSubTab by remember { mutableStateOf(RepairSubTab.ACTIVE) }
    var searchQuery by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<RepairEquipmentItem?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var unfoundScannedSerial by remember { mutableStateOf<String?>(null) }
    var pdfTargetItem by remember { mutableStateOf<RepairEquipmentItem?>(null) }

    // Open intake dialog automatically when navigated from TicketDetailScreen («🔧 В ремонт»)
    LaunchedEffect(prefillDraftFromTicket) {
        if (prefillDraftFromTicket != null) {
            editingItem = prefillDraftFromTicket
            isCreatingNew = true
            onConsumePrefillDraft()
        }
    }

    val launchBarcodeSearch = rememberSafeBarcodeScanner(
        prompt = "Наведите камеру на S/N или QR техники для поиска в ремонте"
    ) { scanned ->
        val found = items.firstOrNull {
            it.serialNumber.equals(scanned, ignoreCase = true) ||
                it.intakeNumber.equals(scanned, ignoreCase = true)
        }
        if (found != null) {
            selectedSubTab = RepairSubTab.entries.firstOrNull { it.matchesItem(found) }
                ?: RepairSubTab.ACTIVE
            searchQuery = scanned
        } else {
            unfoundScannedSerial = scanned
        }
    }

    val activeCount = remember(items) { items.count { RepairSubTab.ACTIVE.matchesItem(it) } }
    val waitingCount = remember(items) { items.count { RepairSubTab.WAITING.matchesItem(it) } }
    val readyCount = remember(items) { items.count { RepairSubTab.READY.matchesItem(it) } }
    val issuedCount = remember(items) { items.count { RepairSubTab.ISSUED.matchesItem(it) } }
    val deletedCount = remember(items) { items.count { RepairSubTab.DELETED.matchesItem(it) } }
    val pendingSyncCount = remember(items) { items.count { it.pendingRemoteSync } }

    val filteredItems = remember(items, selectedSubTab, searchQuery) {
        val matching = items.filter { item ->
            val tabMatches = if (searchQuery.isNotBlank()) {
                true
            } else {
                selectedSubTab.matchesItem(item)
            }
            tabMatches && RepairEquipmentCodec.matchesSearch(item, searchQuery)
        }
        if (selectedSubTab == RepairSubTab.DELETED) {
            matching.sortedByDescending { it.deletedAtEpochMs ?: it.updatedAtEpochMs }
        } else {
            matching
        }
    }

    // Prompt if scanned S/N is not yet registered in «В ремонт»
    unfoundScannedSerial?.let { scannedSn ->
        AlertDialog(
            onDismissRequest = { unfoundScannedSerial = null },
            title = { Text("S/N: $scannedSn", fontWeight = FontWeight.Bold) },
            text = {
                Text("Устройство с этим серийным номером ещё не числится во вкладке «В ремонт». Занести его сейчас?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        unfoundScannedSerial = null
                        isCreatingNew = true
                        editingItem = RepairEquipmentItem(
                            serialNumber = scannedSn,
                            assignedEngineer = engineerDisplayName
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("+ Принять в ремонт", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { unfoundScannedSerial = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    editingItem?.let { draft ->
        RepairEquipmentEditorDialog(
            initial = draft,
            isNewIntake = isCreatingNew,
            defaultEngineer = engineerDisplayName,
            onDismiss = { editingItem = null },
            onSave = { updated, postTicketNote ->
                onSaveItem(updated, postTicketNote)
                editingItem = null
                selectedSubTab = RepairSubTab.entries.firstOrNull { it.matchesItem(updated) }
                    ?: RepairSubTab.ACTIVE
            },
            onDelete = if (!isCreatingNew && !draft.isDeleted) {
                { reason ->
                    onDeleteItem(draft, reason)
                    editingItem = null
                }
            } else null,
            onRestore = if (!isCreatingNew && draft.isDeleted) {
                {
                    onRestoreItem(draft)
                    editingItem = null
                    selectedSubTab = RepairSubTab.entries.firstOrNull {
                        it.matchesItem(draft.copy(isDeleted = false))
                    } ?: RepairSubTab.ACTIVE
                }
            } else null
        )
    }

    pdfTargetItem?.let { item ->
        RepairReceiptPdfDialog(
            item = item,
            engineerName = engineerDisplayName,
            onDismiss = { pdfTargetItem = null },
            onAttachToTicket = { ticketKey, bytes, name, note ->
                onAttachPdfToTicket(ticketKey, bytes, name, note)
                pdfTargetItem = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Corporate csl.by Header Strip
        Surface(
            color = CslMidnight,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Техника в ремонте: ${activeCount + waitingCount} · Готово: $readyCount",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val syncText = when {
                            isSyncing -> "Синхронизация с Jira…"
                            !appSettings.isConfigured -> "Локальный режим (без Jira)"
                            !syncErrorMessage.isNullOrBlank() -> "Локально · повторим синхр."
                            pendingSyncCount > 0 -> "Ожидает выгрузки: $pendingSyncCount"
                            else -> "Синхронизировано в команде"
                        }
                        Icon(
                            imageVector = if (pendingSyncCount == 0 && appSettings.isConfigured) {
                                Icons.Default.CloudDone
                            } else {
                                Icons.Default.Schedule
                            },
                            contentDescription = null,
                            tint = if (pendingSyncCount == 0 && appSettings.isConfigured) CslSuccessGreen else Color(0xFFFFB74D),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = syncText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (appSettings.isConfigured) {
                    IconButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Синхронизировать журнал ремонта",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Button(
                    onClick = {
                        isCreatingNew = true
                        editingItem = RepairEquipmentItem(assignedEngineer = engineerDisplayName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Принять",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Search + Camera Barcode Scanner Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Поиск по клиенту, модели, S/N, REM-…, CSL-…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(17.dp))
                            }
                        }
                        IconButton(
                            onClick = { launchBarcodeSearch() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Сканировать S/N",
                                tint = CslOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Compact 5-subtab scrollable strip (В работе, Ждёт ЗЧ, Готово, Выдано, Удалённые — zero clipping)
        val chipFontSize = with(LocalDensity.current) { 11.dp.toSp() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tabCounts = listOf(
                RepairSubTab.ACTIVE to activeCount,
                RepairSubTab.WAITING to waitingCount,
                RepairSubTab.READY to readyCount,
                RepairSubTab.ISSUED to issuedCount,
                RepairSubTab.DELETED to deletedCount
            )
            for ((tab, count) in tabCounts) {
                val isSelected = selectedSubTab == tab && searchQuery.isBlank()
                val selectedBg = if (tab == RepairSubTab.DELETED) RepairDeletedRed else CslOrange
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            searchQuery = ""
                            selectedSubTab = tab
                        },
                    color = if (isSelected) selectedBg else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${tab.label} ($count)",
                            fontSize = chipFontSize,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = CslOrange.copy(alpha = 0.7f),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "По запросу «$searchQuery» техника не найдена"
                            selectedSubTab == RepairSubTab.DELETED -> "В разделе «Удалённые» записей нет"
                            else -> "В разделе «${selectedSubTab.label}» пока нет техники"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (selectedSubTab == RepairSubTab.DELETED) {
                            "При удалении любая позиция не исчезает бесследно, а сохраняется здесь с записью кто и когда её удалил."
                        } else {
                            "Нажмите «+ Принять», отсканируйте S/N камерой или отправьте оборудование в ремонт прямо из карточки заявки Jira."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedSubTab != RepairSubTab.DELETED) {
                        Button(
                            onClick = {
                                isCreatingNew = true
                                editingItem = RepairEquipmentItem(assignedEngineer = engineerDisplayName)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Занести технику в ремонт", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    RepairEquipmentCard(
                        item = item,
                        appSettings = appSettings,
                        onEdit = {
                            isCreatingNew = false
                            editingItem = item
                        },
                        onQuickChangeStatus = { newStatus ->
                            onSaveItem(
                                item.copy(
                                    status = newStatus,
                                    issuedAtEpochMs = if (newStatus == RepairStatus.ISSUED) {
                                        System.currentTimeMillis()
                                    } else {
                                        null
                                    }
                                ),
                                false
                            )
                            Toast.makeText(
                                context,
                                "${item.intakeNumber}: ${newStatus.shortTitle}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onOpenLinkedTicket = onOpenLinkedTicket,
                        onOpenPdfDialog = { pdfTargetItem = item },
                        onRestore = { onRestoreItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RepairEquipmentCard(
    item: RepairEquipmentItem,
    appSettings: AppSettings,
    onEdit: () -> Unit,
    onQuickChangeStatus: (RepairStatus) -> Unit,
    onOpenLinkedTicket: (String) -> Unit,
    onOpenPdfDialog: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val statusColor = if (item.isDeleted) RepairDeletedRed else statusAccentColor(item.status)
    val pillTitle = if (item.isDeleted) "Удалено" else item.status.shortTitle
    val normalizedPhone = remember(item.clientPhone, appSettings.phoneRegion, appSettings.customCountryCode) {
        if (item.clientPhone.isBlank()) null
        else PhoneNumberNormalizer.normalizeSingle(
            rawInput = item.clientPhone,
            region = appSettings.phoneRegion,
            customCountryCode = appSettings.customCountryCode,
            strictForFreeText = false
        )?.first
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (item.isDeleted) RepairDeletedRed.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Client Name prominent at top + Status Pill on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .background(statusColor, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = item.clientName.ifBlank { "Клиент не указан" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = pillTitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Audit trail banner for deleted items («Кто удалил» + дата/время + причина)
            if (item.isDeleted) {
                val who = item.deletedBy?.trim().orEmpty().ifBlank { "Неизвестно" }
                val whenDeleted = formatRepairTimestamp(item.deletedAtEpochMs ?: item.updatedAtEpochMs)
                val reason = item.deleteReason?.trim().orEmpty()
                Surface(
                    color = RepairDeletedRed.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, RepairDeletedRed.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = buildString {
                                append("🗑 Удалил: ")
                                append(who)
                                if (whenDeleted.isNotBlank()) {
                                    append(" · ")
                                    append(whenDeleted)
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = RepairDeletedRed
                        )
                        if (reason.isNotBlank()) {
                            Text(
                                text = "Причина: $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Row 2: Device Category + Model (prominent)
            Text(
                text = "🔧 ${item.deviceCategory}: ${item.deviceModel.ifBlank { "Без модели" }}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Row 3: Smaller metadata row (REM-number, linked Jira key chip, S/N, Shelf)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.intakeNumber.isNotBlank()) {
                    Text(
                        text = item.intakeNumber,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.linkedTicketKey.isNotBlank()) {
                    Surface(
                        color = CslOrange.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(5.dp),
                        modifier = Modifier.clickable { onOpenLinkedTicket(item.linkedTicketKey.trim()) }
                    ) {
                        Text(
                            text = item.linkedTicketKey.trim().uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CslOrange,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (item.serialNumber.isNotBlank()) {
                    Text(
                        text = "S/N: ${item.serialNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.shelfLocation.isNotBlank()) {
                    Text(
                        text = "📍 ${item.shelfLocation}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val dateLabel = formatRepairTimestamp(item.createdAtEpochMs)
                if (dateLabel.isNotBlank()) {
                    Text(
                        text = "от $dateLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.accessories.isNotBlank()) {
                Text(
                    text = "Комплект: ${item.accessories}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.reportedDefect.isNotBlank()) {
                Text(
                    text = "Неисправность: ${item.reportedDefect}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (item.repairNotes.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🛠 Ремонт / ЗЧ: ${item.repairNotes}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            // Bottom Compact Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isDeleted) {
                    Button(
                        onClick = onRestore,
                        colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        Text(
                            text = "↩ Восстановить",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    item.status.nextLogicalStatus()?.let { nextStatus ->
                        Button(
                            onClick = { onQuickChangeStatus(nextStatus) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = statusAccentColor(nextStatus)
                            ),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Text(
                                text = "→ ${nextStatus.shortTitle}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (item.status != RepairStatus.WAITING_PARTS && item.status != RepairStatus.ISSUED) {
                        OutlinedButton(
                            onClick = { onQuickChangeStatus(RepairStatus.WAITING_PARTS) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Text(
                                text = "⏳ Ждёт ЗЧ",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenPdfDialog,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = CslOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PDF",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                OutlinedButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Карточка",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                if (normalizedPhone != null) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(CslSuccessGreen)
                            .clickable {
                                DialerLauncher.openSystemDialer(
                                    context = context,
                                    phoneInput = normalizedPhone,
                                    region = appSettings.phoneRegion,
                                    customCountryCode = appSettings.customCountryCode,
                                    ticketKey = item.linkedTicketKey.takeIf { it.isNotBlank() },
                                    clientName = item.clientName
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Позвонить клиенту",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    MessengerCircleIconButton(
                        app = MessengerApp.TELEGRAM,
                        size = 30.dp,
                        iconSize = 15.dp,
                        onClick = { MessengerLauncher.openChatByPhone(context, MessengerApp.TELEGRAM, normalizedPhone) }
                    )
                    MessengerCircleIconButton(
                        app = MessengerApp.VIBER,
                        size = 30.dp,
                        iconSize = 15.dp,
                        onClick = { MessengerLauncher.openChatByPhone(context, MessengerApp.VIBER, normalizedPhone) }
                    )
                    MessengerCircleIconButton(
                        app = MessengerApp.WHATSAPP,
                        size = 30.dp,
                        iconSize = 15.dp,
                        onClick = { MessengerLauncher.openChatByPhone(context, MessengerApp.WHATSAPP, normalizedPhone) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepairEquipmentEditorDialog(
    initial: RepairEquipmentItem,
    isNewIntake: Boolean,
    defaultEngineer: String,
    onDismiss: () -> Unit,
    onSave: (RepairEquipmentItem, postNoteToLinkedTicket: Boolean) -> Unit,
    onDelete: ((reason: String) -> Unit)? = null,
    onRestore: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var category by remember(initial) { mutableStateOf(initial.deviceCategory.ifBlank { "Ноутбук" }) }
    var model by remember(initial) { mutableStateOf(initial.deviceModel) }
    var serial by remember(initial) { mutableStateOf(initial.serialNumber) }
    var clientName by remember(initial) { mutableStateOf(initial.clientName) }
    var clientPhone by remember(initial) { mutableStateOf(initial.clientPhone) }
    var linkedTicketKey by remember(initial) { mutableStateOf(initial.linkedTicketKey) }
    var accessories by remember(initial) { mutableStateOf(initial.accessories) }
    var externalCondition by remember(initial) { mutableStateOf(initial.externalCondition) }
    var reportedDefect by remember(initial) { mutableStateOf(initial.reportedDefect) }
    var repairNotes by remember(initial) { mutableStateOf(initial.repairNotes) }
    var shelfLocation by remember(initial) { mutableStateOf(initial.shelfLocation) }
    var assignedEngineer by remember(initial) {
        mutableStateOf(initial.assignedEngineer.ifBlank { defaultEngineer })
    }
    var status by remember(initial) { mutableStateOf(initial.status) }
    var postNoteToTicket by remember(initial, isNewIntake) {
        mutableStateOf(isNewIntake && initial.linkedTicketKey.isNotBlank())
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteReasonDraft by remember { mutableStateOf("") }

    val launchSerialScan = rememberSafeBarcodeScanner(
        prompt = "Наведите камеру на S/N или инвентарный штрихкод техники"
    ) { scanned ->
        serial = scanned
    }

    var activeSpeechField by remember { mutableStateOf(0) } // 1 = model, 2 = defect, 3 = repairNotes
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                when (activeSpeechField) {
                    1 -> model = if (model.isBlank()) spoken else "$model $spoken"
                    2 -> reportedDefect = if (reportedDefect.isBlank()) spoken else "$reportedDefect. $spoken"
                    3 -> repairNotes = if (repairNotes.isBlank()) spoken else "$repairNotes. $spoken"
                }
            }
        }
    }

    fun startSpeechInput(targetField: Int, prompt: String) {
        activeSpeechField = targetField
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
            speechLauncher.launch(intent)
        }.onFailure {
            Toast.makeText(context, "Голосовой ввод недоступен на устройстве", Toast.LENGTH_SHORT).show()
        }
    }

    if (confirmDelete && onDelete != null) {
        val quickReasons = listOf("Ошибочный ввод", "Дубликат", "Отказ клиента")
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Переместить в «Удалённые»?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Запись ${initial.intakeNumber} (${model.ifBlank { category }}) НЕ удаляется бесследно — она попадёт в раздел «Удалённые» с записью кто удалил (${defaultEngineer.ifBlank { "текущий инженер" }}) и временем.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (qr in quickReasons) {
                            FilterChip(
                                selected = deleteReasonDraft == qr,
                                onClick = {
                                    deleteReasonDraft = if (deleteReasonDraft == qr) "" else qr
                                },
                                label = { Text(qr, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RepairDeletedRed,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = deleteReasonDraft,
                        onValueChange = { deleteReasonDraft = it },
                        label = { Text("Причина удаления (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val reason = deleteReasonDraft
                        confirmDelete = false
                        onDelete(reason)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RepairDeletedRed)
                ) {
                    Text("В удалённые", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isNewIntake) "🔧 Приём техники в ремонт" else "🔧 Карточка ${initial.intakeNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!isNewIntake && onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (initial.isDeleted) {
                    val who = initial.deletedBy?.trim().orEmpty().ifBlank { "Неизвестно" }
                    val whenDel = formatRepairTimestamp(initial.deletedAtEpochMs ?: initial.updatedAtEpochMs)
                    Surface(
                        color = RepairDeletedRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, RepairDeletedRed.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "🗑 В удалённых · Удалил: $who${if (whenDel.isNotBlank()) " ($whenDel)" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = RepairDeletedRed
                            )
                            if (!initial.deleteReason.isNullOrBlank()) {
                                Text(
                                    text = "Причина: ${initial.deleteReason}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (onRestore != null) {
                                Button(
                                    onClick = onRestore,
                                    colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("↩ Восстановить позицию из удалённых", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Status selector
                Text(
                    text = "Статус ремонта:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (st in RepairStatus.entries) {
                        val selected = status == st
                        FilterChip(
                            selected = selected,
                            onClick = { status = st },
                            label = { Text(st.shortTitle, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = statusAccentColor(st),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                // Category quick chips
                Text(
                    text = "Тип оборудования:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (cat in RepairEquipmentCodec.DEFAULT_CATEGORIES) {
                        val selected = category.equals(cat, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CslOrange,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Модель / Наименование *") },
                    placeholder = { Text("Например: HP ProBook 450 G8 / Kyocera M2040dn") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { startSpeechInput(1, "Назовите модель оборудования") }) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосом", tint = CslOrange)
                        }
                    }
                )

                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it },
                    label = { Text("Серийный (S/N) или инвентарный номер") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { launchSerialScan() }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать S/N", tint = CslOrange)
                        }
                    }
                )

                OutlinedTextField(
                    value = clientName,
                    onValueChange = { clientName = it },
                    label = { Text("Клиент / Организация *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = clientPhone,
                        onValueChange = { clientPhone = it },
                        label = { Text("Телефон") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = linkedTicketKey,
                        onValueChange = { linkedTicketKey = it.uppercase() },
                        label = { Text("Заявка Jira") },
                        placeholder = { Text("CSL-…") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                }

                // Accessories quick chips
                Text(
                    text = "Комплектность при приёме:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (acc in RepairEquipmentCodec.DEFAULT_ACCESSORIES) {
                        val currentParts = accessories.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val hasAcc = currentParts.any { it.equals(acc, ignoreCase = true) }
                        FilterChip(
                            selected = hasAcc,
                            onClick = {
                                val next = if (hasAcc) {
                                    currentParts.filterNot { it.equals(acc, ignoreCase = true) }
                                } else {
                                    currentParts + acc
                                }
                                accessories = next.joinToString(", ")
                            },
                            label = { Text(acc, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CslMidnight,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = accessories,
                    onValueChange = { accessories = it },
                    label = { Text("Комплект (можно дописать вручную)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = externalCondition,
                    onValueChange = { externalCondition = it },
                    label = { Text("Внешнее состояние / пломбы") },
                    placeholder = { Text("Потёртости корпуса, царапины…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reportedDefect,
                    onValueChange = { reportedDefect = it },
                    label = { Text("Заявленная неисправность *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { startSpeechInput(2, "Опишите неисправность") }) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосом", tint = CslOrange)
                        }
                    }
                )

                OutlinedTextField(
                    value = repairNotes,
                    onValueChange = { repairNotes = it },
                    label = { Text("Диагностика / Запчасти / Выполненный ремонт") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { startSpeechInput(3, "Продиктуйте ход ремонта или запчасти") }) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосом", tint = CslOrange)
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = shelfLocation,
                        onValueChange = { shelfLocation = it },
                        label = { Text("Полка / Место") },
                        placeholder = { Text("Стеллаж А-2") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = assignedEngineer,
                        onValueChange = { assignedEngineer = it },
                        label = { Text("Инженер") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (linkedTicketKey.trim().isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { postNoteToTicket = !postNoteToTicket }
                    ) {
                        Checkbox(
                            checked = postNoteToTicket,
                            onCheckedChange = { postNoteToTicket = it },
                            colors = CheckboxDefaults.colors(checkedColor = CslOrange)
                        )
                        Text(
                            text = "Записать внутреннюю заметку о статусе ремонта в заявку ${linkedTicketKey.trim().uppercase()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (initial.isDeleted && onRestore != null) {
                Button(
                    onClick = onRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen)
                ) {
                    Text("↩ Восстановить", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (model.isBlank() && reportedDefect.isBlank()) {
                            Toast.makeText(context, "Укажите модель или неисправность оборудования", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val saved = initial.copy(
                            deviceCategory = category.trim().ifBlank { "Другое" },
                            deviceModel = model.trim(),
                            serialNumber = serial.trim(),
                            clientName = clientName.trim(),
                            clientPhone = clientPhone.trim(),
                            linkedTicketKey = linkedTicketKey.trim().uppercase(),
                            accessories = accessories.trim(),
                            externalCondition = externalCondition.trim(),
                            reportedDefect = reportedDefect.trim(),
                            repairNotes = repairNotes.trim(),
                            shelfLocation = shelfLocation.trim(),
                            assignedEngineer = assignedEngineer.trim(),
                            status = status
                        )
                        onSave(saved, postNoteToTicket && saved.linkedTicketKey.isNotBlank())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Сохранить", color = Color.White, fontWeight = FontWeight.Bold)
                }
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
private fun RepairReceiptPdfDialog(
    item: RepairEquipmentItem,
    engineerName: String,
    onDismiss: () -> Unit,
    onAttachToTicket: (ticketKey: String, pdfBytes: ByteArray, fileName: String, note: String) -> Unit
) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize(400, 150)) }
    val isIssue = item.status == RepairStatus.READY || item.status == RepairStatus.ISSUED

    fun renderSignatureBitmap(): Bitmap? {
        val allStrokes = strokes.toList()
        if (allStrokes.isEmpty()) return null
        val w = padSize.width.coerceAtLeast(240)
        val h = padSize.height.coerceAtLeast(100)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            strokeWidth = 5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in allStrokes) {
            for (i in 0 until stroke.size - 1) {
                c.drawLine(stroke[i].x, stroke[i].y, stroke[i + 1].x, stroke[i + 1].y, p)
            }
        }
        return bmp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isIssue) "📄 PDF-акт выдачи (${item.intakeNumber})" else "📄 PDF-квитанция приёма (${item.intakeNumber})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${item.deviceCategory}: ${item.deviceModel.ifBlank { "Без модели" }} · ${item.clientName.ifBlank { "Клиент" }}",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Подпись клиента на экране (необязательно):",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (strokes.isNotEmpty()) {
                        TextButton(onClick = { strokes.clear() }) {
                            Text("Очистить")
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(125.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, Color(0xFF94A3B8), RoundedCornerShape(8.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke = listOf(offset) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStroke = currentStroke + change.position
                                },
                                onDragEnd = {
                                    if (currentStroke.size > 1) strokes.add(currentStroke)
                                    currentStroke = emptyList()
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (stroke in strokes) {
                            for (i in 0 until stroke.size - 1) {
                                drawLine(
                                    color = Color(0xFF0F172A),
                                    start = stroke[i],
                                    end = stroke[i + 1],
                                    strokeWidth = 4.5f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                        for (i in 0 until currentStroke.size - 1) {
                            drawLine(
                                color = CslOrange,
                                start = currentStroke[i],
                                end = currentStroke[i + 1],
                                strokeWidth = 4.5f,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }

                if (item.linkedTicketKey.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val pdfFile = PdfServiceActGenerator.generateRepairReceiptPdfFile(
                                    context = context,
                                    item = item,
                                    engineerName = engineerName,
                                    clientSignatureBitmap = renderSignatureBitmap()
                                )
                                val note = if (isIssue) {
                                    "📄 Прикреплён PDF-акт выдачи оборудования из ремонта (${item.intakeNumber}: ${item.deviceModel})"
                                } else {
                                    "📄 Прикреплена PDF-квитанция приёма оборудования в ремонт (${item.intakeNumber}: ${item.deviceModel})"
                                }
                                onAttachToTicket(
                                    item.linkedTicketKey.trim(),
                                    pdfFile.readBytes(),
                                    pdfFile.name,
                                    note
                                )
                            }.onFailure {
                                Toast.makeText(context, "Ошибка формирования PDF", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, CslOrange)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = CslOrange)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Прикрепить PDF к заявке ${item.linkedTicketKey.trim().uppercase()}")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching {
                        val pdfFile = PdfServiceActGenerator.generateRepairReceiptPdfFile(
                            context = context,
                            item = item,
                            engineerName = engineerName,
                            clientSignatureBitmap = renderSignatureBitmap()
                        )
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            pdfFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Документ ${item.intakeNumber} (${item.deviceModel})")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Отправить / распечатать PDF"))
                        onDismiss()
                    }.onFailure {
                        Toast.makeText(context, "Не удалось поделиться PDF", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("Поделиться / Печать PDF", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
