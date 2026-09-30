package com.claudecode.countdown.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.claudecode.countdown.QuickAddActivity
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** One-tap bar that opens the quick-add sheet over the home screen. */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val theme = context.container.widgetTheme
        provideContent {
            // Read inside the session: a live session only recomposes on update().
            val themeVersion by theme.collectAsState()
            val p = remember(themeVersion) { ThemeManager.widgetPalette(context) }
            Row(
                GlanceModifier
                    .fillMaxSize()
                    .background(Color(p.surface))
                    .cornerRadius(28.dp)
                    .padding(horizontal = 16.dp)
                    .clickable(actionStartActivity(QuickAddActivity.intent(context))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", style = TextStyle(color = ColorProvider(Color(p.accent)), fontSize = 24.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(10.dp))
                Text("Добавить задачу", style = TextStyle(color = ColorProvider(Color(p.textSecondary)), fontSize = 15.sp))
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = QuickAddWidget().updateAll(context)
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuickAddWidget()
}
