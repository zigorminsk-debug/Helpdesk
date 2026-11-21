package com.helpdesk.jiraagent.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.helpdesk.jiraagent.domain.RemoteDesktopIdMatch
import com.helpdesk.jiraagent.domain.SysadminEntities
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketAttachment
import com.helpdesk.jiraagent.ui.ClientHistoryUiState
import com.helpdesk.jiraagent.ui.HostDiagnosticUiState
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun QuickReplyMacrosBar(
    templates: List<String>,
    onSelectTemplate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (templates.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = CslOrange,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Быстрые шаблоны (в 1 касание):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            templates.forEach { tpl ->
                val shortTitle = if (tpl.length > 38) tpl.take(36).trimEnd() + "…" else tpl
                Surface(
                    onClick = { onSelectTemplate(tpl) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = shortTitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SysadminEntitiesCard(
    entities: SysadminEntities,
    onRunDiagnostics: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCustomPingDialog by rememberSaveable { mutableStateOf(false) }
    var customHostDraft by rememberSaveable { mutableStateOf("") }

    if (showCustomPingDialog) {
        AlertDialog(
            onDismissRequest = { showCustomPingDialog = false },
            title = { Text("Проверка доступности хоста / портов", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = customHostDraft,
                    onValueChange = { customHostDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("IP-адрес или домен") },
                    placeholder = { Text("Например: 192.168.1.1 или csl.by") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = customHostDraft.trim()
                        if (target.isNotEmpty()) {
                            showCustomPingDialog = false
                            onRunDiagnostics(target)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Проверить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPingDialog = false }) {
                    Text("Отмена")
                }
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lan,
                    contentDescription = null,
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Удалёнка и сеть",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = CslOrange,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = { showCustomPingDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = CslOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Ping / Порты",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (entities.remoteIds.isNotEmpty()) {
                entities.remoteIds.forEach { remote ->
                    RemoteIdRow(context = context, item = remote)
                }
            }

            if (entities.networkTargets.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entities.networkTargets.forEach { net ->
                        Surface(
                            onClick = { onRunDiagnostics(net.host) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = CslOrange,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${net.host} · Проверить",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            if (entities.isEmpty) {
                Text(
                    text = "ID AnyDesk/RustDesk в тексте не найдены. Нажмите «Ping / Порты» для проверки любого узла.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemoteIdRow(
    context: Context,
    item: RemoteDesktopIdMatch
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DesktopWindows,
                contentDescription = null,
                tint = CslOrange,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.formattedId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = {
                    copyPlainText(context, item.normalizedId, "ID ${item.formattedId} скопирован")
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Копировать ID",
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = {
                    copyPlainText(context, item.normalizedId, null)
                    launchRemoteAppOrHint(context, item)
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Открыть приложение удалёнки",
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun copyPlainText(context: Context, value: String, toastMessage: String?) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Help-CSL", value))
    if (!toastMessage.isNullOrBlank()) {
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
}

private fun launchRemoteAppOrHint(context: Context, item: RemoteDesktopIdMatch) {
    val pm = context.packageManager
    val uriPrefix = item.kind.uriPrefix
    if (uriPrefix != null) {
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("$uriPrefix${item.normalizedId}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(uriIntent) }.isSuccess) return
    }
    for (pkg in item.kind.packageNames) {
        val launch = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch != null && runCatching { context.startActivity(launch) }.isSuccess) {
            Toast.makeText(context, "ID ${item.formattedId} скопирован в буфер", Toast.LENGTH_SHORT).show()
            return
        }
    }
    Toast.makeText(
        context,
        "ID ${item.formattedId} скопирован (клиент ${item.kind.label} не установлен на телефоне)",
        Toast.LENGTH_SHORT
    ).show()
}

@Composable
fun HostDiagnosticDialog(
    state: HostDiagnosticUiState,
    onRetest: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.isRunning && state.report == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Диагностика: ${state.targetHost}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (state.isRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), color = CslOrange)
                    Text("Проверка ICMP и TCP-портов (80, 443, 3389, 22, 8291, 445)…")
                }
            } else {
                val rep = state.report!!
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rep.errorMessage != null) {
                        Text(
                            text = rep.errorMessage,
                            color = CslDangerRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "IP-адрес: ${rep.resolvedIp ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (rep.icmpReachable) {
                                "ICMP / Эхо: ДОСТУПЕН (${rep.icmpLatencyMs ?: 0} мс)"
                            } else {
                                "ICMP / Эхо: нет ответа (возможно закрыт файрволом)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rep.icmpReachable) CslSuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        rep.portStatuses.forEach { port ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TCP ${port.port} (${port.serviceName})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (port.isOpen) {
                                        "ОТКРЫТ · ${port.latencyMs ?: 0} мс"
                                    } else {
                                        "закрыт"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (port.isOpen) FontWeight.Bold else FontWeight.Normal,
                                    color = if (port.isOpen) CslSuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!state.isRunning) {
                Button(
                    onClick = { onRetest(state.targetHost) },
                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                ) {
                    Text("Повторить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TicketAttachmentsCard(
    attachments: List<TicketAttachment>,
    isBusy: Boolean,
    loadAttachmentBytes: suspend (String) -> Result<ByteArray>,
    onUploadUri: (Uri, String?) -> Unit,
    onOpenExternal: (TicketAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFileName by remember { mutableStateOf<String?>(null) }
    var zoomAttachment by remember { mutableStateOf<TicketAttachment?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            onUploadUri(uri, pendingCameraFileName)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onUploadUri(uri, null)
        }
    }

    zoomAttachment?.let { att ->
        ZoomableImageDialog(
            attachment = att,
            loadAttachmentBytes = loadAttachmentBytes,
            onDismiss = { zoomAttachment = null }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Вложения и скриншоты (${attachments.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CslOrange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
                            val name = "photo_${System.currentTimeMillis()}.jpg"
                            val photoFile = File(dir, name)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                photoFile
                            )
                            pendingCameraUri = uri
                            pendingCameraFileName = name
                            cameraLauncher.launch(uri)
                        }.onFailure {
                            Toast.makeText(context, "Не удалось открыть камеру", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, CslOrange)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = CslOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Сделать фото",
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = CslOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Прикрепить",
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (attachments.isEmpty()) {
                Text(
                    text = "Вложений нет. Можно отправить фото с камеры или файл в Jira.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val imageItems = attachments.filter { it.isImage }
                val docItems = attachments.filterNot { it.isImage }

                if (imageItems.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        imageItems.forEach { img ->
                            AttachmentImagePreviewTile(
                                attachment = img,
                                loadAttachmentBytes = loadAttachmentBytes,
                                onClick = { zoomAttachment = img }
                            )
                        }
                    }
                }

                docItems.forEach { doc ->
                    Surface(
                        onClick = { onOpenExternal(doc) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = CslOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = doc.filename,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatFileSize(doc.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Открыть файл",
                                tint = CslOrange,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentImagePreviewTile(
    attachment: TicketAttachment,
    loadAttachmentBytes: suspend (String) -> Result<ByteArray>,
    onClick: () -> Unit
) {
    var bitmap by remember(attachment.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(attachment.id) { mutableStateOf(false) }

    LaunchedEffect(attachment.id) {
        val previewUrl = attachment.thumbnailUrl ?: attachment.contentUrl
        val bytes = loadAttachmentBytes(previewUrl).getOrNull()
            ?: if (attachment.thumbnailUrl != null) loadAttachmentBytes(attachment.contentUrl).getOrNull() else null
        if (bytes != null) {
            val decoded = withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            if (decoded != null) {
                bitmap = decoded
            } else {
                failed = true
            }
        } else {
            failed = true
        }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(136.dp)
            .height(104.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val bmp = bitmap
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp,
                        contentDescription = attachment.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                failed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, tint = CslOrange)
                        Text(
                            text = attachment.filename,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = CslOrange)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(CslMidnight.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ZoomableImageDialog(
    attachment: TicketAttachment,
    loadAttachmentBytes: suspend (String) -> Result<ByteArray>,
    onDismiss: () -> Unit
) {
    var fullBitmap by remember(attachment.id) { mutableStateOf<ImageBitmap?>(null) }
    var errorMsg by remember(attachment.id) { mutableStateOf<String?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + offsetChange else Offset.Zero
    }

    LaunchedEffect(attachment.id) {
        loadAttachmentBytes(attachment.contentUrl)
            .onSuccess { bytes ->
                val bmp = withContext(Dispatchers.Default) {
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bmp != null) fullBitmap = bmp else errorMsg = "Формат изображения не поддерживается"
            }
            .onFailure { err ->
                errorMsg = err.message ?: "Не удалось загрузить скриншот"
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CslMidnight.copy(alpha = 0.96f))
        ) {
            val bmp = fullBitmap
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp,
                        contentDescription = attachment.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                            .transformable(state = transformState)
                    )
                }
                errorMsg != null -> {
                    Text(
                        text = errorMsg!!,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                else -> {
                    CircularProgressIndicator(
                        color = CslOrange,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(CslMidnight.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = attachment.filename,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun ClientHistoryCard(
    clientName: String,
    historyState: ClientHistoryUiState,
    onOpenTicket: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!historyState.isLoading && historyState.items.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = CslOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Прошлые обращения клиента (${historyState.items.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange
                    )
                    Text(
                        text = clientName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (historyState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CslOrange, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    historyState.items.forEach { item ->
                        Surface(
                            onClick = { onOpenTicket(item.key) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.key,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CslOrange
                                    )
                                    Text(
                                        text = item.status,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = item.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                item.assigneeName?.let { who ->
                                    Text(
                                        text = "Исполнитель: $who",
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateTicketDialog(
    defaultProjectKey: String,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (projectKey: String, issueType: String, summary: String, description: String, assignToSelf: Boolean) -> Unit
) {
    val context = LocalContext.current
    var projectKey by rememberSaveable { mutableStateOf(defaultProjectKey) }
    var issueType by rememberSaveable { mutableStateOf("Service Request") }
    var summary by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var assignToSelf by rememberSaveable { mutableStateOf(true) }

    val summaryVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) summary = if (summary.isBlank()) spoken else "${summary.trimEnd()} $spoken"
        }
    }

    val descVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) description = if (description.isBlank()) spoken else "${description.trimEnd()} $spoken"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая заявка в Jira", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = projectKey,
                        onValueChange = { projectKey = it.uppercase() },
                        modifier = Modifier.weight(0.42f),
                        singleLine = true,
                        label = { Text("Проект") },
                        placeholder = { Text("CSL") }
                    )
                    OutlinedTextField(
                        value = issueType,
                        onValueChange = { issueType = it },
                        modifier = Modifier.weight(0.58f),
                        singleLine = true,
                        label = { Text("Тип") }
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Service Request", "Task", "Задача").forEach { preset ->
                        Surface(
                            onClick = { issueType = preset },
                            shape = RoundedCornerShape(6.dp),
                            color = if (issueType.equals(preset, ignoreCase = true)) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (issueType.equals(preset, ignoreCase = true)) CslOrange else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Тема обращения *") },
                    maxLines = 2,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                }
                                runCatching { summaryVoiceLauncher.launch(intent) }.onFailure {
                                    Toast.makeText(context, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосом", tint = CslOrange)
                        }
                    }
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Описание / телефон клиента") },
                    minLines = 3,
                    maxLines = 5,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                }
                                runCatching { descVoiceLauncher.launch(intent) }.onFailure {
                                    Toast.makeText(context, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосом", tint = CslOrange)
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Назначить сразу на меня",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = assignToSelf,
                        onCheckedChange = { assignToSelf = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CslOrange)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(projectKey, issueType, summary, description, assignToSelf) },
                enabled = summary.isNotBlank() && !isCreating,
                colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
            ) {
                Text(if (isCreating) "Создание…" else "Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Файл Jira"
    return when {
        bytes < 1024L -> "$bytes Б"
        bytes < 1024L * 1024L -> "${bytes / 1024L} КБ"
        else -> String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
    }
}
