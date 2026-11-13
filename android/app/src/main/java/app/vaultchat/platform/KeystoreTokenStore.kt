package app.vaultchat.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.vaultchat.domain.auth.AuthTokens
import app.vaultchat.domain.auth.SessionUser
import app.vaultchat.domain.auth.StoredSession
import app.vaultchat.domain.auth.TokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session tokens encrypted with a non-exportable AES-256-GCM key in the Android Keystore
 * (docs/04-accounts-and-authentication.md §4). Only ciphertext reaches SharedPreferences, and the
 * app excludes all data from backups.
 *
 * The decrypted session is kept in memory, so the Keystore is used once per app start instead of on every
 * request. A failed decryption no longer deletes the stored session (owner report 2026-11-12, repeated
 * logouts): the Keystore can fail for a moment (busy, many calls at once), and deleting on such a failure
 * logged the user out. The stored session is only replaced by the next login or removed by logout or by
 * the server ending it. If the key is really gone, loading keeps failing and the user logs in again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** The session as last loaded or saved in this process; null until then or after [clear]. */
    private var cached: StoredSession? = null
    private var cachedKey: SecretKey? = null

    @Serializable
    private data class Persisted(
        val accessToken: String,
        val refreshToken: String,
        val accessExpiresAtEpochMs: Long,
        val userId: String,
        val username: String,
        val mustChangePassword: Boolean,
    )

    @Synchronized
    override fun load(): StoredSession? {
        cached?.let { return it }
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        // A few tries: a Keystore failure is often momentary. The stored session is never deleted here.
        repeat(ATTEMPTS) { attempt ->
            try {
                return decrypt(blob).also { cached = it }
            } catch (_: IllegalArgumentException) {
                return null // malformed data (bad Base64 or JSON): retrying cannot help
            } catch (_: GeneralSecurityException) {
                cachedKey = null
            } catch (_: ProviderException) {
                cachedKey = null
            }
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
        }
        return null
    }

    private fun decrypt(blob: String): StoredSession {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        val p = json.decodeFromString(Persisted.serializer(), plain.decodeToString())
        return StoredSession(
            AuthTokens(p.accessToken, p.refreshToken, p.accessExpiresAtEpochMs),
            SessionUser(p.userId, p.username, p.mustChangePassword),
        )
    }

    @Synchronized
    override fun save(session: StoredSession) {
        // In memory first: this process keeps the new tokens even if writing them to disk fails.
        cached = session
        repeat(ATTEMPTS) { attempt ->
            try {
                persist(session)
                return
            } catch (_: GeneralSecurityException) {
                cachedKey = null
            } catch (_: ProviderException) {
                cachedKey = null
            }
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
        }
    }

    private fun persist(session: StoredSession) {
        val p = Persisted(
            session.tokens.accessToken,
            session.tokens.refreshToken,
            session.tokens.accessExpiresAtEpochMs,
            session.user.id,
            session.user.username,
            session.user.mustChangePassword,
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(json.encodeToString(Persisted.serializer(), p).encodeToByteArray())
        val out = cipher.iv + ct
        // commit() rather than apply(): a session must not be lost if the process dies right after login.
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    override fun clear() {
        cached = null
        prefs.edit().remove(KEY_BLOB).commit()
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { cachedKey = it; return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey().also { cachedKey = it }
    }

    private companion object {
        const val PREFS = "vc_session"
        const val KEY_BLOB = "session"
        const val KEY_ALIAS = "vaultchat_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 100L
    }
}
