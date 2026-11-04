package de.wanderlogplus.app.ui.more

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.ui.setup.SetupLink

/** The modules of a QR code for [text] (true = dark), with a quiet zone of [margin] modules. */
fun qrModules(text: String, margin: Int = 4): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to margin, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
}

/** A black-on-white bitmap of the QR code, [scale] pixels per module (scaled up without blur when shown). */
fun qrBitmap(text: String, scale: Int = 8): ImageBitmap {
    val modules = qrModules(text)
    val n = modules.size
    val bmp = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.ARGB_8888)
    for (y in 0 until n) for (x in 0 until n) {
        val color = if (modules[y][x]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, color)
    }
    return bmp.asImageBitmap()
}

/**
 * The setup QR code of the connection in use (scan it with "QR-Code scannen" on another device). It contains the
 * token, which is said plainly. Tags: `setup-qr-dialog`, `setup-qr`.
 */
@Composable
fun SetupQrDialog(server: ServerSettings, debugMode: Boolean, onDismiss: () -> Unit, title: String? = null) {
    val image = remember(server) { qrBitmap(SetupLink.build(server)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("setup-qr-dialog"),
        title = { Text(title ?: if (debugMode) "QR-Code (Debug-Server)" else "QR-Code für die Einrichtung") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    image,
                    contentDescription = "QR-Code mit Server-Adresse und Token",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(240.dp).background(Color.White).testTag("setup-qr"),
                )
                Text(server.url, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Auf dem anderen Gerät „QR-Code scannen“ wählen. Der Code enthält den Zugangs-Token: nur Geräten zeigen, die auf die Reise zugreifen sollen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
