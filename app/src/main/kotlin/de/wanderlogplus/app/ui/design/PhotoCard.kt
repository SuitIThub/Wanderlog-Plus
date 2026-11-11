package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PhotoCardStyle { Compact, Large }

private val CardShape = RoundedCornerShape(18.dp)

/**
 * Rounded card with a photo: [PhotoCardStyle.Compact] has a 56 dp thumbnail on the left, [PhotoCardStyle.Large] a
 * 140 dp image strip on top. Without a photo the [accent] gradient shows [placeholderSymbol].
 */
@Composable
fun PhotoCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    placeholderSymbol: String,
    accent: Color,
    style: PhotoCardStyle = PhotoCardStyle.Compact,
    badges: List<BadgeSpec> = emptyList(),
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** An extra line under the subtitle (e.g. the rating line of a place); null: none. */
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val body: @Composable () -> Unit = {
        when (style) {
            PhotoCardStyle.Compact -> CompactBody(title, subtitle, imageUrl, placeholderSymbol, accent, badges, trailing, extra)
            PhotoCardStyle.Large -> LargeBody(title, subtitle, imageUrl, placeholderSymbol, accent, badges, trailing, extra)
        }
    }
    val m = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(onClick = onClick, modifier = m, shape = CardShape, colors = colors, elevation = elevation) { body() }
    } else {
        Card(modifier = m, shape = CardShape, colors = colors, elevation = elevation) { body() }
    }
}

@Composable
private fun CompactBody(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    symbol: String,
    accent: Color,
    badges: List<BadgeSpec>,
    trailing: (@Composable () -> Unit)?,
    extra: (@Composable () -> Unit)?,
) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoOrGradient(imageUrl, accent, "photo-fallback", Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), symbol, 24.sp)
        Spacer(Modifier.size(12.dp))
        TextBlock(title, subtitle, badges, extra, Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.size(4.dp))
            trailing()
        }
    }
}

@Composable
private fun LargeBody(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    symbol: String,
    accent: Color,
    badges: List<BadgeSpec>,
    trailing: (@Composable () -> Unit)?,
    extra: (@Composable () -> Unit)?,
) {
    Column {
        PhotoOrGradient(imageUrl, accent, "photo-fallback", Modifier.fillMaxWidth().height(140.dp), symbol, 40.sp)
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextBlock(title, subtitle, badges, extra, Modifier.weight(1f))
            if (trailing != null) trailing()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextBlock(title: String, subtitle: String?, badges: List<BadgeSpec>, extra: (@Composable () -> Unit)?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        extra?.invoke()
        if (badges.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { badges.forEach { Badge(it) } }
        }
    }
}
