package kr.urbanmood.context

import kr.urbanmood.contracts.LocationSnapshot
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Bounded streaming XML parsing. Partial results and any DTD/entity declarations fail closed. */
internal object OverpassRoadParser {
    const val MAX_BYTES = 524_288
    fun parse(xml: String): List<RoadWay> {
        require(xml.length <= MAX_BYTES && !xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true))
        val ways = mutableListOf<RoadWay>()
        val ids = mutableSetOf<Long>()
        val stack = mutableListOf<String>()
        var elements = 0
        var id: Long? = null
        var geometry = mutableListOf<RoadPoint>()
        var tags = mutableMapOf<String, String>()
        var rootSeen = false
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity rejected")
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                require(++elements <= 20_000 && stack.size < 8)
                val parent = stack.lastOrNull()
                if (parent == null) { require(!rootSeen && qName == "osm"); rootSeen = true }
                else when (qName) {
                    "way" -> {
                        require(parent == "osm" && ways.size < 1_000)
                        id = attrs.getValue("id")?.toLongOrNull()?.takeIf { it > 0 } ?: error("Invalid way")
                        require(ids.add(id!!))
                        geometry = mutableListOf(); tags = mutableMapOf()
                    }
                    "nd" -> {
                        require(parent == "way" && geometry.size < 16_000)
                        val lat = attrs.getValue("lat")?.toDoubleOrNull()
                        val lon = attrs.getValue("lon")?.toDoubleOrNull()
                        require(lat != null && lat.isFinite() && lat in -90.0..90.0 &&
                            lon != null && lon.isFinite() && lon in -180.0..180.0)
                        geometry.add(RoadPoint(lat, lon))
                    }
                    "tag" -> {
                        require(parent == "way" && tags.size < 128)
                        val key = attrs.getValue("k") ?: error("Invalid tag")
                        val value = attrs.getValue("v") ?: error("Invalid tag")
                        require(key.length <= 256 && value.length <= 4096 && tags.put(key, value) == null)
                    }
                    "bounds" -> require(parent == "way" || parent == "osm")
                    "note", "meta" -> require(parent == "osm")
                    else -> error("Unsupported or partial response") // Includes Overpass <remark> timeout/errors.
                }
                stack.add(qName)
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                require(stack.removeAt(stack.lastIndex) == qName)
                if (qName == "way") {
                    val highway = tags["highway"]?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
                        ?: error("Missing highway")
                    require(geometry.size >= 2)
                    val name = (tags["name:ko"] ?: tags["name"] ?: tags["ref"])
                        ?.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
                        ?.trim()?.take(120)?.takeIf { it.isNotBlank() }
                    if (highway in RoadMatcher.DRIVING_HIGHWAYS) ways.add(RoadWay(id!!, highway, name, geometry.toList()))
                }
            }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        val reader = SAXParserFactory.newInstance().apply { isValidating = false }.newSAXParser().xmlReader
        // Android/JVM providers differ in supported feature flags. Declaration rejection above is mandatory,
        // and the entity resolver rejects every external access even on a provider lacking one of these flags.
        listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach {
            runCatching { reader.setFeature(it, false) }
        }
        reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
        reader.parse(InputSource(StringReader(xml)))
        require(rootSeen && stack.isEmpty())
        return ways
    }
}

/** No coordinates in URLs/logs; one fixed HTTPS endpoint, no redirects, no retries. */
internal class OverpassRoadCall(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val deadline: ((() -> Unit) -> AutoCloseable) = { expire ->
        val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "road-timeout").apply { isDaemon = true } }
        val future = timer.schedule({ expire() }, 12_000, TimeUnit.MILLISECONDS)
        AutoCloseable { future.cancel(false); timer.shutdownNow() }
    },
) : RoadCall {
    @Volatile private var cancelled = false
    @Volatile private var timedOut = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var deadlineHandle: AutoCloseable? = null
    override fun cancel() { cancelled = true; deadlineHandle?.close(); connection?.disconnect() }
    private fun checkActive() { if (cancelled) throw RoadFailure(if (timedOut) "도로 통신 시간 초과" else "도로 조회 취소") }

    override fun fetch(location: LocationSnapshot): List<RoadWay> {
        checkActive()
        val latitude = requireNotNull(location.latitude)
        val longitude = requireNotNull(location.longitude)
        require(latitude.isFinite() && latitude in -85.0..85.0 && longitude.isFinite() && longitude in -180.0..180.0)
        val types = RoadMatcher.DRIVING_HIGHWAYS.joinToString("|")
        val query = "[out:xml][timeout:8][maxsize:2097152];way[\"highway\"~\"^($types)$\"](around:200,$latitude,$longitude);out geom;"
        val payload = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8)
        val conn = openConnection(URL("https://overpass-api.de/api/interpreter"))
        connection = conn
        val timeout = deadline { timedOut = true; cancel() }
        deadlineHandle = timeout
        try {
            checkActive()
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"; conn.doOutput = true; conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/osm3s+xml, application/xml, text/xml")
            conn.setRequestProperty("User-Agent", "DrivingMood-prototype (https://github.com/oiehnow/driving-mood)")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            checkActive()
            val status = conn.responseCode
            if (status != 200) throw RoadFailure(if (status == 429) "지도 서비스 요청 한도 초과" else "지도 서비스 연결 실패", status == 429)
            val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (mime !in setOf("application/osm3s+xml", "application/xml", "text/xml")) throw RoadFailure("지도 응답 형식 오류")
            if (conn.contentLengthLong > OverpassRoadParser.MAX_BYTES) throw RoadFailure("지도 응답 크기 초과")
            val bytes = conn.inputStream.use { stream ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    checkActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > OverpassRoadParser.MAX_BYTES) throw RoadFailure("지도 응답 크기 초과")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            checkActive()
            val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val result = OverpassRoadParser.parse(xml)
            checkActive()
            return result
        } catch (error: RoadFailure) { throw error
        } catch (_: Exception) { throw RoadFailure(if (timedOut) "도로 통신 시간 초과" else "지도 연결 또는 응답 오류")
        } finally { timeout.close(); deadlineHandle = null; conn.disconnect(); connection = null }
    }
}
