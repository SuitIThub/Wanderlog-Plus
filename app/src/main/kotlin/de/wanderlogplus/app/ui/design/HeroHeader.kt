package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.wanderlogplus.app.ui.HeroTitle

private val Scrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.28f),
    0.3f to Color.Transparent,
    0.45f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.72f),
)

/**
 * Journal-style header: photo (or [accent] gradient), dark scrim towards the bottom, uppercase kicker, serif title
 * and chips; optional back and edit buttons on top.
 */
@Composable
fun HeroHeader(
    title: String,
    kicker: String?,
    imageUrl: String?,
    accent: Color,
    height: Dp = 170.dp,
    onBack: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    chips: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    /** Low headers with back/edit buttons pass 1 so kicker and title stay clear of the buttons. */
    titleMaxLines: Int = 2,
) {
    Box(modifier.fillMaxWidth().height(height).testTag("hero")) {
        PhotoOrGradient(imageUrl, accent, "hero-fallback", Modifier.matchParentSize(), gradient = heroGradient(accent))
        Box(Modifier.matchParentSize().background(Scrim))

        if (onBack != null) {
            HeroButton(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", onBack, "hero-back", Modifier.align(Alignment.TopStart))
        }
        if (onEdit != null) {
            HeroButton(Icons.Filled.Edit, "Bearbeiten", onEdit, "hero-edit", Modifier.align(Alignment.TopEnd))
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        ) {
            if (!kicker.isNullOrBlank()) {
                Text(
                    kicker.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = HeroTitle, color = Color.White, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { HeroChip(it) }
                }
            }
        }
    }
}

@Composable
private fun HeroButton(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, modifier: Modifier) {
    // 48 dp touch target around a 40 dp dark circle.
    Surface(
        onClick = onClick,
        modifier = modifier.padding(6.dp).size(48.dp).testTag(tag),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.22f), contentColor = Color.White) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
