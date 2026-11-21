package com.helpdesk.jiraagent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class GitHubReleaseUpdate(
    val tagName: String,
    val versionName: String,
    val versionCode: Int?,
    val releaseTitle: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?
)

enum class InstallLaunchResult {
    LAUNCHED,
    NEEDS_INSTALL_PERMISSION,
    ERROR
}

object AppUpdateManager {

    const val GITHUB_OWNER = "zigorminsk-debug"
    const val GITHUB_REPO = "Helpdesk"

    /**
     * Primary source: GitHub web Atom feed (`github.com`, NOT `api.github.com`).
     * Includes all releases & prereleases, SHA-256, and versionCode without the
     * 60 req/hr unauthenticated IP rate limit that causes HTTP 403 on CGNAT mobile networks.
     */
    private const val RELEASES_ATOM_URL =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases.atom"
    private const val RELEASES_HTML_URL =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=10"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val SHA256_REGEX = Regex("""SHA-256[^\r\n]{0,60}?\b([0-9a-fA-F]{64})\b""", RegexOption.IGNORE_CASE)
    private val ATOM_ENTRY_REGEX = Regex("""<entry\b[^>]*>(.*?)</entry>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ATOM_TAG_LINK_REGEX = Regex("""href\s*=\s*["']([^"']*/releases/tag/([^"'/?#]+))["']""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_LINK_REGEX = Regex("""/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/(v?\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)
    private val HTML_TAGS_STRIP_REGEX = Regex("""<[^>]+>""")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun checkForUpdate(
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE
    ): Result<GitHubReleaseUpdate?> = withContext(Dispatchers.IO) {
        // 1. Primary: GitHub releases.atom feed (no api.github.com 60/hr IP rate limit)
        val atomAttempt = runCatching {
            val atomXml = fetchUrlString(
                url = RELEASES_ATOM_URL,
                accept = "application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8"
            )
            if (!atomXml.contains("<feed", ignoreCase = true)) {
                throw IllegalStateException("Некорректный формат ленты релизов GitHub")
            }
            findNewestUpdateFromAtom(
                atomXml = atomXml,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode
            )
        }
        if (atomAttempt.isSuccess) {
            return@withContext atomAttempt
        }

        // 2. Secondary fallback: GitHub releases HTML page (also free of api.github.com 403 rate limits)
        val htmlAttempt = runCatching {
            val html = fetchUrlString(
                url = RELEASES_HTML_URL,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            findNewestUpdateFromReleasesHtml(
                releasesHtml = html,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode
            )
        }
        if (htmlAttempt.isSuccess) {
            return@withContext htmlAttempt
        }

        // 3. Tertiary fallback: GitHub REST API
        val apiAttempt = runCatching {
            val json = fetchUrlString(
                url = RELEASES_API_URL,
                accept = "application/vnd.github+json"
            )
            findNewestUpdateFromJson(
                releasesJson = json,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode
            )
        }
        if (apiAttempt.isSuccess) {
            return@withContext apiAttempt
        }

        Result.failure(
            atomAttempt.exceptionOrNull()
                ?: htmlAttempt.exceptionOrNull()
                ?: apiAttempt.exceptionOrNull()
                ?: IllegalStateException("Не удалось проверить релизы на GitHub")
        )
    }

    private fun fetchUrlString(url: String, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub вернул код ${response.code}")
            }
            return bodyStr
        }
    }

