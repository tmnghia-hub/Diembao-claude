package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.openai.AiTranslationOptions
import com.nononsenseapps.feeder.openai.TranslationStyle

/**
 * Lets the reader pick the writing style for an AI translation before it starts.
 */
@Composable
fun AiTranslateDialog(
    initialOptions: AiTranslationOptions,
    onConfirm: (AiTranslationOptions) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.translate_with_ai),
    confirmText: String = stringResource(R.string.ai_translate_action),
) {
    var style by remember { mutableStateOf(initialOptions.style) }
    var customStyle by remember { mutableStateOf(initialOptions.customStyle) }
    var annotateTerms by remember { mutableStateOf(initialOptions.annotateTerms) }
    val canConfirm = style != TranslationStyle.CUSTOM || customStyle.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_translate_style_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                TranslationStyle.entries.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = style == option,
                                    onClick = { style = option },
                                    role = Role.RadioButton,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = style == option, onClick = null)
                        Text(
                            text = stringResource(option.titleRes),
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                if (style == TranslationStyle.CUSTOM) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = customStyle,
                        onValueChange = { customStyle = it },
                        label = { Text(stringResource(R.string.ai_style_custom_hint)) },
                        minLines = 2,
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { annotateTerms = !annotateTerms }
                            .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_annotate_terms))
                        Text(
                            text = stringResource(R.string.ai_annotate_terms_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = annotateTerms, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        AiTranslationOptions(
                            style = style,
                            customStyle = customStyle.trim(),
                            annotateTerms = annotateTerms,
                        ),
                    )
                },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
