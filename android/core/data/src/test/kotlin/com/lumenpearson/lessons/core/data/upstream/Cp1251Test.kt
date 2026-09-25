package com.lumenpearson.lessons.core.data.upstream

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The windows-1251 table the «Сетевой город» hash is computed over.
 *
 * The phone carries its own table rather than asking the platform, because
 * Android's charsets come from ICU and nothing here has checked ICU's
 * windows-1251 against CPython's `cp1251`, which is what the server hashes
 * with. The JVM's own charset is known to agree with CPython on every BMP code
 * point, so this holds the table to the JVM's, character by character.
 */
class Cp1251Test {

    @Test
    fun `the table encodes every BMP code point exactly as the JVM windows-1251 charset does`() {
        val encoder = Charset.forName("windows-1251").newEncoder()
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .onMalformedInput(CodingErrorAction.REPORT)
        var encodable = 0
        for (code in 0..0xFFFF) {
            val char = code.toChar()
            if (char.isSurrogate()) continue
            val jvm = if (encoder.canEncode(char)) {
                encoder.reset()
                val buffer = encoder.encode(CharBuffer.wrap(charArrayOf(char)))
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } else {
                null
            }
            val ours = Cp1251.encode(char.toString())
            if (jvm == null) {
                assertNull("U+%04X".format(code), ours)
            } else {
                encodable++
                assertArrayEquals("U+%04X".format(code), jvm, ours)
            }
        }
        // ASCII's 128 and the high half's 127: every byte but 0x98.
        assertEquals(255, encodable)
    }

    /** A question mark in place of a character would hash a different password, and say nothing. */
    @Test
    fun `0x98 and characters outside the table are refused, never replaced by a question mark`() {
        assertNull(Cp1251.encode("a\u0098b"))
        assertNull(Cp1251.encode("ü"))
        assertNull(Cp1251.encode("😀"))
        assertNull(Cp1251.encode("\uD83D"))
        assertArrayEquals(byteArrayOf(0xB8.toByte(), 0xA8.toByte(), 0xB9.toByte()), Cp1251.encode("ёЁ№"))
    }
}
