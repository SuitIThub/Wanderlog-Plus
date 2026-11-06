package de.wanderlogplus.app.ui.guides

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Reads Japanese phrases aloud with the system's text-to-speech; [ready] is false without a Japanese voice. */
class PhraseSpeaker(context: Context) {
    var ready by mutableStateOf(false)
        private set

    /** True once the engine answered (ready or not), so the screen can explain a missing voice. */
    var checked by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null

    init {
        tts = try {
            TextToSpeech(context.applicationContext) { status ->
                val engine = tts
                ready = status == TextToSpeech.SUCCESS && engine != null && when (engine.setLanguage(Locale.JAPAN)) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> false
                    else -> true
                }
                checked = true
            }
        } catch (e: Exception) {
            checked = true
            null
        }
    }

    fun speak(text: String) {
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phrase")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}

@Composable
fun rememberPhraseSpeaker(): PhraseSpeaker {
    val context = LocalContext.current
    val speaker = remember { PhraseSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
