package com.helpdesk.jiraagent.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.SslError
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.helpdesk.jiraagent.data.ConfluenceAttachment
import com.helpdesk.jiraagent.data.ConfluenceBlock
import com.helpdesk.jiraagent.data.ConfluenceCalloutKind
import com.helpdesk.jiraagent.data.ConfluencePageDetail
import com.helpdesk.jiraagent.data.ConfluencePageSummary
import com.helpdesk.jiraagent.data.ConfluenceWikiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.ui.ConfluenceWikiUiState
import com.helpdesk.jiraagent.ui.MainViewModel
import com.helpdesk.jiraagent.ui.theme.CslDangerRed
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslOrangeContainer
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private enum class WikiDisplayMode {
    READER,
    WEBVIEW
}

@Composable
fun ConfluenceWikiScreen(
    wikiState: ConfluenceWikiUiState,
    settings: AppSettings,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(WikiDisplayMode.READER) }
    var searchInput by remember(wikiState.searchQuery) { mutableStateOf(wikiState.searchQuery) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canWebGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadWikiInitialIfNeeded()
    }

    LaunchedEffect(wikiState.requiresWeb2faLogin, wikiState.offlinePagesCount) {
        if (wikiState.requiresWeb2faLogin && wikiState.offlinePagesCount == 0 && wikiState.pages.isEmpty()) {
            mode = WikiDisplayMode.WEBVIEW
        } else if (!wikiState.requiresWeb2faLogin && wikiState.offlinePagesCount > 0 && mode == WikiDisplayMode.WEBVIEW) {
            mode = WikiDisplayMode.READER
        }
    }

    BackHandler(enabled = mode == WikiDisplayMode.READER && wikiState.selectedPage != null) {
        viewModel.navigateBackWikiPage()
    }
    BackHandler(enabled = mode == WikiDisplayMode.WEBVIEW && canWebGoBack) {
        webViewRef?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Compact single-row Confluence bar — never wraps awkwardly on phone screens
        Surface(color = CslMidnight, contentColor = Color.White) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (mode == WikiDisplayMode.WEBVIEW && canWebGoBack) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "📘 w.csl.by",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = settings.usernameOrEmail.ifBlank { "Confluence Wiki" },
                            fontSize = 10.5.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = mode == WikiDisplayMode.WEBVIEW,
                        onClick = { mode = WikiDisplayMode.WEBVIEW },
                        label = { Text("🌐 Web", fontSize = 11.5.sp, maxLines = 1, softWrap = false) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CslOrange,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = mode == WikiDisplayMode.READER,
                        onClick = {
                            mode = WikiDisplayMode.READER
                            if (wikiState.pages.isEmpty() && !wikiState.isLoadingList && !wikiState.isSyncingOfflineDb) {
                                viewModel.loadWikiInitialIfNeeded()
                            }
                        },
                        label = { Text("📖 Читалка", fontSize = 11.5.sp, maxLines = 1, softWrap = false) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CslOrange,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White
                        )
                    )
                    IconButton(
                        onClick = {
                            if (mode == WikiDisplayMode.WEBVIEW) webViewRef?.reload()
                            else viewModel.syncWikiOfflineDatabase(force = true)
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Синхронизировать с w.csl.by", tint = Color.White)
                    }
                }
            }
        }

        if (mode == WikiDisplayMode.WEBVIEW) {
            ConfluenceEmbeddedWebView(
                settings = settings,
                initialTargetUrl = wikiState.selectedPage?.webUrl.orEmpty(),
                searchQuery = wikiState.searchQuery,
                onCanGoBackChanged = { canWebGoBack = it },
                onAuthenticated = { viewModel.onWikiWebAuthenticated() },
                onWebViewReady = { webViewRef = it }
            )
        } else {
            val detail = wikiState.selectedPage
            if (detail != null) {
                ConfluenceAndroidPageReader(
                    detail = detail,
                    wikiState = wikiState,
                    isLoading = wikiState.isLoadingPage,
                    context = context,
                    viewModel = viewModel,
                    onStepBack = { viewModel.navigateBackWikiPage() },
                    onBackToRootList = { viewModel.closeSelectedWikiPage() },
                    onOpenInWebMode = { mode = WikiDisplayMode.WEBVIEW }
                )
            } else {
                ConfluenceAndroidBrowserList(
                    wikiState = wikiState,
                    searchInput = searchInput,
                    onSearchChange = {
                        searchInput = it
                        if (it.isBlank()) viewModel.searchWikiPages("")
                    },
                    onSubmitSearch = { viewModel.searchWikiPages(searchInput) },
                    onSwitchToWebView = { mode = WikiDisplayMode.WEBVIEW },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun ConfluenceAndroidBrowserList(
    wikiState: ConfluenceWikiUiState,
    searchInput: String,
    onSearchChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSwitchToWebView: () -> Unit,
    viewModel: MainViewModel
) {
    var selectedLetter by rememberSaveable(wikiState.selectedSpaceKey) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = searchInput,
            onValueChange = {
                selectedLetter = null
                onSearchChange(it)
            },
            placeholder = { Text("Поиск клиента, IP, инструкции в w.csl.by…", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                    IconButton(onClick = onSubmitSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Найти", tint = CslOrange)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (wikiState.spaces.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(wikiState.spaces, key = { it.key }) { sp ->
                    val labelText = if (sp.name.equals(sp.key, ignoreCase = true)) {
                        sp.key
                    } else {
                        "${sp.key} · ${sp.name}"
                    }
                    FilterChip(
                        selected = wikiState.selectedSpaceKey.equals(sp.key, ignoreCase = true),
                        onClick = { viewModel.selectWikiSpace(sp.key) },
                        label = {
                            Text(
                                text = labelText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
                item {
                    FilterChip(
                        selected = wikiState.selectedSpaceKey == ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY,
                        onClick = { viewModel.selectWikiSpace(ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY) },
                        label = { Text("🕒 Недавние", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }

        if (wikiState.isLoadingList || wikiState.isLoadingPage || wikiState.isSyncingOfflineDb) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
        }

        if (wikiState.isSyncingOfflineDb && !wikiState.syncProgressText.isNullOrBlank()) {
            Text(
                text = "⏬ ${wikiState.syncProgressText}",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = CslOrange
            )
        } else if (wikiState.offlinePagesCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💾 Офлайн-база: ${wikiState.offlinePagesCount} стр. · Синхр.: ${wikiState.offlineSyncedAtLabel} (раз в день)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "🔄 Синхр.",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CslOrange,
                    modifier = Modifier
                        .clickable { viewModel.syncWikiOfflineDatabase(force = true) }
                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                )
            }
        }

        wikiState.errorMessage?.let { err ->
            var patDraft by remember { mutableStateOf("") }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CslOrangeContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(err, fontSize = 12.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSwitchToWebView,
                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                        ) {
                            Text("🌐 Войти через Web (w.csl.by)", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { viewModel.refreshWikiSpacesAndPages() }) {
                            Text("Повторить", fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(
                        value = patDraft,
                        onValueChange = { patDraft = it },
                        placeholder = { Text("Или вставьте PAT-токен Confluence (для 2FA)", fontSize = 11.5.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (patDraft.isNotBlank()) {
                        Button(
                            onClick = { viewModel.saveConfluencePatToken(patDraft) },
                            colors = ButtonDefaults.buttonColors(containerColor = CslMidnight)
                        ) {
                            Text("🔑 Сохранить токен и подключить Читалка", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        val isServerSearch = wikiState.searchResults != null
        val baseList = remember(wikiState.searchResults, wikiState.pages, searchInput) {
            val raw = wikiState.searchResults ?: wikiState.pages
            if (!isServerSearch && searchInput.isNotBlank()) {
                val q = searchInput.trim()
                raw.filter { it.title.contains(q, ignoreCase = true) }
            } else {
                raw
            }
        }

        val availableLetters = remember(baseList, wikiState.selectedSpaceKey, isServerSearch) {
            if (isServerSearch || wikiState.selectedSpaceKey == ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY || baseList.size < 6) {
                emptyList()
            } else {
                baseList.map { ConfluenceWikiClient.firstAlphabetLetter(it.title) }.distinct()
            }
        }

        if (availableLetters.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedLetter == null) CslOrange else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { selectedLetter = null }
                    ) {
                        Text(
                            text = "Все (${baseList.size})",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedLetter == null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
                items(availableLetters, key = { it }) { letter ->
                    val isSel = selectedLetter == letter
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSel) CslOrange else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.clickable {
                            selectedLetter = if (isSel) null else letter
                        }
                    ) {
                        Text(
                            text = letter,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        } else if (baseList.isNotEmpty()) {
            Text(
                text = if (isServerSearch) "Найдено в Wiki: ${baseList.size} (по алфавиту)"
                else "Записей: ${baseList.size}",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val displayedList = remember(baseList, selectedLetter) {
            if (selectedLetter == null) baseList
            else baseList.filter { ConfluenceWikiClient.firstAlphabetLetter(it.title) == selectedLetter }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(displayedList, key = { it.id }) { page ->
                AndroidWikiClientEntryCard(
                    page = page,
                    isSearch = isServerSearch,
                    onClick = { viewModel.openWikiPage(page.id) }
                )
            }
        }
    }
}

@Composable
private fun AndroidWikiClientEntryCard(
    page: ConfluencePageSummary,
    isSearch: Boolean,
    onClick: () -> Unit
) {
    val letterBadge = remember(page.title) { ConfluenceWikiClient.firstAlphabetLetter(page.title) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CslOrange.copy(alpha = 0.14f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = letterBadge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = CslOrange
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = page.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (isSearch && page.excerpt.isNotBlank()) {
                    page.excerpt
                } else {
                    listOfNotNull(
                        page.spaceName.ifBlank { page.spaceKey }.takeIf { it.isNotBlank() },
                        page.updatedDisplay.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Открыть вкладку",
                tint = CslOrange,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ConfluenceAndroidPageReader(
    detail: ConfluencePageDetail,
    wikiState: ConfluenceWikiUiState,
    isLoading: Boolean,
    context: Context,
    viewModel: MainViewModel,
    onStepBack: () -> Unit,
    onBackToRootList: () -> Unit,
    onOpenInWebMode: () -> Unit
) {
    // 0 = Sub-pages ("Подкладки"), 1 = Own page content ("Инфо вкладки")
    var folderViewTab by remember(detail.id) { mutableIntStateOf(0) }
    var activeInlineTabIdx by remember(detail.id) { mutableIntStateOf(0) }
    var subPageFilter by remember(detail.id) { mutableStateOf("") }

    val hasSubPages = detail.childPages.isNotEmpty()
    val parentCrumb = detail.breadcrumbs.lastOrNull()
    val siblingPages = remember(detail.id, wikiState.childPagesByParent, parentCrumb) {
        val pid = wikiState.pageHistoryStack.lastOrNull()?.id ?: parentCrumb?.id
        if (pid != null) wikiState.childPagesByParent[pid].orEmpty() else emptyList()
    }

    val backLabel = when {
        wikiState.pageHistoryStack.isNotEmpty() ->
            wikiState.pageHistoryStack.last().title.take(22)
        detail.breadcrumbs.size > 1 ->
            detail.breadcrumbs.last().title.take(22)
        else -> "К списку"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
        }
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (wikiState.pageHistoryStack.isEmpty() && detail.breadcrumbs.size > 1) {
                                viewModel.openWikiPage(detail.breadcrumbs.last().id)
                            } else {
                                onStepBack()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(backLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (wikiState.pageHistoryStack.isNotEmpty()) {
                            TextButton(
                                onClick = onBackToRootList,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Все клиенты", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenInWebMode,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Web", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Breadcrumb trail (Space › Client › Sub-tab)
                if (detail.breadcrumbs.isNotEmpty() || detail.spaceKey.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (detail.spaceKey.isNotBlank()) {
                            Text(
                                text = detail.spaceKey,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = CslOrange,
                                modifier = Modifier.clickable { onBackToRootList() }
                            )
                        }
                        detail.breadcrumbs.forEach { crumb ->
                            Text("›", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = crumb.title,
                                fontSize = 11.5.sp,
                                color = CslOrange,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier.clickable { viewModel.openWikiPage(crumb.id) }
                            )
                        }
                    }
                }

                Text(detail.title, fontSize = 17.5.sp, fontWeight = FontWeight.Bold)

                // If viewing a sub-page of a client, show sibling sub-tabs bar so user can switch between sub-tabs in 1 tap!
                if (!hasSubPages && siblingPages.size > 1) {
                    val currentSiblingIdx = siblingPages.indexOfFirst { it.id == detail.id }.coerceAtLeast(0)
                    ScrollableTabRow(
                        selectedTabIndex = currentSiblingIdx,
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                        indicator = { positions ->
                            if (currentSiblingIdx in positions.indices) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(positions[currentSiblingIdx]),
                                    color = CslOrange
                                )
                            }
                        }
                    ) {
                        siblingPages.forEachIndexed { idx, sib ->
                            val selected = sib.id == detail.id
                            Tab(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        viewModel.openWikiPage(sib.id, replaceSiblingInStack = true)
                                    }
                                },
                                text = {
                                    Text(
                                        text = sib.title,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) CslOrange else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }
                }

                // If this page HAS child sub-pages ("подкладки с информацией") AND also its own content, allow switching
                if (hasSubPages && detail.hasSubstantialBody) {
                    TabRow(
                        selectedTabIndex = folderViewTab,
                        containerColor = Color.Transparent,
                        indicator = { positions ->
                            if (folderViewTab in positions.indices) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(positions[folderViewTab]),
                                    color = CslOrange
                                )
                            }
                        }
                    ) {
                        Tab(
                            selected = folderViewTab == 0,
                            onClick = { folderViewTab = 0 },
                            text = {
                                Text(
                                    "📂 Подкладки (${detail.childPages.size})",
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (folderViewTab == 0) FontWeight.Bold else FontWeight.Medium,
                                    color = if (folderViewTab == 0) CslOrange else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                        Tab(
                            selected = folderViewTab == 1,
                            onClick = { folderViewTab = 1 },
                            text = {
                                Text(
                                    "📋 Общая инфо",
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (folderViewTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (folderViewTab == 1) CslOrange else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                    }
                }
            }
        }

        if (hasSubPages && (!detail.hasSubstantialBody || folderViewTab == 0)) {
            // Client's Tab: All Sub-pages ("все подкладки с информацией") sorted alphabetically!
            val filteredSubPages = remember(detail.childPages, subPageFilter) {
                if (subPageFilter.isBlank()) detail.childPages
                else detail.childPages.filter { it.title.contains(subPageFilter.trim(), ignoreCase = true) }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (detail.childPages.size > 8) {
                    OutlinedTextField(
                        value = subPageFilter,
                        onValueChange = { subPageFilter = it },
                        placeholder = { Text("Фильтр по подкладкам «${detail.title}»…", fontSize = 12.5.sp, maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "📂 Подкладки с информацией (${detail.childPages.size}) · по алфавиту:",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredSubPages, key = { it.id }) { subPage ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 1.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openWikiPage(subPage.id) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("📑", fontSize = 16.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = subPage.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.5.sp
                                    )
                                    if (subPage.updatedDisplay.isNotBlank()) {
                                        Text(
                                            text = "Обновлено: ${subPage.updatedDisplay}",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Открыть состав",
                                    tint = CslOrange,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Sub-tab Content ("её состав"): tables, code, text, inline Confluence UI tabs, images, attachments
            val blocksToRender = if (detail.inlineTabs.isNotEmpty()) {
                val safeIdx = activeInlineTabIdx.coerceIn(0, detail.inlineTabs.lastIndex)
                detail.inlineTabs[safeIdx].blocks
            } else {
                detail.blocks
            }

            if (detail.inlineTabs.size > 1) {
                val safeIdx = activeInlineTabIdx.coerceIn(0, detail.inlineTabs.lastIndex)
                ScrollableTabRow(
                    selectedTabIndex = safeIdx,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    detail.inlineTabs.forEachIndexed { idx, tab ->
                        Tab(
                            selected = safeIdx == idx,
                            onClick = { activeInlineTabIdx = idx },
                            text = {
                                Text(
                                    text = tab.title,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (safeIdx == idx) FontWeight.Bold else FontWeight.Normal,
                                    color = if (safeIdx == idx) CslOrange else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                    }
                }
            }

            SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = listOfNotNull(
                                detail.updatedDisplay.takeIf { it.isNotBlank() }?.let { "Обн.: $it" },
                                detail.updatedBy.takeIf { it.isNotBlank() },
                                "Нажмите на ячейку таблицы для копирования"
                            ).joinToString(" · "),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(blocksToRender) { block ->
                        AndroidConfluenceBlockView(block = block, context = context, viewModel = viewModel)
                    }

                    if (detail.attachments.isNotEmpty()) {
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            Text("📎 Вложения (${detail.attachments.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        items(detail.attachments, key = { it.id }) { att ->
                            AndroidWikiAttachmentCard(att = att, onOpen = { viewModel.downloadAndShareWikiAttachment(att) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidConfluenceBlockView(
    block: ConfluenceBlock,
    context: Context,
    viewModel: MainViewModel
) {
    fun copyText(value: String, label: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "Скопировано: ${value.take(32)}", Toast.LENGTH_SHORT).show()
    }

    when (block) {
        is ConfluenceBlock.Heading -> {
            val sz = when (block.level) {
                1 -> 17.sp
                2 -> 15.5.sp
                else -> 14.sp
            }
            Text(block.text, fontSize = sz, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
        is ConfluenceBlock.Paragraph -> {
            Text(block.text, fontSize = 13.5.sp, lineHeight = 19.sp)
        }
        is ConfluenceBlock.ListItems -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { idx, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (block.ordered) "${idx + 1}." else "•", color = CslOrange, fontWeight = FontWeight.Bold)
                        Text(item, fontSize = 13.sp)
                    }
                }
            }
        }
        is ConfluenceBlock.Callout -> {
            val border = when (block.kind) {
                ConfluenceCalloutKind.WARNING -> Color(0xFFD97706)
                ConfluenceCalloutKind.NOTE -> CslDangerRed
                ConfluenceCalloutKind.TIP -> CslSuccessGreen
                ConfluenceCalloutKind.INFO -> Color(0xFF2563EB)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = border.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, border.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (block.title.isNotBlank()) {
                        Text(block.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = border)
                    }
                    Text(block.body, fontSize = 12.5.sp)
                }
            }
        }
        is ConfluenceBlock.Code -> {
            Surface(shape = RoundedCornerShape(8.dp), color = CslMidnight, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(block.title.ifBlank { "Код" }, color = Color(0xFF94A3B8), fontSize = 11.sp)
                        TextButton(onClick = { copyText(block.code, "Код") }) {
                            Text("📋 Копировать", color = CslOrange, fontSize = 11.5.sp)
                        }
                    }
                    Text(
                        block.code,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
        is ConfluenceBlock.Table -> {
            val cols = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (block.headers.isNotEmpty()) {
                        Row(modifier = Modifier.background(CslMidnight)) {
                            for (c in 0 until cols) {
                                Box(modifier = Modifier.widthIn(min = 125.dp, max = 240.dp).padding(8.dp)) {
                                    Text(block.headers.getOrNull(c).orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    block.rows.forEachIndexed { rIdx, row ->
                        Row(
                            modifier = Modifier.background(
                                if (rIdx % 2 == 0) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            for (c in 0 until cols) {
                                val v = row.getOrNull(c).orEmpty()
                                Box(
                                    modifier = Modifier
                                        .widthIn(min = 125.dp, max = 240.dp)
                                        .clickable(enabled = v.isNotBlank()) { copyText(v, "Ячейка") }
                                        .padding(8.dp)
                                ) {
                                    Text(v.ifBlank { "—" }, fontSize = 12.sp)
                                }
                            }
                        }
                        if (rIdx < block.rows.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        is ConfluenceBlock.Image -> {
            var bmp by remember(block.url) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(block.url) {
                val bytes = viewModel.loadWikiImageBytes(block.url) ?: return@LaunchedEffect
                val decoded = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                bmp = decoded
            }
            bmp?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = block.alt,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
private fun AndroidWikiAttachmentCard(
    att: ConfluenceAttachment,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📎 ${att.title}", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Открыть ↗", fontSize = 12.sp, color = CslOrange, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Embedded Android WebView for `w.csl.by` operating as a standard persistent browser
 * (NO `Authorization: Basic` header — full compatibility with Confluence Two-Factor Authentication!).
 * Automatically fills Jira credentials into Confluence's login form (`#os_username` / `#username`)
 * and shares authenticated session cookies with the native Reader (`ConfluenceWikiClient`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ConfluenceEmbeddedWebView(
    settings: AppSettings,
    initialTargetUrl: String,
    searchQuery: String,
    onCanGoBackChanged: (Boolean) -> Unit,
    onAuthenticated: () -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    val rawBase = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }.trimEnd('/')
    val baseUrl = if (rawBase.startsWith("http://", true) || rawBase.startsWith("https://", true)) {
        rawBase
    } else "https://$rawBase"

    val targetUrl = remember(baseUrl, initialTargetUrl, searchQuery) {
        when {
            initialTargetUrl.isNotBlank() -> initialTargetUrl
            searchQuery.isNotBlank() ->
                "$baseUrl/dosearchsite.action?queryString=${URLEncoder.encode(searchQuery.trim(), "UTF-8")}"
            else -> baseUrl
        }
    }

    var isLoadingWeb by remember { mutableStateOf(true) }
    var isOnLoginPage by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedTargetUrl by remember { mutableStateOf("") }

    val fullLogin = settings.usernameOrEmail.trim()
    val shortAdLogin = remember(fullLogin) {
        if ('@' in fullLogin) fullLogin.substringBefore('@').trim() else ""
    }

    fun buildDomLoginScript(loginToUse: String, passwordToUse: String, autoSubmit: Boolean): String {
        fun jsStr(s: String): String = buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('"')
        }
        return """
            (function() {
                function setInputVal(el, val) {
                    if (!el) return false;
                    el.focus();
                    var proto = window.HTMLInputElement && window.HTMLInputElement.prototype;
                    var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                    if (desc && desc.set) {
                        desc.set.call(el, val);
                    } else {
                        el.value = val;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                var u = document.querySelector('#os_username, input[name="os_username"], #username, input[name="username"], input[autocomplete="username"]');
                var p = document.querySelector('#os_password, input[name="os_password"], #password, input[name="password"], input[type="password"]');
                if (!p) return "NO_LOGIN_FORM";
                if (u) setInputVal(u, ${jsStr(loginToUse)});
                setInputVal(p, ${jsStr(passwordToUse)});
                var rem = document.querySelector('#os_cookie, input[name="os_cookie"], input[name="rememberMe"], #rememberMe');
                if (rem && !rem.checked) {
                    rem.click();
                    rem.checked = true;
                }
                if (${if (autoSubmit) "true" else "false"}) {
                    setTimeout(function() {
                        var btn = document.querySelector('#loginButton, button[name="login"], input[name="login"], button[type="submit"], input[type="submit"]');
                        if (btn) btn.click();
                        else if (p.form) p.form.submit();
                    }, 180);
                }
                return "LOGIN_FORM_FOUND";
            })();
        """.trimIndent()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoadingWeb) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
        }

        if (isOnLoginPage && fullLogin.isNotEmpty() && settings.passwordOrToken.isNotEmpty()) {
            Surface(
                color = CslOrangeContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔑 Авто-вход Jira:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = {
                            webViewInstance?.evaluateJavascript(
                                buildDomLoginScript(fullLogin, settings.passwordOrToken, autoSubmit = true),
                                null
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CslOrange),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Войти как $fullLogin", fontSize = 11.5.sp)
                    }
                    if (shortAdLogin.isNotEmpty() && !shortAdLogin.equals(fullLogin, ignoreCase = true)) {
                        OutlinedButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript(
                                    buildDomLoginScript(shortAdLogin, settings.passwordOrToken, autoSubmit = true),
                                    null
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Войти как $shortAdLogin", fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    val cookieMgr = CookieManager.getInstance()
                    cookieMgr.setAcceptCookie(true)
                    cookieMgr.setAcceptThirdPartyCookies(this, true)

                    this.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    var autoSubmittedLoginOnce = false

                    webViewClient = object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
                                handler?.proceed(settings.usernameOrEmail.trim(), settings.passwordOrToken)
                            } else {
                                super.onReceivedHttpAuthRequest(view, handler, host, realm)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val urlHost = error?.url.orEmpty().lowercase()
                            if (settings.trustSelfSignedSsl || urlHost.contains("csl.by")) {
                                handler?.proceed()
                            } else {
                                super.onReceivedSslError(view, handler, error)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            // Never inject Authorization: Basic headers — let WebView handle cookies & 2FA natively!
                            return false
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            isLoadingWeb = true
                            onCanGoBackChanged(view?.canGoBack() == true)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoadingWeb = false
                            onCanGoBackChanged(view?.canGoBack() == true)
                            runCatching { CookieManager.getInstance().flush() }

                            val currentUrl = url.orEmpty()
                            val looksLikeLoginUrl = currentUrl.contains("login.action", ignoreCase = true) ||
                                currentUrl.contains("/login", ignoreCase = true) ||
                                currentUrl.contains("dologin", ignoreCase = true)

                            if (fullLogin.isNotEmpty() && settings.passwordOrToken.isNotEmpty()) {
                                val shouldAutoClick = !autoSubmittedLoginOnce && looksLikeLoginUrl
                                if (shouldAutoClick) {
                                    autoSubmittedLoginOnce = true
                                }
                                view?.evaluateJavascript(
                                    buildDomLoginScript(
                                        loginToUse = fullLogin,
                                        passwordToUse = settings.passwordOrToken,
                                        autoSubmit = shouldAutoClick
                                    )
                                ) { jsResult ->
                                    val foundForm = jsResult?.contains("LOGIN_FORM_FOUND") == true
                                    isOnLoginPage = foundForm || looksLikeLoginUrl
                                    if (!isOnLoginPage &&
                                        !currentUrl.contains("two-step", ignoreCase = true) &&
                                        !currentUrl.contains("authenticate", ignoreCase = true) &&
                                        currentUrl.startsWith("http", ignoreCase = true)
                                    ) {
                                        onAuthenticated()
                                    }
                                }
                            } else {
                                isOnLoginPage = looksLikeLoginUrl
                                if (!looksLikeLoginUrl && currentUrl.startsWith("http", ignoreCase = true)) {
                                    onAuthenticated()
                                }
                            }
                        }
                    }

                    webViewInstance = this
                    onWebViewReady(this)
                    lastLoadedTargetUrl = targetUrl
                    // Normal browser GET without Authorization: Basic header!
                    loadUrl(targetUrl)
                }
            },
            update = { view ->
                if (targetUrl.isNotBlank() && targetUrl != lastLoadedTargetUrl) {
                    lastLoadedTargetUrl = targetUrl
                    view.loadUrl(targetUrl)
                }
                onCanGoBackChanged(view.canGoBack())
            }
        )
    }
}
