-keep class com.helpdesk.jiraagent.model.** { *; }
-keep class com.helpdesk.jiraagent.data.Confluence* { *; }
-keep class com.helpdesk.jiraagent.data.ConfluenceBlock$* { *; }
-keep class com.helpdesk.jiraagent.domain.TicketStopwatchEntry { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**

