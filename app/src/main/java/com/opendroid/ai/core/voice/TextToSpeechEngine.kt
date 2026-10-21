package com.opendroid.ai.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var mediaPlayer: MediaPlayer? = null
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

    @Volatile
    private var currentUtteranceId: String? = null

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
        currentUtteranceId = utteranceId
        scope.launch {
            val config = settingsRepository.llmConfig.first()
            val apiKey = config.elevenLabsApiKey
            val voiceId = config.elevenLabsVoiceId.ifEmpty { "21m00Tcm4TlvDq8ikWAM" } // default Rachel voice

            if (apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId)
                    if (success) return@launch
                } catch (e: Exception) {
                    // Fallback to local TTS if ElevenLabs fails
                }
            }

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

    private fun playElevenLabsTts(text: String, apiKey: String, voiceId: String): Boolean {
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

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    mainHandler.post {
                        notifyCompletion(currentUtteranceId)
                    }
                }
                start()
            }
            return true
        }
    }

    fun stop() {
        tts?.stop()
        mediaPlayer?.stop()
    }

    companion object {
        const val DEFAULT_UTTERANCE_ID = "opendroid_tts"
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
