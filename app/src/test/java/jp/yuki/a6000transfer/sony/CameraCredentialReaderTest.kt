package jp.yuki.a6000transfer.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCredentialReaderTest {
    private fun line(text: String, y: Int, x: Int = 500) = CameraCredentialReader.Line(text, x, y)
    private val network = line("SSID DIRECT-Ab1O:a6000", 500)

    @Test fun confirmsWithJapaneseAndLatinAgreement() {
        val reading = CameraCredentialReader.read(
            listOf(network, line("バスワード Sample8K", 560)),
            listOf(network, line("/AA Sample8K", 560)), 1280, 960,
        )
        assertEquals("Sample8K", reading?.credentials?.passphrase)
        assertTrue(reading?.passwordConfirmed == true)
        assertTrue(reading?.ssidNeedsReview == true)
    }

    @Test fun infersUnreadableLabelOnlyWithTwoModelAgreement() {
        val reading = CameraCredentialReader.read(
            listOf(network, line("P0 Sample8K", 520)),
            listOf(network, line("P0 Sample8K", 520)), 1280, 960,
        )
        assertTrue(reading?.passwordConfirmed == true)
        assertNull(CameraCredentialReader.read(
            listOf(network, line("P0 Sample8K", 520)), emptyList(), 1280, 960,
        ))
    }

    @Test fun suggestsLatinWhenJapaneseAddsPunctuation() {
        val reading = CameraCredentialReader.read(
            listOf(network, line("パスワード Samp.le8K", 560)),
            listOf(network, line("/AA Sample8K", 560)), 1280, 960,
        )
        assertEquals("Sample8K", reading?.credentials?.passphrase)
        assertFalse(reading?.passwordConfirmed ?: true)
    }

    @Test fun prefersValueImmediatelyAfterPasswordLabel() {
        val reading = CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K extraText", 560)),
            listOf(network, line("password: Sample8K extraText", 560)), 1280, 960,
        )
        assertEquals("Sample8K", reading?.credentials?.passphrase)
        assertTrue(reading?.passwordConfirmed == true)
    }

    @Test fun keepsLookalikeSsidDisagreementForWifiConfirmation() {
        val reading = CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K", 560)),
            listOf(line("SSID DIRECT-Ab10:a6000", 500), line("/AA Sample8K", 560)),
            1280, 960,
        )
        assertEquals("DIRECT-Ab1O:a6000", reading?.credentials?.ssid)
        assertTrue(reading?.ssidNeedsReview == true)
        assertNull(CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K", 560)),
            listOf(line("SSID DIRECT-Xy10:a6000", 500), line("/AA Sample8K", 560)),
            1280, 960,
        ))
    }

    @Test fun rejectsConflictingPlausiblePasswordsAndDistantText() {
        assertNull(CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K", 560)),
            listOf(network, line("/AA Sample9K", 560)), 1280, 960,
        ))
        assertNull(CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K", 900)),
            listOf(network, line("/AA Sample8K", 900)), 1280, 960,
        ))
        assertNull(CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K", 560)),
            emptyList(), 1280, 960,
        ))
        assertNull(CameraCredentialReader.read(
            listOf(network, line("SampleTen1", 560)),
            listOf(network, line("SampleTen1", 560)), 1280, 960,
        ))
    }

    @Test fun conflictingFramesFailClosed() {
        val first = CameraCredentialReader.read(
            listOf(network, line("パスワード Sample8K", 560)),
            listOf(network, line("/AA Sample8K", 560)), 1280, 960,
        )
        val second = CameraCredentialReader.read(
            listOf(network, line("パスワード Sample9K", 560)),
            listOf(network, line("/AA Sample9K", 560)), 1280, 960,
        )
        assertNull(CameraCredentialReader.combine(first, second))
    }

    @Test fun rejectsMissingCoordinates() {
        assertNull(CameraCredentialReader.read(
            listOf(line("SSID DIRECT-Ab1O:a6000", -1, -1), line("パスワード Sample8K", -1, -1)),
            emptyList(), 1280, 960,
        ))
    }
}
