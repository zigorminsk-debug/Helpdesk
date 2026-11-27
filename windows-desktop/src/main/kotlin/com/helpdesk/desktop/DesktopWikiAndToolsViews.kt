package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.data.ConfluenceAttachment
import com.helpdesk.jiraagent.data.ConfluenceBlock
import com.helpdesk.jiraagent.data.ConfluenceCalloutKind
import com.helpdesk.jiraagent.data.ConfluencePageDetail
import com.helpdesk.jiraagent.data.ConfluencePageSummary
import com.helpdesk.jiraagent.data.ConfluenceWikiClient
import com.helpdesk.jiraagent.shared.DiagnosticScenariosView
import com.helpdesk.jiraagent.shared.DomainDiagnosticCardView
import com.helpdesk.jiraagent.domain.ProbeStageState
import com.helpdesk.jiraagent.domain.SysadminToolboxEngine
import com.helpdesk.jiraagent.domain.SysadminToolboxTabs
import com.helpdesk.jiraagent.model.ClientTechPassport
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

// =============================================================================
//  1. CONFLUENCE WIKI TAB (`w.csl.by` with Jira credentials)
// =============================================================================

@Composable
fun DesktopWikiWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    var searchInput by remember(state.wikiSearchQuery) { mutableStateOf(state.wikiSearchQuery) }
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    var lightboxTitle by remember { mutableStateOf<String?>(null) }
    var lightboxBuffered by remember { mutableStateOf<BufferedImage?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wikiSidebarWidth = when {
            maxWidth >= 1420.dp -> 375.dp
            maxWidth >= 1100.dp -> 335.dp
            else -> 292.dp
        }
        Row(modifier = Modifier.fillMaxSize()) {
            // --- LEFT PANE: Spaces, Search & Page Tree ---
            Surface(
                modifier = Modifier.width(wikiSidebarWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DesktopColors.CslMidnight
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "📘 Confluence Wiki",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    state.settings.confluenceUrl.ifBlank { "https://w.csl.by" },
                                    color = DesktopColors.CslOrange,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { controller.syncWikiOfflineDatabase(force = true) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f))
                                ) {
                                    Text("🔄", fontSize = 12.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { controller.openWikiInBrowserWithSso() },
                                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)
                                ) {
                                    Text("🌐 В браузере", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        val loginLabel = state.settings.usernameOrEmail.ifBlank { "не задан в Настройках" }
                        Text(
                            "🔑 Вход под учётной записью Jira: $loginLabel",
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Search bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = {
                            searchInput = it
                            if (it.isBlank()) controller.searchWikiPages("")
                        },
                        placeholder = {
                            Text(
                                "Поиск клиента, IP, инструкции…",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                                    controller.searchWikiPages(searchInput)
                                    true
                                } else false
                            }
                    )
                    Button(
                        onClick = { controller.searchWikiPages(searchInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("Найти", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }

                // Spaces strip (CSL first, Recent at end) — mouse-wheel + ◀/▶ buttons + HorizontalScrollbar
                if (state.wikiSpaces.isNotEmpty()) {
                    DesktopHorizontalScrollRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        showArrowButtons = true,
                        showScrollbar = true,
                        buttonHeight = 30.dp
                    ) {
                        state.wikiSpaces.forEach { space ->
                            val labelText = if (space.name.equals(space.key, ignoreCase = true)) {
                                space.key
                            } else {
                                "${space.key} · ${space.name.take(18)}"
                            }
                            FilterChip(
                                selected = state.selectedWikiSpaceKey.equals(space.key, ignoreCase = true),
                                onClick = { controller.selectWikiSpace(space.key) },
                                label = {
                                    Text(
                                        labelText,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                        FilterChip(
                            selected = state.selectedWikiSpaceKey == ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY,
                            onClick = { controller.selectWikiSpace(ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY) },
                            label = { Text("🕒 Недавние", fontSize = 11.5.sp, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                if (state.isLoadingWikiList) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopColors.CslOrange)
                }

                if (state.isSyncingWikiOfflineDb) {
                    val pct = (state.wikiSyncProgressFraction.coerceIn(0.01f, 0.99f) * 100f).toInt()
                    Surface(
                        color = DesktopColors.CslOrange.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.50f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⏬ Загрузка офлайн-базы w.csl.by",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DesktopColors.CslOrange
                                )
                                Text(
                                    text = "$pct%",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DesktopColors.CslOrange
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(DesktopColors.CslOrange.copy(alpha = 0.22f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(state.wikiSyncProgressFraction.coerceIn(0.02f, 1f))
                                        .fillMaxHeight()
                                        .background(DesktopColors.CslOrange)
                                )
                            }
                            Text(
                                text = state.wikiSyncProgressText ?: "Подключение к w.csl.by…",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else if (state.wikiOfflinePagesCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "💾 Офлайн: ${state.wikiOfflinePagesCount} стр. · ${state.wikiOfflineAttachmentSummary} · Синхр.: ${state.wikiOfflineSyncedAtLabel}",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "🔄 Синхр.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DesktopColors.CslOrange,
                            modifier = Modifier
                                .clickable { controller.syncWikiOfflineDatabase(force = true) }
                                .padding(start = 6.dp)
                        )
                    }
                }

                state.wikiError?.let { err ->
                    var patInput by remember { mutableStateOf("") }
                    Surface(
                        color = DesktopColors.CslOrange.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.45f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(err, fontSize = 12.sp)
                            Button(
                                onClick = { controller.openWikiInBrowserWithSso() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("🌐 Открыть w.csl.by в браузере", fontSize = 11.5.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { controller.syncWikiOfflineDatabase(force = true) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("🔄 Скачать / Обновить офлайн-базу", fontSize = 11.5.sp, maxLines = 1)
                            }
                            Text(
                                "Для встроенной читалки при включённой 2FA создайте Personal Access Token в профиле w.csl.by:",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { controller.openConfluencePatCreationPage() },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("🔑 Создать токен в w.csl.by (Профиль → Персональные токены) ↗", fontSize = 11.5.sp)
                            }
                            OutlinedTextField(
                                value = patInput,
                                onValueChange = { patInput = it },
                                label = { Text("Вставьте PAT-токен Confluence", fontSize = 11.5.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (patInput.isNotBlank()) {
                                Button(
                                    onClick = { controller.saveConfluencePatToken(patInput) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DesktopColors.ButtonAccent,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Сохранить токен и подключить Wiki", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Page list / alphabetical client directory
                var selectedLetter by remember(state.selectedWikiSpaceKey) { mutableStateOf<String?>(null) }
                val isSearchMode = state.wikiSearchResults != null
                val basePages = remember(state.wikiSearchResults, state.wikiPages, searchInput, isSearchMode) {
                    val raw = state.wikiSearchResults ?: state.wikiPages
                    if (!isSearchMode && searchInput.isNotBlank()) {
                        val q = searchInput.trim()
                        raw.filter { it.title.contains(q, ignoreCase = true) }
                    } else {
                        raw
                    }
                }

                val availableLetters = remember(basePages, state.selectedWikiSpaceKey, isSearchMode) {
                    if (isSearchMode || state.selectedWikiSpaceKey == ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY || basePages.size < 6) {
                        emptyList()
                    } else {
                        basePages.map { ConfluenceWikiClient.firstAlphabetLetter(it.title) }.distinct()
                    }
                }

                if (availableLetters.isNotEmpty()) {
                    DesktopHorizontalScrollRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        showArrowButtons = true,
                        showScrollbar = true,
                        buttonHeight = 24.dp
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selectedLetter == null) DesktopColors.ButtonAccent else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { selectedLetter = null }
                        ) {
                            Text(
                                text = "Все (${basePages.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedLetter == null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        availableLetters.forEach { letter ->
                            val isSel = selectedLetter == letter
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) DesktopColors.ButtonAccent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.clickable { selectedLetter = if (isSel) null else letter }
                            ) {
                                Text(
                                    text = letter,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                val displayedPages = remember(basePages, selectedLetter) {
                    if (selectedLetter == null) basePages
                    else basePages.filter { ConfluenceWikiClient.firstAlphabetLetter(it.title) == selectedLetter }
                }

                if (isSearchMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Найдено страниц: ${displayedPages.size} (по алфавиту)",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = {
                                searchInput = ""
                                controller.searchWikiPages("")
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Сбросить поиск", fontSize = 11.5.sp)
                        }
                    }
                }

                val treeListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = treeListState,
                        modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (displayedPages.isEmpty() && !state.isLoadingWikiList && state.wikiError == null) {
                            item {
                                Text(
                                    if (isSearchMode || searchInput.isNotBlank()) "Ничего не найдено по запросу «$searchInput»"
                                    else "Нет страниц для отображения. Нажмите 🔄 для загрузки из w.csl.by.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            items(displayedPages, key = { it.id }) { page ->
                                WikiTreeNodeItem(
                                    page = page,
                                    depth = 0,
                                    isSearchMode = isSearchMode,
                                    state = state,
                                    controller = controller
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(treeListState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // --- RIGHT PANE: Rich Confluence Page Reader ---
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val detail = state.selectedWikiPage
            when {
                state.isLoadingWikiPage && detail == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = DesktopColors.CslOrange)
                            Text("Загрузка страницы из Confluence (w.csl.by)…", fontSize = 13.sp)
                        }
                    }
                }
                detail == null -> {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("📘 База знаний Confluence (w.csl.by)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Выберите пространство или введите название организации / IP-адрес слева.\nАвторизация выполняется автоматически под вашим логином и паролем Jira.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { controller.openWikiInBrowserWithSso() },
                                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                            ) {
                                Text("🌐 Открыть w.csl.by в браузере (с автовходом)")
                            }
                        }
                    }
                }
                else -> {
                    ConfluencePageReaderPane(
                        detail = detail,
                        state = state,
                        isReloading = state.isLoadingWikiPage,
                        copiedNotice = copiedNotice,
                        onCopied = { copiedNotice = it },
                        controller = controller,
                        onOpenLightbox = { title, img ->
                            lightboxTitle = title
                            lightboxBuffered = img
                        }
                    )
                }
            }
        }
        }
    }

    if (lightboxBuffered != null) {
        DesktopImageLightboxDialog(
            title = lightboxTitle ?: "Изображение Confluence",
            bufferedImage = lightboxBuffered!!,
            onDismiss = { lightboxBuffered = null }
        )
    }
}

@Composable
private fun WikiTreeNodeItem(
    page: ConfluencePageSummary,
    depth: Int,
    isSearchMode: Boolean,
    state: DesktopUiState,
    controller: DesktopController
) {
    val isSelected = state.selectedWikiPage?.id == page.id
    val isExpanded = page.id in state.wikiExpandedPageIds
    val children = state.wikiChildPagesByParent[page.id].orEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(start = (depth * 14).dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isSelected) DesktopColors.CslOrangeContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = if (isSelected) BorderStroke(1.dp, DesktopColors.CslOrange) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { controller.openWikiPage(page.id) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isSearchMode) {
                    Text(
                        text = if (isExpanded) "▾" else "▸",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = DesktopColors.CslOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { controller.toggleWikiTreeExpand(page.id) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                } else {
                    Text("📄", fontSize = 12.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = page.title,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (page.excerpt.isNotBlank() && isSearchMode) {
                        Text(
                            text = page.excerpt,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (page.spaceKey.isNotBlank()) {
                        Text(
                            text = listOfNotNull(
                                page.spaceName.ifBlank { page.spaceKey },
                                page.updatedDisplay.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        if (!isSearchMode && isExpanded && depth < 3) {
            Spacer(Modifier.height(3.dp))
            children.forEach { child ->
                WikiTreeNodeItem(
                    page = child,
                    depth = depth + 1,
                    isSearchMode = false,
                    state = state,
                    controller = controller
                )
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun ConfluencePageReaderPane(
    detail: ConfluencePageDetail,
    state: DesktopUiState,
    isReloading: Boolean,
    copiedNotice: String?,
    onCopied: (String) -> Unit,
    controller: DesktopController,
    onOpenLightbox: (String, BufferedImage) -> Unit
) {
    var folderViewTab by remember(detail.id) { mutableIntStateOf(0) }
    var activeInlineTabIdx by remember(detail.id) { mutableIntStateOf(0) }
    var subPageFilter by remember(detail.id) { mutableStateOf("") }

    val hasSubPages = detail.childPages.isNotEmpty()
    val parentCrumb = detail.ancestors.lastOrNull()
    val siblingPages = remember(detail.id, state.wikiChildPagesByParent, parentCrumb) {
        val pid = state.wikiPageHistoryStack.lastOrNull()?.id ?: parentCrumb?.id
        if (pid != null) state.wikiChildPagesByParent[pid].orEmpty() else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isReloading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopColors.CslOrange)
        }
        // Page top bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Back button + Breadcrumbs + Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DesktopHorizontalScrollRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        showArrowButtons = true,
                        showScrollbar = false,
                        buttonHeight = 26.dp
                    ) {
                        if (state.wikiPageHistoryStack.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { controller.navigateBackWikiPage() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "← ${state.wikiPageHistoryStack.last().title.take(24)}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        } else if (detail.ancestors.size > 1) {
                            OutlinedButton(
                                onClick = { controller.openWikiPage(detail.ancestors.last().id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "← ${detail.ancestors.last().title.take(24)}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        if (detail.spaceKey.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = DesktopColors.CslMidnight,
                                modifier = Modifier.clickable { controller.selectWikiSpace(detail.spaceKey) }
                            ) {
                                Text(
                                    detail.spaceName.ifBlank { detail.spaceKey },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        detail.ancestors.forEach { anc ->
                            Text("›", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                anc.title,
                                fontSize = 11.5.sp,
                                color = DesktopColors.CslOrange,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clickable { controller.openWikiPage(anc.id) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        copiedNotice?.let { msg ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DesktopColors.StatusGreen.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    "✓ $msg",
                                    fontSize = 11.sp,
                                    color = DesktopColors.StatusGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                WindowsExternalLaunchers.copyToClipboard(detail.webUrl)
                                onCopied("Ссылка скопирована")
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("🔗 Ссылка", fontSize = 11.5.sp)
                        }
                        Button(
                            onClick = { controller.openWikiInBrowserWithSso(detail.webUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("🌐 Открыть в w.csl.by", fontSize = 11.5.sp)
                        }
                    }
                }

                Text(detail.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)

                // Sibling sub-tabs switcher when viewing a sub-page of a client
                if (!hasSubPages && siblingPages.size > 1) {
                    DesktopHorizontalScrollRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        showArrowButtons = true,
                        showScrollbar = true,
                        buttonHeight = 28.dp
                    ) {
                        siblingPages.forEach { sib ->
                            val selected = sib.id == detail.id
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        controller.openWikiPage(sib.id, replaceSiblingInStack = true)
                                    }
                                },
                                label = {
                                    Text(
                                        sib.title,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                // Switcher when client page has both sub-tabs AND own root body
                if (hasSubPages && detail.hasSubstantialBody) {
                    TabRow(
                        selectedTabIndex = folderViewTab,
                        containerColor = Color.Transparent
                    ) {
                        Tab(
                            selected = folderViewTab == 0,
                            onClick = { folderViewTab = 0 },
                            text = {
                                Text(
                                    "📂 Подкладки с информацией (${detail.childPages.size})",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (folderViewTab == 0) FontWeight.Bold else FontWeight.Medium,
                                    color = if (folderViewTab == 0) DesktopColors.CslOrange else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                        Tab(
                            selected = folderViewTab == 1,
                            onClick = { folderViewTab = 1 },
                            text = {
                                Text(
                                    "📋 Общая информация вкладки",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (folderViewTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (folderViewTab == 1) DesktopColors.CslOrange else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (hasSubPages && (!detail.hasSubstantialBody || folderViewTab == 0)) {
            val filteredSubPages = remember(detail.childPages, subPageFilter) {
                if (subPageFilter.isBlank()) detail.childPages
                else detail.childPages.filter { it.title.contains(subPageFilter.trim(), ignoreCase = true) }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📂 Все подкладки «${detail.title}» (${detail.childPages.size}) · по алфавиту:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (detail.childPages.size > 6) {
                        OutlinedTextField(
                            value = subPageFilter,
                            onValueChange = { subPageFilter = it },
                            placeholder = { Text("Фильтр по подкладкам…", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.width(260.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredSubPages, key = { it.id }) { child ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            tonalElevation = 1.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { controller.openWikiPage(child.id) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("📑", fontSize = 16.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = child.title,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (child.updatedDisplay.isNotBlank()) {
                                        Text(
                                            text = "Обновлено: ${child.updatedDisplay}",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = "Открыть состав →",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DesktopColors.CslOrange
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val blocksToRender = if (detail.inlineTabs.isNotEmpty()) {
                val safeIdx = activeInlineTabIdx.coerceIn(0, detail.inlineTabs.lastIndex)
                detail.inlineTabs[safeIdx].blocks
            } else {
                detail.blocks
            }

            if (detail.inlineTabs.size > 1) {
                val safeIdx = activeInlineTabIdx.coerceIn(0, detail.inlineTabs.lastIndex)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DesktopHorizontalScrollRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        showArrowButtons = true,
                        showScrollbar = true,
                        buttonHeight = 28.dp
                    ) {
                        detail.inlineTabs.forEachIndexed { idx, tab ->
                            val selected = safeIdx == idx
                            FilterChip(
                                selected = selected,
                                onClick = { activeInlineTabIdx = idx },
                                label = {
                                    Text(
                                        text = tab.title,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Content blocks + attachments
            val readerListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = readerListState,
                        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 28.dp, top = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            val metaParts = listOfNotNull(
                                detail.updatedBy.takeIf { it.isNotBlank() }?.let { "Автор изм.: $it" },
                                detail.updatedDisplay.takeIf { it.isNotBlank() }?.let { "Обновлено: $it" },
                                "Версия v${detail.versionNumber}",
                                "Нажмите на любую ячейку таблицы, чтобы скопировать IP/пароль"
                            )
                            Text(
                                metaParts.joinToString("  ·  "),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(blocksToRender) { block ->
                            ConfluenceBlockView(
                                block = block,
                                controller = controller,
                                onCopied = onCopied,
                                onOpenLightbox = onOpenLightbox
                            )
                        }

                        if (detail.attachments.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "📎 Вложенные файлы страницы (${detail.attachments.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(detail.attachments, key = { it.id }) { att ->
                                ConfluenceAttachmentRow(att = att, onOpen = { controller.downloadAndOpenWikiAttachment(att) })
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(readerListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 6.dp, bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfluenceBlockView(
    block: ConfluenceBlock,
    controller: DesktopController,
    onCopied: (String) -> Unit,
    onOpenLightbox: (String, BufferedImage) -> Unit
) {
    when (block) {
        is ConfluenceBlock.Heading -> {
            val size = when (block.level) {
                1 -> 19.sp
                2 -> 16.5.sp
                3 -> 14.5.sp
                else -> 13.5.sp
            }
            Text(
                text = block.text,
                fontSize = size,
                fontWeight = FontWeight.Bold,
                color = DesktopColors.CslMidnight,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        is ConfluenceBlock.Paragraph -> {
            Text(
                text = block.text,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        is ConfluenceBlock.ListItems -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                block.items.forEachIndexed { idx, item ->
                    val prefix = if (block.ordered) "${idx + 1}." else "•"
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(prefix, fontWeight = FontWeight.Bold, color = DesktopColors.CslOrange, fontSize = 13.sp)
                        Text(item, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
        is ConfluenceBlock.Callout -> {
            val isDark = LocalDesktopDarkTheme.current
            val (bg, border, icon) = when (block.kind) {
                ConfluenceCalloutKind.WARNING -> Triple(
                    if (isDark) DesktopColors.StatusAmber.copy(alpha = 0.14f) else Color(0xFFFFFBEB),
                    DesktopColors.StatusAmber,
                    "⚠"
                )
                ConfluenceCalloutKind.NOTE -> Triple(
                    if (isDark) DesktopColors.StatusRed.copy(alpha = 0.14f) else Color(0xFFFEF2F2),
                    DesktopColors.StatusRed,
                    "📌"
                )
                ConfluenceCalloutKind.TIP -> Triple(
                    if (isDark) DesktopColors.StatusGreen.copy(alpha = 0.14f) else Color(0xFFECFDF5),
                    DesktopColors.StatusGreen,
                    "💡"
                )
                ConfluenceCalloutKind.INFO -> Triple(
                    if (isDark) Color(0xFF2563EB).copy(alpha = 0.15f) else Color(0xFFEFF6FF),
                    if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                    "ℹ"
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = bg,
                border = BorderStroke(1.dp, border.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (block.title.isNotBlank()) {
                        Text("$icon ${block.title}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = border)
                    }
                    Text(block.body, fontSize = 12.5.sp, lineHeight = 18.sp, color = DesktopColors.TextPrimary)
                }
            }
        }
        is ConfluenceBlock.Code -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DesktopColors.CslMidnight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            block.title.ifBlank { "Код / Команда" },
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = {
                                WindowsExternalLaunchers.copyToClipboard(block.code)
                                onCopied("Код скопирован")
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("📋 Копировать", color = DesktopColors.CslOrange, fontSize = 11.sp)
                        }
                    }
                    val codeScroll = rememberScrollState()
                    Text(
                        text = block.code,
                        color = Color(0xFFF8FAFC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .desktopWheelHorizontalScroll(codeScroll)
                            .horizontalScroll(codeScroll)
                    )
                    if (codeScroll.maxValue > 0) {
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(codeScroll),
                            modifier = Modifier.fillMaxWidth().height(7.dp)
                        )
                    }
                }
            }
        }
        is ConfluenceBlock.Table -> {
            val colCount = maxOf(
                block.headers.size,
                block.rows.maxOfOrNull { it.size } ?: 0
            ).coerceAtLeast(1)
            val tableScroll = rememberScrollState()

            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .desktopWheelHorizontalScroll(tableScroll)
                            .horizontalScroll(tableScroll)
                    ) {
                        if (block.headers.isNotEmpty()) {
                            Row(modifier = Modifier.background(DesktopColors.CslMidnight)) {
                                for (c in 0 until colCount) {
                                    val cellText = block.headers.getOrNull(c).orEmpty()
                                    Box(
                                        modifier = Modifier
                                            .widthIn(min = 140.dp, max = 280.dp)
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = cellText,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                        block.rows.forEachIndexed { rIdx, row ->
                            val rowBg = if (rIdx % 2 == 0) DesktopColors.CardBg else DesktopColors.SubtleBg
                            Row(modifier = Modifier.background(rowBg)) {
                                for (c in 0 until colCount) {
                                    val cellVal = row.getOrNull(c).orEmpty()
                                    Box(
                                        modifier = Modifier
                                            .widthIn(min = 140.dp, max = 280.dp)
                                            .clickable(enabled = cellVal.isNotBlank()) {
                                                WindowsExternalLaunchers.copyToClipboard(cellVal)
                                                onCopied("Скопировано: ${cellVal.take(28)}")
                                            }
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = cellVal.ifBlank { "—" },
                                            fontSize = 12.sp,
                                            color = DesktopColors.TextPrimary
                                        )
                                    }
                                }
                            }
                            if (rIdx < block.rows.lastIndex) {
                                HorizontalDivider(color = DesktopColors.BorderSubtle)
                            }
                        }
                    }
                }
                if (tableScroll.maxValue > 0) {
                    Spacer(Modifier.height(3.dp))
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(tableScroll),
                        modifier = Modifier.fillMaxWidth().height(7.dp)
                    )
                }
            }
        }
        is ConfluenceBlock.Image -> {
            var buffered by remember(block.url) { mutableStateOf<BufferedImage?>(null) }
            var composeBmp by remember(block.url) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(block.url) {
                val bytes = controller.loadWikiImageBytes(block.url) ?: return@LaunchedEffect
                val decoded = withContext(Dispatchers.IO) {
                    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                }
                if (decoded != null) {
                    buffered = decoded
                    composeBmp = decoded.toComposeImageBitmap()
                }
            }
            if (composeBmp != null && buffered != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { onOpenLightbox(block.alt.ifBlank { "Схема Confluence" }, buffered!!) }
                ) {
                    Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = composeBmp!!,
                            contentDescription = block.alt,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.heightIn(max = 320.dp).fillMaxWidth()
                        )
                        Text(
                            "🔍 ${block.alt.ifBlank { "Нажмите для увеличения" }}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfluenceAttachmentRow(
    att: ConfluenceAttachment,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("📎 ${att.title}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                if (att.fileSize > 0) {
                    Text(
                        "${(att.fileSize / 1024).coerceAtLeast(1)} КБ · ${att.mediaType}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Открыть", fontSize = 11.5.sp)
            }
        }
    }
}

// =============================================================================
//  2. INLINE ATTACHMENT THUMBNAILS + ZOOMABLE LIGHTBOX
// =============================================================================

@Composable
fun DesktopAttachmentThumbnailStrip(
    attachments: List<TicketAttachment>,
    controller: DesktopController,
    onOpenOriginal: (TicketAttachment) -> Unit
) {
    val imageAttachments = remember(attachments) {
        attachments.filter { it.isImage }
    }
    if (imageAttachments.isEmpty()) return

    var activeLightboxAtt by remember { mutableStateOf<TicketAttachment?>(null) }
    var activeLightboxImg by remember { mutableStateOf<BufferedImage?>(null) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        items(imageAttachments, key = { it.id.ifBlank { it.contentUrl } }) { att ->
            var buffered by remember(att.contentUrl) { mutableStateOf<BufferedImage?>(null) }
            var bitmap by remember(att.contentUrl) { mutableStateOf<ImageBitmap?>(null) }

            LaunchedEffect(att.contentUrl) {
                val url = att.thumbnailUrl?.takeIf { it.isNotBlank() } ?: att.contentUrl
                val bytes = controller.loadJiraAttachmentBytes(url)
                    ?: controller.loadJiraAttachmentBytes(att.contentUrl)
                    ?: return@LaunchedEffect
                val img = withContext(Dispatchers.IO) {
                    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                }
                if (img != null) {
                    buffered = img
                    bitmap = img.toComposeImageBitmap()
                }
            }

            val scope = rememberCoroutineScope()
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .width(155.dp)
                    .clickable {
                        scope.launch {
                            val fullBytes = controller.loadJiraAttachmentBytes(att.contentUrl)
                            val fullImg = fullBytes?.let { b ->
                                withContext(Dispatchers.IO) {
                                    runCatching { ImageIO.read(ByteArrayInputStream(b)) }.getOrNull()
                                }
                            } ?: buffered
                            if (fullImg != null) {
                                activeLightboxAtt = att
                                activeLightboxImg = fullImg
                            } else {
                                onOpenOriginal(att)
                            }
                        }
                    }
            ) {
                Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!,
                                contentDescription = att.filename,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("🖼 Загрузка…", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    Text(
                        "🔍 ${att.filename}",
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (activeLightboxImg != null) {
        DesktopImageLightboxDialog(
            title = activeLightboxAtt?.filename ?: "Просмотр скриншота",
            bufferedImage = activeLightboxImg!!,
            onOpenExternal = activeLightboxAtt?.let { att -> { onOpenOriginal(att) } },
            onDismiss = {
                activeLightboxImg = null
                activeLightboxAtt = null
            }
        )
    }
}

@Composable
fun DesktopImageLightboxDialog(
    title: String,
    bufferedImage: BufferedImage,
    onOpenExternal: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val composeBitmap = remember(bufferedImage) { bufferedImage.toComposeImageBitmap() }
    var zoom by remember { mutableStateOf(1.0f) }
    var copied by remember { mutableStateOf(false) }

    DesktopWorkspaceWindow(onDismissRequest = onDismiss, title = "🔍 $title") {
            Column(modifier = Modifier.fillMaxSize().background(DesktopColors.CslMidnight)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$title (${bufferedImage.width}×${bufferedImage.height} px)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.25f) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("−", color = Color.White) }
                        Text("${(zoom * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = { zoom = (zoom + 0.25f).coerceAtMost(4.0f) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("+", color = Color.White) }
                        OutlinedButton(
                            onClick = { zoom = 1.0f },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("100%", color = Color.White, fontSize = 11.5.sp) }
                        Button(
                            onClick = {
                                WindowsExternalLaunchers.copyImageToClipboard(bufferedImage)
                                copied = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(if (copied) "✓ В буфере" else "📋 Копировать картинку", fontSize = 11.5.sp)
                        }
                        onOpenExternal?.let { openExt ->
                            OutlinedButton(
                                onClick = openExt,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("📂 В Windows", color = Color.White, fontSize = 11.5.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Закрыть", color = Color.White, fontSize = 11.5.sp)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = composeBitmap,
                        contentDescription = title,
                        modifier = Modifier.size(
                            width = (bufferedImage.width * zoom).coerceAtLeast(120f).dp,
                            height = (bufferedImage.height * zoom).coerceAtLeast(90f).dp
                        )
                    )
                }
            }
    }
}

// =============================================================================
//  3. TEAM-SYNCED CLIENT TECH PASSPORT DIALOG (`[HELPCSL_PASSPORT_V1]`)
// =============================================================================

@Composable
fun DesktopClientPassportDialog(
    initialPassport: ClientTechPassport,
    onSave: (ClientTechPassport) -> Unit,
    onOpenInWiki: () -> Unit,
    onDismiss: () -> Unit
) {
    var addresses by remember { mutableStateOf(initialPassport.addressesAndBranches) }
    var gateway by remember { mutableStateOf(initialPassport.gatewayAndProvider) }
    var vpn by remember { mutableStateOf(initialPassport.vpnAndRemoteNotes) }
    var servers by remember { mutableStateOf(initialPassport.serversAndServices) }
    var notes by remember { mutableStateOf(initialPassport.contactsAndNotes) }

    DesktopWorkspaceWindow(onDismissRequest = onDismiss, title = "🏢 Техпаспорт клиента · ${initialPassport.clientDisplayName}") {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🏢 ${initialPassport.clientDisplayName}",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (initialPassport.updatedAtEpochMs > 0)
                                "Облачная синхронизация через Jira · обновил: ${initialPassport.updatedBy.ifBlank { "инженер" }}"
                            else "Единое техдосье организации для всех инженеров на Windows и Android",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onOpenInWiki) {
                        Text("📘 Найти в Wiki (w.csl.by)", fontSize = 12.sp)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = addresses,
                        onValueChange = { addresses = it },
                        label = { Text("📍 Адреса офисов / филиалов / режим работы") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = gateway,
                        onValueChange = { gateway = it },
                        label = { Text("🌐 Шлюз / Внешний IP / Провайдер / Роутер (MikroTik, Keenetic…)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = vpn,
                        onValueChange = { vpn = it },
                        label = { Text("🔐 VPN / AnyDesk / RustDesk / RDP-порты") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = servers,
                        onValueChange = { servers = it },
                        label = { Text("🖥 Серверы / Домен AD / Базы 1С / Принтеры и МФУ") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("👤 Контактные лица на местах и особые пометки") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                initialPassport.copy(
                                    addressesAndBranches = addresses.trim(),
                                    gatewayAndProvider = gateway.trim(),
                                    vpnAndRemoteNotes = vpn.trim(),
                                    serversAndServices = servers.trim(),
                                    contactsAndNotes = notes.trim()
                                )
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                    ) {
                        Text("💾 Сохранить и синхронизировать")
                    }
                }
            }
    }
}

// =============================================================================
//  4. KNOWLEDGE BASE ARCHIVE SEARCH («База решений по закрытым заявкам»)
// =============================================================================

@Composable
fun DesktopKnowledgeArchiveDialog(
    initialQuery: String,
    excludeTicketKey: String?,
    state: DesktopUiState,
    controller: DesktopController,
    onOpenTicket: (String) -> Unit,
    onInsertSolution: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var expandedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) {
            controller.searchKnowledgeArchive(initialQuery, excludeTicketKey)
        }
    }

    DesktopWorkspaceWindow(onDismissRequest = onDismiss, title = "📚 База решений (поиск по решённым заявкам Jira)") {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Код ошибки, модель МФУ, 1С, VPN, симптомы…") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                                    controller.searchKnowledgeArchive(query, excludeTicketKey)
                                    true
                                } else false
                            }
                    )
                    Button(
                        onClick = { controller.searchKnowledgeArchive(query, excludeTicketKey) },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                    ) {
                        Text("🔍 Найти решения")
                    }
                }

                if (state.isSearchingKnowledge) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopColors.CslOrange)
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.knowledgeArchiveResults.isEmpty() && !state.isSearchingKnowledge) {
                        item {
                            Text(
                                "Введите ключевые слова ошибки (например: «0x0000011b», «Kyocera замятие», «ЭЦП Авест», «1С лицензия») и нажмите «Найти решения».",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    items(state.knowledgeArchiveResults, key = { it.key }) { t ->
                        val isExp = expandedKey == t.key
                        val lastComment = t.comments.lastOrNull()
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedKey = if (isExp) null else t.key
                            }
                        ) {
                            SelectionContainer {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${t.key} · ${t.summary}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${t.status} · ${t.createdIso.take(10)}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        "Клиент: ${t.reporterName}  |  Инженер: ${t.assigneeName ?: "—"}",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (lastComment != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = DesktopColors.CardBg,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "💡 Решение (${lastComment.author}): ${if (isExp) lastComment.body else lastComment.body.take(220)}",
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(8.dp)
                                            )
                                        }
                                    } else if (isExp && t.description.isNotBlank()) {
                                        Text(t.description.take(500), fontSize = 12.sp)
                                    }
                                    DisableSelection {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    onOpenTicket(t.key)
                                                    onDismiss()
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text("Открыть карточку ${t.key}", fontSize = 11.5.sp)
                                            }
                                            if (lastComment != null && onInsertSolution != null) {
                                                Button(
                                                    onClick = {
                                                        onInsertSolution(lastComment.body)
                                                        onDismiss()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Вставить решение в ответ", fontSize = 11.5.sp)
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
    }
}

// =============================================================================
//  5. SYSADMIN TOOLBOX DIALOG (Wake-on-LAN, Subnet, Password Gen, Watchlist)
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopSysadminToolboxDialog(
    state: DesktopUiState,
    controller: DesktopController,
    initialTabIndex: Int = 0,
    onDismiss: () -> Unit
) {
    var tabIndex by remember { mutableStateOf(SysadminToolboxTabs.validIdOrDefault(initialTabIndex)) }
    val tabs = SysadminToolboxTabs.ordered
    val scenarioDraftHolder = rememberSaveableStateHolder()
    var scenarioCopyStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tabIndex) {
        if (tabIndex == 3 && state.watchlistStatuses.isEmpty() && !state.isCheckingWatchlist) {
            controller.refreshWatchlistHosts()
        }
    }

    DesktopWorkspaceWindow(onDismissRequest = onDismiss, title = "🛠 Инструменты системного администратора · Help-CSL") {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                // Wrap tabs instead of hiding the first/last item behind horizontal scrolling.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).background(DesktopColors.CslMidnight).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    tabs.forEach { tab ->
                        val selected = tabIndex == tab.id
                        TextButton(
                            onClick = { tabIndex = tab.id },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = if (selected) DesktopColors.ButtonAccent else Color.Transparent, contentColor = Color.White)
                        ) {
                            Text(tab.desktopTitle, fontSize = 12.sp, maxLines = 1, softWrap = false,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = Color.White)
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp)) {
                    when (tabIndex) {
                        0 -> {
                            var mac by remember { mutableStateOf("") }
                            var broadcast by remember { mutableStateOf("255.255.255.255") }
                            var portText by remember { mutableStateOf("9") }
                            var resultMsg by remember { mutableStateOf<String?>(null) }

                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "Отправка Magic Packet (UDP) для дистанционного включения ПК или сервера в локальной/VPN-сети",
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = mac,
                                    onValueChange = { mac = it },
                                    label = { Text("MAC-адрес сетевой карты (например: AA:BB:CC:DD:EE:FF или AABB.CCDD.EEFF)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = broadcast,
                                        onValueChange = { broadcast = it },
                                        label = { Text("Broadcast IP / Шлюз подсети") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = portText,
                                        onValueChange = { portText = it.filter(Char::isDigit) },
                                        label = { Text("UDP порт") },
                                        singleLine = true,
                                        modifier = Modifier.width(120.dp)
                                    )
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val port = portText.toIntOrNull() ?: 9
                                            SysadminToolboxEngine.sendWakeOnLan(mac, broadcast, port)
                                                .onSuccess { r ->
                                                    resultMsg = "✓ Magic Packet успешно отправлен на ${r.normalizedMac} через ${r.targetAddress}:${r.port} (и порт 7)"
                                                }
                                                .onFailure { e ->
                                                    resultMsg = "⚠ Ошибка: ${e.message}"
                                                }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                                ) {
                                    Text("⚡ Разбудить компьютер (Wake-on-LAN)")
                                }
                                resultMsg?.let {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(it, fontSize = 12.5.sp, modifier = Modifier.padding(12.dp))
                                    }
                                }
                            }
                        }

                        1 -> {
                            var ipInput by remember { mutableStateOf("192.168.1.10/24") }
                            val calc = remember(ipInput) { SysadminToolboxEngine.calculateSubnet(ipInput) }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = ipInput,
                                    onValueChange = { ipInput = it },
                                    label = { Text("IPv4 и префикс CIDR или маска (например: 192.168.10.25/24 или 10.8.0.5 255.255.252.0)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                calc.onSuccess { info ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        SelectionContainer {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                SubnetRow("Адрес сети:", "${info.networkAddress}/${info.cidrPrefix}")
                                                SubnetRow("Маска подсети:", "${info.subnetMask}  (Wildcard: ${info.wildcardMask})")
                                                SubnetRow("Первый рабочий хост (шлюз):", info.firstUsableHost)
                                                SubnetRow("Последний рабочий хост:", info.lastUsableHost)
                                                SubnetRow("Broadcast:", info.broadcastAddress)
                                                SubnetRow("Доступно узлов:", "${info.usableHostCount} хостов")
                                                DisableSelection {
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                val summary = """
                                                                    Сеть: ${info.networkAddress}/${info.cidrPrefix}
                                                                    Маска: ${info.subnetMask}
                                                                    Диапазон: ${info.firstUsableHost} — ${info.lastUsableHost}
                                                                    Broadcast: ${info.broadcastAddress} (${info.usableHostCount} узлов)
                                                                """.trimIndent()
                                                                WindowsExternalLaunchers.copyToClipboard(summary)
                                                            }
                                                        ) { Text("📋 Скопировать расчёт", fontSize = 12.sp) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }.onFailure { err ->
                                    Text("⚠ ${err.message}", color = DesktopColors.StatusRed, fontSize = 12.5.sp)
                                }
                            }
                        }

                        2 -> {
                            var service by remember { mutableStateOf("VPN / RDP") }
                            var login by remember { mutableStateOf("") }
                            var length by remember { mutableStateOf(16f) }
                            var useSymbols by remember { mutableStateOf(true) }
                            var password by remember {
                                mutableStateOf(SysadminToolboxEngine.generateStrongPassword(16, true))
                            }
                            var copiedCard by remember { mutableStateOf(false) }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = service,
                                        onValueChange = { service = it },
                                        label = { Text("Сервис (1С, RDP, Почта, VPN)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = login,
                                        onValueChange = { login = it },
                                        label = { Text("Логин пользователя") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Длина: ${length.toInt()}", fontSize = 12.5.sp)
                                    Slider(
                                        value = length,
                                        onValueChange = {
                                            length = it
                                            password = SysadminToolboxEngine.generateStrongPassword(it.toInt(), useSymbols)
                                            copiedCard = false
                                        },
                                        valueRange = 10f..28f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = useSymbols,
                                        onClick = {
                                            useSymbols = !useSymbols
                                            password = SysadminToolboxEngine.generateStrongPassword(length.toInt(), useSymbols)
                                            copiedCard = false
                                        },
                                        label = { Text("Спецсимволы !@#") }
                                    )
                                }
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Сгенерированный пароль") },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = {
                                        password = SysadminToolboxEngine.generateStrongPassword(length.toInt(), useSymbols)
                                        copiedCard = false
                                    }) {
                                        Text("🎲 Новый пароль")
                                    }
                                    OutlinedButton(onClick = {
                                        WindowsExternalLaunchers.copyToClipboard(password)
                                    }) {
                                        Text("📋 Только пароль")
                                    }
                                    Button(
                                        onClick = {
                                            val card = SysadminToolboxEngine.formatCredentialCard(service, login, password)
                                            WindowsExternalLaunchers.copyToClipboard(card)
                                            copiedCard = true
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                                    ) {
                                        Text(if (copiedCard) "✓ Карточка скопирована" else "📋 Скопировать карточку доступа для клиента")
                                    }
                                }
                            }
                        }

                        3 -> {
                            var newHost by remember { mutableStateOf("") }
                            var newLabel by remember { mutableStateOf("") }
                            var selectedDetailHost by remember { mutableStateOf<String?>(null) }
                            var pendingDeleteHost by remember { mutableStateOf<String?>(null) }

                            if (selectedDetailHost != null) {
                                val activeHost = selectedDetailHost!!
                                val normKey = SysadminToolboxEngine.normalizeHostKey(activeHost)
                                val savedLabel = state.watchlistLabels[normKey].orEmpty()
                                val st = state.watchlistStatuses.firstOrNull {
                                    SysadminToolboxEngine.normalizeHostKey(it.host) == normKey ||
                                        it.host.equals(activeHost, ignoreCase = true)
                                }
                                var labelDraft by remember(activeHost, savedLabel) { mutableStateOf(savedLabel) }

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(onClick = { selectedDetailHost = null }) {
                                            Text("← Назад к списку узлов")
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (savedLabel.isNotBlank()) {
                                                Text(
                                                    text = "🏷 $savedLabel",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.5.sp,
                                                    color = DesktopColors.CslOrange
                                                )
                                            }
                                            Text(activeHost, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { controller.refreshWatchlistHosts() },
                                            enabled = !state.isCheckingWatchlist
                                        ) {
                                            Text(if (state.isCheckingWatchlist) "Проверка…" else "🔄 Обновить")
                                        }
                                    }

                                    if (state.isCheckingWatchlist) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopColors.CslOrange)
                                    }

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = labelDraft,
                                                onValueChange = { labelDraft = it },
                                                placeholder = { Text("Подпись узла (чей IP / клиент / роутер, напр. ООО Ромашка — MikroTik)") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = { controller.updateWatchlistHostLabel(activeHost, labelDraft) },
                                                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                                            ) {
                                                Text("💾 Сохранить подпись")
                                            }
                                        }

                                        if (st != null) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                SelectionContainer {
                                                    Column(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        val badge = when {
                                                            !st.isOnline -> "🔴 Нет ответа"
                                                            st.isSslExpiringSoon -> "🟠 SSL истекает через ${st.sslDaysRemaining} дн.!"
                                                            st.stages?.tls?.state == ProbeStageState.FAILED -> "🟠 Ошибка TLS"
                                                            else -> "🟢 Есть ответ"
                                                        }
                                                        val summaryLine = buildList {
                                                            add(badge)
                                                            st.resolvedIp?.let { add("IP: $it") }
                                                            st.latencyMs?.let { add("Проверка доступности: $it мс") }
                                                            add("Проверка: ${st.checkedAtDisplay}")
                                                        }.joinToString("  ·  ")
                                                        Text(summaryLine, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                        st.stages?.lines()?.forEach { line -> Text(line, fontSize = 12.sp) }
                                                        Text("Доступность: системная проверка (ICMP или TCP Echo); отсутствие ответа не доказывает отказ узла.", fontSize = 11.sp)
                                                        st.httpServerSummary?.let {
                                                            Text("🌐 Веб-ответ: $it", fontSize = 12.sp)
                                                        }
                                                        if (st.sslSubject != null) {
                                                            Text(
                                                                text = "🔒 SSL CN: ${st.sslSubject} · действует до ${st.sslExpiresFormatted ?: "—"} (${st.sslDaysRemaining ?: 0} дн.)" +
                                                                    (st.sslIssuer?.takeIf { it.isNotBlank() }?.let { " · УЦ: $it" }.orEmpty()),
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Text(
                                                text = "🔌 Все проверенные TCP-порты (открыто ${st.openPorts.size} из ${st.portStatuses.size}):",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                st.portStatuses.forEach { p ->
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = if (p.isOpen) {
                                                            DesktopColors.StatusGreen.copy(alpha = 0.14f)
                                                        } else {
                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                                        },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 12.dp, vertical = 5.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "${if (p.isOpen) "🟢" else "⚪"} ${p.port}  ·  ${p.serviceName}",
                                                                fontSize = 12.5.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                fontWeight = if (p.isOpen) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                            Text(
                                                                text = if (p.isOpen) {
                                                                    "ОТКРЫТ${p.latencyMs?.let { " · $it мс" }.orEmpty()}"
                                                                } else {
                                                                    p.failureDescription ?: "нет соединения"
                                                                },
                                                                fontSize = 12.sp,
                                                                fontWeight = if (p.isOpen) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (p.isOpen) DesktopColors.StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

                                        OutlinedButton(
                                            onClick = { pendingDeleteHost = activeHost },
                                            border = BorderStroke(1.dp, DesktopColors.StatusRed),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("🗑 Удалить узел из мониторинга", color = DesktopColors.StatusRed, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = newHost,
                                            onValueChange = { newHost = it },
                                            placeholder = { Text("IP или домен (93.84.x.x / vpn.client.by)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = newLabel,
                                            onValueChange = { newLabel = it },
                                            placeholder = { Text("Подпись (чей IP / клиент)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                if (newHost.isNotBlank()) {
                                                    controller.addWatchlistHost(newHost, newLabel)
                                                    newHost = ""
                                                    newLabel = ""
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.ButtonAccent, contentColor = Color.White)
                                        ) {
                                            Text("+ Добавить")
                                        }
                                        OutlinedButton(
                                            onClick = { controller.refreshWatchlistHosts() },
                                            enabled = !state.isCheckingWatchlist
                                        ) {
                                            Text(if (state.isCheckingWatchlist) "Проверка…" else "🔄 Проверить все")
                                        }
                                    }

                                    if (state.isCheckingWatchlist) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopColors.CslOrange)
                                    }

                                    Text(
                                        text = "Нажмите на карточку узла — откроется карточка на всё окно со всеми портами и управлением",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(state.watchlistHosts, key = { it }) { host ->
                                            val normKey = SysadminToolboxEngine.normalizeHostKey(host)
                                            val savedLabel = state.watchlistLabels[normKey].orEmpty()
                                            val st = state.watchlistStatuses.firstOrNull {
                                                SysadminToolboxEngine.normalizeHostKey(it.host) == normKey ||
                                                    it.host.equals(host, ignoreCase = true)
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    when {
                                                        st == null -> MaterialTheme.colorScheme.outlineVariant
                                                        !st.isOnline -> DesktopColors.StatusRed
                                                        st.isSslExpiringSoon || st.stages?.tls?.state == ProbeStageState.FAILED -> DesktopColors.StatusAmber
                                                        else -> DesktopColors.StatusGreen.copy(alpha = 0.5f)
                                                    }
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedDetailHost = host
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        if (savedLabel.isNotBlank()) {
                                                            Text(
                                                                text = "🏷 $savedLabel",
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 13.5.sp,
                                                                color = DesktopColors.CslOrange
                                                            )
                                                        }
                                                        val badge = when {
                                                            st == null -> "⏳ Ожидает проверки"
                                                            !st.isOnline -> "🔴 Нет ответа"
                                                            st.isSslExpiringSoon -> "🟠 SSL истекает через ${st.sslDaysRemaining} дн.!"
                                                            st.stages?.tls?.state == ProbeStageState.FAILED -> "🟠 Ошибка TLS"
                                                            else -> "🟢 Есть ответ"
                                                        }
                                                        Text("$host  ·  $badge", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                        if (st != null) {
                                                            val totalPorts = st.portStatuses.size
                                                            val openCount = st.openPorts.size
                                                            val details = buildList {
                                                                if (st.resolvedIp != null && !st.resolvedIp.equals(host, ignoreCase = true)) {
                                                                    add("IP: ${st.resolvedIp}")
                                                                }
                                                                st.latencyMs?.let { add("${it} мс") }
                                                                if (totalPorts > 0) {
                                                                    val preview = if (openCount > 0) {
                                                                        st.openPorts.take(6).joinToString(",") +
                                                                            if (openCount > 6) "…" else ""
                                                                    } else "нет открытых"
                                                                    add("Порты: $openCount/$totalPorts ($preview)")
                                                                }
                                                                st.sslDaysRemaining?.let { add("SSL: $it дн.") }
                                                            }.joinToString(" · ")
                                                            Text(details, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                    Text(
                                                        text = "Открыть ›",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = DesktopColors.CslOrange
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            pendingDeleteHost?.let { targetHost ->
                                val normKey = SysadminToolboxEngine.normalizeHostKey(targetHost)
                                val lbl = state.watchlistLabels[normKey].orEmpty()
                                val titleHost = if (lbl.isNotBlank()) "«$targetHost» ($lbl)" else "«$targetHost»"
                                DesktopWorkspaceAlert(
                                    onDismissRequest = { pendingDeleteHost = null },
                                    title = { Text("Удалить узел?", fontWeight = FontWeight.Bold) },
                                    text = {
                                        Text("Вы действительно хотите удалить узел $titleHost из списка мониторинга SSL / Узлы?")
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                controller.removeWatchlistHost(targetHost)
                                                pendingDeleteHost = null
                                                selectedDetailHost = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.StatusRed)
                                        ) {
                                            Text("Удалить", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pendingDeleteHost = null }) {
                                            Text("Отмена")
                                        }
                                    }
                                )
                            }
                        }
                        SysadminToolboxTabs.COMMANDS -> DesktopCommandsView()
                        SysadminToolboxTabs.PORTS -> DesktopServicePortsView()
                        SysadminToolboxTabs.DNS -> DesktopDnsLookupView()
                        SysadminToolboxTabs.AD -> {
                            var adCopyStatus by remember { mutableStateOf("") }
                            Column {
                                if (adCopyStatus.isNotBlank()) Text(adCopyStatus, style = MaterialTheme.typography.bodySmall)
                                Box(Modifier.weight(1f)) {
                                    DomainDiagnosticCardView(onCopy = { title, text ->
                                        adCopyStatus = if (runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess) "Скопировано: $title" else "Не удалось скопировать: буфер обмена недоступен"
                                    })
                                }
                            }
                        }
                        SysadminToolboxTabs.SCENARIOS -> scenarioDraftHolder.SaveableStateProvider("scenarios") {
                            Column {
                                if (scenarioCopyStatus.isNotBlank()) Text(scenarioCopyStatus, style = MaterialTheme.typography.bodySmall)
                                Box(Modifier.weight(1f)) {
                                    DiagnosticScenariosView(onCopy = { _, text ->
                                        scenarioCopyStatus = if (runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess) "Скопировано в буфер обмена" else "Буфер обмена недоступен"
                                    })
                                }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun SubnetRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
