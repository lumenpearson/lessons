package com.lumenpearson.lessons.core.data.datastore

import java.security.GeneralSecurityException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The two bearers as the preferences file holds them: sealed with a key that
 * never leaves the phone (#201).
 *
 * The class token and the diary session are each a working credential — the
 * first is often a write token since personal connect codes exist, the second
 * opens a child's marks — and the file they live in is plain protobuf that a
 * rooted phone, or anything that gets a copy of the app's data, reads as text.
 * Backup already leaves the file out; this is the half backup cannot do.
 *
 * What is written is `gcm1:` and the base64 of [AesGcmTokenCipher]'s output.
 * The colon is what tells the two kinds of value apart, and it can: every
 * bearer the server issues is `secrets.token_urlsafe`, whose alphabet has no
 * colon, so a stored value without one is a bearer written before this class
 * existed — it is read as it stands, and [TokenSealing] seals it the first time
 * the file is opened — and a value with one is sealed. A colon behind a prefix
 * this build does not know is a format from a later build, and opens to nothing
 * rather than being sent as a bearer.
 *
 * **Opening is remembered, for the life of the process, failures included.**
 * The Keystore is a binder call per operation, and the preferences emit on
 * every write of every setting; without the memory each emission would be a
 * round trip per token, and a key that is gone would be asked again on every
 * one of them. With it, each sealed value costs one call in a process, and a
 * value that would not open stays unopened until the next process — which is
 * how a lost key becomes «sign in again» and never a loop. What is remembered is
 * the bearer itself, which the interceptors already hold in memory anyway.
 *
 * Nothing here is ever called per request: the interceptors read
 * `CredentialsSnapshot`, and what fills it opens the tokens on the IO
 * dispatcher, before it maps the file — see `LessonsPreferences.opened`.
 */
internal class TokenVault(private val cipher: TokenCipher) {

    /** What each sealed value opened to, or `null` for one that would not. */
    private val opened = ConcurrentHashMap<String, Opened>()

    private class Opened(val token: String?)

    /**
     * The value to write in place of [token].
     *
     * A key that cannot be used at all leaves the bearer as it arrived rather
     * than refusing to store it. Refusing would turn a phone whose Keystore is
     * broken into one that can never join a class or sign in to a diary, and
     * the bearer would still have been in memory; written bare, it is exactly
     * what every install held before this existed, and [TokenSealing] tries
     * again on the next start. It is the one plaintext this class ever writes.
     */
    @Suppress("ReturnCount") // Each early answer is a case the paragraph above names.
    fun seal(token: String): String {
        if (isSealed(token)) return token
        val sealed = try {
            SEALED_PREFIX + Base64.getEncoder().encodeToString(cipher.seal(token.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            return token
        }
        // Remembered at once, so the refresh that follows the write does not
        // ask the Keystore for what this call has just handed it.
        opened[sealed] = Opened(token)
        return sealed
    }

    /**
     * The bearer [stored] holds, or `null` when it holds none this phone can
     * read — its key is gone, it was sealed on another phone, it was damaged,
     * or it is in a format from a later build. Every caller reads `null` the
     * way it reads a token that was never written, which is what makes a lost
     * key a sign-in rather than a crash.
     */
    @Suppress("ReturnCount") // A bare value, one opened before, and the first of two racing readers.
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        opened[stored]?.let { return it.token }
        val token = if (stored.startsWith(SEALED_PREFIX)) {
            try {
                String(cipher.open(Base64.getDecoder().decode(stored.substring(SEALED_PREFIX.length))), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        // Two readers can race to the same value; the first answer stands.
        val first = opened.putIfAbsent(stored, Opened(token)) ?: return token
        return first.token
    }

    companion object {
        /** What every sealed value starts with; the `1` is the format of what follows. */
        const val SEALED_PREFIX = "gcm1:"

        /** Whether [stored] is sealed rather than a bearer; see the class notes on the colon. */
        fun isSealed(stored: String): Boolean = ':' in stored

        /**
         * The phone's vault, one per process: every `LessonsPreferences` shares
         * it, so the alarms' own instances open nothing the container's has not.
         */
        val platform: TokenVault by lazy { TokenVault(AesGcmTokenCipher(AndroidKeystoreKeys(KEY_ALIAS))) }

        /** The Keystore entry's name. A new one would orphan every sealed token. */
        private const val KEY_ALIAS = "lessons.bearers"
    }
}

/**
 * Seals and opens a token's bytes.
 *
 * An interface for one reason: the implementation that matters needs the
 * AndroidKeyStore, which exists on a phone and nowhere else — not on the JVM
 * these tests run on, and not in Robolectric either. Everything that decides
 * what a sealed or unopenable value *means* is in [TokenVault] and tested
 * there; this is the part that is only bytes.
 */
internal interface TokenCipher {

    /** @throws Exception of any kind when the key cannot be used; [TokenVault] decides what it costs. */
    fun seal(plaintext: ByteArray): ByteArray

    /** @throws Exception of any kind when [sealed] does not open with the key this phone holds. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM under a key somebody else holds: one byte of IV length, the IV, then
 * the ciphertext with its tag.
 *
 * The IV is the cipher's own. AndroidKeyStore refuses one chosen by the caller
 * unless the key is made to allow it, and a random one per seal is what GCM
 * needs anyway; the length is written rather than assumed so the format does
 * not depend on which provider produced it. The tag is what makes a damaged or
 * foreign value fail to open instead of opening to garbage that would then be
 * sent as a bearer.
 */
internal class AesGcmTokenCipher(private val keys: Keys) : TokenCipher {

    /** Where the key lives — the AndroidKeyStore on a phone ([AndroidKeystoreKeys]). */
    interface Keys {

        /**
         * The key, or `null` when there is none. Never a new one: opening with
         * a key made on the spot could only fail, and would replace nothing.
         */
        fun existing(): SecretKey?

        /** A new key under the same name, replacing whatever stood there. */
        fun create(): SecretKey
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.existing() ?: keys.create())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    @Suppress("ThrowsCount") // One refusal per way a value can fail to be sealed; each reads as «no token».
    override fun open(sealed: ByteArray): ByteArray {
        val key = keys.existing() ?: throw GeneralSecurityException("No key to open a sealed token with")
        val ivSize = sealed.firstOrNull()?.toInt() ?: throw GeneralSecurityException("Empty sealed token")
        if (ivSize !in IV_SIZES || sealed.size < 1 + ivSize + TAG_BITS / Byte.SIZE_BITS) {
            throw GeneralSecurityException("Not a sealed token")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, ivSize))
        return cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val IV_SIZES = 12..16
    }
}
