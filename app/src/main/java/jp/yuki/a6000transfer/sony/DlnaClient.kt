package jp.yuki.a6000transfer.sony

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

data class DlnaItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbUrl: String,
    val isContainer: Boolean,
    /** <res protocolInfo="http-get:*:image/jpeg:*"> の第3フィールド等。正規化小文字。空の場合あり */
    val mime: String = "",
    /** upnp:class の生値（例 object.item.imageItem.photo）。空の場合あり */
    val upnpClass: String = "",
)

object DlnaClient {
    private const val TIMEOUT_MS = 10000

    /** 応答本文の上限（巨大応答でのメモリ枯渇防止） */
    private const val MAX_BODY_CHARS = 4 * 1024 * 1024

    private fun readCapped(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val s = if (code in 200..299) conn.inputStream else conn.errorStream
        if (s == null) return code to ""
        val sb = StringBuilder()
        s.bufferedReader(Charset.forName("UTF-8")).use { r ->
            val buf = CharArray(32768)
            while (sb.length < MAX_BODY_CHARS) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return code to sb.toString()
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            return readCapped(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpSoap(controlUrl: String, serviceType: String, action: String, bodyXml: String): Pair<Int, String> {
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
        }
        val envelope = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$serviceType">$bodyXml</u:$action></s:Body></s:Envelope>"""
        try {
            conn.outputStream.use { it.write(envelope.toByteArray(Charset.forName("UTF-8"))) }
            return readCapped(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** デバイス記述からContentDirectoryのcontrolURLを探す。locationのベースURLで解決する */
    fun findContentDirectoryControlUrl(deviceDescUrl: String, deviceXml: String): String? {
        val base = deviceDescUrl.substringBeforeLast("/") + "/"
        var inCd = false
        var serviceType = ""
        var controlUrl = ""
        try {
            val f = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val p = f.newPullParser().apply { setInput(StringReader(deviceXml)) }
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "serviceType" -> {
                            val t = p.nextText()
                            inCd = t.contains("ContentDirectory")
                            serviceType = t
                        }
                        "controlURL" -> if (inCd) controlUrl = p.nextText()
                    }
                } else if (e == XmlPullParser.END_TAG && p.name == "service") {
                    if (inCd && controlUrl.isNotEmpty()) {
                        return if (controlUrl.startsWith("http")) controlUrl else base + controlUrl.trimStart('/')
                    }
                    inCd = false
                    serviceType = ""
                    controlUrl = ""
                }
                e = p.next()
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** ストリーミングXMLパーサでDIDLを抽出（巨大応答でも安全） */
    fun parseDidl(didl: String): List<DlnaItem> {
        val items = mutableListOf<DlnaItem>()
        try {
            val f = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val p = f.newPullParser().apply { setInput(StringReader(didl)) }
            var id = ""
            var isContainer = false
            var title = ""
            var url = ""
            var thumb = ""
            var mime = ""
            var upnpClass = ""
            var inItem = false
            var textTarget = ""
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "item", "container" -> {
                            inItem = true
                            isContainer = p.name == "container"
                            id = p.getAttributeValue(null, "id") ?: ""
                            title = ""
                            url = ""
                            thumb = ""
                            mime = ""
                            upnpClass = ""
                        }
                        // namespace-awareのため dc:title→"title"、upnp:class→"class" の
                        // ローカル名で届く（写真一覧が取得できている実績と同一条件）
                        "title", "res", "albumArtURI", "class" -> if (inItem) {
                            textTarget = p.name
                            if (p.name == "res" && mime.isEmpty()) {
                                // protocolInfo="http-get:*:image/jpeg:*" の第3フィールド
                                val pi = p.getAttributeValue(null, "protocolInfo") ?: ""
                                mime = pi.split(":").getOrNull(2)?.trim()?.lowercase() ?: ""
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inItem && textTarget.isNotEmpty()) {
                        when (textTarget) {
                            "title" -> title += p.text
                            "res" -> url += p.text
                            "albumArtURI" -> thumb += p.text
                            "class" -> upnpClass += p.text
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "title", "res", "albumArtURI", "class" -> textTarget = ""
                        "item", "container" -> {
                            items.add(DlnaItem(id, title.trim(), url.trim(), thumb.trim(), isContainer, mime, upnpClass.trim()))
                            inItem = false
                        }
                    }
                }
                e = p.next()
            }
        } catch (_: Exception) {
        }
        return items
    }

    /**
     * location(device記述URL) → ContentDirectory Browse(ObjectID=0) まで一気に実行し、
     * 人間可読なレポートを返す。
     */
    suspend fun browseRoot(locationUrl: String, objectId: String = "0"): String = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeout(60_000) { browseRootInner(locationUrl, objectId) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            "Browse全体タイムアウト（60秒）"
        }
    }

    private suspend fun browseRootInner(locationUrl: String, objectId: String): String {
        val sb = StringBuilder()
        sb.append("LOCATION=$locationUrl\n")
        val (codeD, devXml) = try {
            httpGet(locationUrl)
        } catch (e: Exception) {
            return sb.append("device記述取得失敗: ${e.message}").toString()
        }
        sb.append("device記述: HTTP $codeD (${devXml.length} bytes)\n")
        if (codeD !in 200..299) {
            sb.append(devXml.take(1000))
            return sb.toString()
        }
        val control = findContentDirectoryControlUrl(locationUrl, devXml)
        sb.append("ContentDirectory controlURL=${control ?: "(なし)"}\n")
        if (control == null) {
            sb.append(devXml.take(2000))
            return sb.toString()
        }
        val serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1"
        val (total, returned, found) = browseItems(control, serviceType, objectId, 50)
        sb.append("Browse items=${found.size} total=$total returned=$returned\n")
        found.take(20).forEach {
            sb.append("- [${if (it.isContainer) "C" else "I"}] id=${it.id} title=${it.title}\n  url=${it.url.take(160)}\n  thumb=${it.thumbUrl.take(160)}\n")
        }
        return sb.toString()
    }

    data class BrowsePage(val total: Int, val returned: Int, val items: List<DlnaItem>)

    /** コンテナ連鎖を辿って最初の写真アイテムの(title, url)を返す */
    suspend fun firstPhotoUrl(locationUrl: String): Triple<String, String, String> =
        withContext(Dispatchers.IO) {
            val (_, devXml) = httpGet(locationUrl)
            val control = findContentDirectoryControlUrl(locationUrl, devXml)
                ?: throw IllegalStateException("ContentDirectoryなし")
            val serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1"
            var oid = "0"
            var title = ""
            repeat(6) {
                val page = browseItems(control, serviceType, oid, 50)
                val photo = page.items.firstOrNull { !it.isContainer && it.url.isNotEmpty() }
                if (photo != null) return@withContext Triple(photo.title, photo.url, photo.thumbUrl)
                oid = page.items.firstOrNull { it.isContainer }?.id
                    ?: throw IllegalStateException("写真なし(depth=$it)")
            }
            throw IllegalStateException("深さ上限")
        }

    /** URLをGETしてapp-private領域に保存し、バイト数を返す。失敗時は不完全ファイルを消す */
    suspend fun downloadToFile(
        url: String,
        dst: java.io.File,
        maxBytes: Long = 100L * 1024 * 1024,
    ): Long =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
            }
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                var total = 0L
                conn.inputStream.use { inp ->
                    dst.outputStream().use { out ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IllegalStateException("ファイルが大きすぎます")
                            out.write(buf, 0, n)
                        }
                    }
                }
                total
            } catch (e: Exception) {
                try {
                    dst.delete()
                } catch (_: Exception) {
                }
                throw e
            } finally {
                conn.disconnect()
            }
        }

