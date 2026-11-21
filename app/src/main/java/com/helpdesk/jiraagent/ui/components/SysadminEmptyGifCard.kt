package com.helpdesk.jiraagent.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import kotlin.random.Random

data class SysadminGifItem(
    val number: Int,
    val assetPath: String,
    val title: String,
    val quote: String,
    val aspectRatio: Float
)

object SysadminGifCatalog {
    val items: List<SysadminGifItem> = listOf(
        SysadminGifItem(
            number = 1,
            assetPath = "admin_gifs/admin_gif_01.gif",
            title = "Алло, IT? Выкл/Вкл (IT Crowd)",
            quote = "«Вы пробовали выключить и снова включить?» — очередь чиста!",
            aspectRatio = 500f / 274f
        ),
        SysadminGifItem(
            number = 2,
            assetPath = "admin_gifs/admin_gif_02.gif",
            title = "Танец сисадмина в серверной",
            quote = "Все серверы зелёные, пинг 1 мс, новых тикетов — ноль!",
            aspectRatio = 320f / 179f
        ),
        SysadminGifItem(
            number = 3,
            assetPath = "admin_gifs/admin_gif_03.gif",
            title = "HACKERMAN",
            quote = "Когда закрыл все вопросы одной перезагрузкой службы",
            aspectRatio = 320f / 180f
        ),
        SysadminGifItem(
            number = 4,
            assetPath = "admin_gifs/admin_gif_04.gif",
            title = "Птичка Гомера за клавиатурой",
            quote = "Скрипты крутятся, бэкапы пишутся — 100% автоматизация!",
            aspectRatio = 264f / 200f
        ),
        SysadminGifItem(
            number = 5,
            assetPath = "admin_gifs/admin_gif_05.gif",
            title = "Сверхскоростной разбор (Джим Керри)",
            quote = "Все обращения разлетелись быстрее, чем остыл утренний кофе",
            aspectRatio = 271f / 300f
        ),
        SysadminGifItem(
            number = 6,
            assetPath = "admin_gifs/admin_gif_06.gif",
            title = "Дзен с кофе (This is fine)",
            quote = "Новых заявок 0… Подозрительно тихо, но кофе по расписанию",
            aspectRatio = 1.15f
        ),
        SysadminGifItem(
            number = 7,
            assetPath = "admin_gifs/admin_gif_07.gif",
            title = "Спокойствие Мосса (IT Crowd)",
            quote = "В очереди чисто. Главное правило админа — не трогать то, что работает!",
            aspectRatio = 480f / 270f
        ),
        SysadminGifItem(
            number = 8,
            assetPath = "admin_gifs/admin_gif_08.gif",
            title = "Брент Рэмбо — палец вверх",
            quote = "Ни одного висящего тикета — полёт отличный!",
            aspectRatio = 250f / 188f
        ),
        SysadminGifItem(
            number = 9,
            assetPath = "admin_gifs/admin_gif_09.gif",
            title = "Одобрительный кивок старшего админа",
            quote = "Старший сисадмин одобряет: на горизонте полный порядок",
            aspectRatio = 340f / 191f
        ),
        SysadminGifItem(
            number = 10,
            assetPath = "admin_gifs/admin_gif_10.gif",
            title = "Дежурный кот на клавиатуре",
            quote = "Дежурный кот на посту: режим ожидания новых заявок",
            aspectRatio = 340f / 191f
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
    val gifs = SysadminGifCatalog.items
    var currentIndex by rememberSaveable {
        mutableIntStateOf(Random.nextInt(gifs.size))
    }
    var showCatalogDialog by remember { mutableStateOf(false) }

    val safeIndex = currentIndex.mod(gifs.size)
    val currentGif = gifs[safeIndex]

    val containerModifier = if (scrollable) {
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
    }

    Column(
        modifier = containerModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    currentIndex = (safeIndex + 1) % gifs.size
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF04091E)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 175.dp, max = 235.dp)
                        .aspectRatio(currentGif.aspectRatio.coerceIn(1.1f, 1.85f))
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(Color(0xFF081029)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedAssetGifView(
                        assetPath = currentGif.assetPath,
                        modifier = Modifier.fillMaxSize()
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xCC04091E)
                    ) {
                        Text(
                            text = "GIF ${currentGif.number}/${gifs.size}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = currentGif.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = currentGif.quote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = emptyStatusText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = { currentIndex = (safeIndex - 1 + gifs.size) % gifs.size },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Предыдущая гифка")
            }

            FilledTonalButton(
                onClick = {
                    val next = if (gifs.size > 1) {
                        var candidate = Random.nextInt(gifs.size)
                        if (candidate == safeIndex) candidate = (safeIndex + 1) % gifs.size
                        candidate
                    } else {
                        0
                    }
                    currentIndex = next
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Casino,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Случайная", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = { showCatalogDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Collections,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text("Все 10", style = MaterialTheme.typography.labelLarge)
            }

            IconButton(
                onClick = { currentIndex = (safeIndex + 1) % gifs.size },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Следующая гифка")
            }
        }

        if (showRefreshButton) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRefreshNow,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Обновить сейчас", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showCatalogDialog) {
        AlertDialog(
            onDismissRequest = { showCatalogDialog = false },
            title = {
                Text(
                    text = "10 админских гифок",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(gifs) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    currentIndex = index
                                    showCatalogDialog = false
                                }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == safeIndex,
                                onClick = {
                                    currentIndex = index
                                    showCatalogDialog = false
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${item.number}. ${item.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = item.quote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCatalogDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@Composable
fun AnimatedAssetGifView(
    assetPath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView ->
                if (imageView.tag != assetPath) {
                    (imageView.drawable as? AnimatedImageDrawable)?.stop()
                    imageView.tag = assetPath
                    runCatching {
                        val bytes = context.assets.open(assetPath).use { it.readBytes() }
                        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                        val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        imageView.setImageDrawable(drawable)
                        if (drawable is AnimatedImageDrawable) {
                            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            drawable.start()
                        }
                    }
                } else {
                    val current = imageView.drawable
                    if (current is AnimatedImageDrawable && !current.isRunning) {
                        current.start()
                    }
                }
            },
            onRelease = { imageView ->
                (imageView.drawable as? AnimatedImageDrawable)?.stop()
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
