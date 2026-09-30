package com.claudecode.countdown.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The Supabase project behind sync. The publishable key is meant to ship inside apps: row-level
 * security on the server lets each account reach only its own records.
 */
object SupabaseConfig {
    const val URL = "https://bjwpdcenqeckobvtpvwy.supabase.co"
    const val KEY = "sb_publishable_4KRzQLfSBYfhhr7CjLKdrw_dTrx72js"
    const val TABLE = "sync_records"
}

data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds. */
    val expiresAt: Long,
    val userId: String,
    val email: String,
)

/** A failure with a message ready to show to the user. */
class SyncException(message: String, val code: Int = 0, val authLost: Boolean = false) : Exception(message)

private class HttpResult(val code: Int, val body: String)

private suspend fun http(
    method: String,
    url: String,
    token: String?,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): HttpResult = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("apikey", SupabaseConfig.KEY)
        conn.setRequestProperty("Authorization", "Bearer ${token ?: SupabaseConfig.KEY}")
        conn.setRequestProperty("Accept", "application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
    } catch (e: IOException) {
        throw SyncException("Нет связи с сервером. Проверьте интернет.")
    } finally {
        conn.disconnect()
    }
}

/** Turns Supabase's error answers into Russian messages. */
private fun failure(result: HttpResult): SyncException {
    val json = runCatching { JSONObject(result.body) }.getOrNull()
    val code = json?.optString("error_code").orEmpty().ifEmpty { json?.optString("error").orEmpty() }
    val raw = json?.optString("msg")?.takeIf { it.isNotEmpty() }
        ?: json?.optString("message")?.takeIf { it.isNotEmpty() }
        ?: json?.optString("error_description")?.takeIf { it.isNotEmpty() }
        ?: "HTTP ${result.code}"
    val message = when {
        code == "invalid_credentials" || raw.contains("Invalid login credentials") -> "Неверная почта или пароль."
        code == "email_not_confirmed" -> "Почта не подтверждена: откройте письмо от Supabase и нажмите ссылку, затем войдите."
        code == "user_already_exists" || raw.contains("already registered") -> "Такой аккаунт уже есть — нажмите «Войти»."
        code == "weak_password" || raw.contains("Password should be") -> "Пароль слишком простой: нужно не меньше 6 символов."
        code == "email_address_invalid" || raw.contains("invalid", ignoreCase = true) && raw.contains("email", ignoreCase = true) ->
            "Похоже, в адресе почты ошибка."
        code.contains("rate_limit") -> "Слишком много попыток. Подождите немного и попробуйте снова."
        code == "signup_disabled" -> "Регистрация новых аккаунтов отключена в настройках Supabase."
        result.code == 404 && raw.contains(SupabaseConfig.TABLE) ->
            "На сервере ещё нет таблицы синхронизации: выполните supabase/schema.sql в Supabase."
        else -> "Ошибка сервера: $raw"
    }
    val authLost = result.code == 401 || code in setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "invalid_grant")
    return SyncException(message, result.code, authLost)
}

/** Email and password accounts (Supabase Auth). */
object SupabaseAuth {
    private fun sessionFrom(json: JSONObject): Session {
        val user = json.getJSONObject("user")
        val expiresAt = json.optLong("expires_at").takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600))
        return Session(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = expiresAt,
            userId = user.getString("id"),
            email = user.optString("email"),
        )
    }

    private fun credentials(email: String, password: String) =
        JSONObject().put("email", email.trim()).put("password", password).toString()

    suspend fun signIn(email: String, password: String): Session {
        val r = http("POST", "${SupabaseConfig.URL}/auth/v1/token?grant_type=password", null, credentials(email, password))
        if (r.code !in 200..299) throw failure(r)
        return sessionFrom(JSONObject(r.body))
    }

    /** Null when the project asks to confirm the email first: the user then signs in after clicking the link. */
    suspend fun signUp(email: String, password: String): Session? {
        val r = http("POST", "${SupabaseConfig.URL}/auth/v1/signup", null, credentials(email, password))
        if (r.code !in 200..299) throw failure(r)
        val json = JSONObject(r.body)
        return if (json.has("access_token")) sessionFrom(json) else null
    }

    suspend fun refresh(session: Session): Session {
        val body = JSONObject().put("refresh_token", session.refreshToken).toString()
        val r = http("POST", "${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token", null, body)
        if (r.code !in 200..299) {
            val e = failure(r)
            // A rejected refresh token means the session is over; a 5xx is just a bad moment.
            throw if (r.code in 400..499) SyncException("Сеанс истёк — войдите снова.", r.code, authLost = true) else e
        }
        return sessionFrom(JSONObject(r.body))
    }

    suspend fun signOut(session: Session) {
        runCatching { http("POST", "${SupabaseConfig.URL}/auth/v1/logout", session.accessToken) }
    }
}

/**
 * All records live in one table, `sync_records`: kind (table name), id, updated_at, deleted and the
 * row itself as JSON. The schema and its guard trigger are in supabase/schema.sql.
 */
class SupabaseApi(private val session: suspend (forceRefresh: Boolean) -> Session) : SyncApi {

    private val base = "${SupabaseConfig.URL}/rest/v1/${SupabaseConfig.TABLE}"

    /** Retries once with a fresh token when the server says the old one expired. */
    private suspend fun call(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        var r = http(method, url, session(false).accessToken, body, headers)
        if (r.code == 401) r = http(method, url, session(true).accessToken, body, headers)
        if (r.code !in 200..299) throw failure(r)
        return r
    }

    override suspend fun upsert(records: List<RemoteRecord>) {
        if (records.isEmpty()) return
        val userId = session(false).userId
        val body = JSONArray()
        for (rec in records) {
            body.put(
                JSONObject()
                    .put("user_id", userId)
                    .put("kind", rec.kind)
                    .put("id", rec.id)
                    .put("updated_at", rec.updatedAt)
                    .put("deleted", rec.deleted)
                    .put("data", rec.data)
            )
        }
        call(
            "POST", "$base?on_conflict=user_id,kind,id", body.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    override suspend fun delete(kind: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val userId = session(false).userId
        val list = ids.joinToString(",") { "\"" + it.replace("\"", "") + "\"" }
        call("DELETE", "$base?user_id=eq.$userId&kind=eq.${enc(kind)}&id=in.${enc("($list)")}")
    }

    override suspend fun pull(since: Instant?, limit: Int): List<RemoteRecord> {
        val userId = session(false).userId
        val filter = since?.let { "&server_updated_at=gt.${enc(it.toString())}" }.orEmpty()
        val r = call(
            "GET",
            "$base?select=kind,id,updated_at,deleted,data,server_updated_at&user_id=eq.$userId$filter" +
                "&order=server_updated_at.asc&limit=$limit",
        )
        val rows = JSONArray(r.body)
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            RemoteRecord(
                kind = o.getString("kind"),
                id = o.getString("id"),
                updatedAt = o.getLong("updated_at"),
                deleted = o.getBoolean("deleted"),
                data = o.getJSONObject("data"),
                serverAt = OffsetDateTime.parse(o.getString("server_updated_at")).toInstant(),
            )
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
