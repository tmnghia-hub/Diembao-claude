package com.nononsenseapps.feeder.openai

import androidx.annotation.StringRes
import com.nononsenseapps.feeder.R
import java.security.MessageDigest

/**
 * The writing style an AI model should use when translating an article.
 * Offline translation (ML Kit / Bergamot) cannot change style, only AI models can.
 */
enum class TranslationStyle(
    val key: String,
    @StringRes val titleRes: Int,
    val instruction: String,
) {
    NEWS(
        key = "news",
        titleRes = R.string.ai_style_news,
        instruction =
            "Write natural, fluent editorial prose the way a native-speaking journalist would, not word for word. " +
                "Use a standard modern newspaper style.",
    ),
    SCIENTIFIC(
        key = "scientific",
        titleRes = R.string.ai_style_scientific,
        instruction =
            "Write in a scientific, academic register: objective, precise and impersonal, like a peer-reviewed article. " +
                "Use the established terminology of the field and keep scientific names, units, statistics and citations exactly as in the source. " +
                "Keep hedging words (may, suggests, likely, probably) exactly as strong as in the source and never make a claim more certain than it is.",
    ),
    SIMPLE(
        key = "simple",
        titleRes = R.string.ai_style_simple,
        instruction =
            "Write in plain, easy to understand language for a general reader: short sentences, everyday words, " +
                "and a friendly but neutral tone. Never change the meaning or leave out facts.",
    ),
    FORMAL(
        key = "formal",
        titleRes = R.string.ai_style_formal,
        instruction =
            "Write in a formal, polite and official register suitable for reports and official documents: " +
                "complete sentences, respectful wording, no slang and no colloquial expressions.",
    ),
    CUSTOM(
        key = "custom",
        titleRes = R.string.ai_style_custom,
        instruction = "",
    ),
    ;

    companion object {
        fun fromKey(key: String?): TranslationStyle = entries.firstOrNull { it.key == key } ?: NEWS
    }
}

/**
 * Everything that influences how an AI translation reads. Part of the translation cache key.
 */
data class AiTranslationOptions(
    val style: TranslationStyle = TranslationStyle.NEWS,
    val customStyle: String = "",
    // Keep specialised terms and explain them in brackets next to the translated term
    val annotateTerms: Boolean = true,
) {
    val styleInstruction: String
        get() =
            when (style) {
                TranslationStyle.CUSTOM ->
                    customStyle
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { "Write in the following style requested by the reader: $it" }
                        ?: TranslationStyle.NEWS.instruction

                else -> style.instruction
            }

    /** Short stable id used in cache file names. */
    val cacheKey: String
        get() {
            val raw = "${style.key}|${customStyle.trim()}|$annotateTerms"
            return MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray())
                .joinToString(separator = "") { "%02x".format(it) }
                .take(12)
        }
}

/**
 * Instruction that makes the model keep specialised terms and explain them right next to the term.
 */
fun glossaryInstruction(targetLanguage: String): String =
    "Specialised terms (scientific, medical, technical, legal, archaeological and similar vocabulary a general reader may not know): " +
        "at the first appearance of such a term, write the established term in '$targetLanguage', then directly after it, in round brackets, " +
        "the original term followed by a dash and a very short explanation in '$targetLanguage' (at most 15 words). " +
        "Example shape: translated term (original term - short explanation). " +
        "Later appearances of the same term use only the translated term. " +
        "Annotate only genuinely specialised terms, never everyday words, names of people, places or organisations. " +
        "Write the annotation as plain text inside the sentence; do not add any extra HTML tags for it."

/** What the key looks like it belongs to, so the user only has to paste the key. */
data class DetectedProvider(
    val name: String,
    // Empty means the default OpenAI host
    val baseUrl: String,
    // Used until the real list of models has been loaded
    val defaultModel: String,
    // Substrings, in priority order, used to pick a cheap and fast model from the real list
    val preferredModelHints: List<String>,
)

fun detectProviderFromKey(key: String): DetectedProvider? {
    val trimmed = key.trim()
    return when {
        trimmed.length < 8 -> null
        trimmed.startsWith("sk-ant-") ->
            DetectedProvider(
                name = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1/",
                defaultModel = "claude-haiku-5-5",
                preferredModelHints = listOf("haiku", "sonnet"),
            )

        trimmed.startsWith("sk-or-") ->
            DetectedProvider(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1/",
                defaultModel = "google/gemini-2.5-flash",
                preferredModelHints = listOf("gemini-2.5-flash", "gpt-4o-mini", "flash", "mini"),
            )

        // Classic Gemini keys start with AIza, the newer ones from AI Studio start with AQ.
        trimmed.startsWith("AIza") || trimmed.startsWith("AQ.") ->
            DetectedProvider(
                name = "Google Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
                defaultModel = "gemini-2.5-flash",
                preferredModelHints = listOf("gemini-2.5-flash", "gemini-flash", "flash"),
            )

        trimmed.startsWith("gsk_") ->
            DetectedProvider(
                name = "Groq",
                baseUrl = "https://api.groq.com/openai/v1/",
                defaultModel = "llama-3.3-70b-versatile",
                preferredModelHints = listOf("llama-3.3-70b", "llama"),
            )

        trimmed.startsWith("xai-") ->
            DetectedProvider(
                name = "xAI",
                baseUrl = "https://api.x.ai/v1/",
                defaultModel = "grok-3-mini",
                preferredModelHints = listOf("mini", "grok"),
            )

        trimmed.startsWith("sk-") ->
            DetectedProvider(
                name = "OpenAI",
                baseUrl = "",
                defaultModel = "gpt-4o-mini",
                preferredModelHints = listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-5-mini", "mini"),
            )

        else -> null
    }
}

/**
 * Picks a model from the real list returned by the provider. Returns null when nothing fits,
 * in which case the caller keeps what it has.
 */
fun DetectedProvider.pickModel(availableIds: List<String>): String? {
    if (availableIds.isEmpty()) return null
    val normalized = availableIds.associateBy { it.removePrefix("models/") }
    // Exact default first
    normalized.keys.firstOrNull { it == defaultModel }?.let { return it }
    for (hint in preferredModelHints) {
        normalized.keys
            .filter { it.contains(hint, ignoreCase = true) && !it.contains("preview", true) && !it.contains("image", true) }
            .minByOrNull { it.length }
            ?.let { return it }
    }
    return null
}
