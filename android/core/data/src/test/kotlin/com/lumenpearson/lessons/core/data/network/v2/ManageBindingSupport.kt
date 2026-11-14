package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.GetClassResponse
import com.lumenpearson.lessons.contract.v2.SchoolClass
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.ManageRepositoryImpl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals

internal fun V2BindingFixture.management() = ManageRepositoryImpl(
    manage,
    ioDispatcher = Dispatchers.Unconfined,
)

internal fun V2BindingFixture.managementRequest(path: String, rpc: String, method: String = "GET"): RecordedRequest {
    val sent = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    assertEquals("Bearer synthetic-device", sent.headers["Authorization"])
    assertEquals("73", sent.headers["X-Lessons-Client"])
    assertEquals(if (transport == "rest") method else "POST", sent.method)
    assertEquals(if (transport == "rest") "/school/api/v2/$path" else "/school/api/rpc/lessons.v2.$rpc",
        sent.url.encodedPath)
    return sent
}

internal fun V2BindingFixture.managementBytes(sent: RecordedRequest): ByteArray =
    checkNotNull(sent.body).toByteArray().let { if (transport == "grpc") it.drop(5).toByteArray() else it }

internal fun V2BindingFixture.classZone() {
    success("""{"schoolClass":{"id":7,"name":"9A","timezone":"Europe/Berlin"}}""",
        GetClassResponse.newBuilder().setSchoolClass(SchoolClass.newBuilder().setId(7)
            .setName("9A").setTimezone("Europe/Berlin")).build())
}
