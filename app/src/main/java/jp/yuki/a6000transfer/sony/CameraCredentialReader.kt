package jp.yuki.a6000transfer.sony

import java.text.Normalizer
import kotlin.math.abs

/** Combines OCR lines without guessing missing characters or exposing full OCR text. */
object CameraCredentialReader {
    data class Line(val text: String, val centerX: Int, val centerY: Int)
    data class Reading(
        val credentials: CameraCredentialsParser.Credentials,
        val passwordConfirmed: Boolean,
        val ssidNeedsReview: Boolean,
    ) {
        val needsReview: Boolean get() = !passwordConfirmed || ssidNeedsReview
    }

    private data class Network(val value: String, val line: Line)
    private data class Password(val value: String, val line: Line, val labeled: Boolean)
    private val networkPattern = Regex("DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_-]+")
    private val passwordLabel = Regex("[パバ]スワフ?ード|\\b(?:password|passphrase|pin)\\b", RegexOption.IGNORE_CASE)
    private val ordinaryEight = Regex("[A-Za-z0-9]{8}")

    fun read(japanese: List<Line>, latin: List<Line>, width: Int, height: Int): Reading? {
        if (width <= 0 || height <= 0) return null
        val networks = (japanese + latin).mapNotNull { line ->
            if (line.centerX !in 0 until width || line.centerY !in 0 until height) return@mapNotNull null
            val compact = normalize(line.text).filterNot(Char::isWhitespace)
            val value = networkPattern.find(compact)?.value ?: return@mapNotNull null
            value.takeIf { it.toByteArray(Charsets.UTF_8).size <= 32 }?.let { Network(it, line) }
        }
        if (networks.isEmpty()) return null
        val distinct = networks.map { it.value }.distinct()
        if (distinct.drop(1).any { value ->
                value != distinct.first() && !CameraSsidResolver.areLookalikes(distinct.first(), value)
            }) return null
        val network = networks.first()
        val jp = passwordNear(japanese, network.line, width, height)
        val en = passwordNear(latin, network.line, width, height)
        val alignedLatin = en?.takeIf { jp == null || abs(it.line.centerY - jp.line.centerY) <= maxOf(18, height / 30) }
        val (password, confirmed) = when {
            jp != null && alignedLatin?.value == jp.value &&
                (jp.labeled || alignedLatin.labeled || ordinaryEight.matches(jp.value)) -> jp.value to true
            jp != null && alignedLatin != null && jp.value != alignedLatin.value -> {
                // A spurious punctuation mark in Japanese OCR is common on LCD text.
                // This is only a suggestion and must be reviewed before connecting.
                if (jp.labeled && !ordinaryEight.matches(jp.value) && ordinaryEight.matches(alignedLatin.value)) {
                    alignedLatin.value to false
                } else return null
            }
            else -> return null
        }
        val nonce = network.value.substringAfter("DIRECT-").substringBefore(':')
        val ambiguous = distinct.size > 1 || nonce.any { it in "O0Il1S5B8" }
        return Reading(CameraCredentialsParser.Credentials(network.value, password), confirmed, ambiguous)
    }

    /** A second still can corroborate a tentative result, but conflicting values fail closed. */
    fun combine(first: Reading?, second: Reading?): Reading? = when {
        first == null -> second
        second == null -> first
        first.credentials == second.credentials -> first.copy(
            passwordConfirmed = first.passwordConfirmed || second.passwordConfirmed,
            ssidNeedsReview = first.ssidNeedsReview || second.ssidNeedsReview,
        )
        first.passwordConfirmed && !second.passwordConfirmed -> first
        second.passwordConfirmed && !first.passwordConfirmed -> second
        else -> null
    }

    private fun passwordNear(lines: List<Line>, network: Line, width: Int, height: Int): Password? {
        val maxBelow = maxOf(30, height * 14 / 100)
        return lines.asSequence()
            .filter { it.centerX in 0 until width && it.centerY in 0 until height }
            .filter { it.centerY >= network.centerY - 5 && it.centerY <= network.centerY + maxBelow }
            .filter { abs(it.centerX - network.centerX) <= width * 35 / 100 }
            .mapNotNull { line ->
                val text = normalize(line.text)
                if (text.contains("DIREC", ignoreCase = true)) return@mapNotNull null
                val label = passwordLabel.find(text)
                val tail = if (label != null) text.substring(label.range.last + 1) else text
                val tokens = tail.trim().trimStart(':', '：').trim().split(Regex("\\s+"))
                val token = if (label != null) tokens.firstOrNull().orEmpty() else tokens.lastOrNull().orEmpty()
                token.takeIf { it.length in 8..63 && it.all { char -> char.code in 0x21..0x7e } }
                    ?.let { Password(it, line, label != null) }
            }
            .sortedWith(compareByDescending<Password> { it.labeled }.thenBy { it.line.centerY - network.centerY })
            .firstOrNull()
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
}
