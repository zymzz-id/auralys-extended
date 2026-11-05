/*
 * Auralys Extended (C) 2026 — based on Metrolist (GPL-3.0)
 * Licensed under GPL-3.0
 */
package com.metrolist.music.listentogether

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.NfcEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import timber.log.Timber
import android.graphics.Bitmap
import android.graphics.Color as AwtColor

/**
 * Auralys Extended: Listen Together join via QR code and NFC.
 *
 * QR deep link format: `metrolist://listen?room=CODE`
 * NFC: ndef MIME type "application/vnd.metrolist.listen" with body = room code.
 */
object ListenTogetherShare {
    const val DEEP_LINK_SCHEME = "metrolist"
    const val DEEP_LINK_HOST = "listen"
    const val NFC_MIME = "application/vnd.metrolist.listen"

    fun roomDeepLink(roomCode: String): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST?room=$roomCode"

    fun extractRoomCode(from: String): String? {
        val trimmed = from.trim().uppercase()
        if (trimmed.matches(Regex("^[A-Z0-9]{4,10}$"))) return trimmed
        val marker = "room="
        val idx = trimmed.indexOf(marker)
        if (idx >= 0) {
            val rest = trimmed.substring(idx + marker.length)
            return rest.substringBefore('&').takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AwtColor.BLACK else AwtColor.WHITE)
            }
        }
        return bmp
    }

    fun nfcMessage(roomCode: String): NdefMessage = NdefMessage(
        arrayOf(
            NdefRecord.createMime(NFC_MIME, roomCode.toByteArray()),
            NdefRecord.createUri(roomDeepLink(roomCode)),
        )
    )

    /** Enable Android Beam-style NFC push for the given room (best-effort). */
    fun enableNfcPush(activity: Activity, roomCode: String): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        // Android Beam (setNdefPushMessageCallback / enableForegroundNdefPush) was removed in
        // API 44+; use reflection so both old and new platforms compile and run.
        return try {
            val method = NfcAdapter::class.java.getMethod(
                "setNdefPushMessageCallback",
                NfcAdapter.CreateNdefMessageCallback::class.java,
                Activity::class.java,
            )
            method.invoke(
                adapter,
                NfcAdapter.CreateNdefMessageCallback { nfcMessage(roomCode) },
                activity,
            )
            true
        } catch (e: Exception) {
            Timber.tag("LTShare").d("NFC push unavailable: %s", e.message)
            false
        }
    }
}

/** QR bitmap rendered as a Composable. */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier, size: Int = 512) {
    val bitmap = remember(content) { ListenTogetherShare.generateQrBitmap(content, size) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code",
        modifier = modifier,
    )
}

/**
 * Camera QR scanner. Calls [onRoomCode] with the extracted room code once.
 */
@Composable
fun QrScanner(
    lifecycleOwner: LifecycleOwner,
    onRoomCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val processed = remember { arrayOf(false) }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val scanner = BarcodeScanning.getClient()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy: ImageProxy ->
                    @androidx.camera.core.ExperimentalGetImage
                    val mediaImage = proxy.image
                    if (mediaImage != null && !processed[0]) {
                        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT }?.rawValue
                                    ?: barcodes.firstOrNull()?.rawValue
                                raw?.let { value ->
                                    ListenTogetherShare.extractRoomCode(value)?.let { code ->
                                        processed[0] = true
                                        onRoomCode(code)
                                    }
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } else {
                        proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier.fillMaxWidth().height(320.dp),
    )
}
