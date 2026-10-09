package jp.yuki.a6000transfer.sony

/** Corrects OCR lookalikes only when a nearby or previously connected SSID confirms them. */
object CameraSsidResolver {
    private val confusable = listOf(setOf('0', 'O'), setOf('1', 'I', 'l'), setOf('5', 'S'), setOf('8', 'B'))

    fun resolve(scanned: String, nearby: List<String>, saved: String?): String {
        val available = nearby.filter { it.startsWith("DIRECT-") }.distinct()
        if (scanned in available) return scanned
        val matches = available.filter { areLookalikes(scanned, it) }
        if (matches.size == 1) return matches.single()
        if (matches.isEmpty() && saved != null && areLookalikes(scanned, saved)) return saved
        return scanned
    }

    internal fun areLookalikes(a: String, b: String): Boolean {
        if (!a.startsWith("DIRECT-") || !b.startsWith("DIRECT-")) return false
        val nonceA = a.removePrefix("DIRECT-").substringBefore(':')
        val nonceB = b.removePrefix("DIRECT-").substringBefore(':')
        if (a.substringAfter(':', "") != b.substringAfter(':', "") || nonceA.length != nonceB.length) return false
        val differences = nonceA.indices.filter { nonceA[it] != nonceB[it] }
        return differences.size in 1..2 && differences.all { index ->
            val left = nonceA[index]
            val right = nonceB[index]
            left.equals(right, ignoreCase = true) || confusable.any { pair -> left in pair && right in pair }
        }
    }
}
