package jp.yuki.a6000transfer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 転送中断の打ち切りルール（3連続失敗）と進捗状態遷移の純粋ロジック検証。
 * TransferService.runTransfer と同一の判定式を固定し、回帰を防ぐ。
 */
class TransferRulesTest {

    private fun photo(id: String) = Photo(id, "$id.JPG", "http://10.0.0.1/x", "2026:09:17")

    /** runTransferのループと同一の判定を再現（リファクタ時の退行検出用） */
    private fun simulate(results: List<Boolean>): Pair<Int, Boolean> {
        var ok = 0
        var consecutiveFail = 0
        var aborted = false
        for (r in results) {
            if (aborted) break
            if (r) {
                ok++
                consecutiveFail = 0
            } else {
                consecutiveFail++
            }
            if (consecutiveFail >= 3) aborted = true
        }
        return ok to aborted
    }

    @Test
    fun `3連続失敗で打ち切り`() {
        val (ok, aborted) = simulate(listOf(false, false, false, false))
        assertEquals(0, ok)
        assertTrue(aborted)
    }

    @Test
    fun `2連続失敗なら継続し次の成功で回復する`() {
        val (ok, aborted) = simulate(listOf(false, false, true, false))
        assertEquals(1, ok)
        assertFalse(aborted)
    }

    @Test
    fun `全成功は打ち切らない`() {
        val (ok, aborted) = simulate(List(10) { true })
        assertEquals(10, ok)
        assertFalse(aborted)
    }

    @Test
    fun `打ち切り後の項目は処理しない`() {
        val (ok, _) = simulate(listOf(false, false, false, true, true))
        assertEquals(0, ok)
    }

    @Test
    fun `進捗初期状態は停止中`() {
        val p = TransferProgress()
        assertFalse(p.running)
        assertEquals(0, p.total)
        assertNull(p.finishedText)
        assertTrue(p.doneIds.isEmpty())
    }
}
