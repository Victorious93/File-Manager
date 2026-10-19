package ai.victorious.victorfiles.widget

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.MainActivity
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Home-screen widget (Glance, Apache-2.0) listing the user's bookmarked folders as quick-launch
 * shortcuts. Tapping one opens the app at that folder. No secrets; just paths the user bookmarked.
 */
class BookmarksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FileManagerApplication
        val bookmarks = runCatching { app.bookmarks.list() }.getOrDefault(emptyList())
            .take(MAX_ITEMS)
            .map { it.label to it.path }
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(16.dp)
                        .padding(12.dp),
                ) {
                    Text(
                        "Quick folders",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        modifier = GlanceModifier.padding(bottom = 6.dp)
                            .clickable(actionStartActivity(openIntent(context, null))),
                    )
                    if (bookmarks.isEmpty()) {
                        Text(
                            "No bookmarks yet — star a folder in VictorFiles.",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                        )
                    } else {
                        LazyColumn(GlanceModifier.fillMaxSize()) {
                            items(bookmarks) { (label, path) ->
                                Text(
                                    "📁  $label",
                                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                                    modifier = GlanceModifier.fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clickable(actionStartActivity(openIntent(context, path))),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openIntent(context: Context, path: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (path != null) putExtra(MainActivity.EXTRA_OPEN_PATH, path)
        }

    companion object { private const val MAX_ITEMS = 12 }
}

class BookmarksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BookmarksWidget()
}
