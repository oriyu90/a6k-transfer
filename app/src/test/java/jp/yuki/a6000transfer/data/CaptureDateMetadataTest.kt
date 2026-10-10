package jp.yuki.a6000transfer.data

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureDateMetadataTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun `EXIF元日時にオフセットがないJPEGは元ファイルを残して補完する`() {
        val source = jpeg()
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_MAKE, "SONY")
            saveAttributes()
        }
        val prepared = CaptureDateMetadata.prepare(source, "image/jpeg", "", tokyo)
        try {
            assertNotEquals(source, prepared)
            assertNull(ExifInterface(source.absolutePath).getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            ExifInterface(prepared.absolutePath).apply {
                assertEquals("2020:01:02 03:04:05", getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("+09:00", getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                assertEquals("SONY", getAttribute(ExifInterface.TAG_MAKE))
            }
        } finally { prepared.delete(); source.delete() }
    }

    @Test fun `日時が揃ったJPEGはバイトを変更しない`() {
        val source = jpeg()
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+09:00")
            saveAttributes()
        }
        try {
            assertSame(source, CaptureDateMetadata.prepare(source, "image/jpeg", "", tokyo))
        } finally { source.delete() }
    }

    @Test fun `EXIFがない場合はDLNAのタイムゾーン付き日時を保存する`() {
        val source = jpeg()
        val prepared = CaptureDateMetadata.prepare(source, "image/jpeg", "2019-07-08T12:34:56+02:00", tokyo)
        try {
            assertNotEquals(source, prepared)
            ExifInterface(prepared.absolutePath).apply {
                assertEquals("2019:07:08 12:34:56", getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("+02:00", getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            }
        } finally { prepared.delete(); source.delete() }
    }

    @Test fun `日付コンテナは日付だけ補完し不正な日付は無視する`() {
        assertEquals("2018:02:03 12:00:00", CaptureDateMetadata.parse("2018/02/03", tokyo)?.exifDate)
        assertEquals("2018:02:03 12:00:00", CaptureDateMetadata.parse("2018.02.03", tokyo)?.exifDate)
        assertNull(CaptureDateMetadata.parse("2018/02/30", tokyo))
        assertNull(CaptureDateMetadata.parse("Date", tokyo))
        val source = jpeg()
        try {
            assertSame(source, CaptureDateMetadata.prepare(source, "image/jpeg", "Date", tokyo))
            val prepared = CaptureDateMetadata.prepare(source, "image/jpeg", "2018/02/03", tokyo)
            try {
                assertEquals("2018:02:03 12:00:00", ExifInterface(prepared.absolutePath)
                    .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            } finally { prepared.delete() }
        }
        finally { source.delete() }
    }

    private fun jpeg(): File = File.createTempFile("capture-test-", ".jpg").also { file ->
        checkNotNull(javaClass.getResourceAsStream("/blank.jpg")).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
