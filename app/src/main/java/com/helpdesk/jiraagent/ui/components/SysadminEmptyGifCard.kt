package com.helpdesk.jiraagent.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helpdesk.jiraagent.data.HabrArticleBlock
import com.helpdesk.jiraagent.data.HabrArticleDetail
import com.helpdesk.jiraagent.data.HabrFeedCategory
import com.helpdesk.jiraagent.data.HabrNewsItem
import com.helpdesk.jiraagent.data.HabrNewsRepository
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.random.Random

private const val MORNING_GIF_PREFS = "help_csl_morning_gif"
private const val KEY_DISMISSED_DATE = "dismissed_date_iso"

data class SysadminGifItem(
    val id: String,
    val assetPath: String,
    val quote: String,
    val aspectRatio: Float
)

object SysadminGifCatalog {
    val items: List<SysadminGifItem> = listOf(
        SysadminGifItem(
            id = "roy_off_on",
            assetPath = "admin_gifs/admin_gif_01.gif",
            quote = "«Вы пробовали выключить и снова включить?» — доброе утро, очередь чиста!",
            aspectRatio = 439f / 256f
        ),
        SysadminGifItem(
            id = "moss_fire",
            assetPath = "admin_gifs/admin_gif_07.gif",
            quote = "Доброе утро! В очереди чисто. Главное правило админа — не трогать то, что работает!",
            aspectRatio = 440f / 248f
        )
    )
}

