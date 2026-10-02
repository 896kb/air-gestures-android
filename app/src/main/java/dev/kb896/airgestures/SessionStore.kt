package dev.kb896.airgestures

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Meta sign-in (the session JSON from [Bridge.signinFinish]), encrypted with an
 * AES-GCM key that stays in the Android Keystore: `meta-session.bin` in the app's files
 * holds the 12-byte IV and the ciphertext. Only a claim reads it. Never logged.
 */
object SessionStore {
    private const val ALIAS = "meta-session"
    private const val IV_BYTES = 12

    private fun file(context: Context) = File(context.filesDir, "meta-session.bin")

    fun present(context: Context) = file(context).exists()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey = (keyStore().getKey(ALIAS, null) as SecretKey?)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()

    fun save(context: Context, sessionJson: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(sessionJson.toByteArray())
        // Written aside, then renamed over: a crash never leaves half a file.
        val temp = File(context.filesDir, ".meta-session.bin.tmp")
        temp.outputStream().use { it.write(sealed); it.fd.sync() }
        if (!temp.renameTo(file(context))) throw java.io.IOException("could not save the Meta sign-in")
    }

    /** The session JSON, or null when there is none or it can't be read (e.g. the key is gone). */
    fun load(context: Context): String? = runCatching {
        val sealed = file(context).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }.getOrNull()

    /** Sign out: the file and its key. */
    fun delete(context: Context) {
        file(context).delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
