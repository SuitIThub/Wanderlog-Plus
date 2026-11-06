package de.wanderlogplus.app.ui.attachments

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/** Name and type come from the [ContentResolver]; both have fallbacks. */
class ContentUriSource(private val resolver: ContentResolver, private val uri: Uri) : AttachmentSource {
    override fun fileName(): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }?.takeIf { it.isNotBlank() } ?: "Anhang"

    override fun mimeType(): String = try { resolver.getType(uri) } catch (_: Exception) { null } ?: "application/octet-stream"

    override fun open(): InputStream? = try { resolver.openInputStream(uri) } catch (_: Exception) { null }
}

const val MSG_NO_APP = "Keine App zum Öffnen gefunden"
const val MSG_UNAVAILABLE = "Datei nicht verfügbar"
const val MSG_NOT_OFFLINE = "Datei nicht offline verfügbar"

fun fileProviderAuthority(context: Context) = "${context.packageName}.files"

/** ACTION_VIEW with a FileProvider URI; false when no app handles it, null when the file cannot be shared. */
fun openExternally(context: Context, file: File, mimeType: String): Boolean? {
    val uri = try { FileProvider.getUriForFile(context, fileProviderAuthority(context), file) } catch (_: IllegalArgumentException) { return null }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Wires the picker, open routing, delete and error snackbars into one [AttachmentActions].
 * [onView] shows images and PDFs in the in-app viewer.
 */
@Composable
fun rememberAttachmentActions(
    vm: AttachmentsViewModel,
    files: AttachmentFiles,
    snackbar: SnackbarHostState,
    /** Online photo search; null: only the gallery. */
    webSearch: WebPhotoSearch? = null,
    webFetch: WebPhotoFetch? = null,
    /** What to search for when photos go to a target. */
    queryFor: (AttachmentTarget) -> PhotoQuery = { PhotoQuery("", null) },
    onView: (Attachment) -> Unit,
): AttachmentActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by files.version.collectAsState()
    // Survives recreation of the activity while the picker is open: "E|<TYPE>|<id>" or "D|<date>".
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pending?.let(::decodeTarget)
        pending = null
        if (uri != null && target != null) vm.add(target, ContentUriSource(context.contentResolver, uri))
    }
    var pendingPhotos by rememberSaveable { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_AT_ONCE)) { uris ->
        val target = pendingPhotos?.let(::decodeTarget)
        pendingPhotos = null
        if (uris.isNotEmpty() && target != null) vm.addPhotos(target, uris.map { ContentUriSource(context.contentResolver, it) })
    }
    val pickImages = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    // "Aus Galerie" / "Online suchen" and the search sheet (only with a server).
    var photoRequest by remember { mutableStateOf<PhotoRequest?>(null) }
    val queryForState = androidx.compose.runtime.rememberUpdatedState(queryFor)
    if (webSearch != null && webFetch != null) {
        val thumbs = remember { mutableMapOf<String, ByteArray>() }
        WebPhotoFlow(
            photoRequest, webSearch,
            thumb = { p -> thumbs[p.id] ?: webFetch(p.id, WebPhotoAttachment.THUMB_WIDTH).also { thumbs[p.id] = it } },
            onGallery = { r ->
                pendingPhotos = when (val t = r.target) {
                    is AttachmentTarget.Entity -> "E|${t.type.name}|${t.id}"
                    is AttachmentTarget.Day -> "D|${t.date}"
                }
                pickImages()
            },
            onPicked = { r, picked -> vm.addPhotos(r.target, picked.map { WebPhotoAttachment(it, webFetch) }, r.onAdded) },
            onDone = { photoRequest = null },
        )
    }
    val online = webSearch != null && webFetch != null
    val addPhotosTo: (AttachmentTarget) -> Unit = { target ->
        if (online) {
            photoRequest = PhotoRequest(target, queryForState.value(target), single = false)
        } else {
            pendingPhotos = when (target) {
                is AttachmentTarget.Entity -> "E|${target.type.name}|${target.id}"
                is AttachmentTarget.Day -> "D|${target.date}"
            }
            pickImages()
        }
    }
    val message by vm.message.collectAsState()
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    val onViewState = androidx.compose.runtime.rememberUpdatedState(onView)
    return remember(files, version, vm, online) {
        AttachmentActions(
            available = { hash -> files.fileFor(hash) != null },
            onAdd = { type, id -> pending = "E|${type.name}|$id"; picker.launch(arrayOf("*/*")) },
            onAddToDay = { date -> pending = "D|$date"; picker.launch(arrayOf("*/*")) },
            onOpen = { a ->
                when (val t = openTargetFor(a, files)) {
                    OpenTarget.Unavailable -> scope.launch { snackbar.showSnackbar(MSG_NOT_OFFLINE) }
                    is OpenTarget.Image, is OpenTarget.Pdf -> onViewState.value(a)
                    is OpenTarget.External ->
                        when (openExternally(context, t.file, t.mimeType)) {
                            false -> scope.launch { snackbar.showSnackbar(MSG_NO_APP) }
                            null -> scope.launch { snackbar.showSnackbar(MSG_UNAVAILABLE) }
                            true -> Unit
                        }
                }
            },
            onDelete = { vm.delete(it) },
            onAddPhotos = { type, id -> addPhotosTo(AttachmentTarget.Entity(type, id)) },
            onAddPhotosToDay = { date -> addPhotosTo(AttachmentTarget.Day(date)) },
            files = files,
            onSearchPhotos = if (online) { target, single, onAdded ->
                photoRequest = PhotoRequest(target, queryForState.value(target), single = single, onlineOnly = true, onAdded = onAdded)
            } else null,
        )
    }
}

/** At most this many photos per pick. */
const val MAX_PHOTOS_AT_ONCE = 30

private fun decodeTarget(s: String): AttachmentTarget? {
    val parts = s.split('|', limit = 3)
    return when {
        parts[0] == "D" && parts.size >= 2 -> AttachmentTarget.Day(parts[1])
        parts[0] == "E" && parts.size == 3 ->
            try { AttachmentTarget.Entity(EntityType.valueOf(parts[1]), parts[2]) } catch (_: IllegalArgumentException) { null }
        else -> null
    }
}
