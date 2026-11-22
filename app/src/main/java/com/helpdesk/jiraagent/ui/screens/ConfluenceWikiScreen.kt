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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    LaunchedEffect(Unit) {
        viewModel.loadWikiInitialIfNeeded()
    }

    BackHandler(enabled = mode == WikiDisplayMode.READER && wikiState.selectedPage != null) {
        viewModel.closeSelectedWikiPage()
    }
    BackHandler(enabled = mode == WikiDisplayMode.WEBVIEW && webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top Confluence bar with Reader / WebView toggle
        Surface(color = CslMidnight, contentColor = Color.White) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📘 Confluence Wiki · w.csl.by",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            text = "🔑 Вход Jira: ${settings.usernameOrEmail.ifBlank { "укажите в «Сервер»" }}",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = mode == WikiDisplayMode.READER,
                            onClick = { mode = WikiDisplayMode.READER },
                            label = { Text("📖 Читалка", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CslOrange,
                                selectedLabelColor = Color.White,
                                labelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = mode == WikiDisplayMode.WEBVIEW,
                            onClick = { mode = WikiDisplayMode.WEBVIEW },
                            label = { Text("🌐 Web", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CslOrange,
                                selectedLabelColor = Color.White,
                                labelColor = Color.White
                            )
                        )
                        IconButton(
                            onClick = {
                                if (mode == WikiDisplayMode.WEBVIEW) webViewRef?.reload()
                                else viewModel.refreshWikiSpacesAndPages()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = Color.White)
                        }
                    }
                }
            }
        }

        if (mode == WikiDisplayMode.WEBVIEW) {
            ConfluenceEmbeddedWebView(
                settings = settings,
                initialTargetUrl = wikiState.selectedPage?.webUrl.orEmpty(),
                onWebViewReady = { webViewRef = it }
            )
        } else {
            val detail = wikiState.selectedPage
            if (detail != null) {
                ConfluenceAndroidPageReader(
                    detail = detail,
                    isLoading = wikiState.isLoadingPage,
                    context = context,
                    viewModel = viewModel,
                    onBackToList = { viewModel.closeSelectedWikiPage() },
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
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = searchInput,
            onValueChange = onSearchChange,
            placeholder = { Text("Поиск клиента, IP, инструкции в w.csl.by…", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
            trailingIcon = {
                IconButton(onClick = onSubmitSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Найти", tint = CslOrange)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (wikiState.spaces.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = wikiState.selectedSpaceKey == null,
                        onClick = { viewModel.selectWikiSpace(null) },
                        label = { Text("★ Недавние", fontSize = 12.sp) }
                    )
                }
                items(wikiState.spaces, key = { it.key }) { sp ->
                    FilterChip(
                        selected = wikiState.selectedSpaceKey == sp.key,
                        onClick = { viewModel.selectWikiSpace(sp.key) },
                        label = { Text("${sp.key} · ${sp.name.take(16)}", fontSize = 12.sp) }
                    )
                }
            }
        }

        if (wikiState.isLoadingList || wikiState.isLoadingPage) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
        }

        wikiState.errorMessage?.let { err ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CslDangerRed.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, CslDangerRed.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(err, color = CslDangerRed, fontSize = 12.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.refreshWikiSpacesAndPages() }) {
                            Text("Повторить", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onSwitchToWebView,
                            colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                        ) {
                            Text("🌐 Открыть Web w.csl.by", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        val list = wikiState.searchResults ?: wikiState.pages
        val isSearch = wikiState.searchResults != null

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(list, key = { it.id }) { page ->
                AndroidWikiTreeNode(
                    page = page,
                    depth = 0,
                    isSearch = isSearch,
                    wikiState = wikiState,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun AndroidWikiTreeNode(
    page: ConfluencePageSummary,
    depth: Int,
    isSearch: Boolean,
    wikiState: ConfluenceWikiUiState,
    viewModel: MainViewModel
) {
    val isExpanded = page.id in wikiState.expandedPageIds
    val children = wikiState.childPagesByParent[page.id].orEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(start = (depth * 14).dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().clickable { viewModel.openWikiPage(page.id) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isSearch) {
                    Text(
                        text = if (isExpanded) "▾" else "▸",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange,
                        modifier = Modifier
                            .clickable { viewModel.toggleWikiTreeExpand(page.id) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                } else {
                    Text("📄", fontSize = 14.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(page.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                            subtitle,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (!isSearch && isExpanded && depth < 3) {
            Spacer(Modifier.height(4.dp))
            children.forEach { child ->
                AndroidWikiTreeNode(
                    page = child,
                    depth = depth + 1,
                    isSearch = false,
                    wikiState = wikiState,
                    viewModel = viewModel
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ConfluenceAndroidPageReader(
    detail: ConfluencePageDetail,
    isLoading: Boolean,
    context: Context,
    viewModel: MainViewModel,
    onBackToList: () -> Unit,
    onOpenInWebMode: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CslOrange)
        }
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBackToList, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("К списку", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenInWebMode,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Открыть в Web", fontSize = 12.sp)
                    }
                }
                Text(detail.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        detail.spaceName.ifBlank { detail.spaceKey }.takeIf { it.isNotBlank() },
                        detail.updatedDisplay.takeIf { it.isNotBlank() }?.let { "Обн.: $it" },
                        "Нажмите на ячейку таблицы для копирования"
                    ).joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detail.childPages.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(detail.childPages, key = { it.id }) { ch ->
                            AssistChip(
                                onClick = { viewModel.openWikiPage(ch.id) },
                                label = { Text("📂 ${ch.title}", fontSize = 11.5.sp) }
                            )
                        }
                    }
                }
            }
        }

        SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(detail.blocks) { block ->
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
 * Embedded Android WebView for `w.csl.by` that automatically authenticates using
 * the exact same Jira credentials (`settings.usernameOrEmail` & `settings.passwordOrToken`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ConfluenceEmbeddedWebView(
    settings: AppSettings,
    initialTargetUrl: String,
    onWebViewReady: (WebView) -> Unit
) {
    val rawBase = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }.trimEnd('/')
    val baseUrl = if (rawBase.startsWith("http://", true) || rawBase.startsWith("https://", true)) {
        rawBase
    } else "https://$rawBase"

    val targetUrl = initialTargetUrl.ifBlank { baseUrl }

    val authHeaders = remember(settings.authType, settings.usernameOrEmail, settings.passwordOrToken) {
        val map = mutableMapOf<String, String>()
        if (settings.authType == AuthType.BEARER_PAT && settings.passwordOrToken.isNotBlank()) {
            map["Authorization"] = "Bearer ${settings.passwordOrToken.trim()}"
        } else if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
            val raw = "${settings.usernameOrEmail.trim()}:${settings.passwordOrToken}"
            val encoded = Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            map["Authorization"] = "Basic $encoded"
        }
        map
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                this.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
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
                        val nextUrl = request?.url?.toString() ?: return false
                        if (nextUrl.contains("csl.by", ignoreCase = true) && authHeaders.isNotEmpty()) {
                            view?.loadUrl(nextUrl, authHeaders)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val currentUrl = url.orEmpty()
                        if (!autoSubmittedLoginOnce &&
                            settings.authType != AuthType.BEARER_PAT &&
                            settings.usernameOrEmail.isNotBlank() &&
                            settings.passwordOrToken.isNotBlank() &&
                            (currentUrl.contains("login.action", ignoreCase = true) ||
                                currentUrl.contains("/login", ignoreCase = true))
                        ) {
                            autoSubmittedLoginOnce = true
                            val destRel = if (targetUrl.startsWith(baseUrl, ignoreCase = true)) {
                                targetUrl.substring(baseUrl.length).ifBlank { "/index.action" }
                            } else "/index.action"
                            val postBody = buildString {
                                append("os_username=")
                                append(URLEncoder.encode(settings.usernameOrEmail.trim(), "UTF-8"))
                                append("&os_password=")
                                append(URLEncoder.encode(settings.passwordOrToken, "UTF-8"))
                                append("&os_cookie=true&os_destination=")
                                append(URLEncoder.encode(destRel, "UTF-8"))
                            }
                            view?.postUrl("$baseUrl/dologin.action", postBody.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                }

                onWebViewReady(this)

                if (settings.authType != AuthType.BEARER_PAT &&
                    settings.usernameOrEmail.isNotBlank() &&
                    settings.passwordOrToken.isNotBlank()
                ) {
                    val destRel = if (targetUrl.startsWith(baseUrl, ignoreCase = true)) {
                        targetUrl.substring(baseUrl.length).ifBlank { "/index.action" }
                    } else "/index.action"
                    val postBody = buildString {
                        append("os_username=")
                        append(URLEncoder.encode(settings.usernameOrEmail.trim(), "UTF-8"))
                        append("&os_password=")
                        append(URLEncoder.encode(settings.passwordOrToken, "UTF-8"))
                        append("&os_cookie=true&os_destination=")
                        append(URLEncoder.encode(destRel, "UTF-8"))
                    }
                    postUrl("$baseUrl/dologin.action", postBody.toByteArray(StandardCharsets.UTF_8))
                } else {
                    loadUrl(targetUrl, authHeaders)
                }
            }
        }
    )
}
