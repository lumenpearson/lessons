package com.lumenpearson.lessons.core.data.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The bearers' key, kept by the AndroidKeyStore under [alias] (#201).
 *
 * **Written, never run on a device.** Nothing in this repository can execute
 * it: the unit tests run on a JVM with no AndroidKeyStore provider, and there
 * is no emulator in CI. Every decision about what a missing or broken key
 * *costs* lives in [TokenVault] and is tested there with a key held in memory;
 * what is left here is the platform's API, used the way its documentation
 * shows.
 *
 * AES-256 for GCM, nothing else asked of it:
 *
 *  * **No user authentication.** The widget and the sync worker open the class
 *    token with the screen off and nobody holding the phone; a key that needed
 *    a recent unlock would stop every background sync.
 *  * **No StrongBox.** It is missing on most of the phones this app runs on,
 *    and where it exists it is slow enough to be felt on a cold start; the
 *    ordinary Keystore already keeps the key out of the app's process.
 *
 * A key that cannot be read back is deleted rather than kept. That is only
 * `UnrecoverableKeyException` — the entry is there and will never work again —
 * and what it costs is what a lost key costs anyway: every sealed token opens
 * to nothing and the phone signs in again, and the next seal makes a key that
 * does work. A failure of any other kind is left alone and thrown, because it
 * may be the Keystore having a bad moment rather than the key being gone.
 */
internal class AndroidKeystoreKeys(private val alias: String) : AesGcmTokenCipher.Keys {

    override fun existing(): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return try {
            store.getKey(alias, null) as? SecretKey
        } catch (_: UnrecoverableKeyException) {
            store.deleteEntry(alias)
            null
        }
    }

    override fun create(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
