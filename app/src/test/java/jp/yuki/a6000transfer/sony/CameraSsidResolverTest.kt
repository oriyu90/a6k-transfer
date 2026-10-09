package jp.yuki.a6000transfer.sony

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSsidResolverTest {
    @Test fun resolvesSingleConfirmedOcrLookalike() {
        assertEquals("DIRECT-Ab10:a6000", CameraSsidResolver.resolve(
            "DIRECT-Ab1O:a6000", listOf("DIRECT-Ab10:a6000"), null,
        ))
    }

    @Test fun keepsAmbiguousOrUnrelatedResultForReview() {
        assertEquals("DIRECT-Ab1O:a6000", CameraSsidResolver.resolve(
            "DIRECT-Ab1O:a6000", listOf("DIRECT-Ab10:a6000", "DIRECT-Ab1O:a6000"), null,
        ))
        assertEquals("DIRECT-Ab1O:a6000", CameraSsidResolver.resolve(
            "DIRECT-Ab1O:a6000", listOf("DIRECT-Xy10:a6000"), null,
        ))
    }

    @Test fun resolvesCaseAndDigitLookalikesOnlyWithEvidence() {
        val scanned = "DIRECT-CNEO:a6000"
        val actual = "DIRECT-cNE0:a6000"
        assertEquals(actual, CameraSsidResolver.resolve(scanned, listOf(actual), null))
        assertEquals(actual, CameraSsidResolver.resolve(scanned, emptyList(), actual))
        assertEquals(scanned, CameraSsidResolver.resolve(scanned, emptyList(), null))
        assertEquals(scanned, CameraSsidResolver.resolve(scanned,
            listOf(actual, "DIRECT-CNE0:a6000"), null))
    }
}
