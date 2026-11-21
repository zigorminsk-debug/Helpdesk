package com.helpdesk.jiraagent.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    val gifs = SysadminGifCatalog.items
    var currentIndex by rememberSaveable {
        mutableIntStateOf(Random.nextInt(gifs.size))
    }

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
                            .heightIn(min = 180.dp, max = 240.dp)
                            .aspectRatio(gif.aspectRatio)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (gifs.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { currentIndex = (safeIndex - 1 + gifs.size) % gifs.size },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Предыдущая гифка",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                gifs.indices.forEach { idx ->
                    Box(
                        modifier = Modifier
                            .size(if (idx == safeIndex) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx == safeIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                            .clickable { currentIndex = idx }
                    )
                }

                IconButton(
                    onClick = { currentIndex = (safeIndex + 1) % gifs.size },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Следующая гифка",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = emptyStatusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (showRefreshButton) {
            Spacer(Modifier.height(12.dp))
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
                        // Hardware/default allocator enables smooth VSYNC RenderThread playback
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
