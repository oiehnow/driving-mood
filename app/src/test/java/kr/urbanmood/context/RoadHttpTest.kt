package kr.urbanmood.context

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RoadHttpTest {
    private class Connection(val bytes: ByteArray = "<osm version=\"0.6\"/>".toByteArray(), val status: Int = 200) : HttpURLConnection(URL("https://overpass-api.de/api/interpreter")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getContentType() = "application/osm3s+xml; charset=utf-8"
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getOutputStream() = sent
    }
    private val fix = LocationSnapshot(Availability.AVAILABLE,37.5,127.0,10f,source = SourceInfo("GPS",1_000))
    @Test fun sendsOneBoundedHttpsPostWithoutRedirectOrVideo() {
        val conn = Connection(); var calls = 0
        val call = OverpassRoadCall(openConnection = { url ->
            calls++; assertEquals("https", url.protocol); assertNull(url.query); conn
        })
        assertTrue(call.fetch(fix).isEmpty())
        assertEquals(1,calls)
        assertEquals("POST",conn.requestMethod)
        assertFalse(conn.instanceFollowRedirects)
        val query = java.net.URLDecoder.decode(conn.sent.toString("UTF-8"),"UTF-8")
        assertTrue(query.contains("around:200,37.5,127.0"))
        assertTrue(query.contains("[timeout:8]"))
        assertTrue(query.contains("out geom"))
        assertTrue("Only motor-road classes should be requested", query.contains("motorway|motorway_link|trunk"))
        assertTrue(conn.disconnected)
    }
    @Test fun rejectsRedirectsOversizedBodiesAndInvalidUtf8() {
        for (conn in listOf(Connection(status = 302), Connection(ByteArray(524_289) { 65 }),
            Connection(byteArrayOf(0xc3.toByte(), 0x28)))) {
            assertThrows(Exception::class.java) { OverpassRoadCall(openConnection = { conn }).fetch(fix) }
            assertTrue(conn.disconnected)
        }
    }
    @Test fun deadlineDisconnectsAndDoesNotReturnPartialSuccess() {
        val conn = Connection()
        val call = OverpassRoadCall(openConnection = { conn }, deadline = { expire -> expire(); AutoCloseable {} })
        assertThrows(Exception::class.java) { call.fetch(fix) }
        assertTrue(conn.disconnected)
    }
    @Test fun cancelledCallNeverOpensConnection() {
        var calls = 0
        val call = OverpassRoadCall(openConnection = { calls++; Connection() })
        call.cancel()
        assertThrows(Exception::class.java) { call.fetch(fix) }
        assertEquals(0,calls)
    }
}
