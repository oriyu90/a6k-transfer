package jp.yuki.a6000transfer.sony

/** Corrects one common OCR lookalike only when a known Wi-Fi SSID confirms it. */
object CameraSsidResolver {
    private val confusable = listOf(setOf('0', 'O'), setOf('1', 'I', 'l'), setOf('5', 'S'), setOf('8', 'B'))

    fun resolve(scanned: String, nearby: List<String>, saved: String?): String {
        val available = nearby.distinct()
        if (scanned in available) return scanned
        val matches = available.filter { closeLookalike(scanned, it) }
        if (matches.size == 1) return matches.single()
        if (matches.isEmpty() && saved != null && closeLookalike(scanned, saved)) return saved
        return scanned
    }

    private fun closeLookalike(a: String, b: String): Boolean {
        if (!a.startsWith("DIRECT-") || !b.startsWith("DIRECT-") || a.length != b.length) return false
        val differences = a.indices.filter { a[it] != b[it] }
        return differences.size == 1 && confusable.any { pair ->
            a[differences.single()] in pair && b[differences.single()] in pair
        }
    }
}
