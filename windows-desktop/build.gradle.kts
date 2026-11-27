import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

val appVersionCode = (project.findProperty("versionCode")?.toString()?.toIntOrNull()) ?: 1028
val rawVersionName = (project.findProperty("versionName")?.toString()?.takeIf { it.isNotBlank() }) ?: "1.0.28"
// Windows jpackage requires X.Y.Z where X >= 1
val cleanWindowsVersion = Regex("""(\d+)\.(\d+)\.(\d+)""")
    .find(rawVersionName)
    ?.value
    ?: "1.0.28"

group = "com.helpdesk.desktop"
version = cleanWindowsVersion

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.setSrcDirs(
                listOf(
                    file("src/main/kotlin"),
                    file("../app/src/main/java")
                )
            )
            kotlin.include(
                "com/helpdesk/desktop/**",
                "com/helpdesk/jiraagent/model/**",
                "com/helpdesk/jiraagent/domain/**",
                "com/helpdesk/jiraagent/data/JiraApiClient.kt",
                "com/helpdesk/jiraagent/data/JiraJsonParser.kt",
                "com/helpdesk/jiraagent/data/HabrNewsRepository.kt",
                "com/helpdesk/jiraagent/data/ConfluenceWikiClient.kt",
                "com/helpdesk/jiraagent/data/ConfluenceOfflineStore.kt",
                "com/helpdesk/jiraagent/data/ZabbixApiClient.kt"
            )
            kotlin.exclude(
                "**/SettingsTransferCodec.kt"
            )
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("dnsjava:dnsjava:3.5.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.helpdesk.desktop.MainKt"

        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dhelpcsl.version=$rawVersionName",
            "-Dhelpcsl.versionCode=$appVersionCode"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Help-CSL"
            packageVersion = cleanWindowsVersion
            vendor = "CSL Support"
            description = "Help-CSL Jira Helpdesk Agent for Windows"
            copyright = "Copyright 2026 CSL Support"
            includeAllModules = true

            windows {
                iconFile.set(project.file("src/main/resources/help-csl.ico"))
            }
        }
    }
}
