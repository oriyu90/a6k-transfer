package jp.yuki.a6000transfer.ui

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import jp.yuki.a6000transfer.sony.CameraCredentialReader
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Processes one in-memory still. The image and both models close on every completion path. */
internal object CameraOcrProcessor {
    @OptIn(markerClass = [ExperimentalGetImage::class])
    fun read(
        image: ImageProxy,
        executor: Executor,
        onComplete: (CameraCredentialReader.Reading?) -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        var japanese: TextRecognizer? = null
        var latin: TextRecognizer? = null
        fun finish(result: CameraCredentialReader.Reading?) {
            if (!finished.compareAndSet(false, true)) return
            try { image.close() } catch (_: Exception) { }
            try { japanese?.close() } catch (_: Exception) { }
            try { latin?.close() } catch (_: Exception) { }
            onComplete(result)
        }

        try {
            val mediaImage = image.image ?: run { finish(null); return }
            val rotation = image.imageInfo.rotationDegrees
            val width = if (rotation % 180 == 0) image.width else image.height
            val height = if (rotation % 180 == 0) image.height else image.width
            val input = InputImage.fromMediaImage(mediaImage, rotation)
            val jpReader = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            japanese = jpReader
            jpReader.process(input).addOnCompleteListener(executor) { jpTask ->
                val jpLines = try {
                    if (jpTask.isSuccessful) lines(jpTask.result) else emptyList()
                } catch (_: Exception) { emptyList() }
                try {
                    try {
                        jpReader.close()
                        japanese = null
                    } catch (_: Exception) { }
                    val latinReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    latin = latinReader
                    latinReader.process(input).addOnCompleteListener(executor) { latinTask ->
                        val reading = try {
                            val latinLines = if (latinTask.isSuccessful) lines(latinTask.result) else emptyList()
                            CameraCredentialReader.read(jpLines, latinLines, width, height)
                        } catch (_: Exception) { null }
                        finish(reading)
                    }
                } catch (_: Exception) {
                    finish(null)
                }
            }
        } catch (_: Exception) {
            finish(null)
        }
    }

    private fun lines(text: Text): List<CameraCredentialReader.Line> =
        text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                CameraCredentialReader.Line(
                    line.text,
                    line.boundingBox?.centerX() ?: -1,
                    line.boundingBox?.centerY() ?: -1,
                )
            }
        }
}
