package com.claudecode.countdown.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.Updater
import com.claudecode.countdown.container
import com.claudecode.countdown.data.sync.Backup
import com.claudecode.countdown.data.sync.SyncException
import com.claudecode.countdown.ui.LocalSnackbarHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Account state, a manual "sync now" and sign-out; signed out, an invitation to sign in. */
@Composable
internal fun SyncSettings() {
    val context = LocalContext.current
    val sync = remember { context.container.sync }
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var signIn by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val divider = @Composable { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant) }

    val email = status.email
    if (email == null) {
        SettingRow(
            "Войти в аккаунт",
            status.error ?: "Задачи, списки и привычки станут одинаковыми на всех ваших устройствах",
            onClick = { signIn = true },
        )
    } else {
        SettingRow("Аккаунт", email)
        divider()
        SettingRow(
            "Синхронизировать сейчас",
            when {
                status.running -> "Идёт синхронизация…"
                status.error != null -> status.error
                status.lastSyncAt != null -> "Последняя: " + formatMoment(status.lastSyncAt!!)
                else -> "Ещё не было"
            },
            onClick = if (status.running) null else ({ scope.launch { sync.syncNow() } }),
        )
        if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        divider()
        SettingRow("Выйти", "Данные на этом устройстве останутся", onClick = { confirmSignOut = true })
    }

    if (signIn) SignInDialog(onDismiss = { signIn = false })
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Синхронизация остановится. Задачи на этом устройстве останутся, а в аккаунте сохранится их последняя копия.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { sync.signOut() } }) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Отмена") } },
        )
    }
}

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))
private val dayAndTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru"))

private fun formatMoment(millis: Long): String {
    val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (at.toLocalDate() == LocalDate.now()) "сегодня в " + at.format(timeOnly) else at.format(dayAndTime)
}

@Composable
private fun SignInDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { context.container.sync }
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    // Not saveable: the password should not end up in the saved screen state.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val ready = email.contains('@') && password.length >= 6 && !busy

    fun run(action: suspend () -> String?) {
        busy = true
        message = null
        scope.launch {
            try {
                val info = action()
                if (info == null) onDismiss() else message = info
            } catch (e: SyncException) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Вход в аккаунт") },
        text = {
            Column {
                Text(
                    "Один аккаунт на всех ваших устройствах. Если аккаунта ещё нет — введите почту, придумайте пароль и нажмите «Создать аккаунт».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("Почта") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    supportingText = { Text("Не меньше 6 символов") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                }
                TextButton(
                    onClick = {
                        run {
                            if (sync.signUp(email, password)) null
                            else "Мы отправили письмо на $email. Откройте его, подтвердите почту и нажмите «Войти»."
                        }
                    },
                    enabled = ready,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Создать аккаунт") }
            }
        },
        confirmButton = {
            TextButton(onClick = { run { sync.signIn(email, password); null } }, enabled = ready) { Text("Войти") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

/** Saving all data to a file and merging a file back in. */
@Composable
internal fun BackupSettings() {
    val context = LocalContext.current
    val container = remember { context.container }
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var toRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = Backup.export(container.rows, Updater.currentVersionName(context))
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }
            snackbar.showSnackbar(if (result.isSuccess) "Резервная копия сохранена" else "Не удалось сохранить файл")
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> toRestore = uri }

    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    SettingRow("Сохранить резервную копию", "Все задачи, списки, привычки и фокус — в один файл", onClick = {
        save.launch("tiktak-" + LocalDate.now() + ".json")
    })
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    SettingRow("Восстановить из копии", "Добавит записи из файла к текущим", onClick = {
        open.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
    })

    toRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { toRestore = null },
            title = { Text("Восстановить из копии?") },
            text = {
                Text(
                    "Записи из файла добавятся к текущим. Если запись есть и там, и здесь, останется более новая её версия — " +
                        "ничего сделанное после копии не пропадёт."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toRestore = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                                Backup.import(container.rows, text)
                            }
                        }
                        result.onSuccess { container.onDataChanged() }
                        snackbar.showSnackbar(
                            result.fold(
                                onSuccess = { if (it == 0) "Всё уже на месте: новых записей нет" else "Восстановлено записей: $it" },
                                onFailure = { (it as? Backup.NotABackup)?.message ?: "Не удалось прочитать файл" },
                            )
                        )
                    }
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { toRestore = null }) { Text("Отмена") } },
        )
    }
}
