package jp.yuki.a6000transfer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 種別判定とフォルダ名サニタイズの単体テスト。
 * 動画/RAW仕様（v1.1.0確定分）と不正入力の防御を固定する。
 */
class MediaKindTest {

    @Test
    fun `α6000実測値は正しく分類される`() {
        // 実測: image/jpeg + object.item.imageItem.photo
        assertEquals(MediaKind.PHOTO, classifyMedia("image/jpeg", "object.item.imageItem.photo", "DSC00074.JPG"))
        // 実測: video/mp4 + object.item.videoItem.movie
        assertEquals(MediaKind.VIDEO, classifyMedia("video/mp4", "object.item.videoItem.movie", "MAH00096.MP4"))
    }

    @Test
    fun `mimeが空でもクラスと拡張子で判定する`() {
        assertEquals(MediaKind.PHOTO, classifyMedia("", "object.item.imageItem.photo", "DSC.jpg"))
        assertEquals(MediaKind.VIDEO, classifyMedia("", "object.item.videoItem.movie", "MAH00096.MP4"))
        assertEquals(MediaKind.VIDEO, classifyMedia("", "", "MAH00096.MP4"))
        assertEquals(MediaKind.PHOTO, classifyMedia("", "", "DSC00074.JPG"))
    }

    @Test
    fun `RAW系はOTHER扱いで転送対象外`() {
        assertEquals(MediaKind.OTHER, classifyMedia("", "", "DSC00074.ARW"))
        assertEquals(MediaKind.OTHER, classifyMedia("application/octet-stream", "", "X.ARW"))
    }

    @Test
    fun `大文字小文字を無視する`() {
        assertEquals(MediaKind.VIDEO, classifyMedia("VIDEO/MP4", "", "A.MP4"))
        assertEquals(MediaKind.PHOTO, classifyMedia("", "Object.Item.ImageItem.Photo", "a.JPG"))
    }

    @Test
    fun `不明種別はOTHER`() {
        assertEquals(MediaKind.OTHER, classifyMedia("", "", ""))
        assertEquals(MediaKind.OTHER, classifyMedia("audio/mpeg", "object.item.audioItem", "x.mp3"))
        assertEquals(MediaKind.OTHER, classifyMedia("text/html", "", "page.html"))
    }

    @Test
    fun `保存フォルダ名は不正文字を置換し空なら既定に戻す`() {
        // このクラス内ではContext不要の正規化ロジックのみを検証する
        // （saveFolder本体はContext依存のため端末テストで確認）
        fun clean(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).trim('_').ifBlank { "A6000Transfer" }

        assertEquals("A6000Transfer", clean("A6000Transfer"))
        assertEquals("My_Folder-2", clean("My Folder-2"))
        assertEquals("TestA6K", clean("TestA6K"))
        assertEquals("TestA6K", clean("  TestA6K  "))
        assertFalse(clean("../evil").contains("/"))
        assertFalse(clean("a/b\\c").contains("/"))
        assertFalse(clean("a/b\\c").contains("\\"))
    }
}
