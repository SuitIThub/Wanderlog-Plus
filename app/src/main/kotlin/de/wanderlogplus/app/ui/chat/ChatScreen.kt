package de.wanderlogplus.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.categorySymbol
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.app.ui.design.PhotoCardStyle
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatSummary

/** Example questions on an empty chat (tap sends). */
val CHAT_EXAMPLES = listOf(
    "Was lohnt sich hier am meisten?",
    "Plan für einen Regentag",
    "Gutes Essen in der Nähe, nicht zu touristisch",
    "Suche im Web nach Events in dieser Woche",
)

@Composable
fun ChatRoute(
    vm: ChatViewModel,
    snapshot: TripSnapshot?,
    onBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    actions: ChatActions? = null,
    /** Opens what an "@" mention points to; null: mentions are only highlighted. */
    onOpenRef: ((de.wanderlogplus.shared.sync.ChatRef) -> Unit)? = null,
) {
    val chat by vm.chat.collectAsState()
    val chats by vm.chats.collectAsState()
    val context by vm.context.collectAsState()
    val sending by vm.sending.collectAsState()
    val message by vm.message.collectAsState()
    val shareLocation by vm.shareLocation.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.any { it }) vm.setShareLocation(true) else vm.locationDenied() }
    ChatScreen(
        chat, chats, context, sending, message, snapshot,
        onBack = onBack, onSend = { t, r -> vm.send(t, r) }, onRetry = vm::retry, onClearContext = vm::clearContext, onNewChat = vm::newChat,
        onShowList = vm::refreshList, onSelect = vm::select, onDelete = vm::delete, onConsumeMessage = vm::consumeMessage,
        onOpenPlace = onOpenPlace, onOpenUrl = onOpenUrl, actions = actions, onOpenRef = onOpenRef,
        shareLocation = shareLocation,
        onToggleLocation = if (!vm.canShareLocation) null else { on ->
            when {
                !on -> vm.setShareLocation(false)
                hasLocationPermission(ctx) -> vm.setShareLocation(true)
                else -> askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatInfo?,
    chats: List<ChatSummary>,
    context: ChatContext?,
    sending: Boolean,
    message: String?,
    snapshot: TripSnapshot?,
    onBack: () -> Unit,
    /** The text and the objects picked with "@" that it still mentions. */
    onSend: (String, List<de.wanderlogplus.shared.sync.ChatRef>) -> Unit,
    onRetry: () -> Unit,
    onClearContext: () -> Unit,
    onNewChat: () -> Unit,
    onShowList: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onConsumeMessage: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    /** Card buttons ("Einplanen", "Zu Orten"); null: cards only open. */
    actions: ChatActions? = null,
    shareLocation: Boolean = false,
    /** "Standort mitsenden" on/off; null hides the switch. */
    onToggleLocation: ((Boolean) -> Unit)? = null,
    /** Opens what an "@" mention points to; null: mentions are only highlighted. */
    onOpenRef: ((de.wanderlogplus.shared.sync.ChatRef) -> Unit)? = null,
) {
    var field by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var refs by remember { mutableStateOf(emptyList<de.wanderlogplus.shared.sync.ChatRef>()) }
    val draft = field.text
    val mentionOptions = remember(snapshot) { snapshot?.let { Mentions.options(it) }.orEmpty() }
    val mention = Mentions.queryAt(draft, field.selection.end, refs)
    val mentionHits = if (mention == null || !field.selection.collapsed) emptyList() else Mentions.search(mentionOptions, mention.query)
    var showList by remember { mutableStateOf(false) }
    val busy = sending || chat?.pending == true
    fun send() {
        if (!busy && draft.isNotBlank()) {
            onSend(draft, Mentions.kept(draft, refs))
            field = androidx.compose.ui.text.input.TextFieldValue("")
            refs = emptyList()
        }
    }
    val messages = chat?.messages.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, chat?.pending) {
        val count = messages.size + (if (chat?.pending == true) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().testTag("chat-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("chat-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text(
                chat?.title?.ifBlank { null } ?: "Reise-Chat",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onShowList(); showList = true }, modifier = Modifier.testTag("chat-list")) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Alle Chats")
            }
            IconButton(onClick = onNewChat, enabled = chat != null && !busy, modifier = Modifier.testTag("chat-new")) {
                Icon(Icons.Filled.Add, contentDescription = "Neuer Chat")
            }
        }
        HorizontalDivider()

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("chat-messages"),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item(key = "empty") { EmptyChat(onExample = { if (!busy) onSend(it, emptyList()) }) }
            }
            items(messages, key = { it.id }) { m ->
                when (m.role) {
                    ChatMessage.ROLE_USER -> UserBubble(m, onOpenRef)
                    ChatMessage.ROLE_ERROR -> ErrorBubble(m, showRetry = m == messages.last() && !busy, onRetry)
                    else -> AssistantMessage(m, snapshot, onOpenPlace, onOpenUrl, actions, context?.date)
                }
            }
            if (chat?.pending == true) {
                item(key = "pending") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp).testTag("chat-pending")) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(chat.status ?: "Denke nach …", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (message != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).testTag("chat-message"), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = onConsumeMessage) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                }
            }
        }

        if (context?.label != null) {
            AssistChip(
                onClick = onClearContext,
                label = { Text("Kontext: " + context.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Kontext entfernen", modifier = Modifier.size(16.dp)) },
                modifier = Modifier.padding(start = 12.dp, top = 4.dp).testTag("chat-context"),
            )
        }
        if (mentionHits.isNotEmpty() && mention != null) {
            MentionList(mentionHits) { picked ->
                val (text, cursor) = Mentions.insert(draft, field.selection.end, mention, picked.ref.label)
                field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(cursor))
                refs = (refs + picked.ref).distinct()
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onToggleLocation != null) {
                androidx.compose.material3.IconToggleButton(
                    checked = shareLocation,
                    onCheckedChange = onToggleLocation,
                    modifier = Modifier.testTag("chat-location"),
                ) {
                    Icon(
                        if (shareLocation) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                        contentDescription = if (shareLocation) "Standort wird mitgesendet" else "Standort mitsenden",
                        tint = if (shareLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.size(8.dp))
            }
            val mentionColor = MaterialTheme.colorScheme.primary
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    field = if (v.text.length > 4000) v.copy(text = v.text.take(4000)) else v
                    refs = Mentions.kept(field.text, refs)
                },
                visualTransformation = remember(refs, mentionColor) { MentionHighlight(refs, mentionColor) },
                placeholder = { Text(if (shareLocation) "Frag, was hier in der Nähe geht …" else "Frag nach Vorschlägen oder Wünschen …") },
                modifier = Modifier.weight(1f).testTag("chat-input"),
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
            )
            IconButton(
                onClick = { send() },
                enabled = draft.isNotBlank() && !busy,
                modifier = Modifier.testTag("chat-send"),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden") }
        }
    }

    if (showList) {
        ModalBottomSheet(onDismissRequest = { showList = false }) {
            ChatList(chats, current = chat?.id, onSelect = { onSelect(it); showList = false }, onDelete = onDelete)
        }
    }
}