@Composable
fun SysadminEmptyStatePanel(
    emptyStatusText: String,
    showRefreshButton: Boolean,
    onRefreshNow: () -> Unit,
    settings: AppSettings = AppSettings(),
    scrollable: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val gifs = SysadminGifCatalog.items
    var currentIndex by rememberSaveable {
        mutableIntStateOf(Random.nextInt(gifs.size))
    }
    val safeIndex = currentIndex.mod(gifs.size)
    val currentGif = gifs[safeIndex]

    val gifPrefs = remember(context) {
        context.getSharedPreferences(MORNING_GIF_PREFS, Context.MODE_PRIVATE)
    }
    var dismissedDateIso by remember {
        mutableStateOf(gifPrefs.getString(KEY_DISMISSED_DATE, null))
    }
    val sessionStartedEpochMs = remember { System.currentTimeMillis() }
    val nowInstant by produceState(initialValue = Instant.now()) {
        while (true) {
            delay(20_000L)
            value = Instant.now()
        }
    }

    val showMorningGif = remember(settings, dismissedDateIso, nowInstant) {
        BelarusWorkCalendar.shouldShowMorningGif(
            settings = settings,
            dismissedDateIso = dismissedDateIso,
            sessionStartedEpochMs = sessionStartedEpochMs,
            maxSessionDisplayMinutes = 10L,
            nowInstant = nowInstant
        )
    }

    val dismissGifToday: () -> Unit = {
        val today = BelarusWorkCalendar.currentMinskDateIso()
        dismissedDateIso = today
        gifPrefs.edit().putString(KEY_DISMISSED_DATE, today).apply()
    }

    var habrCategoryName by rememberSaveable { mutableStateOf(HabrFeedCategory.NEWS.name) }
    val habrCategory = remember(habrCategoryName) {
        runCatching { HabrFeedCategory.valueOf(habrCategoryName) }.getOrDefault(HabrFeedCategory.NEWS)
    }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var habrItems by remember(habrCategory) {
        mutableStateOf(HabrNewsRepository.peekCached(habrCategory).orEmpty())
    }
    var isLoadingHabr by remember { mutableStateOf(habrItems.isEmpty()) }
    var habrError by remember { mutableStateOf<String?>(null) }
    var activeReaderIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(habrCategory, reloadNonce) {
        val force = reloadNonce > 0
        if (habrItems.isEmpty() || force) {
            isLoadingHabr = true
        }
        habrError = null
        val result = HabrNewsRepository.loadFeed(category = habrCategory, forceRefresh = force)
        result.fold(
            onSuccess = { loaded ->
                habrItems = loaded
                habrError = if (loaded.isEmpty()) "Публикации временно недоступны" else null
            },
            onFailure = { err ->
                if (habrItems.isEmpty()) {
                    habrError = err.message ?: "Ошибка подключения к habr.com"
                }
            }
        )
        isLoadingHabr = false
    }

    if (scrollable) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = if (showMorningGif) "gif_top_block" else "clean_queue_strip") {
                if (showMorningGif) {
                    GifAndStatusTopBlock(
                        gifs = gifs,
                        safeIndex = safeIndex,
                        currentGif = currentGif,
                        onSelectIndex = { currentIndex = it },
                        onDismissToday = dismissGifToday,
                        emptyStatusText = emptyStatusText,
                        showRefreshButton = showRefreshButton,
                        onRefreshNow = onRefreshNow
                    )
                } else {
                    CompactEmptyQueueStrip(
                        emptyStatusText = emptyStatusText,
                        showRefreshButton = showRefreshButton,
                        onRefreshNow = onRefreshNow
                    )
                }
            }

            item(key = "habr_feed_header") {
                HabrFeedHeaderCard(
                    selectedCategory = habrCategory,
                    isLoading = isLoadingHabr,
                    onSelectCategory = { cat ->
                        if (cat != habrCategory) {
                            reloadNonce = 0
                            activeReaderIndex = null
                            habrCategoryName = cat.name
                        }
                    },
                    onRefreshFeed = { reloadNonce++ }
                )
            }

            if (habrError != null && habrItems.isEmpty()) {
                item(key = "habr_feed_error") {
                    HabrFeedErrorCard(
                        message = habrError ?: "Не удалось загрузить новости",
                        onRetry = { reloadNonce++ }
                    )
                }
            } else {
                items(
                    items = habrItems,
                    key = { "habr_${it.id}" }
                ) { newsItem ->
                    HabrNewsItemCard(
                        item = newsItem,
                        onClick = {
                            val idx = habrItems.indexOfFirst { it.id == newsItem.id }
                            activeReaderIndex = if (idx >= 0) idx else 0
                        }
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showMorningGif) {
                GifAndStatusTopBlock(
                    gifs = gifs,
                    safeIndex = safeIndex,
                    currentGif = currentGif,
                    onSelectIndex = { currentIndex = it },
                    onDismissToday = dismissGifToday,
                    emptyStatusText = emptyStatusText,
                    showRefreshButton = showRefreshButton,
                    onRefreshNow = onRefreshNow
                )
            } else {
                CompactEmptyQueueStrip(
                    emptyStatusText = emptyStatusText,
                    showRefreshButton = showRefreshButton,
                    onRefreshNow = onRefreshNow
                )
            }

            HabrFeedHeaderCard(
                selectedCategory = habrCategory,
                isLoading = isLoadingHabr,
                onSelectCategory = { cat ->
                    if (cat != habrCategory) {
                        reloadNonce = 0
                        activeReaderIndex = null
                        habrCategoryName = cat.name
                    }
                },
                onRefreshFeed = { reloadNonce++ }
            )

            if (habrError != null && habrItems.isEmpty()) {
                HabrFeedErrorCard(
                    message = habrError ?: "Не удалось загрузить новости",
                    onRetry = { reloadNonce++ }
                )
            } else {
                habrItems.forEachIndexed { idx, newsItem ->
                    HabrNewsItemCard(
                        item = newsItem,
                        onClick = { activeReaderIndex = idx }
                    )
                }
            }
        }
    }

    val openIdx = activeReaderIndex
    if (openIdx != null && openIdx in habrItems.indices) {
        HabrArticleReaderDialog(
            item = habrItems[openIdx],
            hasPrev = openIdx > 0,
            hasNext = openIdx < habrItems.lastIndex,
            onPrev = { if (openIdx > 0) activeReaderIndex = openIdx - 1 },
            onNext = { if (openIdx < habrItems.lastIndex) activeReaderIndex = openIdx + 1 },
            onDismiss = { activeReaderIndex = null }
        )
    }
}

