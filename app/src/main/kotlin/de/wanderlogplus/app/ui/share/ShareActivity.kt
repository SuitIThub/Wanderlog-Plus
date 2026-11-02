package de.wanderlogplus.app.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.AppContainer
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.attachments.ContentUriSource
import de.wanderlogplus.app.ui.attachments.fileProviderAuthority
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val MSG_SETUP_FIRST = "Erst Server einrichten und synchronisieren"

/**
 * Runs the import on [writeScope] (process-wide, survives rotation) and publishes the outcome.
 * The streams are copied completely before [result] is set, i.e. before the activity finishes.
 */
class ShareViewModel(
    private val sink: ShareSink,
    private val sources: List<de.wanderlogplus.app.ui.attachments.AttachmentSource>,
    private val writeScope: CoroutineScope,
) : ViewModel() {
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing
    private val _result = MutableStateFlow<ShareResult?>(null)
    val result: StateFlow<ShareResult?> = _result

    fun attach(target: ShareTarget) {
        if (!_importing.compareAndSet(false, true)) return
        writeScope.launch(Dispatchers.IO) {
            val r = try {
                importShared(sink, sources, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Also errors (e.g. OutOfMemoryError): the result must always be set so the activity finishes.
                ShareResult(0, 0, sources.size)
            }
            _result.value = r
        }
    }
}

class ShareActivity : ComponentActivity() {
    private val container get() = (application as WanderlogPlusApp).container

    private val uris: List<Uri> by lazy { sharedUris(intent, fileProviderAuthority(this)) }

    private val vm: ShareViewModel by viewModels {
        val c = container
        val sources = uris.map { ContentUriSource(contentResolver, it) }
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShareViewModel(containerSink(c), sources, c.scope) as T
        }
    }

    private val reelVm: ReelShareViewModel by viewModels {
        val c = container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReelShareViewModel({ c.syncApi() }, c.scope, cacheDir) as T
        }
    }

    private val sharedText: String? by lazy { intent.getStringExtra(Intent.EXTRA_TEXT) }

    /** A reel link shared as text (Instagram's "Teilen" → app). */
    private val reelLink: String? by lazy { firstLinkIn(sharedText)?.takeIf { isReelLink(it) } }

    /** A single shared video can also go to the reel analysis instead of becoming an attachment. */
    private val videoMime: String? by lazy {
        uris.singleOrNull()?.let { contentResolver.getType(it) }?.takeIf { it.startsWith("video/") }
    }

    private fun openReel(jobId: String) {
        startActivity(
            Intent(this, de.wanderlogplus.app.ui.MainActivity::class.java)
                .putExtra(de.wanderlogplus.app.ui.EXTRA_REEL_JOB, jobId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        // A shared reel link (no file): hand it to the server right away.
        if (savedInstanceState == null && uris.isEmpty()) reelLink?.let { reelVm.startLink(it) }
        setContent {
            WanderlogPlusTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        val hasServer by remember { c.settings.server.map { it != null } }.collectAsState(null)
                        val snapshot by produceState<TripSnapshot?>(null) {
                            try {
                                c.snapshots().snapshot.collect { value = it }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                value = null
                            }
                        }
                        val reelState by reelVm.state.collectAsState()
                        LaunchedEffect(reelState) { (reelState as? ReelShareState.Started)?.let { openReel(it.jobId) } }
                        val importing by vm.importing.collectAsState()
                        val result by vm.result.collectAsState()
                        // Leaving mid-import would hide the outcome; the import finishes on its own.
                        BackHandler(enabled = importing) { }
                        LaunchedEffect(result) {
                            result?.let {
                                Toast.makeText(applicationContext, it.message(), Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                        when {
                            reelState == ReelShareState.Working -> ReelWorking()
                            reelState is ReelShareState.Failed -> Message((reelState as ReelShareState.Failed).message)
                            uris.isEmpty() && reelLink != null -> ReelWorking()
                            else -> ShareContent(
                                hasServer = hasServer,
                                snapshot = snapshot,
                                fileCount = uris.size,
                                importing = importing,
                                onPick = vm::attach,
                                onReel = videoMime?.let { mime -> { reelVm.startVideo(contentResolver, uris.single(), mime, sharedText) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareContent(hasServer: Boolean?, snapshot: TripSnapshot?, fileCount: Int, importing: Boolean, onPick: (ShareTarget) -> Unit, onReel: (() -> Unit)? = null) {
    when {
        fileCount == 0 -> Message("Keine Datei und kein Reel-Link erhalten")
        importing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        hasServer == null -> Unit
        hasServer == false || (snapshot != null && snapshot.isEmpty) -> Message(MSG_SETUP_FIRST)
        snapshot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> {
            val targets = remember(snapshot) { shareTargets(snapshot) }
            ShareTargetScreen(targets, fileCount, onPick, onReel = onReel)
        }
    }
}

@Composable
private fun ReelWorking() {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Reel wird an die KI geschickt …", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** Attaches to the picked target; a Day that does not exist yet is created only after the file was imported. */
private fun containerSink(c: AppContainer) = ShareSink { target, input, name, mime ->
    c.addAttachment(
        target.type,
        { if (target.type == EntityType.DAY && target.id.startsWith(DATE_ID_PREFIX)) c.ensureDay(target.id.removePrefix(DATE_ID_PREFIX)) else target.id },
        input,
        name,
        mime,
    )
}

/** EXTRA_STREAM: one Uri for SEND, a list for SEND_MULTIPLE. [ownAuthority] is our FileProvider, whose URIs are rejected. */
internal fun sharedUris(intent: Intent, ownAuthority: String): List<Uri> {
    val list = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    }
    // Only content:// - a file:// URI from another app could point at this app's private files,
    // and so could a URI of our own FileProvider (handed back to us).
    return list.filter { it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.equals(ownAuthority, ignoreCase = true) }
}
