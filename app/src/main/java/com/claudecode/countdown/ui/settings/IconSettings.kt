package com.claudecode.countdown.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.AppIcon
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.data.Tool
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.claudecode.countdown.AppIconStyle
import com.claudecode.countdown.container
import com.claudecode.countdown.ui.AppLogo

/** Launcher icon colours, shown as the icons themselves; the chosen one is outlined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconSettings() {
    val context = LocalContext.current
    val current = remember { AppIcon.current(context) }
    var asked by remember { mutableStateOf<AppIconStyle?>(null) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.padding(16.dp)) {
        Text(
            "Цвет значка приложения на главном экране и в списке приложений.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (style in AppIconStyle.entries) {
                val selected = style == current
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { if (style != current) asked = style }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .border(if (selected) 3.dp else 0.dp, if (selected) scheme.primary else Color.Transparent, RoundedCornerShape(22.dp))
                            .padding(4.dp),
                    ) { AppLogo(style, Modifier.size(56.dp)) }
                    Text(
                        style.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(64.dp).padding(top = 4.dp),
                    )
                }
            }
        }
    }

    asked?.let { style ->
        AlertDialog(
            onDismissRequest = { asked = null },
            icon = { AppLogo(style, Modifier.size(48.dp)) },
            title = { Text("Значок «${style.label}»?") },
            text = {
                Text(
                    "Приложение закроется и сразу откроется заново — так Android меняет значок. " +
                        "Если значок пропадёт с главного экрана, добавьте его снова из списка приложений."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asked = null
                    switchIcon(context, style)
                }) { Text("Сменить") }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text("Отмена") } },
        )
    }
}

/**
 * Android closes the screens that were opened through the icon being switched off, so the app is
 * reopened right away through the new one, on Settings where the user was.
 */
private fun switchIcon(context: Context, style: AppIconStyle) {
    AppIcon.apply(context, style)
    // Widgets open the app through the launcher component: point them at the new one.
    context.container.refreshAllWidgets()
    Toast.makeText(context, "Значок изменён", Toast.LENGTH_SHORT).show()
    context.startActivity(
        MainActivity.launchIntent(context)
            .putExtra(MainActivity.EXTRA_OPEN_TOOL, Tool.SETTINGS.name)
            // A task of its own: the old task, opened through the icon now switched off, is removed
            // by the system as a whole, and would take the new screen with it.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    )
}
