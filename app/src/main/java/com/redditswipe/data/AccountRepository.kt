package com.redditswipe.data

import android.content.Context
import com.redditswipe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The optional account, through the relay Worker (see relay/src/account.js): sign in with a code sent by
 * email, then upload and download one JSON snapshot of the things that sync. The session token and sync
 * bookkeeping live in their own SharedPreferences, so signing out clears just that.
 */
class AccountRepository(context: Context) {

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    val email: String? get() = prefs.getString("email", null)

    val isSignedIn: Boolean get() = prefs.getString("token", null) != null

    /** The server's updatedAt for the snapshot this phone last uploaded or applied. */
    var syncedAt: Long
        get() = prefs.getLong("synced_at", 0L)
        set(value) = prefs.edit().putLong("synced_at", value).apply()

    /** What the synced data looked like at that point, to tell whether anything changed here since. */
    var syncedFingerprint: String?
        get() = prefs.getString("synced_fingerprint", null)
        set(value) = prefs.edit().putString("synced_fingerprint", value).apply()

    suspend fun requestCode(email: String) {
        call("POST", "/auth/code", JSONObject().put("email", email))
    }

    suspend fun verifyCode(email: String, code: String) {
        val result = call("POST", "/auth/verify", JSONObject().put("email", email).put("code", code))
        prefs.edit()
            .clear()
            .putString("token", result.getString("token"))
            .putString("email", result.getString("email"))
            .apply()
    }

    suspend fun signOut() {
        // Ending the session on the server is a courtesy; the phone forgets it either way.
        runCatching { call("POST", "/auth/signout", null) }
        prefs.edit().clear().apply()
    }

    /** The account's snapshot and when it was uploaded; null data before the first upload. */
    suspend fun download(): Pair<JSONObject?, Long> {
        val result = call("GET", "/sync", null)
        return result.optJSONObject("data") to result.optLong("updatedAt")
    }

    suspend fun upload(data: JSONObject, updatedAt: Long) {
        call("PUT", "/sync", JSONObject().put("data", data).put("updatedAt", updatedAt))
    }

    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(BuildConfig.RELAY_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            prefs.getString("token", null)?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { json -> connection.outputStream.use { it.write(json.toString().encodeToByteArray()) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                prefs.edit().clear().apply()
                throw SignedOutException(json.optString("error", "Signed out. Sign in again."))
            }
            if (status !in 200..299) {
                throw AccountException(json.optString("error").ifEmpty { "The server answered HTTP $status." })
            }
            json
        } catch (e: java.io.IOException) {
            throw AccountException("No connection. Try again when you're online.")
        } finally {
            connection.disconnect()
        }
    }
}

open class AccountException(message: String) : Exception(message)

/** The session ended (signed out on the server, or expired); the phone has forgotten it too. */
class SignedOutException(message: String) : AccountException(message)
