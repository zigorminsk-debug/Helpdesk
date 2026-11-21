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
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=10"

    private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val SHA256_REGEX = Regex("""SHA-256[^0-9a-fA-F]*([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)

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
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Help-CSL-Android/$currentVersionName")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub API вернул код ${response.code}")
                }
                findNewestUpdateFromJson(
                    releasesJson = bodyStr,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode
                )
            }
        }
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
                .header("User-Agent", "Help-CSL-Android/${BuildConfig.VERSION_NAME}")
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
