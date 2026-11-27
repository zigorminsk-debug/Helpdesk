package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ConfluenceOfflineSnapshot(
    val syncedAtEpochMs: Long,
    val syncedAtLabel: String,
    val syncedUsername: String,
    val spaces: List<ConfluenceSpace>,
    val rootPagesBySpace: Map<String, List<ConfluencePageSummary>>,
    val recentPages: List<ConfluencePageSummary>,
    val childPagesByParent: Map<String, List<ConfluencePageSummary>>,
    val pagesById: Map<String, ConfluencePageDetail>,
    val attachmentTotal: Int = 0,
    val attachmentAvailable: Int = 0,
    val attachmentProblems: Int = 0,
    val attachmentSyncCompleted: Boolean = false
) {
    val totalPagesCount: Int get() = pagesById.size
    val attachmentSummary: String get() = if (!attachmentSyncCompleted) "Вложения ещё не синхронизированы"
        else "Вложения при синхр.: $attachmentAvailable/$attachmentTotal" +
            if (attachmentProblems > 0) " · проблем: $attachmentProblems (списки или файлы); повторите синхронизацию" else ""
}

data class ConfluenceSyncProgress(
    val message: String,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val fraction: Float = 0f
) {
    val percentInt: Int get() = (fraction.coerceIn(0f, 1f) * 100f).toInt()
}

sealed class ConfluenceSyncOutcome {
    data class Synced(val snapshot: ConfluenceOfflineSnapshot) : ConfluenceSyncOutcome()
    data class OfflineUsingCache(
        val snapshot: ConfluenceOfflineSnapshot?,
        val message: String
    ) : ConfluenceSyncOutcome()
    data class RequiresInitialWeb2fa(val message: String) : ConfluenceSyncOutcome()
    data class AccountRevokedWipeTriggered(val reason: String) : ConfluenceSyncOutcome()
}

/**
 * Persistent local offline database for Confluence (`w.csl.by`).
 * Downloads all spaces, client hierarchy, sub-pages, tables, inline tabs, and images onto the device,
 * syncs once per 24 hours, and immediately wipes the local database if `w.csl.by` is reachable
 * and rejects account authorization.
 */
class ConfluenceOfflineStore(private val storageDir: File) {

    private val dbFile = File(storageDir, "wiki_offline_db.json.gz")
    private val mediaDir = File(storageDir, "wiki_offline_media")
    private val attachmentDir = File(storageDir, "wiki_offline_attachments")
    private val attachmentMutex = Mutex()
    @Volatile private var generation = 0L
    private val syncMutex = Mutex()

    @Volatile
    private var memorySnapshot: ConfluenceOfflineSnapshot? = null

    @Volatile
    private var loadedFromDisk: Boolean = false

    init {
        storageDir.mkdirs()
    }

    @Synchronized
    fun getSnapshot(): ConfluenceOfflineSnapshot? {
        if (!loadedFromDisk) {
            loadedFromDisk = true
            memorySnapshot = readSnapshotFromDisk()
        }
        return memorySnapshot
    }

    fun hasOfflineSnapshot(): Boolean {
        val snap = getSnapshot()
        return snap != null && snap.pagesById.isNotEmpty()
    }

    fun isDailySyncDue(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val snap = getSnapshot() ?: return true
        if (snap.pagesById.isEmpty() || !snap.attachmentSyncCompleted) return true
        return (nowEpochMs - snap.syncedAtEpochMs) >= DAILY_SYNC_INTERVAL_MS
    }

    fun getDefaultSpaceKey(): String? {
        val snap = getSnapshot() ?: return null
        return snap.spaces.firstOrNull { it.key.equals("CSL", ignoreCase = true) }?.key
            ?: snap.spaces.firstOrNull { it.type == "global" }?.key
            ?: snap.spaces.firstOrNull()?.key
    }

    fun getRootPagesForSpace(spaceKey: String?): List<ConfluencePageSummary> {
        val snap = getSnapshot() ?: return emptyList()
        if (spaceKey == ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY) {
            return snap.recentPages
        }
        val key = spaceKey ?: getDefaultSpaceKey() ?: return snap.recentPages
        val exact = snap.rootPagesBySpace.entries
            .firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
        if (!exact.isNullOrEmpty()) return exact

        // Fallback: all pages in that space whose ancestors are at most 1 level deep
        val inSpace = snap.pagesById.values.filter { it.spaceKey.equals(key, ignoreCase = true) }
        val minDepth = inSpace.minOfOrNull { it.breadcrumbs.size } ?: 0
        return ConfluenceWikiClient.sortPagesAlphabetically(
            inSpace.filter { it.breadcrumbs.size == minDepth }.map { it.toSummary() }
        )
    }

    fun getPageDetail(pageId: String): ConfluencePageDetail? {
        return getSnapshot()?.pagesById?.get(pageId)
    }

    fun getChildPages(parentId: String): List<ConfluencePageSummary> {
        val snap = getSnapshot() ?: return emptyList()
        return snap.childPagesByParent[parentId]
            ?: snap.pagesById[parentId]?.childPages
            ?: emptyList()
    }

