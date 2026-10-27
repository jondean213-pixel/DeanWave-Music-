package com.deanwaylabs.deanwave.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject

/**
 * The music-Drive sign-in, wired to Jon's music-Drive account.
 *
 * The Google side is already done (Cloud console, project DeanWave):
 * an Android OAuth client for package com.deanwaylabs.deanwave with
 * this app's release fingerprint. The client ID below is a public app
 * identifier, not an account, email, or token — it is safe to ship in
 * the app, and it is how Google recognizes DeanWave at sign-in.
 *
 * Flow (AppAuth, no Google client library — the same no-new-Google-lib
 * rule the uploader follows):
 * 1. The app opens Google's sign-in page for the drive.file scope
 *    (per-file access only: the app touches only files it creates).
 *    Jon signs in as his music-Drive account on Google's own screen —
 *    no password ever passes through DeanWave.
 * 2. Google returns an authorization code; AppAuth exchanges it for an
 *    access token + refresh token. The tokens live in the app's
 *    private preferences (no other app can read them; this phone is
 *    not rooted). A later hardening step can move them to encrypted
 *    preferences; the seam ([DriveCredentials]) does not change.
 * 3. [freshTokenBlocking] hands the uploader a live access token,
 *    refreshing silently when the old one expires. The uploader calls
 *    it on a background thread already, so the blocking wait is safe.
 *
 * The music folder ("DeanWave Music") is found or created once per
 * sign-in via the Drive API, so songs land in one tidy folder instead
 * of the Drive root.
 *
 * Blocking where noted; call those off the main thread.
 */
object DriveAuthConfig {
    const val CLIENT_ID =
        "790446424712-9rk9mkviglviiamk5jn3rjijcfr8oj16.apps.googleusercontent.com"
    const val REDIRECT_URI = "https://jondean213-pixel.github.io/DeanWave-Music-/deanwave-auth.html"
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val MUSIC_FOLDER_NAME = "DeanWave Music"

    val SERVICE_CONFIG = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )
}

class DriveAuth(private val context: Context) {
    companion object {
        private const val PREFS = "drive_auth"
        private const val KEY_STATE = "auth_state"
        private const val KEY_FOLDER_ID = "music_folder_id"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var authService: AuthorizationService? = null

    private fun service(): AuthorizationService {
        return authService ?: AuthorizationService(context).also { authService = it }
    }

    fun isConnected(): Boolean {
        return try {
            restoreState()?.isAuthorized == true
        } catch (_: Exception) {
            false
        }
    }

    /** The sign-in page intent. Launch it; the result comes back to [handleSignInResult]. */
    fun signInIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            DriveAuthConfig.SERVICE_CONFIG,
            DriveAuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(DriveAuthConfig.REDIRECT_URI)
        )
            .setScope(DriveAuthConfig.SCOPE_DRIVE_FILE)
            // Always show Google's account chooser. The sign-in window
            // can carry a different session than the browser's active
            // account (field-proven: Chrome on deanjon6529, the window
            // still opened jondean213), so never trust the default —
            // make Jon pick the music account every time.
            .setPrompt("select_account")
            .build()
        return service().getAuthorizationRequestIntent(request)
    }

    /**
     * Completes sign-in from the returning intent. True when tokens
     * landed and were saved; false leaves the previous state untouched.
     * Call off the main thread — the token exchange is network I/O.
     */
    fun handleSignInResult(data: Intent?): Boolean {
        val response = data?.let { AuthorizationResponse.fromIntent(it) } ?: return false
        val error = net.openid.appauth.AuthorizationException.fromIntent(data)
        if (error != null) return false
        val latch = CountDownLatch(1)
        var ok = false
        service().performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, exception ->
            if (tokenResponse != null && exception == null) {
                val state = AuthState(response, tokenResponse, null)
                saveState(state)
                ok = true
            }
            latch.countDown()
        }
        latch.await(60, TimeUnit.SECONDS)
        return ok
    }

    fun signOut() {
        prefs.edit().remove(KEY_STATE).remove(KEY_FOLDER_ID).apply()
    }

    /** A live access token, refreshing silently when expired. Null when not signed in. */
    fun freshTokenBlocking(): String? {
        val state = restoreState() ?: return null
        val latch = CountDownLatch(1)
        var token: String? = null
        state.performActionWithFreshTokens(service()) { accessToken, _, exception ->
            if (exception == null) token = accessToken
            saveState(state)
            latch.countDown()
        }
        latch.await(60, TimeUnit.SECONDS)
        return token
    }

    /** The music folder id, found or created once and then cached. Null on any failure. */
    fun musicFolderIdBlocking(): String? {
        prefs.getString(KEY_FOLDER_ID, null)?.let { return it }
        val token = freshTokenBlocking() ?: return null
        val found = findOrCreateFolder(token) ?: return null
        prefs.edit().putString(KEY_FOLDER_ID, found).apply()
        return found
    }

    private fun findOrCreateFolder(token: String): String? {
        return try {
            val query = URLEncoder.encode(
                "name = '${DriveAuthConfig.MUSIC_FOLDER_NAME}' and " +
                    "mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                "UTF-8"
            )
            val listUrl = URL("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id)")
            val listConn = listUrl.openConnection() as HttpURLConnection
            listConn.setRequestProperty("Authorization", "Bearer $token")
            listConn.connectTimeout = 15_000
            listConn.readTimeout = 30_000
            if (listConn.responseCode == 200) {
                val files = JSONObject(
                    listConn.inputStream.bufferedReader().use { it.readText() }
                ).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return files.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
                }
            }
            val createConn =
                URL("https://www.googleapis.com/drive/v3/files").openConnection() as HttpURLConnection
            createConn.requestMethod = "POST"
            createConn.doOutput = true
            createConn.setRequestProperty("Authorization", "Bearer $token")
            createConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            createConn.connectTimeout = 15_000
            createConn.readTimeout = 30_000
            val metadata = JSONObject().apply {
                put("name", DriveAuthConfig.MUSIC_FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            createConn.outputStream.use {
                it.write(metadata.toString().toByteArray(Charsets.UTF_8))
            }
            if (createConn.responseCode != 200) return null
            JSONObject(createConn.inputStream.bufferedReader().use { it.readText() })
                .optString("id").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreState(): AuthState? {
        val json = prefs.getString(KEY_STATE, null) ?: return null
        return try {
            AuthState.jsonDeserialize(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveState(state: AuthState) {
        try {
            prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
        } catch (_: Exception) {
            // A failed save means the next launch signs in again — never a crash.
        }
    }
}

/** [DriveCredentials] backed by the signed-in music-Drive account. */
class TokenDriveCredentials(private val auth: DriveAuth) : DriveCredentials {
    override fun accessToken(): String? = auth.freshTokenBlocking()
}
