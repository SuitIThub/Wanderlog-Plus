package de.wanderlogplus.app.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/** Test tag for a Glance node (found by `hasTestTag` in widget tests). */
fun GlanceModifier.tag(t: String): GlanceModifier = semantics { testTag = t }

/** The widget's card: theme background, rounded, padded; [onClick] makes the whole widget a button. */
@Composable
fun WidgetFrame(
    onClick: Action?,
    modifier: GlanceModifier = GlanceModifier,
    padding: Int = 10,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(padding.dp)
    if (onClick != null) m = m.clickable(onClick)
    Column(m, content = content)
}

/** Centered quiet text for empty states („Noch keine Vorhersage“). */
@Composable
fun Hint(text: String, onClick: Action? = null) {
    WidgetFrame(onClick) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, GlanceModifier.tag("hint"), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 3)
        }
    }
}

@Composable
fun TitleText(text: String, modifier: GlanceModifier = GlanceModifier, maxLines: Int = 1, size: TextUnit = 15.sp, color: ColorProvider = GlanceTheme.colors.onSurface) =
    Text(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold), maxLines = maxLines)

@Composable
fun BodyText(text: String, modifier: GlanceModifier = GlanceModifier, maxLines: Int = 1, size: TextUnit = 13.sp, color: ColorProvider = GlanceTheme.colors.onSurface) =
    Text(text, modifier, style = TextStyle(color = color, fontSize = size), maxLines = maxLines)

@Composable
fun LabelText(text: String, modifier: GlanceModifier = GlanceModifier, maxLines: Int = 1, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant) =
    Text(text, modifier, style = TextStyle(color = color, fontSize = 11.sp), maxLines = maxLines)

/** A small filled button ([label]) for widget actions. */
@Composable
fun PillButton(label: String, onClick: Action, tag: String, modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp).clickable(onClick).tag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}
