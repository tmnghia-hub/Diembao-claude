package com.nononsenseapps.feeder.ui.compose.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.openai.TRANSLATION_PROVIDER_AI_PREFIX
import com.nononsenseapps.feeder.openai.TRANSLATION_PROVIDER_DEEPL
import com.nononsenseapps.feeder.openai.TRANSLATION_PROVIDER_OFFLINE

/**
 * Small editorial note telling the reader which engine produced the translation.
 */
@Composable
fun TranslatedByNote(
    provider: String,
    modifier: Modifier = Modifier,
) {
    if (provider.isBlank()) {
        return
    }

    val providerName =
        when {
            provider == TRANSLATION_PROVIDER_OFFLINE -> stringResource(R.string.translation_provider_offline)
            provider == TRANSLATION_PROVIDER_DEEPL -> stringResource(R.string.translation_provider_deepl)
            provider.startsWith(TRANSLATION_PROVIDER_AI_PREFIX) ->
                stringResource(
                    R.string.translation_provider_ai,
                    provider.removePrefix(TRANSLATION_PROVIDER_AI_PREFIX),
                )

            else -> provider
        }

    Text(
        text = stringResource(R.string.translated_by, providerName),
        style = MaterialTheme.typography.labelMedium.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
