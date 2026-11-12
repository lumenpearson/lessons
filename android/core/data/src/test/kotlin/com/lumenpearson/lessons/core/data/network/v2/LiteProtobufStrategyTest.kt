package com.lumenpearson.lessons.core.data.network.v2

import com.google.rpc.ErrorInfo
import com.lumenpearson.lessons.contract.v2.ClassSummary
import com.lumenpearson.lessons.contract.v2.CreateDeviceRequest
import com.lumenpearson.lessons.contract.v2.ScheduleWindow
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteProtobufStrategyTest {
    private val strategy = LiteProtobufStrategy()

    @Test
    fun `join encodes the literal protobuf wire format`() {
        val codec = strategy.codec(CreateDeviceRequest::class)
        val message = CreateDeviceRequest.newBuilder().setCode("DEMO24").build()

        assertEquals("proto", strategy.serializationName())
        assertEquals("proto", codec.encodingName())
        assertEquals("0a0644454d4f3234", codec.serialize(message).readByteString().hex())
    }

    @Test
    fun `join decodes bytes independently of its encoder`() {
        val bytes = byteArrayOf(10, 6, 68, 69, 77, 79, 50, 52)
        val parsed = strategy.codec(CreateDeviceRequest::class).deserialize(Buffer().write(bytes))

        assertEquals("DEMO24", parsed.code)
        assertFalse(parsed.hasDeviceName())
    }

    @Test
    fun `nested window preserves optional presence and unknown fields`() {
        val school = ClassSummary.newBuilder().setId(7).setName("7A")
            .setTimezone("Europe/Moscow").setGrade(0).build()
        val message = ScheduleWindow.newBuilder().setSchoolClass(school).build()
        val codec = strategy.codec(ScheduleWindow::class)
        // A future field 99 must survive binary parsing and forwarding.
        val bytes = codec.serialize(message).write(byteArrayOf(0x98.toByte(), 6, 1))
        val parsed = codec.deserialize(bytes)

        assertEquals("7A", parsed.schoolClass.name)
        assertTrue(parsed.schoolClass.hasGrade())
        assertEquals(0, parsed.schoolClass.grade)
        assertFalse(parsed.schoolClass.hasSchool())
        assertEquals(parsed, codec.deserialize(codec.serialize(parsed)))
        assertEquals(message.serializedSize + 3, codec.serialize(parsed).size.toInt())
    }

    @Test
    fun `map bytes are deterministic across insertion order`() {
        val forward = ErrorInfo.newBuilder().putMetadata("a", "1").putMetadata("z", "2").build()
        val backward = ErrorInfo.newBuilder().putMetadata("z", "2").putMetadata("a", "1").build()
        val codec = strategy.codec(ErrorInfo::class)

        assertEquals(
            "1a060a01611201311a060a017a120132",
            codec.deterministicSerialize(forward).readByteString().hex(),
        )
        assertEquals(
            codec.deterministicSerialize(forward).readByteString(),
            codec.deterministicSerialize(backward).readByteString(),
        )
    }

    @Test
    fun `non message classes fail before network serialization`() {
        assertThrows(IllegalArgumentException::class.java) { strategy.codec(String::class) }
    }

    @Test
    fun `strategy supplies its reflection free detail parser`() {
        assertSame(LiteErrorDetails, strategy.errorDetailParser())
    }
}
