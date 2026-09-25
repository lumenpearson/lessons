package com.lumenpearson.lessons.core.data.upstream

import java.security.MessageDigest

/**
 * windows-1251, carried as a table rather than asked of the platform.
 *
 * «Сетевой город» hashes the password's windows-1251 bytes, so a character that
 * encodes differently here than on the server is a password that is right on
 * one side and wrong on the other. The JVM's charset and CPython's `cp1251`
 * agree on every BMP code point (`Cp1251Test` holds the table to the JVM's);
 * Android's charsets come from ICU, which nothing here has checked, so the
 * table is this app's own and the device's converter is never asked.
 *
 * ASCII is itself; the high half is the 128 entries below, and `0x98` maps to
 * nothing, as in both references.
 */
internal object Cp1251 {

    private val HIGH: IntArray = intArrayOf(
        0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021, // 0x80
        0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F, // 0x88
        0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, // 0x90
        -1, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F, // 0x98
        0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7, // 0xA0
        0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407, // 0xA8
        0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7, // 0xB0
        0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457, // 0xB8
        0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417, // 0xC0
        0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F, // 0xC8
        0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427, // 0xD0
        0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F, // 0xD8
        0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437, // 0xE0
        0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F, // 0xE8
        0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447, // 0xF0
        0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F, // 0xF8
    )

    private val REVERSE: Map<Char, Byte> = buildMap {
        HIGH.forEachIndexed { index, codePoint ->
            if (codePoint >= 0) put(codePoint.toChar(), (0x80 + index).toByte())
        }
    }

    /**
     * The bytes of [text], or `null` when any character has none — never a
     * question mark in its place, which would hash a different password.
     * A surrogate half is refused like any other unmapped character, so a
     * text this accepts has exactly one character per code point.
     */
    fun encode(text: String): ByteArray? {
        val out = ByteArray(text.length)
        for ((index, char) in text.withIndex()) {
            out[index] = when {
                char.code < 0x80 -> char.code.toByte()
                else -> REVERSE[char] ?: return null
            }
        }
        return out
    }
}

/**
 * The password pair «Сетевой город» asks for, computed on the phone so the
 * password itself goes nowhere: `pw2 = md5hex(salt + md5hex(cp1251(password)))`
 * and `pw` is its first `len(password)` characters — the scheme of every
 * windows-1251 client, and the server's `_hash_password` byte for byte, held
 * there by the shared vectors.
 */
internal object NetSchoolPassword {

    /**
     * `(pw, pw2)`, or `null` when windows-1251 cannot encode a character of
     * [password] — which the caller reports as a wrong password without the
     * character, since a message holding it would hold part of the password.
     *
     * [salt] must be ASCII; the caller checks, because a salt that is not is
     * the server's failure and not the password's.
     */
    fun hash(salt: String, password: String): Pair<String, String>? {
        val raw = Cp1251.encode(password) ?: return null
        val inner = md5Hex(raw)
        val pw2 = md5Hex((salt + inner).toByteArray(Charsets.US_ASCII))
        // `Cp1251.encode` accepted only BMP characters, so `length` counts what
        // Python's `len` counts.
        return pw2.take(password.length) to pw2
    }

    // By hand rather than `"%02x".format`, which formats in the default locale
    // and would hash an Arabic-locale phone's password with Arabic digits.
    private fun md5Hex(bytes: ByteArray): String = buildString(32) {
        for (byte in MessageDigest.getInstance("MD5").digest(bytes)) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}
