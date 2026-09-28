package com.claudecode.countdown

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks the latest GitHub release, downloads its APK and hands it to the system installer.
 * CI publishes every build as release "build-<N>", where N is also the app's versionCode.
 */
object Updater {

    private const val OWNER = "dstpnklen20-hue"
    private const val REPO = "countdown-widget"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val TAG_PREFIX = "build-"

    private const val PREFS = "updater_prefs"
    private const val KEY_DOWNLOADED_CODE = "downloaded_code"

    private class Release(val versionCode: Long, val apkUrl: String)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun apkFile(context: Context) = File(File(context.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    private fun fetchLatest(): Release? {
        val json = JSONObject(httpGet(LATEST_URL))
        val tag = json.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) return null
        val code = tag.removePrefix(TAG_PREFIX).toLongOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                return Release(code, asset.getString("browser_download_url"))
            }
        }
        return null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CountdownWidget")
        }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release, target: File) {
        val part = File(target.parentFile, "update.part")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("rename failed")
    }

    /**
     * Looks for a newer release. If there is one it is downloaded in the background and
     * the user is asked to install it. [manual] adds toasts for "up to date" and errors.
     */
    fun checkForUpdates(activity: AppCompatActivity, manual: Boolean, onStatus: (String) -> Unit = {}) {
        if (busy) return
        val app = activity.applicationContext
        // Local builds have versionCode 1; auto-updating them would replace them with the CI build.
        if (!manual && currentVersionCode(app) <= 1L) return
        busy = true
        onStatus(activity.getString(R.string.update_checking))

        executor.execute {
            try {
                val current = currentVersionCode(app)
                val file = apkFile(app)
                val downloaded = prefs(app).getLong(KEY_DOWNLOADED_CODE, 0L)
                if (downloaded <= current) file.delete()

                val release = fetchLatest()
                if (release == null || release.versionCode <= current) {
                    post(activity) {
                        onStatus(activity.getString(R.string.update_up_to_date))
                        if (manual) toast(activity, R.string.update_up_to_date)
                    }
                    return@execute
                }

                if (!(file.exists() && downloaded == release.versionCode)) {
                    post(activity) { onStatus(activity.getString(R.string.update_downloading, release.versionCode)) }
                    download(release, file)
                    prefs(app).edit().putLong(KEY_DOWNLOADED_CODE, release.versionCode).apply()
                }
                post(activity) {
                    onStatus(activity.getString(R.string.update_ready, release.versionCode))
                    promptInstall(activity, release.versionCode, file)
                }
            } catch (e: Exception) {
                post(activity) {
                    onStatus(activity.getString(R.string.update_error))
                    if (manual) toast(activity, R.string.update_error)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun post(activity: AppCompatActivity, block: () -> Unit) {
        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) block()
        }
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private fun promptInstall(activity: AppCompatActivity, versionCode: Long, file: File) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_message, versionCode))
            .setPositiveButton(R.string.update_install) { _, _ -> install(activity, file) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun install(context: Context, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            toast(context, R.string.update_allow_installs)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        }
    }
}
