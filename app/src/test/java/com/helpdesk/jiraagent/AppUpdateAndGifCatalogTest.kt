package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.ui.components.SysadminGifCatalog
import com.helpdesk.jiraagent.update.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdateAndGifCatalogTest {

    @Test
    fun `semantic version comparator handles v prefix and multi-digit segments`() {
        assertTrue(AppUpdateManager.compareSemanticVersions("v1.0.13", "1.0.12") > 0)
        assertEquals(0, AppUpdateManager.compareSemanticVersions("v1.0.12", "1.0.12"))
        assertTrue(AppUpdateManager.compareSemanticVersions("1.0.9", "1.0.12") < 0)
        assertTrue(AppUpdateManager.compareSemanticVersions("1.1.0", "1.0.99") > 0)
    }

    @Test
    fun `findNewestUpdateFromJson detects newer prerelease from GitHub releases API`() {
        val sampleReleasesJson = """
            [
              {
                "tag_name": "v1.0.13",
                "name": "Help-CSL v1.0.13",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/zigorminsk-debug/Helpdesk/releases/tag/v1.0.13",
                "body": "Version: 1.0.13 (versionCode=1013)\nSHA-256: `fa3eb2e4c835a4ecd258a6696309c13b3bacd2b0e657f087ed280c778118a7ae`",
                "assets": [
                  {
                    "name": "help-csl-latest.apk",
                    "size": 15000000,
                    "browser_download_url": "https://github.com/zigorminsk-debug/Helpdesk/releases/download/v1.0.13/help-csl-latest.apk"
                  },
                  {
                    "name": "help-csl-v1.0.13.apk",
                    "size": 15000000,
                    "browser_download_url": "https://github.com/zigorminsk-debug/Helpdesk/releases/download/v1.0.13/help-csl-v1.0.13.apk"
                  }
                ]
              },
              {
                "tag_name": "v1.0.12",
                "name": "Help-CSL v1.0.12",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/zigorminsk-debug/Helpdesk/releases/tag/v1.0.12",
                "body": "Version: 1.0.12 (versionCode=1012)",
                "assets": [
                  {
                    "name": "help-csl-v1.0.12.apk",
                    "size": 14000000,
                    "browser_download_url": "https://github.com/zigorminsk-debug/Helpdesk/releases/download/v1.0.12/help-csl-v1.0.12.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val found = AppUpdateManager.findNewestUpdateFromJson(
            releasesJson = sampleReleasesJson,
            currentVersionName = "1.0.12",
            currentVersionCode = 1012
        )
        assertNotNull(found)
        assertEquals("1.0.13", found!!.versionName)
        assertEquals(1013, found.versionCode)
        assertEquals("help-csl-v1.0.13.apk", found.apkFileName)
        assertEquals(
            "fa3eb2e4c835a4ecd258a6696309c13b3bacd2b0e657f087ed280c778118a7ae",
            found.expectedSha256
        )

        val upToDate = AppUpdateManager.findNewestUpdateFromJson(
            releasesJson = sampleReleasesJson,
            currentVersionName = "1.0.13",
            currentVersionCode = 1013
        )
        assertNull(upToDate)
    }

    @Test
    fun `all 10 sysadmin empty-screen GIF assets exist and are valid animated GIF89a`() {
        val items = SysadminGifCatalog.items
        assertEquals(10, items.size)

        val assetsRoot = listOf(
            File("src/main/assets"),
            File("app/src/main/assets")
        ).firstOrNull { it.exists() }
        assertNotNull("Assets directory should exist", assetsRoot)

        items.forEachIndexed { idx, item ->
            assertEquals(idx + 1, item.number)
            val gifFile = File(assetsRoot!!, item.assetPath)
            assertTrue("Missing GIF asset: ${gifFile.path}", gifFile.exists())
            assertTrue("GIF asset too small: ${gifFile.path}", gifFile.length() > 10_000L)
            val header = ByteArray(6)
            gifFile.inputStream().use { it.read(header) }
            val magic = String(header, Charsets.US_ASCII)
            assertTrue("Expected GIF header in ${item.assetPath}, got $magic", magic == "GIF89a" || magic == "GIF87a")
        }
    }
}
