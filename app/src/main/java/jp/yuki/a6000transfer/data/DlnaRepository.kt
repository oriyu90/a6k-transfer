package jp.yuki.a6000transfer.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import jp.yuki.a6000transfer.sony.CameraProfile
import jp.yuki.a6000transfer.sony.DiscoveryResult
import jp.yuki.a6000transfer.sony.DlnaClient
import jp.yuki.a6000transfer.sony.DlnaItem
import jp.yuki.a6000transfer.sony.SsdpDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Photo(
    val id: String,
    val title: String,
    val url: String,
    val dateTitle: String,
)

data class DateGroup(
    val title: String,
    val photos: List<Photo>,
)

/** 本番転送フローのRepository。固定ID・固定ポートを持たず毎回解決する。 */
object DlnaRepository {
    const val SERVICE_TYPE = "urn:schemas-upnp-org:service:ContentDirectory:1"

    /** サムネイル同時取得の上限（大量写真時のソケット枯渇防止） */
    private val thumbSlots = kotlinx.coroutines.sync.Semaphore(4)

    /** ギャラリー登録済みタイトル一覧（重複転送の事前照合用。巨大ギャラリー対策で上限あり） */
    suspend fun existingTitles(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val out = mutableSetOf<String>()
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val idx = c.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    out.add(c.getString(idx) ?: "")
                    if (out.size >= 10000) break
                }
            }
        } catch (_: Exception) {
        }
        out
    }

    /** NOTIFY待受からDmsDescのLOCATIONを抜き出す */
    suspend fun discoverLocation(context: Context, waitMs: Long = 20000): String? {
        val pkts = SsdpDiscovery.listen(context, waitMs)
        return firstLocation(pkts)
    }

    private val locRe = Regex("""LOCATION:\s*(http://\S+?\.xml)""", RegexOption.IGNORE_CASE)

    private fun firstLocation(pkts: List<String>): String? {
        // Sony製DMSを優先（複数応答時の取り違え防止）
        val locs = pkts.mapNotNull { locRe.find(it)?.groupValues?.get(1)?.trim() }.distinct()
        return locs.firstOrNull { it.contains("10.0.0.1") || it.contains("192.168.122.1") }
            ?: pkts.mapNotNull { locRe.find(it) }.firstOrNull()?.groupValues?.get(1)?.trim()
            ?: locs.firstOrNull()
    }

    /**
     * 機種プロファイル対応の自動探索。
     * auto時はα6000→α6100予測の順にST・GW候補を試す。
     * DLNA LOCATIONが本命。副経路としてScalarWebAPI(dd.xml)の有無も返す。
     */
    suspend fun discoverCamera(context: Context, profileId: String?): DiscoveryResult =
        withContext(Dispatchers.IO) {
            val profiles = if (CameraProfile.isAuto(profileId)) {
                listOf(CameraProfile.A6000_PROFILE, CameraProfile.A6100_PROFILE)
            } else {
                listOf(CameraProfile.of(profileId))
            }
            val waitMs = profiles.maxOf { it.listenMs }
            // 1. パッシブ待受（α6000実績の本命経路）
            var location = firstLocation(SsdpDiscovery.listen(context, waitMs))
            // 2. M-SEARCHでSTを順に試す
            if (location == null) {
                val sts = profiles.flatMap { it.ssdpSt }.distinct()
                val gateways = profiles.flatMap { it.gateways }.distinct()
                for (st in sts) {
                    val hits = try {
                        SsdpDiscovery.discover(context, st, 6000, gateways)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val sonyFirst = hits.sortedBy { !it.server.contains("Sony", ignoreCase = true) }
                    location = sonyFirst.firstOrNull { it.location.isNotEmpty() }?.location
                    if (location != null) break
                }
            }
            // 3. ScalarWebAPI(dd.xml)副経路の有無だけ確認（次世代機の判定材料）
            val gateways = profiles.flatMap { it.gateways }.distinct()
            var remoteApi: String? = null
            for (gw in gateways) {
                if (probeScalarApi(gw)) {
                    remoteApi = "http://$gw"
                    break
                }
            }
            DiscoveryResult(location, remoteApi, gateways)
        }

    /** dd.xmlにScalarWebAPI記述があればtrue（短タイムアウト） */
    private fun probeScalarApi(gateway: String): Boolean {
        return try {
            val conn = (java.net.URL("http://$gateway/sony/dd.xml").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            try {
                if (conn.responseCode !in 200..299) return false
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                text.contains("ScalarWebAPI")
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun controlUrl(locationUrl: String): String = withContext(Dispatchers.IO) {
        val conn = (java.net.URL(locationUrl).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            DlnaClient.findContentDirectoryControlUrl(locationUrl, xml)
                ?: throw IllegalStateException("ContentDirectoryなし")
        } finally {
            conn.disconnect()
        }
    }

    /** 全ページ取得（サーバー異常時の無限ループ防止に上限あり） */
    suspend fun browseAll(control: String, objectId: String): List<DlnaItem> {
        val out = mutableListOf<DlnaItem>()
        var start = 0
        repeat(20) {
            val page = DlnaClient.browseItems(control, SERVICE_TYPE, objectId, 100, start)
            if (page.total < 0) throw IllegalStateException("Browse失敗 oid=$objectId")
            out.addAll(page.items)
            if (out.size >= page.total || page.items.isEmpty()) return out
            start += page.items.size
            if (out.size >= 2000) return out
        }
        return out
    }

    /** ツリー走査：日付グループ→写真一覧。階層変動に強い深さ優先 */
    suspend fun loadTree(locationUrl: String, maxDepth: Int = 3): List<DateGroup> {
        val control = controlUrl(locationUrl)
        val groups = mutableListOf<DateGroup>()
        suspend fun walk(oid: String, dateTitle: String, depth: Int) {
            if (depth > maxDepth) return
            val items = browseAll(control, oid)
            val photos = items.filter { !it.isContainer && it.url.isNotEmpty() }
            if (photos.isNotEmpty()) {
                val t = dateTitle.ifBlank { "日付不明" }
                val existing = groups.indexOfFirst { it.title == t }
                val mapped = photos.map { Photo(it.id, it.title.ifBlank { it.id }, it.url, t) }
                if (existing >= 0) {
                    groups[existing] = groups[existing].copy(photos = groups[existing].photos + mapped)
                } else {
                    groups.add(DateGroup(t, mapped))
                }
            }
            items.filter { it.isContainer }.forEach { c ->
                walk(c.id, if (c.title.isNotBlank() && dateTitle.isBlank()) c.title else dateTitle.ifBlank { c.title }, depth + 1)
            }
        }
        walk("0", "", 0)
        return groups
    }

    fun cacheFile(context: Context, kind: String, title: String): File {
        val dir = File(context.cacheDir, "a6000/$kind").apply { mkdirs() }
        trimCacheDir(dir, maxFiles = 300, maxBytes = 200L * 1024 * 1024)
        val safe = title.ifBlank { "photo" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return File(dir, safe)
    }

    /** キャッシュ肥大化防止：ファイル数・合計サイズの上限を超えたら古いものから削除 */
    private fun trimCacheDir(dir: File, maxFiles: Int, maxBytes: Long) {
        try {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            var count = files.size
            for (f in files) {
                if (count <= maxFiles && total <= maxBytes) break
                val len = f.length()
                if (f.delete()) {
                    count--
                    total -= len
                }
            }
        } catch (_: Exception) {
        }
    }

    /** サムネイル用に縮小デコード。キャッシュ済みファイルを使い回す */
    suspend fun thumbnailBitmap(context: Context, photo: Photo, maxSize: Int = 320): Bitmap? {
        thumbSlots.acquire()
        try {
            return withContext(Dispatchers.IO) {
                try {
                    val f = cacheFile(context, "thumb", photo.id.ifBlank { photo.title })
                    if (!f.exists() || f.length() == 0L) {
                        DlnaClient.downloadToFile(photo.url, f)
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            thumbSlots.release()
        }
    }

    /** フルサイズ取得（進捗コールバック付き） */
    suspend fun downloadFull(
        context: Context,
        photo: Photo,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val f = cacheFile(context, "full", photo.title)
        val conn = (java.net.URL(photo.url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            f
        } finally {
            conn.disconnect()
        }
    }

    /** ギャラリー（DCIM/A6000Transfer）に保存 */
    suspend fun saveToGallery(context: Context, file: File, title: String): Uri? =
        withContext(Dispatchers.IO) {
            val name = if (title.lowercase().endsWith(".jpg") || title.lowercase().endsWith(".jpeg")) title else "$title.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/A6000Transfer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                null
            }
        }
}
