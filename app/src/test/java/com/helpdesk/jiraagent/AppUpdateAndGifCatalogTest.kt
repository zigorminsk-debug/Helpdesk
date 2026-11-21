package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.HabrRssParser
import com.helpdesk.jiraagent.ui.components.SysadminGifCatalog
import com.helpdesk.jiraagent.update.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

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
    fun `selected sysadmin empty-screen GIF assets 1 and 7 exist and are valid animated GIF89a`() {
        val items = SysadminGifCatalog.items
        assertEquals(2, items.size)
        assertEquals("admin_gifs/admin_gif_01.gif", items[0].assetPath)
        assertEquals("admin_gifs/admin_gif_07.gif", items[1].assetPath)

        val assetsRoot = listOf(
            File("src/main/assets"),
            File("app/src/main/assets")
        ).firstOrNull { it.exists() }
        assertNotNull("Assets directory should exist", assetsRoot)

        items.forEach { item ->
            assertTrue("Quote should not be blank", item.quote.isNotBlank())
            val gifFile = File(assetsRoot!!, item.assetPath)
            assertTrue("Missing GIF asset: ${gifFile.path}", gifFile.exists())
            assertTrue("GIF asset too small: ${gifFile.path}", gifFile.length() > 100_000L)
            val header = ByteArray(6)
            gifFile.inputStream().use { it.read(header) }
            val magic = String(header, Charsets.US_ASCII)
            assertTrue("Expected GIF header in ${item.assetPath}, got $magic", magic == "GIF89a" || magic == "GIF87a")
        }
    }

    @Test
    fun `habr rss parser extracts clean Russian titles urls tags and strips html`() {
        val sampleRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title><![CDATA[Все публикации подряд на Хабре]]></title>
                <item>
                  <guid isPermaLink="true">https://habr.com/ru/articles/1110676/</guid>
                  <pubDate>Sat, 21 Nov 2026 13:02:16 GMT</pubDate>
                  <title><![CDATA[Эволюция NFQWS2 ASUS Community (+&nbsp;Keenetic)]]></title>
                  <description><![CDATA[<img src="https://habrastorage.org/test.png"/><p>История адаптации &laquo;nfqws2&raquo; &mdash; для роутеров ASUS и OpenWrt.</p> <a href="https://habr.com/ru/articles/1110676/?utm_source=habrahabr#habracut">Читать далее</a>]]></description>
                  <link>https://habr.com/ru/articles/1110676/?utm_source=habrahabr&amp;utm_medium=rss&amp;utm_campaign=1110676#habracut</link>
                  <dc:creator>Profy13</dc:creator>
                  <category><![CDATA[Сетевое оборудование]]></category>
                  <category><![CDATA[Системное администрирование]]></category>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = HabrRssParser.parse(
            rssXml = sampleRss,
            maxItems = 10,
            nowDateMinsk = LocalDate.of(2026, 11, 21)
        )
        assertEquals(1, items.size)
        val first = items.first()
        assertEquals("Эволюция NFQWS2 ASUS Community (+ Keenetic)", first.title)
        assertEquals("https://habr.com/ru/articles/1110676/", first.url)
        assertEquals("История адаптации «nfqws2» — для роутеров ASUS и OpenWrt.", first.summary)
        assertFalse(first.summary.contains("Читать далее"))
        assertEquals("Profy13", first.author)
        assertEquals("Сегодня, 16:02", first.publishedLabel)
        assertEquals(listOf("Сетевое оборудование", "Системное администрирование"), first.tags)
    }

    @Test
    fun `launcher mipmap icon resources exist across all densities`() {
        val resRoot = listOf(
            File("src/main/res"),
            File("app/src/main/res")
        ).firstOrNull { it.exists() }
        assertNotNull("res directory should exist", resRoot)

        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val files = listOf("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png")
        for (d in densities) {
            for (f in files) {
                val iconFile = File(resRoot!!, "mipmap-$d/$f")
                assertTrue("Missing launcher icon ${iconFile.path}", iconFile.exists() && iconFile.length() > 1000L)
            }
        }
    }
}
