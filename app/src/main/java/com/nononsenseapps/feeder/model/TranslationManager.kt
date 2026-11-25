package com.nononsenseapps.feeder.model

import android.app.Application
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.OpenAISettings
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.TranslationApiSettings
import com.nononsenseapps.feeder.localtranslation.LocalTranslator
import com.nononsenseapps.feeder.openai.AiTranslationOptions
import com.nononsenseapps.feeder.openai.OpenAIApi
import com.nononsenseapps.feeder.openai.OpenAIApi.TranslationResult
import com.nononsenseapps.feeder.openai.OpenAIApi.TranslationResult.ErrorAction
import com.nononsenseapps.feeder.openai.TRANSLATION_PROVIDER_OFFLINE
import com.nononsenseapps.feeder.openai.canSummarize
import com.nononsenseapps.feeder.openai.canTranslate
import com.nononsenseapps.feeder.openai.translationProviderKey
import com.nononsenseapps.feeder.openai.isDeepL
import com.nononsenseapps.feeder.openai.isLocalTranslation
import com.nononsenseapps.feeder.openai.isOfflineTranslationProvider
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.util.FilePathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.security.MessageDigest

class TranslationManager(
    override val di: DI,
) : DIAware {
    private val application: Application by instance()
    private val repository: Repository by instance()
    private val openAIApi: OpenAIApi by instance()
    private val localTranslator: LocalTranslator by instance()
    private val filePathProvider: FilePathProvider by instance()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    suspend fun getCachedTranslatedFeedListItem(
        item: FeedListItem,
        settings: TranslationApiSettings,
        targetLanguage: String,
    ): CachedFeedListItemTranslation =
        withContext(Dispatchers.IO) {
            if (!settings.canTranslate || targetLanguage.isBlank()) {
                return@withContext CachedFeedListItemTranslation(
                    item = item,
                    hasCachedTranslation = false,
                    isFullyCached = false,
                )
            }

            val cache = loadCache(item.id, settings, targetLanguage)
            val titleHash = sha256(item.title)
            val snippetHash = sha256(item.snippet)

            val hasCachedTitle =
                item.title.isNotBlank() &&
                    cache.titleHash == titleHash &&
                    cache.translatedTitle.isUsableCachedTranslation(
                        original = item.title,
                        sourceLanguage = cache.sourceLanguage,
                        targetLanguage = targetLanguage,
                        settings = settings,
                    )
            val hasCachedSnippet =
                item.snippet.isNotBlank() &&
                    cache.snippetHash == snippetHash &&
                    cache.translatedSnippet.isUsableCachedTranslation(
                        original = item.snippet,
                        sourceLanguage = cache.sourceLanguage,
                        targetLanguage = targetLanguage,
                        settings = settings,
                    )
            val titleReady = item.title.isBlank() || hasCachedTitle
            val snippetReady = item.snippet.isBlank() || hasCachedSnippet

            CachedFeedListItemTranslation(
                item =
                    item.copy(
                        title = cache.translatedTitle.takeIf { hasCachedTitle } ?: item.title,
                        snippet = cache.translatedSnippet.takeIf { hasCachedSnippet } ?: item.snippet,
                    ),
                hasCachedTranslation = hasCachedTitle || hasCachedSnippet,
                isFullyCached = titleReady && snippetReady,
            )
        }

    suspend fun translateFeedListItem(item: FeedListItem): FeedListItem =
        translateFeedListItem(
            item = item,
            settings = translationSettings(),
            targetLanguage = repository.preferredTranslationLanguage.value,
        )

    suspend fun translateFeedListItem(
        item: FeedListItem,
        settings: TranslationApiSettings,
        targetLanguage: String,
    ): FeedListItem =
        withContext(Dispatchers.IO) {
            val targetLanguage = targetLanguage.trim()
            if (!settings.canTranslate || targetLanguage.isBlank()) {
                return@withContext item
            }

            val cache = loadCache(item.id, settings, targetLanguage)
            val titleHash = sha256(item.title)
            val snippetHash = sha256(item.snippet)
            val cachedTitle =
                cache.translatedTitle.takeIf {
                    cache.titleHash == titleHash &&
                        it.isUsableCachedTranslation(
                            original = item.title,
                            sourceLanguage = cache.sourceLanguage,
                            targetLanguage = targetLanguage,
                            settings = settings,
                        )
                }
            val cachedSnippet =
                cache.translatedSnippet.takeIf {
                    cache.snippetHash == snippetHash &&
                        it.isUsableCachedTranslation(
                            original = item.snippet,
                            sourceLanguage = cache.sourceLanguage,
                            targetLanguage = targetLanguage,
                            settings = settings,
                        )
                }

            val titleReady = item.title.isBlank() || cachedTitle != null
            val snippetReady = item.snippet.isBlank() || cachedSnippet != null
            if (titleReady && snippetReady) {
                return@withContext item.copy(
                    title = cachedTitle ?: item.title,
                    snippet = cachedSnippet ?: item.snippet,
                )
            }
            val cachedSourceLanguage =
                if (cachedTitle != null || cachedSnippet != null) {
                    cache.sourceLanguage
                } else {
                    ""
                }

            val detectedSameLanguage =
                detectSourceLanguageIfAlreadyTargetLanguage(
                    content =
                        listOf(item.title, item.snippet)
                            .filter(String::isNotBlank)
                            .joinToString(separator = "\n\n"),
                    targetLanguage = targetLanguage,
                    settings = settings,
                )
            if (detectedSameLanguage != null) {
                val updatedCache =
                    cache.copy(
                        sourceLanguage = detectedSameLanguage,
                        titleHash = if (item.title.isBlank()) null else titleHash,
                        translatedTitle = if (item.title.isBlank()) null else item.title,
                        snippetHash = if (item.snippet.isBlank()) null else snippetHash,
                        translatedSnippet = if (item.snippet.isBlank()) null else item.snippet,
                    )
                if (updatedCache != cache) {
                    saveCache(
                        item.id,
                        settings,
                        targetLanguage,
                        updatedCache,
                    )
                }
                return@withContext item
            }

            val translatedSnippetResult =
                if (item.snippet.isBlank() || cachedSnippet != null) {
                    null
                } else {
                    translateOrNull(
                        content = item.snippet,
                        targetLanguage = targetLanguage,
                        settings = settings,
                        sourceLangHint = cachedSourceLanguage,
                    )
                }
            val translatedSnippet = cachedSnippet ?: translatedSnippetResult?.content.orEmpty()
            val sourceLanguageHint =
                translatedSnippetResult
                    ?.detectedLanguageOrBlank()
                    .orEmpty()
                    .ifBlank { cachedSourceLanguage }

            val translatedTitleResult =
                if (item.title.isBlank() || cachedTitle != null) {
                    null
                } else {
                    translateOrNull(
                        content = item.title,
                        targetLanguage = targetLanguage,
                        settings = settings,
                        sourceLangHint = sourceLanguageHint,
                    )
                }
            val translatedTitle = cachedTitle ?: translatedTitleResult?.content.orEmpty()
            val sourceLanguage =
                translatedSnippetResult
                    ?.detectedLanguageOrBlank()
                    .orEmpty()
                    .ifBlank { translatedTitleResult?.detectedLanguageOrBlank().orEmpty() }
                    .ifBlank { cachedSourceLanguage }

            val updatedCache =
                cache.copy(
                    sourceLanguage = sourceLanguage.ifBlank { cachedSourceLanguage },
                    titleHash =
                        when {
                            item.title.isBlank() -> null
                            translatedTitle.isNotBlank() -> titleHash
                            else -> cache.titleHash
                        },
                    translatedTitle =
                        when {
                            item.title.isBlank() -> null
                            translatedTitle.isNotBlank() -> translatedTitle
                            else -> cache.translatedTitle
                        },
                    snippetHash =
                        when {
                            item.snippet.isBlank() -> null
                            translatedSnippet.isNotBlank() -> snippetHash
                            else -> cache.snippetHash
                        },
                    translatedSnippet =
                        when {
                            item.snippet.isBlank() -> null
                            translatedSnippet.isNotBlank() -> translatedSnippet
                            else -> cache.translatedSnippet
                        },
                )

            // A fallback translation must not be remembered as if the API had produced it,
            // otherwise the better translation would never be retried
            val usedOfflineFallback =
                !settings.isLocalTranslation &&
                    (
                        isOfflineTranslationProvider(translatedSnippetResult?.provider.orEmpty()) ||
                            isOfflineTranslationProvider(translatedTitleResult?.provider.orEmpty())
                    )
            if (updatedCache != cache && !usedOfflineFallback) {
                saveCache(
                    item.id,
                    settings,
                    targetLanguage,
                    updatedCache,
                )
            }

            item.copy(
                title = translatedTitle.ifBlank { item.title },
                snippet = translatedSnippet.ifBlank { item.snippet },
            )
        }

    suspend fun getOrTranslateArticle(
        itemId: Long,
        title: String,
        html: String,
        isFullText: Boolean,
    ): ArticleTranslation? =
        withContext(Dispatchers.IO) {
            val settings = translationSettings()
            val targetLanguage = repository.preferredTranslationLanguage.value.trim()
            if (!settings.canTranslate || targetLanguage.isBlank() || html.isBlank()) {
                return@withContext null
            }

            val cache = loadCache(itemId, settings, targetLanguage)
            val titleHash = sha256(title)
            val htmlHash = sha256(html)

            val cachedHtml =
                if (isFullText) {
                    cache.translatedFullArticleHtml.takeIf { cache.fullArticleHtmlHash == htmlHash }
                } else {
                    cache.translatedArticleHtml.takeIf { cache.articleHtmlHash == htmlHash }
                }
            val cachedTitle =
                cache.translatedTitle.takeIf {
                    cache.titleHash == titleHash &&
                        it.isUsableCachedTranslation(
                            original = title,
                            sourceLanguage = cache.sourceLanguage,
                            targetLanguage = targetLanguage,
                            settings = settings,
                        )
                }

            if (cachedHtml != null && cachedTitle != null) {
                return@withContext ArticleTranslation(
                    translatedTitle = cachedTitle,
                    translatedHtml = cachedHtml,
                    sourceLanguage = cache.sourceLanguage,
                    translatedBy = cache.translatedBy,
                )
            }

            val detectedSameLanguage =
                detectArticleAlreadyInTargetLanguage(
                    itemId = itemId,
                    title = title,
                    html = html,
                    isFullText = isFullText,
                    settings = settings,
                    targetLanguage = targetLanguage,
                    existingCache = cache,
                )
            if (detectedSameLanguage != null) {
                return@withContext ArticleTranslation(
                    translatedTitle = title,
                    translatedHtml = html,
                    sourceLanguage = detectedSameLanguage,
                )
            }
            val cachedSourceLanguage = cache.sourceLanguage

            val translatedHtmlResult =
                if (cachedHtml != null) {
                    null
                } else {
                    translateOrThrow(
                        content = html,
                        targetLanguage = targetLanguage,
                        settings = settings,
                        sourceLangHint = cachedSourceLanguage,
                        preserveHtml = true,
                    )
                }
            val sourceLanguageHint =
                translatedHtmlResult
                    ?.detectedLanguageOrBlank()
                    .orEmpty()
                    .ifBlank { cachedSourceLanguage }

            val translatedTitleResult =
                if (cachedTitle != null) {
                    null
                } else {
                    translateOrThrow(
                        content = title,
                        targetLanguage = targetLanguage,
                        settings = settings,
                        sourceLangHint = sourceLanguageHint,
                    )
                }
            val translatedTitle = cachedTitle ?: translatedTitleResult?.content.orEmpty().ifBlank { title }
            val translatedHtml = cachedHtml ?: translatedHtmlResult?.content.orEmpty().ifBlank { html }
            val sourceLanguage =
                translatedHtmlResult
                    ?.detectedLanguageOrBlank()
                    .orEmpty()
                    .ifBlank { translatedTitleResult?.detectedLanguageOrBlank().orEmpty() }
                    .ifBlank { cachedSourceLanguage }

            val translatedBy =
                translatedHtmlResult
                    ?.provider
                    .orEmpty()
                    .ifBlank { translatedTitleResult?.provider.orEmpty() }
                    .ifBlank { cache.translatedBy }

            val updatedCache =
                cache.copy(
                    sourceLanguage = sourceLanguage.ifBlank { cachedSourceLanguage },
                    translatedBy = translatedBy,
                    titleHash = titleHash,
                    translatedTitle = translatedTitle,
                    articleHtmlHash = if (!isFullText) htmlHash else cache.articleHtmlHash,
                    translatedArticleHtml = if (!isFullText) translatedHtml else cache.translatedArticleHtml,
                    fullArticleHtmlHash = if (isFullText) htmlHash else cache.fullArticleHtmlHash,
                    translatedFullArticleHtml = if (isFullText) translatedHtml else cache.translatedFullArticleHtml,
                )

            // A fallback translation must not be remembered as if the API had produced it,
            // otherwise the better translation would never be retried
            if (settings.isLocalTranslation || !isOfflineTranslationProvider(translatedBy)) {
                saveCache(itemId, settings, targetLanguage, updatedCache)
            }

            ArticleTranslation(
                translatedTitle = translatedTitle,
                translatedHtml = translatedHtml,
                sourceLanguage = sourceLanguage,
                translatedBy = translatedBy,
            )
        }


    /**
     * Translates an article with the AI model, in the style the reader picked. Only called when the
     * reader explicitly asks for it. Unlike the default flow it never falls back to offline
     * translation, because the whole point is the style, and errors are shown to the reader.
     */
    suspend fun translateArticleWithAi(
        itemId: Long,
        title: String,
        html: String,
        isFullText: Boolean,
        options: AiTranslationOptions,
    ): ArticleTranslation =
        withContext(Dispatchers.IO) {
            val settings = repository.openAISettings.value
            val targetLanguage = repository.preferredTranslationLanguage.value.trim()
            if (!settings.canSummarize) {
                throw IllegalStateException(application.getString(R.string.ai_translate_needs_key))
            }
            if (targetLanguage.isBlank() || html.isBlank()) {
                throw IllegalStateException(application.getString(R.string.set_translation_language_first))
            }

            val variant = "ai-${settings.modelId.hashCode().toUInt().toString(16)}-${options.cacheKey}"
            val cache = loadCache(itemId, settings, targetLanguage, variant)
            val titleHash = sha256(title)
            val htmlHash = sha256(html)
            val cachedHtml =
                if (isFullText) {
                    cache.translatedFullArticleHtml.takeIf { cache.fullArticleHtmlHash == htmlHash }
                } else {
                    cache.translatedArticleHtml.takeIf { cache.articleHtmlHash == htmlHash }
                }
            val cachedTitle = cache.translatedTitle.takeIf { cache.titleHash == titleHash && !it.isNullOrBlank() }
            if (cachedHtml != null && cachedTitle != null) {
                return@withContext ArticleTranslation(
                    translatedTitle = cachedTitle,
                    translatedHtml = cachedHtml,
                    sourceLanguage = cache.sourceLanguage,
                    translatedBy = cache.translatedBy.ifBlank { settings.translationProviderKey },
                )
            }

            fun failure(result: TranslationResult.Error): Nothing = throw IllegalStateException(result.content.ifBlank { "AI translation failed" })

            val htmlResult =
                if (cachedHtml != null) {
                    null
                } else {
                    when (
                        val result =
                            openAIApi.translate(
                                content = html,
                                targetLanguage = targetLanguage,
                                settings = settings,
                                preserveHtml = true,
                                options = options,
                            )
                    ) {
                        is TranslationResult.Success -> result
                        is TranslationResult.Error -> failure(result)
                    }
                }
            val titleResult =
                if (cachedTitle != null) {
                    null
                } else {
                    // Titles are short, so no term annotations there
                    when (
                        val result =
                            openAIApi.translate(
                                content = title,
                                targetLanguage = targetLanguage,
                                settings = settings,
                                preserveHtml = false,
                                options = options.copy(annotateTerms = false),
                            )
                    ) {
                        is TranslationResult.Success -> result
                        is TranslationResult.Error -> failure(result)
                    }
                }

            val translatedHtml = cachedHtml ?: htmlResult?.content.orEmpty().ifBlank { html }
            val translatedTitle = cachedTitle ?: titleResult?.content.orEmpty().ifBlank { title }
            val sourceLanguage =
                htmlResult
                    ?.detectedLanguage
                    .orEmpty()
                    .ifBlank { titleResult?.detectedLanguage.orEmpty() }
                    .ifBlank { cache.sourceLanguage }
            val translatedBy = settings.translationProviderKey

            saveCache(
                itemId,
                settings,
                targetLanguage,
                cache.copy(
                    sourceLanguage = sourceLanguage,
                    translatedBy = translatedBy,
                    titleHash = titleHash,
                    translatedTitle = translatedTitle,
                    articleHtmlHash = if (!isFullText) htmlHash else cache.articleHtmlHash,
                    translatedArticleHtml = if (!isFullText) translatedHtml else cache.translatedArticleHtml,
                    fullArticleHtmlHash = if (isFullText) htmlHash else cache.fullArticleHtmlHash,
                    translatedFullArticleHtml = if (isFullText) translatedHtml else cache.translatedFullArticleHtml,
                ),
                variant,
            )

            ArticleTranslation(
                translatedTitle = translatedTitle,
                translatedHtml = translatedHtml,
                sourceLanguage = sourceLanguage,
                translatedBy = translatedBy,
            )
        }

    suspend fun hasCachedTranslatedArticle(
        itemId: Long,
        title: String,
        html: String,
        isFullText: Boolean,
        settings: TranslationApiSettings,
        targetLanguage: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val targetLanguage = targetLanguage.trim()
            if (!settings.canTranslate || targetLanguage.isBlank() || html.isBlank()) {
                return@withContext false
            }

            val cache = loadCache(itemId, settings, targetLanguage)
            val titleHash = sha256(title)
            val htmlHash = sha256(html)
            val cachedHtml =
                if (isFullText) {
                    cache.translatedFullArticleHtml.takeIf { cache.fullArticleHtmlHash == htmlHash }
                } else {
                    cache.translatedArticleHtml.takeIf { cache.articleHtmlHash == htmlHash }
                }
            val cachedTitle =
                cache.translatedTitle.takeIf {
                    cache.titleHash == titleHash &&
                        it.isUsableCachedTranslation(
                            original = title,
                            sourceLanguage = cache.sourceLanguage,
                            targetLanguage = targetLanguage,
                            settings = settings,
                        )
                }

            cachedHtml != null && cachedTitle != null
        }

    suspend fun detectArticleAlreadyInTargetLanguage(
        itemId: Long,
        title: String,
        html: String,
        isFullText: Boolean,
    ): String? =
        withContext(Dispatchers.IO) {
            val settings = translationSettings()
            val targetLanguage = repository.preferredTranslationLanguage.value.trim()
            detectArticleAlreadyInTargetLanguage(
                itemId = itemId,
                title = title,
                html = html,
                isFullText = isFullText,
                settings = settings,
                targetLanguage = targetLanguage,
                existingCache = null,
            )
        }

    private fun loadCache(
        itemId: Long,
        settings: OpenAISettings,
        targetLanguage: String,
        variant: String = "",
    ): CachedTranslations =
        cacheFile(itemId, settings, targetLanguage, variant)
            .takeIf(File::isFile)
            ?.readText()
            ?.let { runCatching { json.decodeFromString<CachedTranslations>(it) }.getOrNull() }
            ?: CachedTranslations()

    private fun saveCache(
        itemId: Long,
        settings: OpenAISettings,
        targetLanguage: String,
        cache: CachedTranslations,
        variant: String = "",
    ) {
        val file = cacheFile(itemId, settings, targetLanguage, variant)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(CachedTranslations.serializer(), cache))
    }

    private fun cacheFile(
        itemId: Long,
        settings: OpenAISettings,
        targetLanguage: String,
        variant: String = "",
    ): File {
        val provider =
            when {
                settings.isLocalTranslation -> "local"
                settings.isDeepL -> "deepl"
                else -> "openai"
            }
        return filePathProvider.cacheDir
            .resolve("translations")
            .resolve(
                "$itemId.$provider${if (variant.isBlank()) "" else ".$variant"}.${
                    targetLanguage
                        .trim()
                        .lowercase()
                        .replace(Regex("[^a-z0-9._-]+"), "_")
                        .ifBlank { "default" }
                }.json",
            )
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * API (DeepL) first, then the AI model, then offline when nothing else is configured.
     */
    private fun translationSettings(): TranslationApiSettings = repository.effectiveTranslationSettings.value

    private suspend fun translateOrNull(
        content: String,
        targetLanguage: String,
        settings: TranslationApiSettings,
        sourceLangHint: String = "",
        preserveHtml: Boolean = false,
    ): TranslationResult.Success? =
        when (
            val result =
                translate(
                    content = content,
                    targetLanguage = targetLanguage,
                    settings = settings,
                    sourceLangHint = sourceLangHint,
                    preserveHtml = preserveHtml,
                )
        ) {
            is TranslationResult.Success -> result.takeIf { it.content.isNotBlank() }
            is TranslationResult.Error -> null
        }

    private suspend fun translateOrThrow(
        content: String,
        targetLanguage: String,
        settings: OpenAISettings,
        sourceLangHint: String = "",
        preserveHtml: Boolean = false,
    ): TranslationResult.Success =
        when (
            val result =
                translate(
                    content = content,
                    targetLanguage = targetLanguage,
                    settings = settings,
                    sourceLangHint = sourceLangHint,
                    preserveHtml = preserveHtml,
                )
        ) {
            is TranslationResult.Success ->
                result.takeIf { it.content.isNotBlank() }
                    ?: throw IllegalStateException("Translation failed")
            is TranslationResult.Error -> {
                val message = result.content.ifBlank { "Translation failed" }
                when (result.action) {
                    ErrorAction.DownloadBergamotRuntime -> throw BergamotRuntimeDownloadRequiredException(message)
                    ErrorAction.OpenSystemTranslationSettings -> throw SystemTranslationSettingsRequiredException(message)
                    ErrorAction.None -> throw IllegalStateException(message)
                }
            }
        }

    private suspend fun translate(
        content: String,
        targetLanguage: String,
        settings: OpenAISettings,
        sourceLangHint: String,
        preserveHtml: Boolean,
    ): TranslationResult {
        if (settings.isLocalTranslation) {
            return translateOffline(
                content = content,
                targetLanguage = targetLanguage,
                sourceLangHint = sourceLangHint,
                preserveHtml = preserveHtml,
            )
        }

        val apiResult =
            openAIApi.translate(
                content = content,
                targetLanguage = targetLanguage,
                settings = settings,
                preserveHtml = preserveHtml,
            )
        if (apiResult is TranslationResult.Success) {
            return apiResult
        }

        // The API failed (no network, quota, wrong key...). Silently fall back to offline.
        val offlineResult =
            translateOffline(
                content = content,
                targetLanguage = targetLanguage,
                sourceLangHint = sourceLangHint,
                preserveHtml = preserveHtml,
            )
        return when {
            offlineResult is TranslationResult.Success -> offlineResult
            // Let the UI offer the model download if that is what offline needs
            offlineResult is TranslationResult.Error && offlineResult.action != ErrorAction.None -> offlineResult
            else -> apiResult
        }
    }

    private suspend fun translateOffline(
        content: String,
        targetLanguage: String,
        sourceLangHint: String,
        preserveHtml: Boolean,
    ): TranslationResult =
        when (
            val result =
                localTranslator.translate(
                    content = content,
                    targetLanguage = targetLanguage,
                    sourceLangHint = sourceLangHint,
                    preserveHtml = preserveHtml,
                )
        ) {
            is TranslationResult.Success -> result.copy(provider = result.provider.ifBlank { TRANSLATION_PROVIDER_OFFLINE })
            is TranslationResult.Error -> result
        }

    private fun detectSourceLanguageIfAlreadyTargetLanguage(
        content: String,
        targetLanguage: String,
        settings: OpenAISettings,
        preserveHtml: Boolean = false,
    ): String? =
        runCatching {
            val detectionSamples =
                prepareTextSamplesForLanguageDetection(
                    content = content,
                    preserveHtml = preserveHtml,
                ).filter(::hasEnoughTextForLanguageDetection)
            if (detectionSamples.isEmpty()) {
                return@runCatching null
            }

            val detectedLanguages =
                detectionSamples.map { sample ->
                    val detectedLanguage =
                        application
                            .detectLocaleFromText(
                                text = sample,
                                minConfidence = 95.0f,
                            ).firstOrNull()
                            ?.locale
                            ?.toLanguageTag()
                            ?: return@runCatching null

                    if (
                        !detectedLanguageMatchesTranslationTarget(
                            detectedLanguage = detectedLanguage,
                            targetLanguage = targetLanguage,
                            settings = settings,
                        )
                    ) {
                        return@runCatching null
                    }

                    detectedLanguage
                }

            detectedLanguages.firstOrNull()
        }.getOrNull()

    private fun detectArticleAlreadyInTargetLanguage(
        itemId: Long,
        title: String,
        html: String,
        isFullText: Boolean,
        settings: OpenAISettings,
        targetLanguage: String,
        existingCache: CachedTranslations?,
    ): String? {
        if (!settings.canTranslate || targetLanguage.isBlank() || html.isBlank()) {
            return null
        }

        val cache = existingCache ?: loadCache(itemId, settings, targetLanguage)
        val titleHash = sha256(title)
        val htmlHash = sha256(html)
        val currentHtmlHash =
            if (isFullText) {
                cache.fullArticleHtmlHash
            } else {
                cache.articleHtmlHash
            }
        val cachedSameLanguage =
            cache.titleHash == titleHash &&
                currentHtmlHash == htmlHash &&
                cache.sourceLanguage.isNotBlank() &&
                detectedLanguageMatchesTranslationTarget(
                    detectedLanguage = cache.sourceLanguage,
                    targetLanguage = targetLanguage,
                    settings = settings,
                )
        if (cachedSameLanguage) {
            return cache.sourceLanguage
        }

        val detectedSameLanguage =
            detectSourceLanguageIfAlreadyTargetLanguage(
                content =
                    buildString {
                        if (title.isNotBlank()) {
                            append(title)
                        }
                        if (html.isNotBlank()) {
                            if (isNotEmpty()) {
                                append("\n\n")
                            }
                            append(html)
                        }
                    },
                targetLanguage = targetLanguage,
                settings = settings,
                preserveHtml = true,
            ) ?: return null

        saveCache(
            itemId = itemId,
            settings = settings,
            targetLanguage = targetLanguage,
            cache =
                cache.copy(
                    sourceLanguage = detectedSameLanguage,
                    titleHash = if (title.isBlank()) null else titleHash,
                    translatedTitle = if (title.isBlank()) null else title,
                    articleHtmlHash = if (!isFullText) htmlHash else cache.articleHtmlHash,
                    translatedArticleHtml = if (!isFullText) html else cache.translatedArticleHtml,
                    fullArticleHtmlHash = if (isFullText) htmlHash else cache.fullArticleHtmlHash,
                    translatedFullArticleHtml = if (isFullText) html else cache.translatedFullArticleHtml,
                ),
        )
        return detectedSameLanguage
    }
}

