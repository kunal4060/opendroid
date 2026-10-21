package com.opendroid.ai.core.voice.kws

/**
 * Pure, JVM-testable core of the offline wake word (D1): the English
 * pronunciation lexicon shipped with the KWS model, conversion of a
 * user-facing wake phrase ("Open Droid") into sherpa-onnx keyword lines,
 * and the sensitivity → detection-threshold mapping.
 *
 * The bundled model (`sherpa-onnx-kws-zipformer-zh-en-3M`) is a
 * phone-based spotter: its `tokens.txt` contains English phonemes
 * (CMU-style, with stress digits) plus Chinese pinyin parts. A keyword is
 * therefore a space-separated phoneme sequence followed by `@NAME`,
 * exactly the format of the model's own bundled test keywords, e.g.
 * `L AY1 T AH1 P @LIGHT_UP`. English pronunciations come from the model's
 * `en.phone` lexicon (126k entries), so arbitrary English wake phrases
 * work without any retraining — but a word the lexicon does not know
 * cannot be spotted, and that case is reported, never papered over.
 */

/** Which wake-word engine the user selected in Settings. */
enum class WakeEngineKind(val settingsId: String, val label: String) {
    OFFLINE_SHERPA("OFFLINE", "Offline (on-device)"),
    LEGACY_SPEECH("LEGACY", "Legacy (system speech recogniser)");

    companion object {
        /** Unknown ids resolve to the offline engine — offline is the default. */
        fun fromSettingsId(id: String?): WakeEngineKind =
            entries.firstOrNull { it.settingsId == id } ?: OFFLINE_SHERPA
    }
}

/** Observable state of the wake-word pipeline, surfaced in Settings. */
sealed interface WakeWordEngineState {
    data object Idle : WakeWordEngineState
    data object Starting : WakeWordEngineState
    data object Listening : WakeWordEngineState
    data object Stopped : WakeWordEngineState
    data class Error(val message: String) : WakeWordEngineState
}

/**
 * Decides which engine may actually run. The one hard rule: when the user
 * asked for the offline engine but its model is unavailable, the answer is
 * `null` (surface an error) — silently substituting the network-dependent
 * legacy recogniser would fake the feature.
 */
object WakeEnginePolicy {
    fun resolve(requested: WakeEngineKind, offlineModelAvailable: Boolean): WakeEngineKind? =
        when (requested) {
            WakeEngineKind.OFFLINE_SHERPA ->
                if (offlineModelAvailable) WakeEngineKind.OFFLINE_SHERPA else null
            WakeEngineKind.LEGACY_SPEECH -> WakeEngineKind.LEGACY_SPEECH
        }
}

/**
 * Word → phoneme-sequence lexicon parsed from the model's `en.phone`
 * asset. File format: one pronunciation per line, `WORD PH PH ...`;
 * a word may appear on several lines (alternate pronunciations) — the
 * first is used, matching how the file itself is ordered.
 */
class PhoneLexicon private constructor(
    private val entries: Map<String, List<String>>
) {
    val size: Int get() = entries.size

    fun contains(word: String): Boolean = entries.containsKey(word)

    /** First listed pronunciation of [word] (already normalised), or null. */
    fun pronunciationOf(word: String): List<String>? = entries[word]

    companion object {
        fun parse(lines: Sequence<String>): PhoneLexicon {
            val map = LinkedHashMap<String, List<String>>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) continue
                // Alternate pronunciations in CMU-style files are marked
                // WORD(2), WORD(3)… — they are the same word.
                val word = parts[0].substringBefore('(').uppercase()
                if (word.isEmpty() || map.containsKey(word)) continue
                map[word] = parts.drop(1)
            }
            return PhoneLexicon(map)
        }
    }
}

/** Result of converting a wake phrase into spotter keyword lines. */
sealed interface KeywordBuildResult {
    /** Ready-to-write keyword lines (one per phrase variant). */
    data class Ready(val lines: List<String>, val displayName: String) : KeywordBuildResult

    /** These words are not in the model's pronunciation lexicon. */
    data class UnknownWords(val words: List<String>) : KeywordBuildResult
}

object WakeKeywordFactory {
    const val DEFAULT_PHRASE = "Open Droid"

    /** Boosting score written into the spotter config; sherpa's own default. */
    const val KEYWORDS_SCORE = 1.5f

    /**
     * Maps the Settings sensitivity slider (0 = strict … 1 = eager) to the
     * spotter's detection threshold. Monotonic by construction: higher
     * sensitivity ⇒ lower threshold ⇒ wakes more easily (and false-wakes
     * more). The midpoint reproduces sherpa's shipped default of 0.25.
     */
    fun thresholdForSensitivity(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0f, 1f)
        return 0.35f - 0.20f * s
    }

    /**
     * Splits a user phrase into normalised lexicon words: upper-cased,
     * hyphens treated as spaces, surrounding punctuation dropped (inner
     * apostrophes kept — the lexicon contains entries like `'BOUT`).
     */
    fun wordsOf(phrase: String): List<String> =
        phrase.uppercase()
            .replace('-', ' ')
            .split(Regex("\\s+"))
            .map { it.trim().trim('.', ',', '!', '?', ';', ':', '"') }
            .filter { it.isNotEmpty() }

    /** `Hey Open Droid` → `HEY_OPEN_DROID` (the `@name` in a keyword line). */
    fun displayNameFor(phrase: String): String =
        wordsOf(phrase).joinToString("_") { word ->
            word.filter { it.isLetterOrDigit() }
        }.ifEmpty { "WAKE_WORD" }

    /**
     * Builds the keyword lines for [phrase]. Besides the phrase itself, a
     * "hey …" variant is added when the phrase doesn't already start with
     * it (people naturally say both), which is the multi-tokenisation
     * coverage the D1 research prescribes. Lines use the model's own
     * format — phonemes + `@NAME`, no per-line score/threshold overrides;
     * sensitivity is applied once, via the spotter config.
     */
    fun buildLines(phrase: String, lexicon: PhoneLexicon): KeywordBuildResult {
        val effective = phrase.trim().ifEmpty { DEFAULT_PHRASE }
        val words = wordsOf(effective)
        if (words.isEmpty()) return KeywordBuildResult.UnknownWords(emptyList())

        val unknown = words.filterNot { lexicon.contains(it) }
        if (unknown.isNotEmpty()) return KeywordBuildResult.UnknownWords(unknown)

        fun lineFor(lineWords: List<String>, name: String): String {
            val phones = lineWords.flatMap { lexicon.pronunciationOf(it).orEmpty() }
            return phones.joinToString(" ") + " @" + name
        }

        val displayName = displayNameFor(effective)
        val lines = mutableListOf(lineFor(words, displayName))
        if (words.first() != "HEY" && lexicon.contains("HEY")) {
            lines += lineFor(listOf("HEY") + words, "HEY_$displayName")
        }
        return KeywordBuildResult.Ready(lines.distinct(), displayName)
    }

    /** Serialises keyword lines into `keywords.txt` content. */
    fun keywordsFileContent(lines: List<String>): String =
        lines.joinToString(separator = "\n", postfix = "\n")
}