    fun searchOffline(rawQuery: String, spaceKeyFilter: String? = null): List<ConfluencePageSummary> {
        val snap = getSnapshot() ?: return emptyList()
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        val qLower = q.lowercase(Locale("ru", "RU"))
        val effectiveSpace = spaceKeyFilter?.takeIf {
            it.isNotBlank() && it != ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY
        }

        val matches = mutableListOf<ConfluencePageSummary>()
        for (detail in snap.pagesById.values) {
            if (effectiveSpace != null && !detail.spaceKey.equals(effectiveSpace, ignoreCase = true)) {
                continue
            }
            val titleMatch = detail.title.lowercase(Locale("ru", "RU")).contains(qLower)
            val bodyCorpus = extractSearchableText(detail)
            val bodyIdx = bodyCorpus.lowercase(Locale("ru", "RU")).indexOf(qLower)
            if (titleMatch || bodyIdx >= 0) {
                val snippet = when {
                    bodyIdx >= 0 -> {
                        val from = (bodyIdx - 40).coerceAtLeast(0)
                        val to = (bodyIdx + q.length + 70).coerceAtMost(bodyCorpus.length)
                        val rawSnippet = bodyCorpus.substring(from, to).replace('\n', ' ').trim()
                        (if (from > 0) "…" else "") + rawSnippet + (if (to < bodyCorpus.length) "…" else "")
                    }
                    detail.breadcrumbs.isNotEmpty() ->
                        detail.breadcrumbs.joinToString(" › ") { it.title }
                    else -> ""
                }
                matches.add(
                    ConfluencePageSummary(
                        id = detail.id,
                        title = detail.title,
                        spaceKey = detail.spaceKey,
                        spaceName = detail.spaceName,
                        excerpt = snippet,
                        updatedLabel = detail.updatedLabel,
                        updatedBy = detail.updatedBy,
                        webPath = detail.webUrl,
                        hasChildrenHint = detail.childPages.isNotEmpty()
                    )
                )
            }
        }
        return ConfluenceWikiClient.sortPagesAlphabetically(matches)
    }

