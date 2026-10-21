package com.opendroid.ai.core.voice.kws

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Fully on-device wake-word engine (D1) on sherpa-onnx keyword spotting.
 *
 * Pipeline — entirely local, no network anywhere in this path:
 * microphone → [AudioRecord] 16 kHz mono PCM → [KeywordSpotter]
 * (Zipformer transducer KWS, int8) inference on a dedicated thread →
 * detection callback on the main thread. The model, its phoneme lexicon
 * and the generated `keywords.txt` all live in app-private storage,
 * copied from bundled assets on first use, so listening works in
 * airplane mode from first run.
 *
 * Failure honesty: any initialisation failure (missing/corrupt model,
 * native library load error, microphone unavailable) is published as
 * [WakeWordEngineState.Error] via [WakeWordStatus]. This engine NEVER
 * substitutes another recogniser behind the user's back.
 */
class SherpaKwsEngine(private val context: Context) : KeywordSpotterEngine {

    override val kind: WakeEngineKind = WakeEngineKind.OFFLINE_SHERPA

    @Volatile private var keywordPhrase: String = WakeKeywordFactory.DEFAULT_PHRASE
    @Volatile private var sensitivity: Float = 0.5f

    @Volatile private var running = false

    /**
     * Monotonic start generation. stop() can return while its capture
     * thread is still alive (join timeout — the thread owns release and
     * finishes later), and a restart of THIS instance then runs two
     * generations side by side. Without an identity check the zombie
     * generation installs its handles into the shared spotter/stream
     * fields over its successor's, enters the capture loop alongside it
     * (the shared `running` flag was re-armed by the new start), and —
     * once the successor has exited and cleared the thread reference —
     * releases handles that are not its own: a double-release of the
     * successor's, its own leaked (finding #9). Every generation tags
     * its work with the value it was started with and touches shared
     * state only while that tag is still the current generation.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var onDetected: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configure(keywordPhrase: String, sensitivity: Float) {
        this.keywordPhrase = keywordPhrase
        this.sensitivity = sensitivity.coerceIn(0f, 1f)
    }

    override fun start(onDetected: (String) -> Unit) {
        if (running) return
        this.onDetected = onDetected
        running = true
        val myGeneration = generation.incrementAndGet()
        WakeWordStatus.publish(WakeWordEngineState.Starting, kind)
        captureThread = Thread({ runEngine(myGeneration) }, "opendroid-kws").apply { start() }
    }

    private fun runEngine(myGeneration: Int) {
        var newSpotter: KeywordSpotter? = null
        var newStream: OnlineStream? = null
        var installed = false
        try {
            val modelDir = KwsModelFiles.ensureExtracted(context)
            val (keywordLines, fallbackNote) = resolveKeywordLines(modelDir)
            val keywordsFile = File(modelDir, KwsModelFiles.KEYWORDS_FILE_NAME)
            keywordsFile.writeText(WakeKeywordFactory.keywordsFileContent(keywordLines))

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, KwsModelFiles.ENCODER).absolutePath,
                        decoder = File(modelDir, KwsModelFiles.DECODER).absolutePath,
                        joiner = File(modelDir, KwsModelFiles.JOINER).absolutePath
                    ),
                    tokens = File(modelDir, KwsModelFiles.TOKENS).absolutePath,
                    numThreads = NUM_INFERENCE_THREADS,
                    modelType = "zipformer2"
                ),
                keywordsFile = keywordsFile.absolutePath,
                keywordsScore = WakeKeywordFactory.KEYWORDS_SCORE,
                keywordsThreshold = WakeKeywordFactory.thresholdForSensitivity(sensitivity)
            )
            // null AssetManager → all paths above are plain filesystem paths.
            val createdSpotter = KeywordSpotter(null, config)
            newSpotter = createdSpotter
            val createdStream = createdSpotter.createStream()
            newStream = createdStream
            if (myGeneration != generation.get() || !running) {
                // Superseded by a newer start while this generation was
                // still initialising (only reachable after a timed-out
                // stop), or stop() raced the (file-heavy) initialisation:
                // release and leave quietly. This generation's handles
                // were never installed, so the shared fields — possibly
                // already a successor's — are not touched, and no
                // Listening is published after Stopped (finding #9).
                releaseHandles(createdStream, createdSpotter)
                newStream = null
                newSpotter = null
                return
            }
            spotter = createdSpotter
            stream = createdStream
            installed = true
            WakeWordStatus.publish(WakeWordEngineState.Listening, kind, fallbackNote)
            captureLoop(myGeneration, createdSpotter, createdStream)
            // The capture thread owns native release on exit: stop() frees
            // the handles itself only after a successful join, so they are
            // never released from under a live capture thread (finding #2).
            releaseOnExit(myGeneration, createdStream, createdSpotter)
        } catch (e: Exception) {
            if (myGeneration == generation.get()) {
                running = false
                if (installed) {
                    releaseNative()
                } else {
                    releaseHandles(newStream, newSpotter)
                }
                WakeWordStatus.publish(
                    WakeWordEngineState.Error(
                        "Offline wake word failed to start: ${e.message ?: e.javaClass.simpleName}"
                    ),
                    kind
                )
            } else {
                // A superseded generation failing late must not clear the
                // successor's running flag, publish Error over its
                // Listening, or release the successor's field handles —
                // only its own, if it ever installed them (finding #9).
                if (installed) {
                    releaseOnExit(myGeneration, newStream, newSpotter)
                } else {
                    releaseHandles(newStream, newSpotter)
                }
            }
        } finally {
            // Self-clean so a later stop()/start() never acts on a dead
            // thread reference. Identity-checked: start() may already have
            // installed a successor thread.
            if (captureThread === Thread.currentThread()) captureThread = null
        }
    }

    /**
     * Builds the keyword lines for the configured phrase. When the user's
     * custom phrase uses words the bundled pronunciation lexicon does not
     * know, the engine listens for the default phrase instead and says so
     * (returned note) — the custom phrase is never silently half-matched.
     */
    private fun resolveKeywordLines(modelDir: File): Pair<List<String>, String?> {
        val lexiconFile = File(modelDir, KwsModelFiles.LEXICON)
        val lexicon = lexiconFile.bufferedReader().useLines { PhoneLexicon.parse(it) }
        return when (val result = WakeKeywordFactory.buildLines(keywordPhrase, lexicon)) {
            is KeywordBuildResult.Ready -> result.lines to null
            is KeywordBuildResult.UnknownWords -> {
                val fallback = WakeKeywordFactory.buildLines(WakeKeywordFactory.DEFAULT_PHRASE, lexicon)
                val lines = (fallback as? KeywordBuildResult.Ready)?.lines
                    ?: throw IllegalStateException("Bundled lexicon lacks the default wake phrase")
                lines to ("\"${keywordPhrase.trim()}\" uses words the offline model doesn't know " +
                    "(${result.words.joinToString()}); listening for " +
                    "\"${WakeKeywordFactory.DEFAULT_PHRASE}\" instead.")
            }
        }
    }

