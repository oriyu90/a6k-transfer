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
    val kind: MediaKind = MediaKind.PHOTO,
    val mime: String = "",
)

/** カメラがDLNAで公開するメディア種別（α6000実測: JPEG写真＋MP4動画のみ） */
enum class MediaKind { PHOTO, VIDEO, OTHER }

/**
 * DIDLのmime・upnp:class・タイトル拡張子から種別を判定する。
 * α6000実測値: 写真=image/jpeg＋object.item.imageItem.photo、
 * 動画=video/mp4＋object.item.videoItem.movie（DLNA PN=AVC_MP4_MP_HD）。
 * RAW（.ARW等）はツリーに現れないためOTHER扱い（転送対象外）。
 */
fun classifyMedia(mime: String, upnpClass: String, title: String): MediaKind {
    val m = mime.lowercase()
    val c = upnpClass.lowercase()
    val ext = title.substringAfterLast('.', "").lowercase()
    if (m.startsWith("video/") || c.contains("videoitem") ||
        ext in setOf("mp4", "m2ts", "mts", "mov")
    ) return MediaKind.VIDEO
    if (m.startsWith("image/") || c.contains("imageitem") ||
        ext in setOf("jpg", "jpeg", "heic", "heif", "png")
    ) return MediaKind.PHOTO
    return MediaKind.OTHER
}

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
            // 動画タイトルも照合する（v1.1.0でMP4転送対応）
            if (out.size < 10000) {
                context.contentResolver.query(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME),
                    null, null, null,
                )?.use { c ->
                    val idx = c.getColumnIndex(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        out.add(c.getString(idx) ?: "")
                        if (out.size >= 10000) break
                    }
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
            // 転送対象は写真・動画のみ。カメラが公開しない種別（RAW等）は対象外
            val photos = items.filter { !it.isContainer && it.url.isNotEmpty() }
                .mapNotNull { item ->
                    val kind = classifyMedia(item.mime, item.upnpClass, item.title)
                    if (kind == MediaKind.OTHER) return@mapNotNull null
                    val t = item.title.ifBlank { item.id }
                    Photo(item.id, t, item.url, "", kind, item.mime)
                }
            if (photos.isNotEmpty()) {
                val t = dateTitle.ifBlank { "日付不明" }
                val existing = groups.indexOfFirst { it.title == t }
                val mapped = photos.map { it.copy(dateTitle = t) }
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

    /** フルサイズ取得（進捗コールバック・上限付き。動画の大容量化に対応） */
    suspend fun downloadFull(
        context: Context,
        photo: Photo,
        maxBytes: Long = 4L * 1024 * 1024 * 1024,
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
            try {
                conn.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) throw IllegalStateException("ファイルが大きすぎます")
                            out.write(buf, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                throw e
            }
            f
        } finally {
            conn.disconnect()
        }
    }

    /** 保存フォルダ名（DCIM配下・Download配下のサブフォルダ）。英数/_/-のみ、既定A6000Transfer */
    const val DEFAULT_FOLDER = "A6000Transfer"

    fun saveFolder(context: Context): String {
        val raw = try {
            context.getSharedPreferences("a6000diag", Context.MODE_PRIVATE)
                .getString("folder", DEFAULT_FOLDER) ?: DEFAULT_FOLDER
        } catch (_: Exception) {
            DEFAULT_FOLDER
        }
        val clean = raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).trim('_')
        return clean.ifBlank { DEFAULT_FOLDER }
    }

    fun setSaveFolder(context: Context, name: String) {
        try {
            context.getSharedPreferences("a6000diag", Context.MODE_PRIVATE)
                .edit().putString("folder", name.trim().take(64)).apply()
        } catch (_: Exception) {
        }
    }

    /** 端末上で過去に転送成功したタイトル（永続履歴。MediaStore照合と併用する） */
    private const val PREF_HISTORY = "a6000history"
    private const val KEY_TITLES = "titles"
    private const val MAX_HISTORY = 10000

    fun downloadHistory(context: Context): Set<String> = try {
        context.getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE)
            .getStringSet(KEY_TITLES, emptySet())?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    fun addDownloadHistory(context: Context, titles: Collection<String>) {
        if (titles.isEmpty()) return
        try {
            val prefs = context.getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE)
            val cur = prefs.getStringSet(KEY_TITLES, emptySet())?.toMutableSet() ?: mutableSetOf()
            cur.addAll(titles)
            // 肥大防止：上限超過時は古い順が分からないため半分を残す（Set順は不定だが履歴用途で許容）
            val trimmed = if (cur.size > MAX_HISTORY) cur.toList().takeLast(MAX_HISTORY).toSet() else cur
            prefs.edit().putStringSet(KEY_TITLES, trimmed).apply()
        } catch (_: Exception) {
        }
    }

    fun clearDownloadHistory(context: Context) {
        try {
            context.getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE)
                .edit().remove(KEY_TITLES).apply()
        } catch (_: Exception) {
        }
    }

    /** 種別に応じてギャラリー（写真/動画）へ保存 */
    suspend fun saveMedia(context: Context, file: File, photo: Photo): Uri? =
        when (photo.kind) {
            MediaKind.VIDEO -> saveVideo(context, file, photo.title)
            else -> savePhoto(context, file, photo.title, photo.mime)
        }

    /** ギャラリー（DCIM/保存フォルダ）に写真を保存 */
    suspend fun saveToGallery(context: Context, file: File, title: String): Uri? =
        savePhoto(context, file, title, "image/jpeg")

    private suspend fun savePhoto(context: Context, file: File, title: String, mime: String): Uri? =
        withContext(Dispatchers.IO) {
            val lower = title.lowercase()
            val name = if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".png")
            ) {
                title
            } else {
                "$title.jpg"
            }
            val mimeType = if (mime.lowercase().startsWith("image/")) mime.lowercase() else "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + saveFolder(context))
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

    /** ギャラリー（DCIM/保存フォルダ）に動画を保存 */
    suspend fun saveVideo(context: Context, file: File, title: String): Uri? =
        withContext(Dispatchers.IO) {
            val lower = title.lowercase()
            val name = if (lower.endsWith(".mp4") || lower.endsWith(".mov") ||
                lower.endsWith(".m2ts") || lower.endsWith(".mts")
            ) {
                title
            } else {
                "$title.mp4"
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + saveFolder(context))
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
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