    fun getCachedMediaBytes(urlOrPath: String): ByteArray? {
        if (urlOrPath.isBlank()) return null
        val f = File(mediaDir, sha256Hex(urlOrPath))
        if (!f.exists() || !f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    @Synchronized
    fun saveCachedMediaBytes(urlOrPath: String, bytes: ByteArray) {
        if (urlOrPath.isBlank() || bytes.isEmpty() || bytes.size > MAX_CACHED_IMAGE_BYTES) return
        runCatching {
            mediaDir.mkdirs()
            File(mediaDir, sha256Hex(urlOrPath)).writeBytes(bytes)
        }
    }

    private fun attachmentKey(att: ConfluenceAttachment): String = sha256Hex(
        "${att.downloadUrl}\n${att.id}\n${att.versionNumber}\n${att.fileSizeBytes}\n${att.title}"
    )

    private fun safeAttachmentName(title: String): String {
        val name = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.')
            .take(160).ifBlank { "wiki_file" }
        val stem = name.substringBefore('.').uppercase(Locale.ROOT)
        return if (stem in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")) "_$name" else name
    }

    @Synchronized
    fun getCachedAttachmentFile(att: ConfluenceAttachment): File? {
        if (att.downloadUrl.isBlank()) return null
        val file = File(File(attachmentDir, attachmentKey(att)), safeAttachmentName(att.title))
        return file.takeIf { it.isFile && (att.fileSizeBytes <= 0L || it.length() == att.fileSizeBytes) }
    }

    /** Atomically caches a specific attachment version. Failed/cancelled downloads never replace the old copy. */
    suspend fun getOrDownloadAttachment(att: ConfluenceAttachment, wikiClient: ConfluenceWikiClient,
        settings: AppSettings, forceRefresh: Boolean = false, expectedGeneration: Long? = null): Result<File> {
        val requestGeneration = expectedGeneration ?: generation
        return attachmentMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(requestGeneration == generation) { "Офлайн-база была очищена" }
                if (!forceRefresh) getCachedAttachmentFile(att)?.let { return@runCatching it }
                val currentGeneration = requestGeneration
                if (att.fileSizeBytes > MAX_ATTACHMENT_BYTES) throw IllegalStateException("Файл больше лимита ${MAX_ATTACHMENT_BYTES / 1024 / 1024} МБ")
                val dir = File(attachmentDir, attachmentKey(att))
                check(dir.mkdirs() || dir.isDirectory) { "Не удалось создать папку вложения" }
                if (att.fileSizeBytes > 0L && dir.usableSpace > 0 && dir.usableSpace < att.fileSizeBytes + FREE_SPACE_RESERVE_BYTES) {
                    throw IllegalStateException("Недостаточно места для вложения")
                }
                val tmp = File(dir, ".download-${UUID.randomUUID()}.tmp")
                try {
                    wikiClient.downloadAttachmentToFile(att, settings, tmp, MAX_ATTACHMENT_BYTES).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    synchronized(this@ConfluenceOfflineStore) {
                        check(currentGeneration == generation) { "Офлайн-база очищена во время загрузки" }
                        val target = File(dir, safeAttachmentName(att.title))
                        try {
                            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        target
                    }
                } finally { tmp.delete() }
            }.onFailure { if (it is CancellationException) throw it }
        }
        }
    }

    /** Do not execute downloaded programs/scripts when the user opens a document. */
    fun isSafeAttachmentToOpen(att: ConfluenceAttachment): Boolean {
        val ext = att.title.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext !in setOf("exe", "com", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "msi", "msp", "scr", "lnk", "url", "reg", "jar", "sh", "apk")
    }

    @Synchronized
    fun clearOfflineDatabase() {
        generation++
        memorySnapshot = null
        loadedFromDisk = true
        runCatching { if (dbFile.exists()) dbFile.delete() }
        runCatching {
            val tmp = File(storageDir, "wiki_offline_db.json.gz.tmp")
            if (tmp.exists()) tmp.delete()
        }
        runCatching {
            if (mediaDir.exists()) mediaDir.deleteRecursively()
            if (attachmentDir.exists()) attachmentDir.deleteRecursively()
        }
    }

    /**
     * Synchronizes the full Confluence database onto the device:
     * 1. Verifies server reachability and account authorization on `w.csl.by`.
     * 2. If `w.csl.by` is reachable and authorization fails -> wipes the offline DB and returns [ConfluenceSyncOutcome.AccountRevokedWipeTriggered].
     * 3. If `w.csl.by` is unreachable -> keeps the existing offline DB intact.
     * 4. If authorized -> downloads all spaces, hierarchies, pages, tables, tabs, and inline images for full offline work.
     */
    suspend fun syncFromServer(
        wikiClient: ConfluenceWikiClient,
        settings: AppSettings,
        onProgress: (String) -> Unit = {},
        onProgressDetail: (ConfluenceSyncProgress) -> Unit = {}
    ): ConfluenceSyncOutcome = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            fun reportProgress(
                msg: String,
                downloaded: Int = 0,
                total: Int = 0,
                fraction: Float = 0f
            ) {
                val safeFraction = fraction.coerceIn(0f, 1f)
                onProgress(msg)
                onProgressDetail(
                    ConfluenceSyncProgress(
                        message = msg,
                        downloadedPages = downloaded,
                        totalPages = total,
                        fraction = safeFraction
                    )
                )
            }

            val syncGeneration = generation
            val hadExistingDb = hasOfflineSnapshot()
            reportProgress(
                msg = "Проверка связи и учётной записи на w.csl.by…",
                fraction = 0.03f
            )

            val access = wikiClient.verifyConfluenceAccountAccess(settings, hadExistingDb)
            if (syncGeneration != generation) return@withContext ConfluenceSyncOutcome.OfflineUsingCache(null, "Настройки или офлайн-база изменились во время синхронизации")
            when (access) {
                is ConfluenceAccessCheckResult.OfflineUnreachable -> {
                    return@withContext ConfluenceSyncOutcome.OfflineUsingCache(
                        snapshot = getSnapshot(),
                        message = access.reason
                    )
                }
                is ConfluenceAccessCheckResult.RequiresInitialWeb2fa -> {
                    return@withContext ConfluenceSyncOutcome.RequiresInitialWeb2fa(access.message)
                }
                is ConfluenceAccessCheckResult.AccountDisabledOrUnauthorized -> {
                    clearOfflineDatabase()
                    return@withContext ConfluenceSyncOutcome.AccountRevokedWipeTriggered(access.reason)
                }
                is ConfluenceAccessCheckResult.Authorized -> {
                    try {
                        reportProgress(
                            msg = "Загрузка списка пространств w.csl.by…",
                            fraction = 0.07f
                        )
                        val spaces = wikiClient.fetchSpaces(settings).getOrThrow()
                        val recentPages = wikiClient.fetchSpaceRootOrRecentPages(
                            spaceKey = ConfluenceWikiClient.RECENT_PSEUDO_SPACE_KEY,
                            settings = settings
                        ).getOrDefault(emptyList())

                        val rootPagesBySpace = LinkedHashMap<String, List<ConfluencePageSummary>>()
                        val allPagesById = LinkedHashMap<String, ConfluencePageDetail>()
                        val allChildPagesByParent = LinkedHashMap<String, List<ConfluencePageSummary>>()

                        // Synchronize every accessible space.
                        val spacesToSync = spaces
                        val spaceSpan = 0.80f / spacesToSync.size.coerceAtLeast(1)

                        for ((idx, sp) in spacesToSync.withIndex()) {
                            val spaceBaseFraction = 0.08f + idx * spaceSpan
                            reportProgress(
                                msg = "Синхронизация «${sp.key}» (${idx + 1}/${spacesToSync.size})…",
                                downloaded = allPagesById.size,
                                total = allPagesById.size,
                                fraction = spaceBaseFraction
                            )

                            val roots = wikiClient.fetchSpaceRootOrRecentPages(
                                spaceKey = sp.key,
                                settings = settings,
                                homepageIdHint = sp.homepageId
                            ).getOrDefault(emptyList())

                            val spaceDetails = wikiClient.fetchAllSpacePagesWithDetails(
                                spaceKey = sp.key,
                                spaceNameHint = sp.name,
                                settings = settings,
                                onBatchProgress = { inSpaceCount, inSpaceTotal ->
                                    val inSpaceRatio = if (inSpaceTotal > 0) {
                                        (inSpaceCount.toFloat() / inSpaceTotal.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0.5f
                                    }
                                    val cumDownloaded = allPagesById.size + inSpaceCount
                                    val estTotal = if (inSpaceTotal > 0) {
                                        allPagesById.size + inSpaceTotal
                                    } else {
                                        cumDownloaded
                                    }
                                    val label = if (inSpaceTotal > 0) {
                                        "Скачивание «${sp.key}» (${idx + 1}/${spacesToSync.size}): $inSpaceCount из $inSpaceTotal стр. (всего $cumDownloaded стр.)"
                                    } else {
                                        "Скачивание «${sp.key}» (${idx + 1}/${spacesToSync.size}): $cumDownloaded стр.…"
                                    }
                                    reportProgress(
                                        msg = label,
                                        downloaded = cumDownloaded,
                                        total = estTotal,
                                        fraction = spaceBaseFraction + inSpaceRatio * spaceSpan
                                    )
                                }
                            ).getOrThrow()

                            for (d in spaceDetails) {
                                allPagesById[d.id] = d
                                if (d.childPages.isNotEmpty()) {
                                    allChildPagesByParent[d.id] = d.childPages
                                }
                            }

                            val resolvedRoots = if (roots.isNotEmpty()) {
                                roots
                            } else {
                                val minDepth = spaceDetails.minOfOrNull { it.breadcrumbs.size } ?: 0
                                ConfluenceWikiClient.sortPagesAlphabetically(
                                    spaceDetails.filter { it.breadcrumbs.size == minDepth }.map { it.toSummary() }
                                )
                            }
                            rootPagesBySpace[sp.key] = resolvedRoots
                        }

                        if (allPagesById.isEmpty()) {
                            return@withContext if (!hadExistingDb) {
                                ConfluenceSyncOutcome.RequiresInitialWeb2fa(
                                    "Сервер w.csl.by не вернул ни одной доступной страницы. Выполните вход во вкладке «🌐 Web» или укажите PAT-токен."
                                )
                            } else {
                                ConfluenceSyncOutcome.OfflineUsingCache(
                                    snapshot = getSnapshot(),
                                    message = "Сервер w.csl.by вернул пустой список страниц — сохранена текущая локальная база"
                                )
                            }
                        }

                        // Pre-cache up to 80 inline diagram/screenshot images not yet cached on disk
                        var newlyCachedImages = 0
                        for (detail in allPagesById.values) {
                            if (newlyCachedImages >= 80) break
                            val imageUrls = buildList {
                                detail.blocks.filterIsInstance<ConfluenceBlock.Image>().forEach { add(it.url) }
                                detail.inlineTabs.forEach { t ->
                                    t.blocks.filterIsInstance<ConfluenceBlock.Image>().forEach { add(it.url) }
                                }
                            }.distinct()
                            for (imgUrl in imageUrls) {
                                if (newlyCachedImages >= 80) break
                                if (getCachedMediaBytes(imgUrl) == null) {
                                    wikiClient.downloadProtectedBytes(imgUrl, settings).getOrNull()?.let { bytes ->
                                        synchronized(this@ConfluenceOfflineStore) {
                                            check(syncGeneration == generation) { "Офлайн-база была очищена" }
                                            saveCachedMediaBytes(imgUrl, bytes)
                                        }
                                        newlyCachedImages++
                                        if (newlyCachedImages % 5 == 0) {
                                            reportProgress(
                                                msg = "Кэширование схем и изображений ($newlyCachedImages) — база ${allPagesById.size} стр.…",
                                                downloaded = allPagesById.size,
                                                total = allPagesById.size,
                                                fraction = 0.87f
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Fetch complete attachment lists separately: expanded children can be truncated.
                        var listingProblems = 0
                        val oldSnapshot = getSnapshot()
                        for ((index, id) in allPagesById.keys.toList().withIndex()) {
                            currentCoroutineContext().ensureActive()
                            check(syncGeneration == generation) { "Офлайн-база была очищена" }
                            val page = allPagesById.getValue(id)
                            val result = wikiClient.fetchPageAttachments(id, settings)
                            val attachments = result.getOrElse {
                                if (it is CancellationException) throw it
                                listingProblems++
                                (page.attachments + oldSnapshot?.pagesById?.get(id)?.attachments.orEmpty()).distinctBy { a -> a.id }
                            }
                            allPagesById[id] = page.copy(attachments = attachments)
                            if (index % 10 == 0) reportProgress("Проверка вложений страниц: ${index + 1} / ${allPagesById.size}…",
                                allPagesById.size, allPagesById.size, 0.88f)
                        }
                        val attachments = allPagesById.values.flatMap { it.attachments }.distinctBy { attachmentKey(it) }
                        var downloadProblems = 0
                        for ((index, att) in attachments.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            check(syncGeneration == generation) { "Офлайн-база была очищена" }
                            // Unknown versions must be revalidated at each sync, while known versions are reused.
                            val result = getOrDownloadAttachment(att, wikiClient, settings, forceRefresh = att.versionNumber <= 0, expectedGeneration = syncGeneration)
                            if (result.isFailure) downloadProblems++
                            reportProgress("Синхронизация вложений: ${index + 1} / ${attachments.size} · ошибок: $downloadProblems",
                                allPagesById.size, allPagesById.size, 0.89f + 0.08f * ((index + 1).toFloat() / attachments.size.coerceAtLeast(1)))
                        }
                        val available = attachments.count { getCachedAttachmentFile(it) != null }
                        val attachmentProblems = listingProblems + downloadProblems

                        val nowMs = System.currentTimeMillis()
                        val minskFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                            .withZone(ZoneId.of("Europe/Minsk"))
                        val snapshot = ConfluenceOfflineSnapshot(
                            syncedAtEpochMs = nowMs,
                            syncedAtLabel = minskFmt.format(Instant.ofEpochMilli(nowMs)),
                            syncedUsername = access.username,
                            spaces = spaces,
                            rootPagesBySpace = rootPagesBySpace,
                            recentPages = recentPages,
                            childPagesByParent = allChildPagesByParent,
                            pagesById = allPagesById,
                            attachmentTotal = attachments.size,
                            attachmentAvailable = available,
                            attachmentProblems = attachmentProblems,
                            attachmentSyncCompleted = true
                        )

                        reportProgress(
                            msg = "Сохранение офлайн-базы (${allPagesById.size} стр.) на устройство…",
                            downloaded = allPagesById.size,
                            total = allPagesById.size,
                            fraction = 0.98f
                        )
                        synchronized(this@ConfluenceOfflineStore) {
                            check(syncGeneration == generation) { "Офлайн-база была очищена" }
                            saveSnapshotToDisk(snapshot)
                            memorySnapshot = snapshot
                            loadedFromDisk = true
                        }
                        if (attachmentProblems == 0) {
                            val activeKeys = attachments.map { attachmentKey(it) }.toSet()
                            attachmentMutex.withLock {
                                synchronized(this@ConfluenceOfflineStore) {
                                    if (syncGeneration == generation) attachmentDir.listFiles()?.filter { it.isDirectory && it.name !in activeKeys }?.forEach { it.deleteRecursively() }
                                }
                            }
                        }
                        reportProgress(
                            msg = "Офлайн-база: ${allPagesById.size} стр., вложения $available/${attachments.size}; ошибок: $attachmentProblems",
                            downloaded = allPagesById.size,
                            total = allPagesById.size,
                            fraction = 1.0f
                        )
                        return@withContext ConfluenceSyncOutcome.Synced(snapshot)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (revoked: ConfluenceAccountRevokedException) {
                        synchronized(this@ConfluenceOfflineStore) {
                            if (syncGeneration == generation) clearOfflineDatabase()
                        }
                        return@withContext ConfluenceSyncOutcome.AccountRevokedWipeTriggered(
                            revoked.message ?: "Доступ учётной записи отклонён"
                        )
                    } catch (e: Exception) {
                        return@withContext ConfluenceSyncOutcome.OfflineUsingCache(
                            snapshot = getSnapshot(),
                            message = e.message ?: "Не удалось завершить синхронизацию с w.csl.by"
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun persistSnapshot(snapshot: ConfluenceOfflineSnapshot) {
        saveSnapshotToDisk(snapshot)
        memorySnapshot = snapshot
        loadedFromDisk = true
    }

    @Synchronized
    fun upsertPageDetailInCache(detail: ConfluencePageDetail) {
        val cur = getSnapshot() ?: return
        val updatedPages = LinkedHashMap(cur.pagesById).apply { put(detail.id, detail) }
        val updatedChildren = if (detail.childPages.isNotEmpty()) {
            LinkedHashMap(cur.childPagesByParent).apply { put(detail.id, detail.childPages) }
        } else {
            cur.childPagesByParent
        }
        val next = cur.copy(pagesById = updatedPages, childPagesByParent = updatedChildren)
        memorySnapshot = next
        runCatching { saveSnapshotToDisk(next) }
    }

    private fun extractSearchableText(detail: ConfluencePageDetail): String {
        val sb = StringBuilder()
        fun appendBlocks(list: List<ConfluenceBlock>) {
            for (b in list) {
                when (b) {
                    is ConfluenceBlock.Heading -> sb.append(b.text).append('\n')
                    is ConfluenceBlock.Paragraph -> sb.append(b.text).append('\n')
                    is ConfluenceBlock.ListItems -> b.items.forEach { sb.append(it).append('\n') }
                    is ConfluenceBlock.Code -> sb.append(b.code).append('\n')
                    is ConfluenceBlock.Callout -> sb.append(b.body).append('\n')
                    is ConfluenceBlock.Table -> {
                        if (b.headers.isNotEmpty()) sb.append(b.headers.joinToString(" | ")).append('\n')
                        b.rows.forEach { r -> sb.append(r.joinToString(" | ")).append('\n') }
                    }
                    is ConfluenceBlock.Image -> {
                        if (b.alt.isNotBlank()) sb.append(b.alt).append('\n')
                    }
                }
            }
        }
        appendBlocks(detail.blocks)
        for (tab in detail.inlineTabs) {
            sb.append(tab.title).append('\n')
            appendBlocks(tab.blocks)
        }
        return sb.toString()
    }

    private fun ConfluencePageDetail.toSummary(): ConfluencePageSummary = ConfluencePageSummary(
        id = id,
        title = title,
        spaceKey = spaceKey,
        spaceName = spaceName,
        updatedLabel = updatedLabel,
        updatedBy = updatedBy,
        webPath = webUrl,
        hasChildrenHint = childPages.isNotEmpty()
    )

    // ==================== R8-SAFE GZIP JSON SERIALIZATION ====================

    private fun saveSnapshotToDisk(snapshot: ConfluenceOfflineSnapshot) {
        storageDir.mkdirs()
        val tmpFile = File(storageDir, "wiki_offline_db.json.gz.tmp")
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("syncedAtEpochMs", snapshot.syncedAtEpochMs)
            addProperty("syncedAtLabel", snapshot.syncedAtLabel)
            addProperty("syncedUsername", snapshot.syncedUsername)
            addProperty("attachmentTotal", snapshot.attachmentTotal)
            addProperty("attachmentAvailable", snapshot.attachmentAvailable)
            addProperty("attachmentProblems", snapshot.attachmentProblems)
            addProperty("attachmentSyncCompleted", snapshot.attachmentSyncCompleted)
            add("spaces", JsonArray().apply { snapshot.spaces.forEach { add(encodeSpace(it)) } })
            add("recentPages", JsonArray().apply { snapshot.recentPages.forEach { add(encodeSummary(it)) } })
            add(
                "rootPagesBySpace",
                JsonObject().apply {
                    snapshot.rootPagesBySpace.forEach { (k, list) ->
                        add(k, JsonArray().apply { list.forEach { add(encodeSummary(it)) } })
                    }
                }
            )
            add("pages", JsonArray().apply { snapshot.pagesById.values.forEach { add(encodeDetail(it)) } })
        }

        FileOutputStream(tmpFile).use { fos ->
            GZIPOutputStream(fos).use { gzos ->
                BufferedWriter(OutputStreamWriter(gzos, StandardCharsets.UTF_8)).use { writer ->
                    writer.write(root.toString())
                }
            }
        }
        if (dbFile.exists()) {
            dbFile.delete()
        }
        tmpFile.renameTo(dbFile)
    }

    private fun readSnapshotFromDisk(): ConfluenceOfflineSnapshot? {
        if (!dbFile.exists() || !dbFile.isFile) return null
        return runCatching {
            val jsonText = FileInputStream(dbFile).use { fis ->
                GZIPInputStream(fis).use { gzis ->
                    BufferedReader(InputStreamReader(gzis, StandardCharsets.UTF_8)).use { it.readText() }
                }
            }
            val root = JsonParser.parseString(jsonText).asJsonObject
            val syncedAtMs = root.optLong("syncedAtEpochMs") ?: 0L
            val syncedLabel = root.optStr("syncedAtLabel").orEmpty()
            val syncedUser = root.optStr("syncedUsername").orEmpty()

            val spaces = root.optArray("spaces")?.mapNotNull { decodeSpace(it) }.orEmpty()
            val recent = root.optArray("recentPages")?.mapNotNull { decodeSummary(it) }.orEmpty()

            val rootsBySpace = LinkedHashMap<String, List<ConfluencePageSummary>>()
            root.optObj("rootPagesBySpace")?.entrySet()?.forEach { (k, el) ->
                if (el.isJsonArray) {
                    rootsBySpace[k] = el.asJsonArray.mapNotNull { decodeSummary(it) }
                }
            }

            val pagesMap = LinkedHashMap<String, ConfluencePageDetail>()
            val childrenMap = LinkedHashMap<String, List<ConfluencePageSummary>>()
            root.optArray("pages")?.forEach { el ->
                val d = decodeDetail(el) ?: return@forEach
                pagesMap[d.id] = d
                if (d.childPages.isNotEmpty()) {
                    childrenMap[d.id] = d.childPages
                }
            }

            ConfluenceOfflineSnapshot(
                syncedAtEpochMs = syncedAtMs,
                syncedAtLabel = syncedLabel,
                syncedUsername = syncedUser,
                spaces = spaces,
                rootPagesBySpace = rootsBySpace,
                recentPages = recent,
                childPagesByParent = childrenMap,
                pagesById = pagesMap,
                attachmentTotal = root.optInt("attachmentTotal") ?: 0,
                attachmentAvailable = root.optInt("attachmentAvailable") ?: 0,
                attachmentProblems = root.optInt("attachmentProblems") ?: 0,
                attachmentSyncCompleted = root.optBool("attachmentSyncCompleted") ?: false
            )
        }.getOrNull()
    }

    private fun encodeSpace(s: ConfluenceSpace): JsonObject = JsonObject().apply {
        addProperty("key", s.key)
        addProperty("name", s.name)
        addProperty("type", s.type)
        if (s.homepageId != null) addProperty("homepageId", s.homepageId)
        addProperty("description", s.description)
    }

    private fun decodeSpace(el: JsonElement): ConfluenceSpace? {
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val key = o.optStr("key") ?: return null
        return ConfluenceSpace(
            key = key,
            name = o.optStr("name") ?: key,
            type = o.optStr("type") ?: "global",
            homepageId = o.optStr("homepageId"),
            description = o.optStr("description").orEmpty()
        )
    }

    private fun encodeSummary(p: ConfluencePageSummary): JsonObject = JsonObject().apply {
        addProperty("id", p.id)
        addProperty("title", p.title)
        addProperty("spaceKey", p.spaceKey)
        addProperty("spaceName", p.spaceName)
        addProperty("excerpt", p.excerpt)
        addProperty("updatedLabel", p.updatedLabel)
        addProperty("updatedBy", p.updatedBy)
        addProperty("webPath", p.webPath)
        addProperty("hasChildrenHint", p.hasChildrenHint)
    }

    private fun decodeSummary(el: JsonElement): ConfluencePageSummary? {
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = o.optStr("id") ?: return null
        return ConfluencePageSummary(
            id = id,
            title = o.optStr("title").orEmpty(),
            spaceKey = o.optStr("spaceKey").orEmpty(),
            spaceName = o.optStr("spaceName").orEmpty(),
            excerpt = o.optStr("excerpt").orEmpty(),
            updatedLabel = o.optStr("updatedLabel").orEmpty(),
            updatedBy = o.optStr("updatedBy").orEmpty(),
            webPath = o.optStr("webPath").orEmpty(),
            hasChildrenHint = o.optBool("hasChildrenHint") ?: true
        )
    }

    private fun encodeBlock(b: ConfluenceBlock): JsonObject = JsonObject().apply {
        when (b) {
            is ConfluenceBlock.Heading -> {
                addProperty("t", "h")
                addProperty("level", b.level)
                addProperty("text", b.text)
            }
            is ConfluenceBlock.Paragraph -> {
                addProperty("t", "p")
                addProperty("text", b.text)
            }
            is ConfluenceBlock.ListItems -> {
                addProperty("t", "list")
                addProperty("ordered", b.ordered)
                add("items", JsonArray().apply { b.items.forEach { add(it) } })
            }
            is ConfluenceBlock.Code -> {
                addProperty("t", "code")
                addProperty("code", b.code)
                addProperty("title", b.title)
                if (b.language != null) addProperty("lang", b.language)
            }
            is ConfluenceBlock.Callout -> {
                addProperty("t", "callout")
                addProperty("kind", b.kind.name)
                addProperty("title", b.title)
                addProperty("body", b.body)
            }
            is ConfluenceBlock.Table -> {
                addProperty("t", "table")
                add("headers", JsonArray().apply { b.headers.forEach { add(it) } })
                add(
                    "rows",
                    JsonArray().apply {
                        b.rows.forEach { row ->
                            add(JsonArray().apply { row.forEach { cell -> add(cell) } })
                        }
                    }
                )
            }
            is ConfluenceBlock.Image -> {
                addProperty("t", "img")
                addProperty("url", b.url)
                addProperty("alt", b.alt)
                if (b.caption != null) addProperty("caption", b.caption)
            }
        }
    }

    private fun decodeBlock(el: JsonElement): ConfluenceBlock? {
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return when (o.optStr("t")) {
            "h" -> ConfluenceBlock.Heading(
                level = o.optInt("level") ?: 2,
                text = o.optStr("text").orEmpty()
            )
            "p" -> ConfluenceBlock.Paragraph(o.optStr("text").orEmpty())
            "list" -> ConfluenceBlock.ListItems(
                ordered = o.optBool("ordered") ?: false,
                items = o.optArray("items")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            )
            "code" -> ConfluenceBlock.Code(
                code = o.optStr("code").orEmpty(),
                title = o.optStr("title").orEmpty(),
                language = o.optStr("lang")
            )
            "callout" -> ConfluenceBlock.Callout(
                kind = runCatching {
                    ConfluenceCalloutKind.valueOf(o.optStr("kind").orEmpty())
                }.getOrDefault(ConfluenceCalloutKind.INFO),
                title = o.optStr("title").orEmpty(),
                body = o.optStr("body").orEmpty()
            )
            "table" -> ConfluenceBlock.Table(
                headers = o.optArray("headers")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
                rows = o.optArray("rows")?.mapNotNull { rEl ->
                    rEl.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { cEl ->
                        cEl.takeIf { it.isJsonPrimitive }?.asString
                    }
                }.orEmpty()
            )
            "img" -> ConfluenceBlock.Image(
                url = o.optStr("url").orEmpty(),
                alt = o.optStr("alt").orEmpty(),
                caption = o.optStr("caption")
            )
            else -> null
        }
    }

    private fun encodeDetail(d: ConfluencePageDetail): JsonObject = JsonObject().apply {
        addProperty("id", d.id)
        addProperty("title", d.title)
        addProperty("spaceKey", d.spaceKey)
        addProperty("spaceName", d.spaceName)
        addProperty("versionNumber", d.versionNumber)
        addProperty("updatedBy", d.updatedBy)
        addProperty("updatedLabel", d.updatedLabel)
        addProperty("webUrl", d.webUrl)
        addProperty("hasSubstantialBody", d.hasSubstantialBody)
        add(
            "breadcrumbs",
            JsonArray().apply {
                d.breadcrumbs.forEach { b ->
                    add(JsonObject().apply {
                        addProperty("id", b.id)
                        addProperty("title", b.title)
                    })
                }
            }
        )
        add("childPages", JsonArray().apply { d.childPages.forEach { add(encodeSummary(it)) } })
        add(
            "inlineTabs",
            JsonArray().apply {
                d.inlineTabs.forEach { tab ->
                    add(JsonObject().apply {
                        addProperty("id", tab.id)
                        addProperty("title", tab.title)
                        add("blocks", JsonArray().apply { tab.blocks.forEach { add(encodeBlock(it)) } })
                    })
                }
            }
        )
        add(
            "attachments",
            JsonArray().apply {
                d.attachments.forEach { a ->
                    add(JsonObject().apply {
                        addProperty("id", a.id)
                        addProperty("title", a.title)
                        addProperty("mediaType", a.mediaType)
                        addProperty("fileSizeBytes", a.fileSizeBytes)
                        addProperty("downloadUrl", a.downloadUrl)
                        addProperty("versionNumber", a.versionNumber)
                    })
                }
            }
        )
        add("blocks", JsonArray().apply { d.blocks.forEach { add(encodeBlock(it)) } })
    }

    private fun decodeDetail(el: JsonElement): ConfluencePageDetail? {
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = o.optStr("id") ?: return null
        val breadcrumbs = o.optArray("breadcrumbs")?.mapNotNull { bEl ->
            val bo = bEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val bid = bo.optStr("id") ?: return@mapNotNull null
            ConfluenceBreadcrumb(id = bid, title = bo.optStr("title").orEmpty())
        }.orEmpty()
        val childPages = o.optArray("childPages")?.mapNotNull { decodeSummary(it) }.orEmpty()
        val inlineTabs = o.optArray("inlineTabs")?.mapNotNull { tEl ->
            val to = tEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            ConfluenceInlineTab(
                id = to.optStr("id").orEmpty(),
                title = to.optStr("title").orEmpty(),
                blocks = to.optArray("blocks")?.mapNotNull { decodeBlock(it) }.orEmpty()
            )
        }.orEmpty()
        val attachments = o.optArray("attachments")?.mapNotNull { aEl ->
            val ao = aEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val aid = ao.optStr("id") ?: return@mapNotNull null
            ConfluenceAttachment(
                id = aid,
                title = ao.optStr("title").orEmpty(),
                mediaType = ao.optStr("mediaType") ?: "application/octet-stream",
                fileSizeBytes = ao.optLong("fileSizeBytes") ?: 0L,
                downloadUrl = ao.optStr("downloadUrl").orEmpty(),
                versionNumber = ao.optInt("versionNumber") ?: 0
            )
        }.orEmpty()
        val blocks = o.optArray("blocks")?.mapNotNull { decodeBlock(it) }.orEmpty()

        return ConfluencePageDetail(
            id = id,
            title = o.optStr("title").orEmpty(),
            spaceKey = o.optStr("spaceKey").orEmpty(),
            spaceName = o.optStr("spaceName").orEmpty(),
            versionNumber = o.optInt("versionNumber") ?: 1,
            updatedBy = o.optStr("updatedBy").orEmpty(),
            updatedLabel = o.optStr("updatedLabel").orEmpty(),
            webUrl = o.optStr("webUrl").orEmpty(),
            breadcrumbs = breadcrumbs,
            childPages = childPages,
            inlineTabs = inlineTabs,
            attachments = attachments,
            blocks = blocks,
            hasSubstantialBody = o.optBool("hasSubstantialBody") ?: true
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.optStr(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonNull || !e.isJsonPrimitive) null else e.asString
    }

    private fun JsonObject.optInt(key: String): Int? {
        val e = get(key) ?: return null
        return if (e.isJsonNull || !e.isJsonPrimitive) null else runCatching { e.asInt }.getOrNull()
    }

    private fun JsonObject.optLong(key: String): Long? {
        val e = get(key) ?: return null
        return if (e.isJsonNull || !e.isJsonPrimitive) null else runCatching { e.asLong }.getOrNull()
    }

    private fun JsonObject.optBool(key: String): Boolean? {
        val e = get(key) ?: return null
        return if (e.isJsonNull || !e.isJsonPrimitive) null else runCatching { e.asBoolean }.getOrNull()
    }

    private fun JsonObject.optObj(key: String): JsonObject? {
        val e = get(key) ?: return null
        return if (e.isJsonObject) e.asJsonObject else null
    }

    private fun JsonObject.optArray(key: String): JsonArray? {
        val e = get(key) ?: return null
        return if (e.isJsonArray) e.asJsonArray else null
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES: Long = 512L * 1024 * 1024
        private const val FREE_SPACE_RESERVE_BYTES: Long = 64L * 1024 * 1024
        const val DAILY_SYNC_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
        private const val MAX_CACHED_IMAGE_BYTES: Int = 2 * 1024 * 1024
    }
}
