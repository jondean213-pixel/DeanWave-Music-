package com.deanwaylabs.deanwave.library

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * The music-Drive sign-in, via Google's own sign-in kit
 * (play-services-auth) — no AppAuth, no client secret anywhere: not
 * in the app, not in the repo, not in CI. Installed apps authenticate
 * by package + release fingerprint (the Android OAuth client already
 * in the DeanWave Cloud project), so there is nothing secret to store.
 *
 * Flow:
 * 1. The app opens Google's sign-in screen for the drive.file scope
 *    (per-file access only: the app touches only files it creates).
 *    Jon signs in as his music-Drive account on Google's own screen —
 *    no password ever passes through DeanWave.
 * 2. The signed-in account's email is saved in the app's private
 *    preferences. Tokens are NOT stored: [freshTokenBlocking] asks
 *    Google's token service for a live one each time, which refreshes
 *    silently when the old one expires.
 * 3. [freshTokenBlocking] hands the uploader a live access token.
 *    The uploader calls it on a background thread already, so the
 *    blocking wait is safe.
 *
 * The music folder ("DeanWave Music") is found or created once per
 * sign-in via the Drive API, so songs land in one tidy folder instead
 * of the Drive root.
 *
 * This replaced an AppAuth build whose Web client demanded a client
 * secret at token time — a secret Google no longer lets anyone view
 * again once created, which stranded the sign-in behind a browser
 * session that could not reach the repo owner's GitHub account.
 *
 * Blocking where noted; call those off the main thread.
 */
object DriveAuthConfig {
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val MUSIC_FOLDER_NAME = "DeanWave Music"
}

class DriveAuth(private val context: Context) {
    companion object {
        private const val PREFS = "drive_auth"
        private const val KEY_ACCOUNT = "account_email"
        private const val KEY_FOLDER_ID = "music_folder_id"
        // Left over from the retired AppAuth build; ignored now, and
        // cleared on sign-out so nothing stale lingers.
        private const val KEY_STATE = "auth_state"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConnected(): Boolean {
        val email = prefs.getString(KEY_ACCOUNT, null) ?: return false
        return try {
            GoogleSignIn.getLastSignedInAccount(context)?.email == email
        } catch (_: Exception) {
            false
        }
    }

    /** The sign-in page intent. Launch it; the result comes back to [handleSignInResult]. */
    fun signInIntent(): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveAuthConfig.SCOPE_DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    /**
     * Completes sign-in from the returning intent. True when the
     * account landed and was saved; false leaves the previous state
     * untouched. Call off the main thread.
     */
    /** Why the last sign-in failed, in plain words. Null when it worked. */
    var lastSignInError: String? = null
        private set

    fun handleSignInResult(data: Intent?): Boolean {
        if (data == null) {
            lastSignInError = "no sign-in response came back"
            return false
        }
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val email = account.email ?: run {
                lastSignInError = "sign-in returned no account"
                return false
            }
            prefs.edit().putString(KEY_ACCOUNT, email).remove(KEY_FOLDER_ID).apply()
            lastSignInError = null
            true
        } catch (e: ApiException) {
            lastSignInError = e.message ?: "sign-in failed (code ${e.statusCode})"
            false
        } catch (e: Exception) {
            lastSignInError = e.message ?: "sign-in failed"
            false
        }
    }

    fun signOut() {
        try {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(context, options).signOut()
        } catch (_: Exception) {
        }
        prefs.edit().remove(KEY_ACCOUNT).remove(KEY_STATE).remove(KEY_FOLDER_ID).apply()
    }

    /** A live access token, refreshing silently when expired. Null when not signed in. */
    fun freshTokenBlocking(): String? {
        val email = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return try {
            GoogleAuthUtil.getToken(
                context,
                Account(email, "com.google"),
                "oauth2:${DriveAuthConfig.SCOPE_DRIVE_FILE}"
            )
        } catch (e: UserRecoverableAuthException) {
            // Drive access needs one more approval: the next Connect
            // tap re-runs sign-in and re-grants the scope.
            lastSignInError = "Drive access needs one more approval — tap Connect again"
            null
        } catch (_: Exception) {
            null
        }
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
}

/** [DriveCredentials] backed by the signed-in music-Drive account. */
class TokenDriveCredentials(private val auth: DriveAuth) : DriveCredentials {
    override fun accessToken(): String? = auth.freshTokenBlocking()
}