    /**
     * Parses `https://github.com/zigorminsk-debug/Helpdesk/releases.atom`.
     */
    fun findNewestUpdateFromAtom(
        atomXml: String,
        currentVersionName: String,
        currentVersionCode: Int
    ): GitHubReleaseUpdate? {
        if (atomXml.isBlank()) return null

        val candidates = mutableListOf<GitHubReleaseUpdate>()
        for (entryMatch in ATOM_ENTRY_REGEX.findAll(atomXml)) {
            val entryXml = entryMatch.groupValues[1]
            val linkMatch = ATOM_TAG_LINK_REGEX.find(entryXml) ?: continue
            val htmlUrl = decodeXmlEntities(linkMatch.groupValues[1]).trim()
            val tagName = decodeXmlEntities(linkMatch.groupValues[2]).trim()
            val cleanVersion = normalizeVersionString(tagName)
            if (cleanVersion.isEmpty()) continue

            val rawTitle = extractXmlTag(entryXml, "title").orEmpty()
            val releaseTitle = decodeXmlEntities(rawTitle).trim().ifBlank { "Help-CSL v$cleanVersion" }

            val rawContent = extractXmlTag(entryXml, "content").orEmpty()
            val decodedHtmlContent = decodeXmlEntities(rawContent)
            val plainNotes = decodedHtmlContent
                .replace(Regex("</?(li|p|br|ul|div)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(HTML_TAGS_STRIP_REGEX, "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")

            val parsedVersionCode = VERSION_CODE_REGEX.find(decodedHtmlContent)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val parsedSha256 = SHA256_REGEX.find(decodedHtmlContent)?.groupValues?.getOrNull(1)?.lowercase()

            val apkFileName = "help-csl-v$cleanVersion.apk"
            val apkDownloadUrl =
                "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$apkFileName"

            val candidate = GitHubReleaseUpdate(
                tagName = tagName,
                versionName = cleanVersion,
                versionCode = parsedVersionCode,
                releaseTitle = releaseTitle,
                releaseNotes = plainNotes,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkDownloadUrl,
                apkFileName = apkFileName,
                apkSizeBytes = 0L,
                expectedSha256 = parsedSha256
            )

            if (isCandidateNewer(candidate, currentVersionName, currentVersionCode)) {
                candidates.add(candidate)
            }
        }

        return candidates.maxWithOrNull { a, b -> compareReleases(a, b) }
    }

    /**
     * Fallback parser for `https://github.com/zigorminsk-debug/Helpdesk/releases` HTML.
     */
    fun findNewestUpdateFromReleasesHtml(
        releasesHtml: String,
        currentVersionName: String,
        currentVersionCode: Int
    ): GitHubReleaseUpdate? {
        if (releasesHtml.isBlank()) return null

        val seenTags = HashSet<String>()
        val candidates = mutableListOf<GitHubReleaseUpdate>()

        for (m in HTML_TAG_LINK_REGEX.findAll(releasesHtml)) {
            val tagName = m.groupValues[1].trim()
            if (!seenTags.add(tagName)) continue
            val cleanVersion = normalizeVersionString(tagName)
            if (cleanVersion.isEmpty()) continue

            val apkFileName = "help-csl-v$cleanVersion.apk"
            val candidate = GitHubReleaseUpdate(
                tagName = tagName,
                versionName = cleanVersion,
                versionCode = null,
                releaseTitle = "Help-CSL v$cleanVersion",
                releaseNotes = "",
                htmlUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tagName",
                apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$apkFileName",
                apkFileName = apkFileName,
                apkSizeBytes = 0L,
                expectedSha256 = null
            )

            if (isCandidateNewer(candidate, currentVersionName, currentVersionCode)) {
                candidates.add(candidate)
            }
        }

        return candidates.maxWithOrNull { a, b -> compareReleases(a, b) }
    }

    /**
     * Pure JSON parser & version comparator (testable on JVM unit tests).
     */
    fun findNewestUpdateFromJson(
        releasesJson: String,
        currentVersionName: String,
        currentVersionCode: Int
    ): GitHubReleaseUpdate? {
        val parsed = runCatching { JsonParser.parseString(releasesJson) }.getOrNull() ?: return null
        if (!parsed.isJsonArray) return null

        val candidates = mutableListOf<GitHubReleaseUpdate>()
        for (el in parsed.asJsonArray) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            if (obj.optBoolean("draft", false)) continue

            val tagName = obj.optString("tag_name").trim()
            if (tagName.isEmpty()) continue
            val cleanVersion = normalizeVersionString(tagName)
            if (cleanVersion.isEmpty()) continue

            val assets = obj.getAsJsonArray("assets") ?: continue
            var chosenAsset: JsonObject? = null
            for (assetEl in assets) {
                if (!assetEl.isJsonObject) continue
                val assetObj = assetEl.asJsonObject
                val assetName = assetObj.optString("name")
                val downloadUrl = assetObj.optString("browser_download_url")
                if (!assetName.endsWith(".apk", ignoreCase = true) || downloadUrl.isBlank()) continue
                if (chosenAsset == null || !assetName.contains("latest", ignoreCase = true)) {
                    chosenAsset = assetObj
                    if (!assetName.contains("latest", ignoreCase = true)) break
                }
            }
            val apkAsset = chosenAsset ?: continue

            val body = obj.optString("body")
            val parsedVersionCode = VERSION_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val parsedSha256 = SHA256_REGEX.find(body)?.groupValues?.getOrNull(1)?.lowercase()

            val candidate = GitHubReleaseUpdate(
                tagName = tagName,
                versionName = cleanVersion,
                versionCode = parsedVersionCode,
                releaseTitle = obj.optString("name").ifBlank { "Help-CSL v$cleanVersion" },
                releaseNotes = body,
                htmlUrl = obj.optString("html_url"),
                apkDownloadUrl = apkAsset.optString("browser_download_url"),
                apkFileName = apkAsset.optString("name").ifBlank { "help-csl-v$cleanVersion.apk" },
                apkSizeBytes = apkAsset.optLong("size", 0L),
                expectedSha256 = parsedSha256
            )

            if (isCandidateNewer(candidate, currentVersionName, currentVersionCode)) {
                candidates.add(candidate)
            }
        }

        return candidates.maxWithOrNull { a, b -> compareReleases(a, b) }
    }

    private fun extractXmlTag(xml: String, tagName: String): String? {
        val escaped = Regex.escape(tagName)
        val r = Regex("<$escaped(?:\\s[^>]*)?>(.*?)</$escaped>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return r.find(xml)?.groupValues?.get(1)
    }

    private fun decodeXmlEntities(raw: String): String {
        return raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    fun isCandidateNewer(
        candidate: GitHubReleaseUpdate,
        currentVersionName: String,
        currentVersionCode: Int
    ): Boolean {
        val semCmp = compareSemanticVersions(candidate.versionName, currentVersionName)
        if (semCmp != 0) return semCmp > 0
        val candCode = candidate.versionCode
        if (candCode != null && currentVersionCode > 0) {
            return candCode > currentVersionCode
        }
        return false
    }

    fun compareSemanticVersions(v1: String, v2: String): Int {
        val p1 = parseSemanticParts(v1)
        val p2 = parseSemanticParts(v2)
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val a = p1.getOrElse(i) { 0 }
            val b = p2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun parseSemanticParts(raw: String): List<Int> {
        val normalized = normalizeVersionString(raw)
        if (normalized.isEmpty()) return emptyList()
        return Regex("""\d+""").findAll(normalized)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    private fun normalizeVersionString(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    private fun compareReleases(a: GitHubReleaseUpdate, b: GitHubReleaseUpdate): Int {
        val sem = compareSemanticVersions(a.versionName, b.versionName)
        if (sem != 0) return sem
        return (a.versionCode ?: 0).compareTo(b.versionCode ?: 0)
    }

    suspend fun downloadUpdateApk(
        context: Context,
        update: GitHubReleaseUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = "help-csl-v${update.versionName}.apk"
            val targetFile = File(updatesDir, safeName)
            val tempFile = File(updatesDir, "$safeName.part")

            // Clean older cached APKs
            updatesDir.listFiles()?.forEach { f ->
                if (f.name != safeName) runCatching { f.delete() }
            }

            // Reuse already downloaded & verified APK if present
            if (targetFile.exists() && targetFile.length() > 100_000L && isValidApkFile(targetFile, update.expectedSha256)) {
                onProgress(targetFile.length(), targetFile.length())
                return@runCatching targetFile
            }

            val request = Request.Builder()
                .url(update.apkDownloadUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Ошибка скачивания APK: HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Пустой ответ при скачивании APK")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: update.apkSizeBytes

                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val expectedSha = update.expectedSha256
                if (!expectedSha.isNullOrBlank() && !actualSha256.equals(expectedSha, ignoreCase = true)) {
                    tempFile.delete()
                    throw IllegalStateException("Контрольная сумма SHA-256 скачанного APK не совпала")
                }

                if (!hasZipMagicHeader(tempFile)) {
                    tempFile.delete()
                    throw IllegalStateException("Скачанный файл повреждён (не является APK)")
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                targetFile
            }
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun launchApkInstaller(context: Context, apkFile: File): InstallLaunchResult {
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            return InstallLaunchResult.NEEDS_INSTALL_PERMISSION
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallLaunchResult.LAUNCHED
        }.getOrElse {
            InstallLaunchResult.ERROR
        }
    }

    private fun isValidApkFile(file: File, expectedSha256: String?): Boolean {
        if (!hasZipMagicHeader(file)) return false
        if (expectedSha256.isNullOrBlank()) return true
        val sha = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    digest.update(buf, 0, r)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return false
        return sha.equals(expectedSha256, ignoreCase = true)
    }

    private fun hasZipMagicHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return runCatching {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }.getOrDefault(false)
    }

    private fun JsonObject.optString(key: String): String {
        val el: JsonElement = get(key) ?: return ""
        return if (el.isJsonPrimitive) el.asString else ""
    }

    private fun JsonObject.optBoolean(key: String, default: Boolean): Boolean {
        val el: JsonElement = get(key) ?: return default
        return if (el.isJsonPrimitive) runCatching { el.asBoolean }.getOrDefault(default) else default
    }

    private fun JsonObject.optLong(key: String, default: Long): Long {
        val el: JsonElement = get(key) ?: return default
        return if (el.isJsonPrimitive) runCatching { el.asLong }.getOrDefault(default) else default
    }
}
