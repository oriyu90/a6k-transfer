package jp.yuki.a6000transfer.sony

import java.text.Normalizer

/** Reads the credential panel shown by Sony cameras. Never logs or persists OCR text. */
object CameraCredentialsParser {
    data class Credentials(val ssid: String, val passphrase: String)

    private val ssidPattern = Regex("DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_-]+")
    private val passwordLabel = Regex("(?:[パバ]スワード|password|passphrase|PIN)", RegexOption.IGNORE_CASE)

    fun parse(rawLines: List<String>): Credentials? {
        val lines = rawLines.map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
        // OCR often inserts spaces into DIRECT or after ':' on the camera LCD.
        val ssids = lines.flatMap { line ->
            ssidPattern.findAll(line.filterNot(Char::isWhitespace)).map { it.value }.toList()
        }
            .filter { it.toByteArray(Charsets.UTF_8).size <= 32 }
            .distinct()
        if (ssids.size != 1) return null

        val passwords = lines.mapIndexedNotNull { index, line ->
            val label = passwordLabel.find(line) ?: return@mapIndexedNotNull null
            val afterLabel = line.substring(label.range.last + 1).trimStart(' ', '\t', ':', '：')
            val candidate = (afterLabel.ifEmpty { lines.getOrNull(index + 1).orEmpty() })
                .trim().substringBefore(' ').substringBefore('\t')
            candidate.takeIf { value ->
                value.length in 8..63 && value.all { it.code in 0x21..0x7e }
            }
        }.distinct()
        if (passwords.size != 1) return null
        return Credentials(ssids.single(), passwords.single())
    }
}
