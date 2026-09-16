package jp.yuki.a6000transfer.sony

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

data class ScalarService(
    val type: String,
    val actionListUrl: String,
)

object SonyClient {
    var baseUrl: String = "http://10.0.0.1:10000"
    private const val TIMEOUT_MS = 8000

    fun endpoint(service: String): String = "$baseUrl/sony/$service"

    suspend fun post(
        service: String,
        method: String,
        params: String = "[]",
        version: String = "1.0",
        id: Int = 1,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("method", method)
            .put("params", JSONArray(params))
            .put("id", id)
            .put("version", version)
            .toString()
        val conn = (URL(endpoint(service)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            "HTTP $code\n$text"
        } catch (e: Exception) {
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            "HTTP $code\n$text"
        } catch (e: Exception) {
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }

    /** dd.xml相当のデバイス記述からScalarWebAPIサービス一覧を抜き出す */
    fun parseDeviceDesc(xml: String): List<ScalarService> {
        val out = mutableListOf<ScalarService>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val p = factory.newPullParser().apply { setInput(StringReader(xml)) }
            var type: String? = null
            var url: String? = null
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "X_ScalarWebAPI_ServiceType" -> type = p.nextText()
                        "X_ScalarWebAPI_ActionList_URL" -> url = p.nextText()
                    }
                    if (p.name == "X_ScalarWebAPI_Service") {
                        type = null
                        url = null
                    }
                } else if (event == XmlPullParser.END_TAG && p.name == "X_ScalarWebAPI_Service") {
                    if (type != null && url != null) out.add(ScalarService(type!!, url!!))
                }
                event = p.next()
            }
        } catch (_: Exception) {
        }
        return out
    }
}