    suspend fun browseItems(controlUrl: String, serviceType: String, objectId: String, count: Int, start: Int = 0): BrowsePage =
        withContext(Dispatchers.IO) {
            val body = "<ObjectID>$objectId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>"
            val (codeB, resp) = httpSoap(controlUrl, serviceType, "Browse", body)
            if (codeB !in 200..299) return@withContext BrowsePage(-1, -1, emptyList())
            val totalRe = Regex("""<(?:n:)?TotalMatches>(\d+)</""")
            val numRe = Regex("""<(?:n:)?NumberReturned>(\d+)</""")
            val total = totalRe.find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val returned = numRe.find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val didlEsc = Regex("""<Result>(.*?)</Result>""", RegexOption.DOT_MATCHES_ALL).find(resp)?.groupValues?.get(1) ?: ""
            val didl = didlEsc.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
            BrowsePage(total, returned, parseDidl(didl))
        }

    /**
     * 0 → 最初のコンテナ → 最初のコンテナ…と自動で辿り、写真アイテムまで到達する。
     * 手入力なしで一覧取得するための診断用ドリル。
     */
    suspend fun drillDown(locationUrl: String): String = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeout(90_000) { drillDownInner(locationUrl) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            "ドリル全体タイムアウト（90秒）"
        }
    }

    private suspend fun drillDownInner(locationUrl: String): String {        val sb = StringBuilder()
        val (_, devXml) = try {
            httpGet(locationUrl)
        } catch (e: Exception) {
            return "device記述取得失敗: ${e.message}"
        }
        val control = findContentDirectoryControlUrl(locationUrl, devXml)
            ?: return "ContentDirectoryなし"
        val serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1"
        var oid = "0"
        repeat(6) { depth ->
            val page = try {
                browseItems(control, serviceType, oid, 50)
            } catch (e: Exception) {
                return sb.append("Browse失敗(depth=$depth oid=$oid): ${e.message}").toString()
            }
            sb.append("depth=$depth oid=$oid total=${page.total} items=${page.items.size}\n")
            val next = page.items.firstOrNull { it.isContainer }
            val photos = page.items.filter { !it.isContainer }
            photos.take(30).forEach {
                sb.append("- [I] title=${it.title}\n  url=${it.url.take(200)}\n  thumb=${it.thumbUrl.take(200)}\n")
            }
            if (next == null) return sb.append("(末端到達)").toString()
            if (photos.isNotEmpty()) return sb.append("(写真とコンテナ混在・終了)").toString()
            oid = next.id
        }
        return sb.append("(深さ上限)").toString()
    }
}