    private fun captureLoop(myGeneration: Int, activeSpotter: KeywordSpotter, activeStream: OnlineStream) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("Microphone does not support 16 kHz mono capture")
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_SAMPLES * 2)
            )
        } catch (e: SecurityException) {
            throw IllegalStateException("Microphone permission is not granted", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone could not be initialised")
        }
        audioRecord = record
        try {
            record.startRecording()
            val shorts = ShortArray(CHUNK_SAMPLES)
            var consecutiveReadErrors = 0
            // The generation term matters, not just `running`: after a
            // timed-out stop the successor re-arms `running`, and a zombie
            // generation must still leave instead of decoding alongside
            // its successor on its own handles (finding #9).
            while (running && myGeneration == generation.get()) {
                val read = record.read(shorts, 0, shorts.size)
                if (read <= 0) {
                    // A transient bad read is worth a short retry, but a
                    // persistent error (audio server died, mic seized by
                    // another app) must not spin this thread at 100% CPU
                    // until the battery dies: back off, count, and give up
                    // into the Error state (finding #1).
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        throw IllegalStateException(
                            "Microphone reads kept failing (last read result $read)"
                        )
                    }
                    try {
                        Thread.sleep(READ_ERROR_BACKOFF_MS)
                    } catch (_: InterruptedException) {
                        // Nobody interrupts this thread; the loop re-checks
                        // `running` and exits promptly on stop either way.
                    }
                    continue
                }
                consecutiveReadErrors = 0
                val samples = FloatArray(read) { shorts[it] / 32768.0f }
                activeStream.acceptWaveform(samples, SAMPLE_RATE_HZ)
                while (activeSpotter.isReady(activeStream)) {
                    activeSpotter.decode(activeStream)
                    val keyword = activeSpotter.getResult(activeStream).keyword
                    if (keyword.isNotBlank()) {
                        // Reset right after a detection, per the engine's
                        // contract, then hand over on the main thread. A
                        // superseded generation must not deliver its
                        // detection through the successor's callback.
                        activeSpotter.reset(activeStream)
                        val callback = onDetected
                        mainHandler.post {
                            if (running && myGeneration == generation.get()) callback?.invoke(keyword)
                        }
                        break
                    }
                }
            }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            if (audioRecord === record) audioRecord = null
        }
    }

    override fun stop() {
        if (!running && captureThread == null) return
        running = false
        // Unblock a pending read() so the capture thread can observe the flag.
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        val thread = captureThread
        when {
            thread == null -> releaseNative()
            thread === Thread.currentThread() -> {
                // stop() from the capture thread itself: release inline;
                // the loop observes `running == false` and exits.
                captureThread = null
                releaseNative()
            }
            else -> {
                thread.join(STOP_JOIN_TIMEOUT_MS)
                if (!thread.isAlive) {
                    // Successful join: the capture thread already released
                    // the native handles and the mic on its way out, so
                    // stop() returns with the engine fully torn down.
                    if (captureThread === thread) captureThread = null
                }
                // Join timed out — the capture thread may still be inside
                // native init/decode or a stuck read(). Do NOT null the
                // reference or release here: that defeated releaseNative()'s
                // own "never free under a live capture thread" guard and
                // risked a native use-after-release crash (finding #2).
                // The capture thread owns release on exit; the still-set
                // reference keeps the guard armed until it is truly done.
            }
        }
        if (WakeWordStatus.info.value.state !is WakeWordEngineState.Error) {
            WakeWordStatus.publish(WakeWordEngineState.Stopped, kind)
        }
    }

    /** Releases the given handles quietly, stream before spotter. */
    private fun releaseHandles(streamToRelease: OnlineStream?, spotterToRelease: KeywordSpotter?) {
        try {
            streamToRelease?.release()
        } catch (_: Exception) {
        }
        try {
            spotterToRelease?.release()
        } catch (_: Exception) {
        }
    }

    /**
     * Exit-path release for one capture generation. While the generation
     * is still current, its handles are the shared fields and the usual
     * [releaseNative] path applies. A superseded generation must never
     * release through the fields — they may already hold its successor's
     * handles (releasing them is a double-release/use-after-release;
     * skipping its own leaks them) — so it clears the fields only where
     * they still identity-match its own handles and then releases those
     * handles directly (finding #9).
     */
    private fun releaseOnExit(myGeneration: Int, ownStream: OnlineStream?, ownSpotter: KeywordSpotter?) {
        if (myGeneration == generation.get()) {
            releaseNative()
            return
        }
        if (stream === ownStream) stream = null
        if (spotter === ownSpotter) spotter = null
        releaseHandles(ownStream, ownSpotter)
    }

    private fun releaseNative() {
        // If the capture thread is still alive and we are not it, leave the
        // native handles to the finalizers rather than racing a release
        // against in-flight inference.
        val thread = captureThread
        if (thread != null && thread.isAlive && thread !== Thread.currentThread()) return
        try {
            stream?.release()
        } catch (_: Exception) {
        }
        try {
            spotter?.release()
        } catch (_: Exception) {
        }
        stream = null
        spotter = null
    }

    override fun destroy() {
        stop()
        onDetected = null
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16000
        private const val FEATURE_DIM = 80

        /** 100 ms of audio per inference step, as in the engine's samples. */
        private const val CHUNK_SAMPLES = 1600

        /** Battery/CPU bound: the 3M int8 spotter needs very little. */
        private const val NUM_INFERENCE_THREADS = 2
        private const val STOP_JOIN_TIMEOUT_MS = 1000L

        /** Pause between retries of a failing [AudioRecord.read] (finding #1). */
        private const val READ_ERROR_BACKOFF_MS = 50L

        /** Consecutive read failures tolerated before the engine errors out. */
        private const val MAX_CONSECUTIVE_READ_ERRORS = 50

        /** True when the KWS model files are bundled in this build's assets. */
        fun isModelBundled(context: Context): Boolean {
            return try {
                val names = context.assets.list(KwsModelFiles.ASSET_DIR) ?: return false
                names.contains(KwsModelFiles.TOKENS) && names.contains(KwsModelFiles.ENCODER)
            } catch (_: Exception) {
                false
            }
        }
    }
}

/**
 * The bundled KWS model files and their app-private extraction. The model
 * ships in `assets/kws/` (airplane-mode from first run); extraction to
 * `filesDir/kws/` exists because the spotter needs real filesystem paths
 * (including for the runtime-generated `keywords.txt`).
 */
object KwsModelFiles {
    const val ASSET_DIR = "kws"
    const val ENCODER = "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-13-avg-2-chunk-16-left-64.onnx"
    const val JOINER = "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val LEXICON = "en.phone"
    const val KEYWORDS_FILE_NAME = "keywords.txt"

    /** Model identity; bump when the bundled model changes to re-extract. */
    private const val MODEL_ID = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
    private const val MARKER_FILE = ".model-id"

    private val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS, LEXICON)

    fun ensureExtracted(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val marker = File(dir, MARKER_FILE)
        val upToDate = marker.exists() && marker.readText().trim() == MODEL_ID &&
            MODEL_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }
        if (!upToDate) {
            if (!dir.exists()) dir.mkdirs()
            for (name in MODEL_FILES) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            marker.writeText(MODEL_ID)
        }
        return dir
    }
}
