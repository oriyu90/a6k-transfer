package jp.yuki.a6000transfer.data

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit

/** 撮影時刻の優先順位: 画像内のEXIF → DLNA項目の日時 → 日付コンテナ。 */
internal object CaptureDateMetadata {
    private val exifFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val plainDateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val compactDate = DateTimeFormatter.ofPattern("uuuuMMdd")
        .withResolverStyle(ResolverStyle.STRICT)

    data class CaptureDate(val local: LocalDateTime, val offset: ZoneOffset) {
        val exifDate: String get() = local.format(exifFormat)
        val exifOffset: String get() = offset.id.let { if (it == "Z") "+00:00" else it }
    }

    fun parse(raw: String, zone: ZoneId = ZoneId.systemDefault()): CaptureDate? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        try {
            val date = OffsetDateTime.parse(value)
            if (date.year !in 1980..2100) return null
            return CaptureDate(date.toLocalDateTime(), date.offset)
        } catch (_: Exception) { }
        val local = try {
            when {
                value.matches(Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")) ->
                    LocalDateTime.parse(value, exifFormat)
                value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) ->
                    LocalDateTime.parse(value, plainDateTime)
                value.contains('T') -> LocalDateTime.parse(value)
                value.matches(Regex("\\d{8}")) -> LocalDate.parse(value, compactDate).atTime(12, 0)
                value.matches(Regex("\\d{4}[-/:.]\\d{2}[-/:.]\\d{2}")) ->
                    LocalDate.parse(value.replace('/', '-').replace(':', '-').replace('.', '-')).atTime(12, 0)
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        if (local.year !in 1980..2100) return null
        return CaptureDate(local, local.atZone(zone).offset)
    }

    /**
     * EXIFを編集する必要があるときだけ、元のダウンロードファイルを残して別コピーに書き込む。
     * EXIFが読めない、または空き容量不足なら元ファイルを転送する。
     */
    fun prepare(file: File, mime: String, dateHint: String, zone: ZoneId = ZoneId.systemDefault()): File {
        val jpeg = mime.equals("image/jpeg", ignoreCase = true) ||
            file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true)
        if (!jpeg) return file
        var staged: File? = null
        try {
            val source = ExifInterface(file.absolutePath)
            val original = parse(source.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty(), zone)
            val existingOffset = source.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            if (original != null && existingOffset != null && validOffset(existingOffset)) return file
            val hint = parse(dateHint, zone)
            val capture = when {
                original == null -> hint
                hint != null && hint.local.truncatedTo(ChronoUnit.SECONDS) == original.local -> hint
                else -> original
            } ?: return file

            staged = File.createTempFile("capture-date-", ".jpg", file.parentFile)
            file.copyTo(staged, overwrite = true)
            val target = ExifInterface(staged.absolutePath)
            if (original == null) {
                target.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, capture.exifDate)
            }
            target.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, capture.exifOffset)
            target.saveAttributes()
            // 書き込み失敗やパース不能な値を含むファイルを公開しない。
            val verified = ExifInterface(staged.absolutePath)
            if (verified.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != capture.exifDate ||
                verified.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) != capture.exifOffset) {
                staged.delete()
                return file
            }
            return staged
        } catch (_: Exception) {
            staged?.delete()
            return file
        }
    }

    private fun validOffset(value: String): Boolean = try {
        if (!value.matches(Regex("[+-]\\d{2}:\\d{2}"))) false
        else { ZoneOffset.of(value); true }
    } catch (_: Exception) {
        false
    }
}