@Composable
private fun EmptyChat(onExample: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("Frag mich nach Vorschlägen", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ich suche in euren hinterlegten Orten und auf Wunsch im Web. Der Bildschirm, von dem du kommst, dient als Kontext.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        CHAT_EXAMPLES.forEach { ex -> SuggestionChip(onClick = { onExample(ex) }, label = { Text(ex) }, modifier = Modifier.testTag("chat-example")) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UserBubble(m: ChatMessage, onOpenRef: ((de.wanderlogplus.shared.sync.ChatRef) -> Unit)?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                Text(
                    highlighted(m.text, m.refs, MaterialTheme.colorScheme.primary),
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val refs = Mentions.kept(m.text, m.refs)
            if (onOpenRef != null && refs.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.widthIn(max = 320.dp).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                ) {
                    refs.forEachIndexed { i, r ->
                        androidx.compose.material3.SuggestionChip(
                            onClick = { onOpenRef(r) },
                            label = { Text(refSymbol(r.type) + " " + r.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("chat-ref-${m.id}-$i"),
                        )
                    }
                }
            }
            val meta = listOfNotNull(m.author, m.context?.label).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
        }
    }
}

@Composable
private fun ErrorBubble(m: ChatMessage, showRetry: Boolean, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("chat-error")) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.text, Modifier.weight(1f, fill = false).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            if (showRetry) TextButton(onClick = onRetry, modifier = Modifier.testTag("chat-retry")) { Text("Erneut senden") }
        }
    }
}

@Composable
private fun AssistantMessage(
    m: ChatMessage,
    snapshot: TripSnapshot?,
    onOpenPlace: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    actions: ChatActions?,
    defaultDate: String?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MarkdownText(m.text, Modifier.padding(horizontal = 4.dp))
        m.cards.forEachIndexed { i, c ->
            if (c.kind == ChatCard.KIND_PLAN) {
                ChatPlanCard(c, "${m.id}-$i", actions)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChatCardView(c, snapshot, onOpenPlace, onOpenUrl)
                    if (actions != null) ChatCardActions(c, "${m.id}-$i", snapshot, defaultDate, actions)
                }
            }
        }
    }
}

