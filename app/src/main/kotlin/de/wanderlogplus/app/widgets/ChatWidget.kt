package de.wanderlogplus.app.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import de.wanderlogplus.app.R
import de.wanderlogplus.shared.sync.QuickAsk
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ChatWidget : TripWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val models = loadModels(context)
        val image = topImage(context, models)
        provideContent { GlanceTheme { ChatContent(context, models, bucketOf(LocalSize.current), image) } }
    }

    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = ChatContent(context, m, bucket, null)
}

class ChatReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ChatWidget()
}

/** The top suggestion's photo (small, software bitmap for the widget); null without one or on any error. */
private suspend fun topImage(context: Context, m: WidgetModels): Bitmap? {
    val key = m.chat.quick?.takeIf { it.state == QuickResult.DONE }?.cards?.firstOrNull()?.imageKey ?: return null
    return try {
        val req = coil.request.ImageRequest.Builder(context)
            .data(de.wanderlogplus.app.ui.design.placeImageUrl(key))
            .allowHardware(false)
            .size(320)
            .build()
        (coil.Coil.imageLoader(context).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** „650 m“ / „1,2 km“. */
fun distanceText(meters: Int): String =
    if (meters < 1000) "${(meters + 5) / 10 * 10} m" else String.format(Locale.GERMAN, "%.1f km", meters / 1000.0)

private fun askLabel(kind: String) = if (kind == QuickAsk.KIND_FOOD) "Essen" else "Sehenswürdigkeit"

/**
 * „Reise-Chat“ with „Was jetzt?“: the chat button, and – from STRIP on – the sight and food asks. Too small for a
 * result (STRIP, SMALL) the asks open the app with the question sent; from MEDIUM on they run in the background
 * and the result shows here, the best suggestion first.
 */
@Composable
fun ChatContent(context: Context, m: WidgetModels, bucket: Bucket, image: Bitmap?) {
    val q = m.chat.quick
    val openChat = openApp(context, WidgetTarget.Chat(q?.chatId))
    if (bucket == Bucket.TINY) {
        WidgetFrame(openChat, GlanceModifier.tag("chat-open"), padding = 6) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { WidgetIcon(R.drawable.ic_w_chat, size = 26) }
        }
        return
    }
    val inPlace = bucket != Bucket.STRIP && bucket != Bucket.SMALL
    fun ask(kind: String): Action =
        if (inPlace) actionRunCallback<AskCallback>(actionParametersOf(AskCallback.KIND to kind))
        else openApp(context, WidgetTarget.Ask(kind))
    WidgetFrame(null, padding = 8) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconPill(R.drawable.ic_w_chat, if (bucket.wide && bucket != Bucket.STRIP) "Chat" else null, openChat, "chat-open")
            Spacer(GlanceModifier.width(6.dp))
            IconPill(R.drawable.ic_w_sight, if (bucket == Bucket.SMALL) null else "Sehen", ask(QuickAsk.KIND_SIGHT), "ask-sight")
            Spacer(GlanceModifier.width(6.dp))
            IconPill(R.drawable.ic_w_food, if (bucket == Bucket.SMALL) null else "Essen", ask(QuickAsk.KIND_FOOD), "ask-food")
        }
        if (bucket == Bucket.STRIP) return@WidgetFrame
        Spacer(GlanceModifier.height(6.dp))
        if (!inPlace) {
            LabelText("Was passt jetzt? Tippen startet den Chat.", maxLines = 3)
            return@WidgetFrame
        }
        Result(context, m, bucket, image, openChat)
    }
}

@Composable
private fun IconPill(icon: Int, label: String?, onClick: Action, tag: String) {
    Row(
        GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp).clickable(onClick).tag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WidgetIcon(icon, size = 16, color = GlanceTheme.colors.onPrimaryContainer)
        label?.let {
            Spacer(GlanceModifier.width(4.dp))
            BodyText(it, color = GlanceTheme.colors.onPrimaryContainer)
        }
    }
}

@Composable
private fun Result(context: Context, m: WidgetModels, bucket: Bucket, image: Bitmap?, openChat: Action) {
    val q = m.chat.quick
    if (q == null) {
        Column(GlanceModifier.clickable(openChat)) {
            BodyText("Frag den Reise-Chat …")
            m.chat.lastTitle?.let { LabelText(it) }
            if (bucket.big) LabelText("„Sehen“ und „Essen“ schlagen vor, was jetzt in der Nähe gut passt – passend zur freien Zeit bis zum nächsten Programmpunkt.", maxLines = 4)
        }
        return
    }
    val faded = if (m.chat.stale) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface
    val asked = LocalDateTime.ofInstant(Instant.ofEpochMilli(q.at), ZoneId.systemDefault())
    val stamp = "von %02d:%02d".format(asked.hour, asked.minute)
    when (q.state) {
        QuickResult.RUNNING -> BodyText("Suche passende Ziele …", GlanceModifier.tag("quick-running"))
        QuickResult.FAILED -> BodyText("Keine Antwort – im Chat erneut versuchen", GlanceModifier.clickable(openChat), maxLines = 2)
        else -> {
            val top = q.cards.firstOrNull()
            if (top == null) {
                BodyText(q.text ?: "Keine Vorschläge", GlanceModifier.clickable(openChat), maxLines = if (bucket.big) 6 else 2, color = faded)
            } else {
                val open = top.savedPlaceId?.let { openApp(context, WidgetTarget.Place(it)) } ?: openChat
                val showImage = image != null && bucket != Bucket.MEDIUM
                if (bucket.big) {
                    Column(GlanceModifier.fillMaxWidth().clickable(open).tag("quick-top")) {
                        if (showImage) {
                            Image(ImageProvider(image!!), contentDescription = top.title, contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxWidth().height(if (bucket == Bucket.LARGE) 90.dp else 70.dp).cornerRadius(10.dp).tag("quick-image"))
                            Spacer(GlanceModifier.height(4.dp))
                        }
                        TopText(q.kind, top, faded)
                    }
                    q.cards.drop(1).take(3).forEachIndexed { i, c ->
                        val go = c.savedPlaceId?.let { openApp(context, WidgetTarget.Place(it)) } ?: openChat
                        BodyText(listOfNotNull(c.title, c.distanceMeters?.let(::distanceText)).joinToString(" · "), GlanceModifier.clickable(go).padding(top = 3.dp).tag("quick-more-$i"), color = faded)
                    }
                } else {
                    Row(GlanceModifier.fillMaxWidth().clickable(open).tag("quick-top"), verticalAlignment = Alignment.CenterVertically) {
                        if (showImage) {
                            Image(ImageProvider(image!!), contentDescription = top.title, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(52.dp).cornerRadius(8.dp).tag("quick-image"))
                            Spacer(GlanceModifier.width(8.dp))
                        }
                        Column(GlanceModifier.defaultWeight()) { TopText(q.kind, top, faded) }
                    }
                }
            }
            LabelText(stamp + if (m.chat.stale) " · älter" else "")
        }
    }
}

@Composable
private fun TopText(kind: String, c: QuickCard, color: androidx.glance.unit.ColorProvider) {
    LabelText(askLabel(kind) + (c.distanceMeters?.let { " · " + distanceText(it) } ?: ""))
    TitleText(c.title, maxLines = 1, color = color)
    c.subtitle?.let { LabelText(it, maxLines = 1) }
}