@Composable
private fun CompactEmptyQueueStrip(
    emptyStatusText: String,
    showRefreshButton: Boolean,
    onRefreshNow: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = CslSuccessGreen,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = emptyStatusText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (showRefreshButton) {
                FilledTonalButton(
                    onClick = onRefreshNow,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CslOrange.copy(alpha = 0.14f),
                        contentColor = CslOrange
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Обновить",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun GifAndStatusTopBlock(
    gifs: List<SysadminGifItem>,
    safeIndex: Int,
    currentGif: SysadminGifItem,
    onSelectIndex: (Int) -> Unit,
    onDismissToday: () -> Unit,
    emptyStatusText: String,
    showRefreshButton: Boolean,
    onRefreshNow: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onSelectIndex((safeIndex + 1) % gifs.size)
                },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 208.dp)
                        .aspectRatio(currentGif.aspectRatio)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(Color(0xFF04091E))
                ) {
                    Crossfade(
                        targetState = currentGif,
                        animationSpec = tween(durationMillis = 300),
                        label = "sysadmin_gif_crossfade"
                    ) { gif ->
                        AnimatedAssetGifView(
                            assetPath = gif.assetPath,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = CslMidnight.copy(alpha = 0.82f)
                    ) {
                        Text(
                            text = "☀️ Начало смены",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = CslOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        onClick = onDismissToday,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = CslMidnight.copy(alpha = 0.82f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Скрыть гифку",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Скрыть",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }

                Text(
                    text = currentGif.quote,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (gifs.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { onSelectIndex((safeIndex - 1 + gifs.size) % gifs.size) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Предыдущая гифка",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    gifs.indices.forEach { idx ->
                        Box(
                            modifier = Modifier
                                .size(if (idx == safeIndex) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == safeIndex) CslOrange else MaterialTheme.colorScheme.outline
                                )
                                .clickable { onSelectIndex(idx) }
                        )
                    }
                    IconButton(
                        onClick = { onSelectIndex((safeIndex + 1) % gifs.size) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Следующая гифка",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = emptyStatusText,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (showRefreshButton) {
                FilledTonalButton(
                    onClick = onRefreshNow,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CslOrange.copy(alpha = 0.14f),
                        contentColor = CslOrange
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Обновить",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HabrFeedHeaderCard(
    selectedCategory: HabrFeedCategory,
    isLoading: Boolean,
    onSelectCategory: (HabrFeedCategory) -> Unit,
    onRefreshFeed: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CslMidnight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CslOrange
                ) {
                    Text(
                        text = "ХАБР",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "Свежее на habr.com · Чтение в приложении",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                IconButton(
                    onClick = onRefreshFeed,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить ленту Хабра",
                        tint = CslOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(7.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HabrFeedCategory.entries.forEach { cat ->
                    val selected = cat == selectedCategory
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectCategory(cat) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) CslOrange else Color.White.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = cat.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.82f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (isLoading) {
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = CslOrange,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
            }
        }
    }
}

@Composable
private fun HabrNewsItemCard(
    item: HabrNewsItem,
    onClick: () -> Unit
) {
    val stripeWidthPx = with(LocalDensity.current) { 3.5.dp.toPx() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = CslOrange,
                        size = Size(width = stripeWidthPx, height = size.height)
                    )
                }
                .padding(start = 13.dp, end = 11.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.publishedLabel.isNotBlank()) {
                    Text(
                        text = item.publishedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CslOrange
                    )
                }

                item.author?.let { author ->
                    Text(
                        text = "• @$author",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.weight(1f))

                item.tags.firstOrNull()?.let { tag ->
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = tag,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = "Читать статью",
                    modifier = Modifier.size(14.dp),
                    tint = CslOrange
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (item.summary.isNotBlank()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HabrArticleReaderDialog(
    item: HabrNewsItem,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var detail by remember(item.url) { mutableStateOf<HabrArticleDetail?>(null) }
    var isLoading by remember(item.url) { mutableStateOf(true) }
    var errorMsg by remember(item.url) { mutableStateOf<String?>(null) }
    var retryNonce by remember(item.url) { mutableIntStateOf(0) }

    LaunchedEffect(item.url, retryNonce) {
        isLoading = true
        errorMsg = null
        HabrNewsRepository.loadArticle(item, forceRefresh = retryNonce > 0)
            .onSuccess { loaded -> detail = loaded }
            .onFailure { err -> errorMsg = err.message ?: "Не удалось загрузить публикацию" }
        isLoading = false
    }

    val copyToClipboard: (String, String) -> Unit = { label, text ->
        runCatching {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "$label скопировано", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top reader bar
                Surface(
                    color = CslMidnight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = CslOrange) {
                            Text(
                                text = "ХАБР",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Чтение статьи",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(
                            onClick = onPrev,
                            enabled = hasPrev,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "Предыдущая статья",
                                tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = onNext,
                            enabled = hasNext,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Следующая статья",
                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard("Ссылка", item.url) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Скопировать ссылку",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = CslOrange
                            )
                        }
                    }
                }

                when {
                    isLoading && detail == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = CslOrange)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Загружаем полный текст публикации…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    errorMsg != null && detail == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = errorMsg ?: "Ошибка загрузки статьи",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(10.dp))
                                FilledTonalButton(onClick = { retryNonce++ }) {
                                    Text("Повторить загрузку")
                                }
                            }
                        }
                    }

                    detail != null -> {
                        val art = detail!!
                        SelectionContainer(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item(key = "header") {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = art.title,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        val metaParts = buildList {
                                            if (art.publishedLabel.isNotBlank()) add(art.publishedLabel)
                                            art.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                                            art.readingTimeMinutes?.let { add("⏱ $it мин") }
                                            art.viewsCount?.let { add("👁 $it") }
                                            art.score?.let { sc -> add(if (sc >= 0) "+$sc" else "$sc") }
                                        }
                                        if (metaParts.isNotEmpty()) {
                                            Text(
                                                text = metaParts.joinToString("  •  "),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = CslOrange,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        if (art.hubs.isNotEmpty()) {
                                            Text(
                                                text = art.hubs.joinToString(" · "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                items(
                                    count = art.blocks.size,
                                    key = { idx -> "blk_$idx" }
                                ) { idx ->
                                    when (val block = art.blocks[idx]) {
                                        is HabrArticleBlock.Heading -> {
                                            Text(
                                                text = block.text,
                                                style = if (block.level <= 2) {
                                                    MaterialTheme.typography.titleMedium
                                                } else {
                                                    MaterialTheme.typography.titleSmall
                                                },
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                modifier = Modifier.padding(top = 6.dp)
                                            )
                                        }

                                        is HabrArticleBlock.Paragraph -> {
                                            Text(
                                                text = block.text,
                                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        }

                                        is HabrArticleBlock.Quote -> {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = CslOrange.copy(alpha = 0.08f),
                                                border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.35f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = block.text,
                                                    modifier = Modifier.padding(12.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontStyle = FontStyle.Italic,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }

                                        is HabrArticleBlock.BulletList -> {
                                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                block.items.forEachIndexed { i, li ->
                                                    val bullet = if (block.ordered) "${i + 1}." else "•"
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text(
                                                            text = bullet,
                                                            fontWeight = FontWeight.Bold,
                                                            color = CslOrange,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        Text(
                                                            text = li,
                                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                                            color = MaterialTheme.colorScheme.onBackground,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        is HabrArticleBlock.CodeBlock -> {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = Color(0xFF0B1224),
                                                border = BorderStroke(1.dp, Color(0xFF233253)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = block.language?.uppercase() ?: "CODE",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = CslOrange,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        TextButton(
                                                            onClick = { copyToClipboard("Код", block.code) },
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = "📋 Копировать",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color.White
                                                            )
                                                        }
                                                    }
                                                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                                        Text(
                                                            text = block.code,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 12.sp,
                                                            lineHeight = 17.sp,
                                                            color = Color(0xFFE2E8F0)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        is HabrArticleBlock.Image -> {
                                            HabrInlineImageAndroid(
                                                imageUrl = block.url,
                                                caption = block.caption
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
}

@Composable
private fun HabrInlineImageAndroid(
    imageUrl: String,
    caption: String?
) {
    var bitmap by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageUrl) {
        val bytes = HabrNewsRepository.loadImageBytes(imageUrl)
        if (bytes != null) {
            bitmap = withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val bmp = bitmap ?: return
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = bmp,
            contentDescription = caption,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
        )
        if (!caption.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HabrFeedErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRetry) {
                Text("Повторить", fontWeight = FontWeight.SemiBold, color = CslOrange)
            }
        }
    }
}

@Composable
fun AnimatedAssetGifView(
    assetPath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        var decodedDrawable by remember(assetPath) { mutableStateOf<Drawable?>(null) }

        LaunchedEffect(assetPath) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.assets.open(assetPath).use { it.readBytes() }
                    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                    }
                }.getOrNull()
            }
            decodedDrawable = loaded
        }

        DisposableEffect(decodedDrawable) {
            val anim = decodedDrawable as? AnimatedImageDrawable
            anim?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            anim?.start()
            onDispose {
                anim?.stop()
            }
        }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView ->
                val d = decodedDrawable
                if (imageView.drawable !== d) {
                    imageView.setImageDrawable(d)
                }
                if (d is AnimatedImageDrawable && !d.isRunning) {
                    d.start()
                }
            },
            onRelease = { imageView ->
                (imageView.drawable as? AnimatedImageDrawable)?.stop()
                imageView.setImageDrawable(null)
            }
        )
    } else {
        AndroidView(
            modifier = modifier,
            factory = { ctx -> LegacyMovieGifView(ctx) },
            update = { view -> view.setGifAsset(assetPath) }
        )
    }
}

@Suppress("DEPRECATION")
private class LegacyMovieGifView(context: Context) : View(context) {
    private var currentAsset: String? = null
    private var movie: Movie? = null
    private var startMillis: Long = 0L

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setGifAsset(assetPath: String) {
        if (currentAsset == assetPath) return
        currentAsset = assetPath
        startMillis = 0L
        movie = runCatching {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            Movie.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = movie ?: return
        val w = m.width().coerceAtLeast(1)
        val h = m.height().coerceAtLeast(1)
        if (width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()
        if (startMillis == 0L) startMillis = now
        val duration = m.duration().let { if (it <= 0) 1000 else it }
        m.setTime(((now - startMillis) % duration).toInt())

        val scale = minOf(width.toFloat() / w, height.toFloat() / h)
        val dx = (width - w * scale) / 2f
        val dy = (height - h * scale) / 2f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f)
        canvas.restore()

        postInvalidateOnAnimation()
    }
}