@Composable
fun ChatCardView(c: ChatCard, snapshot: TripSnapshot?, onOpenPlace: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    val placeId = c.savedPlaceId
    val url = c.url
    if (c.kind == ChatCard.KIND_GUIDE) {
        de.wanderlogplus.app.ui.guides.ChatGuideCard(c)
    } else if (c.kind == ChatCard.KIND_PLACE && placeId != null) {
        val saved = snapshot?.savedPlaces?.firstOrNull { it.id == placeId }
        val place = saved?.let { snapshot.places[it.placeId] }
        val region = saved?.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
        PhotoCard(
            title = place?.name ?: c.title,
            subtitle = c.subtitle,
            imageUrl = place?.heroImageUrl(),
            placeholderSymbol = categorySymbol(saved?.category),
            accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary,
            style = PhotoCardStyle.Compact,
            onClick = { onOpenPlace(placeId) },
            modifier = Modifier.fillMaxWidth().testTag("chat-card-$placeId"),
        )
    } else if (url != null) {
        Card(
            onClick = { onOpenUrl(url) },
            modifier = Modifier.fillMaxWidth().testTag("chat-web-card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    c.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(hostOf(url) + " · nicht hinterlegt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Im Browser öffnen", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

internal fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

@Composable
private fun ChatList(chats: List<ChatSummary>, current: String?, onSelect: (String) -> Unit, onDelete: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag("chat-sheet")) {
        Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (chats.isEmpty()) {
            Text("Noch keine Chats.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        chats.forEach { c ->
            ListItem(
                headlineContent = { Text(c.title.ifBlank { "Neuer Chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(listOfNotNull(c.author, c.preview.ifBlank { null }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    if (!c.pending) IconButton(onClick = { onDelete(c.id) }, modifier = Modifier.testTag("chat-delete-${c.id}")) {
                        Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                    }
                },
                colors = if (c.id == current) androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else androidx.compose.material3.ListItemDefaults.colors(),
                modifier = Modifier.testTag("chat-item-${c.id}").clickable { onSelect(c.id) },
            )
        }
    }
}

/** Slim bar with the tab's title and the chat button, for screens without a photo header. */
@Composable
fun ChatTopBar(title: String, onOpenChat: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
        IconButton(onClick = onOpenChat, modifier = Modifier.testTag("chat-open")) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Reise-Chat", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Symbol of a mention's kind, in lists and chips. */
internal fun refSymbol(type: String): String = when (type) {
    de.wanderlogplus.shared.sync.ChatRef.PLACE -> "📍"
    de.wanderlogplus.shared.sync.ChatRef.DAY -> "📅"
    de.wanderlogplus.shared.sync.ChatRef.STAY -> "🏨"
    de.wanderlogplus.shared.sync.ChatRef.TRAIN -> "🚆"
    de.wanderlogplus.shared.sync.ChatRef.FLIGHT -> "✈️"
    de.wanderlogplus.shared.sync.ChatRef.GUIDE -> "📖"
    else -> "🗺️"
}

/** [text] with each "@label" of [refs] in [color], semi-bold. */
internal fun highlighted(text: String, refs: List<de.wanderlogplus.shared.sync.ChatRef>, color: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        Mentions.spans(text, refs).forEach { (range, _) ->
            addStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), range.first, range.last + 1)
        }
    }

/** Colors the picked mentions in the input; the text itself is unchanged. */
private class MentionHighlight(
    private val refs: List<de.wanderlogplus.shared.sync.ChatRef>,
    private val color: androidx.compose.ui.graphics.Color,
) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString) =
        androidx.compose.ui.text.input.TransformedText(highlighted(text.text, refs, color), androidx.compose.ui.text.input.OffsetMapping.Identity)
}

/** The objects matching what follows the "@", above the input. */
@Composable
private fun MentionList(hits: List<MentionOption>, onPick: (MentionOption) -> Unit) {
    Surface(
        tonalElevation = 3.dp, shadowElevation = 2.dp, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat-mentions"),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            hits.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(o) }.padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("chat-mention-${o.ref.type}-${o.ref.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(refSymbol(o.ref.type), Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.ref.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (o.subtitle.isNotBlank()) Text(o.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
