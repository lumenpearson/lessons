package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DataStore's contract without its file — one value, edits one at a time,
 * every change emitted — counting each time anybody starts reading it.
 *
 * Not the file because what the tests here ask is about values, and
 * DataStore's file layer cannot replace a file on Windows, where they run too.
 */
internal class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val turn = Mutex()
    val reads = AtomicInteger()

    val value: Preferences get() = state.value

    override val data: Flow<Preferences> = flow {
        reads.incrementAndGet()
        emitAll(state)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        turn.withLock { transform(state.value).toPreferences().also { state.value = it } }
}

/**
 * The AndroidKeyStore's part, played by the JVM's own AES provider.
 *
 * Real AES-GCM through the same [AesGcmTokenCipher] the phone runs — only the
 * place the key is kept differs — so a test here seals and opens actual
 * ciphertext, and a tag that does not verify is a real failure rather than a
 * fake's say-so. [lose] is a wiped Keystore; a second instance is another phone.
 */
internal class MemoryKeys : AesGcmTokenCipher.Keys {

    @Volatile
    private var key: SecretKey? = null

    override fun existing(): SecretKey? = key

    override fun create(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    /** What a wiped Keystore, or a restore onto another phone, leaves behind. */
    fun lose() {
        key = null
    }
}

/** A cipher that counts what it is asked, around a real one. */
internal class CountingCipher(private val inner: TokenCipher) : TokenCipher {
    val seals = AtomicInteger()
    val opens = AtomicInteger()

    override fun seal(plaintext: ByteArray): ByteArray = inner.seal(plaintext).also { seals.incrementAndGet() }

    override fun open(sealed: ByteArray): ByteArray {
        opens.incrementAndGet()
        return inner.open(sealed)
    }
}

/** A Keystore that will do nothing at all — the one that cannot even make a key. */
internal object BrokenCipher : TokenCipher {
    override fun seal(plaintext: ByteArray): ByteArray = throw IllegalStateException("Keystore unavailable")
    override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("Keystore unavailable")
}

/** A vault over a key of its own, or over [keys] when two vaults must share one. */
internal fun testVault(keys: MemoryKeys = MemoryKeys()): TokenVault = TokenVault(AesGcmTokenCipher(keys))
