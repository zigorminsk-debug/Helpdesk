package com.helpdesk.jiraagent.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.helpdesk.jiraagent.data.HabrFeedCategory
import com.helpdesk.jiraagent.data.HabrNewsItem
import com.helpdesk.jiraagent.data.HabrNewsRepository
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.random.Random

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
            quote = "«Вы пробовали выключить и снова включить?» — очередь чиста!",
            aspectRatio = 439f / 256f
        ),
        SysadminGifItem(
            id = "moss_fire",
            assetPath = "admin_gifs/admin_gif_07.gif",
            quote = "В очереди чисто. Главное правило админа — не трогать то, что работает!",
            aspectRatio = 440f / 248f
        )
    )
}

@Composable
fun SysadminEmptyStatePanel(
    emptyStatusText: String,
    showRefreshButton: Boolean,
    onRefreshNow: () -> Unit,
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

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    if (scrollable) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "gif_top_block") {
                GifAndStatusTopBlock(
                    gifs = gifs,
                    safeIndex = safeIndex,
                    currentGif = currentGif,
                    onSelectIndex = { currentIndex = it },
                    emptyStatusText = emptyStatusText,
                    showRefreshButton = showRefreshButton,
                    onRefreshNow = onRefreshNow
                )
            }

            item(key = "habr_feed_header") {
                HabrFeedHeaderCard(
                    selectedCategory = habrCategory,
                    isLoading = isLoadingHabr,
                    onSelectCategory = { cat ->
                        if (cat != habrCategory) {
                            reloadNonce = 0
                            habrCategoryName = cat.name
                        }
                    },
                    onRefreshFeed = { reloadNonce++ },
                    onOpenHabrSite = { openUrl("https://habr.com/ru/") }
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
                        onClick = { openUrl(newsItem.url) }
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
            GifAndStatusTopBlock(
                gifs = gifs,
                safeIndex = safeIndex,
                currentGif = currentGif,
                onSelectIndex = { currentIndex = it },
                emptyStatusText = emptyStatusText,
                showRefreshButton = showRefreshButton,
                onRefreshNow = onRefreshNow
            )

            HabrFeedHeaderCard(
                selectedCategory = habrCategory,
                isLoading = isLoadingHabr,
                onSelectCategory = { cat ->
                    if (cat != habrCategory) {
                        reloadNonce = 0
                        habrCategoryName = cat.name
                    }
                },
                onRefreshFeed = { reloadNonce++ },
                onOpenHabrSite = { openUrl("https://habr.com/ru/") }
            )

            if (habrError != null && habrItems.isEmpty()) {
                HabrFeedErrorCard(
                    message = habrError ?: "Не удалось загрузить новости",
                    onRetry = { reloadNonce++ }
                )
            } else {
                habrItems.forEach { newsItem ->
                    HabrNewsItemCard(
                        item = newsItem,
                        onClick = { openUrl(newsItem.url) }
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
            Crossfade(
                targetState = currentGif,
                animationSpec = tween(durationMillis = 300),
                label = "sysadmin_gif_crossfade"
            ) { gif ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 168.dp, max = 216.dp)
                            .aspectRatio(gif.aspectRatio)
                            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .background(Color(0xFF04091E)),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedAssetGifView(
                            assetPath = gif.assetPath,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Text(
                        text = gif.quote,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
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
    onRefreshFeed: () -> Unit,
    onOpenHabrSite: () -> Unit
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
                    text = "Новости habr.com/ru",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenHabrSite() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                IconButton(
                    onClick = onOpenHabrSite,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Открыть habr.com/ru",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }

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
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
