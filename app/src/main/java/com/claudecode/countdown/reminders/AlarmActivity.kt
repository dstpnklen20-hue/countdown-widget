package com.claudecode.countdown.reminders

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.ui.TikTakTheme
import com.claudecode.countdown.ui.rememberNow
import com.claudecode.countdown.ui.formatTime
import kotlinx.coroutines.launch

/**
 * An event reminder set to ring as an alarm, full screen and over the lock screen. The sound
 * comes from its notification (repeating until answered); both buttons take it down.
 */
class AlarmActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_TASK_ID = "task_id"

        fun intent(context: Context, taskId: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return finish()
        val palette = ThemeManager.palette(this)

        fun close(snoozeMinutes: Int?) {
            ReminderNotifier.cancel(this, taskId)
            if (snoozeMinutes != null) {
                val repo = container.tasks
                container.appScope.launch { repo.snooze(taskId, snoozeMinutes) }
            }
            finish()
        }

        setContent {
            TikTakTheme(palette) {
                val task by produceState<Task?>(null) { value = container.tasks.get(taskId) }
                val now by rememberNow(1_000)
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.Alarm, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(formatTime(now), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(24.dp))
                    Text(task?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    task?.let { t ->
                        ReminderNotifier.whenText(t)?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                    Button(onClick = { close(null) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выключить") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { close(10) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Отложить на 10 минут") }
                }
            }
        }
    }
}
