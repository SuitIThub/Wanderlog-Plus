package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** "DEBUG" plus the host of the debug server, small at the top centre over every screen. Tag `debug-marker`. */
@Composable
fun DebugMarker(serverUrl: String?) {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = Color(0xFFC62828),
            contentColor = Color.White,
            shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
            modifier = Modifier.testTag("debug-marker"),
        ) {
            Text(
                "DEBUG" + (serverUrl?.let { " · " + debugHost(it) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

/** "192.168.1.20:8080" from "http://192.168.1.20:8080/wlp". */
fun debugHost(url: String): String = url.substringAfter("://").substringBefore('/').ifBlank { url }