data class ArticleTranslation(
    val translatedTitle: String,
    val translatedHtml: String,
    val sourceLanguage: String,
    // Which engine produced the translation, see TRANSLATION_PROVIDER_*
    val translatedBy: String = "",
)

class SystemTranslationSettingsRequiredException(
    message: String,
) : IllegalStateException(message)

class BergamotRuntimeDownloadRequiredException(
    message: String,
) : IllegalStateException(message)

@Serializable
private data class CachedTranslations(
    val sourceLanguage: String = "",
    val titleHash: String? = null,
    val translatedTitle: String? = null,
    val snippetHash: String? = null,
    val translatedSnippet: String? = null,
    val articleHtmlHash: String? = null,
    val translatedArticleHtml: String? = null,
    val fullArticleHtmlHash: String? = null,
    val translatedFullArticleHtml: String? = null,
    val translatedBy: String = "",
)

private fun OpenAIApi.TranslationResult.detectedLanguageOrBlank(): String =
    when (this) {
        is OpenAIApi.TranslationResult.Success -> detectedLanguage
        is OpenAIApi.TranslationResult.Error -> ""
    }

private fun String?.isUsableCachedTranslation(
    original: String,
    sourceLanguage: String,
    targetLanguage: String,
    settings: OpenAISettings,
): Boolean {
    if (isNullOrBlank()) {
        return false
    }
    if (this != original) {
        return true
    }
    return sourceLanguage.isBlank() ||
        detectedLanguageMatchesTranslationTarget(
            detectedLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            settings = settings,
        )
}

data class CachedFeedListItemTranslation(
    val item: FeedListItem,
    val hasCachedTranslation: Boolean,
    val isFullyCached: Boolean,
)
