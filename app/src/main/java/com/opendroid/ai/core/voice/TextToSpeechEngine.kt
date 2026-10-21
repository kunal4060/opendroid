package com.opendroid.ai.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class TextToSpeechEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Set by [destroy]. The blocking ElevenLabs download cannot be
     * interrupted by scope cancellation alone, so in-flight work re-checks
     * this before creating a player — otherwise speech would start
     * playing from an engine whose owner believes it is gone (finding #3).
     */
    @Volatile
    private var destroyed = false
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Serialises every [mediaPlayer] hand-off against [destroy]. The
     * destroyed re-check and the player creation in [playElevenLabsTts]
     * must be atomic with respect to destroy()'s release: a bare
     * check-then-create lets destroy() slip into the gap, after which
     * the in-flight speak assigns and starts a player on a destroyed
     * engine that nothing ever releases (finding #10).
     */
    private val mediaPlayerLock = Any()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var onCompletionListener: (() -> Unit)? = null

    /**
     * Completion observer that receives the id of the utterance that just
     * finished (see [speak]). Lets a caller react only to ITS OWN speech —
     * e.g. OpenDroidService starts approval listening only when the
     * plan-approval prompt itself completes, never when unrelated
     * read-aloud speech happens to finish.
     */
    var onUtteranceCompletionListener: ((String?) -> Unit)? = null

    // Additional completion observers, for callers that must not clobber the
    // single [onCompletionListener] slot owned by OpenDroidService (chat
    // read-aloud queues its chunks off these). Notified on the main thread,
    // exactly like [onCompletionListener].
    private val completionListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addCompletionListener(listener: () -> Unit) {
        completionListeners.addIfAbsent(listener)
    }

    fun removeCompletionListener(listener: () -> Unit) {
        completionListeners.remove(listener)
    }

    private fun notifyCompletion(utteranceId: String? = null) {
        onCompletionListener?.invoke()
        onUtteranceCompletionListener?.invoke(utteranceId)
        completionListeners.forEach { listener -> runCatching { listener() } }
    }

    /** Registered listeners only — for paths where no speech actually played. */
    private fun notifyCompletionListeners() {
        completionListeners.forEach { listener -> runCatching { listener() } }
    }

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    notifyCompletion(utteranceId)
                }
            }
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    notifyCompletion(utteranceId)
                }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
            }
        }
    }

    fun speak(text: String) {
        speak(text, DEFAULT_UTTERANCE_ID)
    }

    /**
     * Speaks [text] tagged with [utteranceId]; completion observers that
     * care about a specific utterance ([onUtteranceCompletionListener])
     * receive this id when the speech finishes.
     */
    fun speak(text: String, utteranceId: String) {
        scope.launch {
            if (destroyed) return@launch
            val config = settingsRepository.llmConfig.first()
            val apiKey = config.elevenLabsApiKey
            val voiceId = config.elevenLabsVoiceId.ifEmpty { "21m00Tcm4TlvDq8ikWAM" } // default Rachel voice

            if (apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId, utteranceId)
                    if (success) return@launch
                } catch (e: Exception) {
                    // Fallback to local TTS if ElevenLabs fails
                }
            }

            // Destroyed while the ElevenLabs attempt was in flight: the
            // fallback must not speak either.
            if (destroyed) return@launch

            // Local fallback — must run on main thread
            if (isInitialized) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            } else {
                // Nothing will be spoken (engine not ready, no cloud voice):
                // still release registered listeners so a read-aloud queue
                // waiting on completion doesn't hang. The service's
                // onCompletionListener is deliberately NOT fired — no speech
                // happened, so no Speaking state needs resetting.
                mainHandler.post {
                    notifyCompletionListeners()
                }
            }
        }
    }

    private fun playElevenLabsTts(text: String, apiKey: String, voiceId: String, utteranceId: String): Boolean {
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val jsonPayload = """
            {
              "text": "$escapedText",
              "model_id": "eleven_monolingual_v1",
              "voice_settings": {
                "stability": 0.5,
                "similarity_boost": 0.75
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body
            
            val tempFile = File.createTempFile("elevenlabs_", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            
            FileOutputStream(tempFile).use { fos ->
                body.byteStream().copyTo(fos)
            }

            // destroy() may have run while the (blocking) download was in
            // flight — never create a player on a destroyed engine. The
            // check and the creation happen under mediaPlayerLock, and
            // destroy() takes the same lock to release, so the check
            // cannot go stale between test and assignment (finding #10).
            synchronized(mediaPlayerLock) {
                if (destroyed) return false

                mediaPlayer?.release()
                mediaPlayer = null
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    // Capture THIS utterance's id in the closure: the speech
                    // that actually played is what completed. Reading a
                    // shared field at completion time mis-attributes the
                    // event to any interleaved speak() — with an approval
                    // prompt in flight that either never arms approval
                    // listening or arms it for unrelated speech (finding #3).
                    setOnCompletionListener {
                        mainHandler.post {
                            notifyCompletion(utteranceId)
                        }
                    }
                    // Without an error listener a failed playback leaves the
                    // player in the Error state — where stop() throws — and
                    // no completion ever fires. Report the error as this
                    // utterance's completion (the platform's own fallback
                    // when no listener is installed) so listeners advance
                    // and the player leaves the error path handled.
                    setOnErrorListener { _, _, _ ->
                        mainHandler.post {
                            notifyCompletion(utteranceId)
                        }
                        true
                    }
                    start()
                }
            }
            return true
        }
    }

    fun stop() {
        tts?.stop()
        // MediaPlayer.stop() is illegal outside the Prepared/Started/
        // Paused/PlaybackCompleted states: the field can still reference
        // the player released just before a failed re-creation, or one a
        // failed playback left in the Error state. Barge-in and session
        // stop call this on the main thread — an unguarded stop() turns
        // one bad cloud-TTS download into a crash (finding #4). A player
        // in one of those states is not playing anything stop() could
        // silence, so the exception is safe to swallow.
        try {
            synchronized(mediaPlayerLock) { mediaPlayer?.stop() }
        } catch (_: IllegalStateException) {
            // Player already released or in the Error state: nothing to stop.
        }
    }

    companion object {
        const val DEFAULT_UTTERANCE_ID = "opendroid_tts"
    }

    fun destroy() {
        destroyed = true
        // Cancel pending speaks; an in-flight blocking download still
        // finishes its network call but hits the destroyed checks above
        // and never reaches a MediaPlayer.
        scope.cancel()
        tts?.shutdown()
        tts = null
        // Same lock as the creation path: either an in-flight speak
        // created its player first (and it is released here), or this
        // release runs first and the speak's in-lock destroyed check
        // refuses to create one at all (finding #10).
        synchronized(mediaPlayerLock) {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
}
