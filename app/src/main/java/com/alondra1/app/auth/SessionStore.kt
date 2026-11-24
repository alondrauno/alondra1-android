package com.alondra1.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SessionState(
    val signedIn: Boolean = false,
    val email: String? = null,
    val tenantId: String? = null,
    val syncEnabled: Boolean = false,
    val boundUser: String? = null,
)

/**
 * Account session on this device. The refresh token is kept in EncryptedSharedPreferences (key in the Android
 * Keystore); the access token only in memory. Signing out never touches tasks, notes or events.
 */
open class SessionStore(private val prefs: SharedPreferences?) {
    @Volatile private var access: String? = null
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SessionState> = _state

    private fun read() = SessionState(
        signedIn = prefs?.getString(K_REFRESH, null) != null,
        email = prefs?.getString(K_EMAIL, null),
        tenantId = prefs?.getString(K_TENANT, null),
        syncEnabled = prefs?.getBoolean(K_SYNC, false) ?: false,
        boundUser = prefs?.getString(K_BOUND, null),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
        _state.value = read()
    }

    fun accessToken() = access
    fun refreshToken(): String? = prefs?.getString(K_REFRESH, null)
    fun cursor(): String? = prefs?.getString(K_CURSOR, null)
    fun userId(): String? = prefs?.getString(K_USER, null)

    fun saveTokens(response: JsonObject) {
        access = response["access_token"]?.jsonPrimitive?.contentOrNull
        val refresh = response["refresh_token"]?.jsonPrimitive?.contentOrNull
        val user = response["user"]?.jsonObject
        edit {
            putString(K_REFRESH, refresh)
            user?.get("email")?.jsonPrimitive?.contentOrNull?.let { putString(K_EMAIL, it) }
            user?.get("id")?.jsonPrimitive?.contentOrNull?.let { putString(K_USER, it) }
        }
    }

    fun setSync(tenantId: String?, enabled: Boolean) = edit {
        putString(K_TENANT, tenantId); putBoolean(K_SYNC, enabled)
        if (enabled) putString(K_BOUND, prefs?.getString(K_USER, null))
    }

    fun setCursor(cursor: String?) = edit { putString(K_CURSOR, cursor) }

    /** The cloud session ended (logout, revoked, expired). Sync stops; local data stays on the device. */
    fun signedOut() {
        access = null
        edit { remove(K_REFRESH); putBoolean(K_SYNC, false); remove(K_CURSOR) }
    }

    companion object {
        private const val K_REFRESH = "refresh_token"
        private const val K_EMAIL = "email"
        private const val K_USER = "user_id"
        private const val K_TENANT = "tenant_id"
        private const val K_SYNC = "sync_enabled"
        private const val K_CURSOR = "cursor"
        private const val K_BOUND = "bound_user"

        /** "secure_session" is excluded from backups and device transfer (res/xml/data_extraction_rules.xml). */
        fun create(context: Context): SessionStore {
            val prefs = runCatching {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(context, "secure_session", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            }.getOrNull()
            return SessionStore(prefs)
        }

        fun inMemory(context: Context, name: String = "test_session") =
            SessionStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}
