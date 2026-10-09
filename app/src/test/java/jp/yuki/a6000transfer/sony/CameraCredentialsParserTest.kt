package jp.yuki.a6000transfer.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCredentialsParserTest {
    @Test fun readsJapaneseCameraPanel() {
        val result = CameraCredentialsParser.parse(listOf(
            "スマートフォンを操作して", "SSID DIRECT-Ab12:a6000",
            "パスワード Example8K", "機器名称 a6000",
        ))
        assertEquals(CameraCredentialsParser.Credentials("DIRECT-Ab12:a6000", "Example8K"), result)
    }

    @Test fun readsSplitAndFullwidthOcrLines() {
        val result = CameraCredentialsParser.parse(listOf(
            "ＳＳＩＤ", "ＤＩＲＥＣＴ－Ab12：a6000", "パスワード", "Example8K",
        ))
        assertEquals(CameraCredentialsParser.Credentials("DIRECT-Ab12:a6000", "Example8K"), result)
    }

    @Test fun toleratesCameraLcdOcrSpacingAndLabelShape() {
        val result = CameraCredentialsParser.parse(listOf(
            "SSID DI RECT-Ab12: a6000", "バスワード Example8K",
        ))
        assertEquals(CameraCredentialsParser.Credentials("DIRECT-Ab12:a6000", "Example8K"), result)
    }

    @Test fun rejectsMissingOrAmbiguousCredentials() {
        assertNull(CameraCredentialsParser.parse(listOf("SSID DIRECT-Ab12:a6000", "機器名称 a6000")))
        assertNull(CameraCredentialsParser.parse(listOf(
            "DIRECT-Ab12:a6000", "DIRECT-Zz99:a6000", "パスワード Example8K",
        )))
        assertNull(CameraCredentialsParser.parse(listOf("DIRECT-Ab12:a6000", "パスワード Example")))
    }
}
