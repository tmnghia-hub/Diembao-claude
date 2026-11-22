package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Newspaper masthead: the title, a double rule and today's date.
 */
@Composable
fun NewsMasthead(modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    val dateText =
        remember(locale) {
            LocalDate
                .now()
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
                .uppercase(locale)
        }
    val ruleColor = MaterialTheme.colorScheme.onBackground

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = LocalDimens.current.margin)
                .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(ruleColor),
            )
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ruleColor),
            )
        }
        Text(
            text = dateText,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
        )
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
