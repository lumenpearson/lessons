package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Codec
import com.connectrpc.ErrorDetailParser
import com.connectrpc.SerializationStrategy
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import kotlin.reflect.KClass
import okio.Buffer
import okio.BufferedSource

internal class LiteProtobufStrategy : SerializationStrategy {
    override fun serializationName(): String = "proto"

    override fun <E : Any> codec(clazz: KClass<E>): Codec<E> {
        require(MessageLite::class.java.isAssignableFrom(clazz.java)) { "A protobuf lite message is required" }
        // The consumer rule keeps this method even when the generated class is renamed.
        val instance = clazz.java.getMethod("getDefaultInstance").invoke(null) as MessageLite
        return object : Codec<E> {
            override fun encodingName(): String = "proto"

            override fun serialize(message: E): Buffer = Buffer().write((message as MessageLite).toByteArray())

            override fun deterministicSerialize(message: E): Buffer {
                val proto = message as MessageLite
                val bytes = ByteArray(proto.serializedSize)
                val output = CodedOutputStream.newInstance(bytes)
                output.useDeterministicSerialization()
                proto.writeTo(output)
                output.checkNoSpaceLeft()
                return Buffer().write(bytes)
            }

            override fun deserialize(source: BufferedSource): E =
                requireNotNull(clazz.java.cast(instance.parserForType.parseFrom(source.readByteArray())))
        }
    }

    override fun errorDetailParser(): ErrorDetailParser = LiteErrorDetails
}
