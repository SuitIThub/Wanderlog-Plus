package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.wanderlogplus.app.ui.LocalDarkTheme

data class BadgeSpec(val text: String, val tone: BadgeTone)

enum class BadgeTone { Neutral, Positive, Warning, Accent }

/** Small uppercase, letter-spaced label above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = readableOn(color, MaterialTheme.colorScheme.background),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Status pill ("offen", "🎫 Ticket", "nicht offline"). */
@Composable
fun Badge(spec: BadgeSpec, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val (bg, fg) = when (spec.tone) {
        BadgeTone.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
        BadgeTone.Accent -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeTone.Positive -> if (dark) Color(0xFF23452A) to Color(0xFFB5E6B0) else Color(0xFFD5EFD0) to Color(0xFF1B5124)
        BadgeTone.Warning -> if (dark) Color(0xFF4A3800) to Color(0xFFFFDF8F) else Color(0xFFFFE8A6) to Color(0xFF5A4200)
    }
    Surface(modifier, shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Text(
            spec.text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Round action button with a label below (navigation, transit, website, call, Tabelog). The whole column is the
 * button, so TalkBack announces the label once; [tag] goes on it.
 */
@Composable
fun ActionCircle(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    Column(
        modifier.width(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** One tab of a screen's `PrimaryTabRow` (day detail, place detail, Logistik); [tag] goes on the tab. */
@Composable
fun ScreenTab(label: String, selected: Boolean, tag: String, maxLines: Int = 1, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(label, maxLines = maxLines, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.testTag(tag),
    )
}

/** Quiet placeholder for an empty section. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Diagonal gradient in [accent], used wherever a photo is missing. */
internal fun accentGradient(accent: Color): Brush = Brush.linearGradient(
    listOf(lerp(accent, Color.White, 0.22f), accent, lerp(accent, Color.Black, 0.38f)),
)

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * [fg] (e.g. a region color used for small text) moved towards white on dark or towards black on light [bg] until
 * it reaches a contrast of 4.5:1; colors that are readable already stay unchanged.
 */
fun readableOn(fg: Color, bg: Color): Color {
    val target = if (bg.luminance() < 0.5f) Color.White else Color.Black
    var x = fg
    repeat(20) {
        if (contrast(x, bg) >= 4.5f) return x
        x = lerp(x, target, 0.1f)
    }
    return x
}

/** Highest luminance that still gives white text a contrast of at least 4.5:1 (with some margin). */
private const val MAX_LUMINANCE_UNDER_WHITE = 0.16f

/** [c] darkened step by step until white text on it is readable (contrast >= 4.5:1). */
internal fun darkenForWhiteText(c: Color): Color {
    var x = c
    repeat(40) {
        if (x.luminance() <= MAX_LUMINANCE_UNDER_WHITE) return x
        x = lerp(x, Color.Black, 0.08f)
    }
    return x
}

/** Stops of [heroGradient]: like [accentGradient], but every tone is dark enough for white text in both themes. */
fun heroGradientColors(accent: Color): List<Color> {
    val base = darkenForWhiteText(accent)
    return listOf(darkenForWhiteText(lerp(accent, Color.White, 0.22f)), base, lerp(base, Color.Black, 0.38f))
}

/** Fallback behind white header text (hero, countdown): never a pale gradient, also for light accents in dark mode. */
fun heroGradient(accent: Color): Brush = Brush.linearGradient(heroGradientColors(accent))

/**
 * Photo from [url] over an accent gradient. The gradient is always drawn first (no empty hole while loading) and
 * carries [fallbackTag] when it is what the user sees: no URL, or the image failed to load.
 */
@Composable
internal fun PhotoOrGradient(
    url: String?,
    accent: Color,
    fallbackTag: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolSize: TextUnit = 26.sp,
    gradient: Brush = accentGradient(accent),
) {
    var failed by remember(url) { mutableStateOf(false) }
    val showFallback = url == null || failed
    Box(modifier) {
        Box(
            Modifier.matchParentSize().background(gradient).then(if (showFallback) Modifier.testTag(fallbackTag) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (symbol != null && showFallback) Text(symbol, fontSize = symbolSize, color = Color.White)
        }
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Rounded surface card shared by Mehr, Einstellungen, Import, Konflikte, Setup (same look as [PhotoCard]). */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp)
    val shape = RoundedCornerShape(18.dp)
    if (onClick != null) {
        androidx.compose.material3.Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation, content = content)
    } else {
        androidx.compose.material3.Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation, content = content)
    }
}

/** Rounded accent square with an icon, the leading element of Mehr rows. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
    }
}

/** Widens the element by [by] on both sides beyond its parent's padding (e.g. a scrolling chip row reaching the screen edges). */
fun Modifier.bleedHorizontally(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + 2 * extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth,
        ),
    )
    layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

/** Thin "insert here" element between program items: a short line with a small ＋ in the middle. Tag `insert-<tag>`. */
@Composable
fun InsertButton(tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 8.dp).size(30.dp).testTag("insert-$tag"),
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.Add,
                contentDescription = "Hier einfügen",
                modifier = Modifier.size(18.dp),
            )
        }
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}
