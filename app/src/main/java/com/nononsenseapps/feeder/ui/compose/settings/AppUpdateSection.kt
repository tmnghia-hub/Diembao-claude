package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data object UpToDate : UpdateUiState

    data object NotConfigured : UpdateUiState

    data class Available(
        val apkUrl: String,
    ) : UpdateUiState

    data class NeedsPermission(
        val apkUrl: String,
    ) : UpdateUiState

    data class Downloading(
        val percent: Int,
    ) : UpdateUiState

    data class Error(
        val message: String,
    ) : UpdateUiState
}

/**
 * "Check for updates" entry: looks for a newer build on GitHub Releases, downloads it and hands
 * it to the system installer. Installing over the current app keeps all data.
 */
@Composable
fun AppUpdateSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun startDownload(apkUrl: String) {
        scope.launch {
            state = UpdateUiState.Downloading(0)
            try {
                val apk =
                    AppUpdater.download(context, apkUrl) { percent ->
                        state = UpdateUiState.Downloading(percent)
                    }
                state =
                    if (AppUpdater.install(context, apk)) {
                        UpdateUiState.Available(apkUrl)
                    } else {
                        UpdateUiState.NeedsPermission(apkUrl)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UpdateUiState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun startCheck() {
        scope.launch {
            state = UpdateUiState.Checking
            state =
                when (val result = AppUpdater.check()) {
                    is AppUpdater.CheckResult.NotConfigured -> UpdateUiState.NotConfigured
                    is AppUpdater.CheckResult.UpToDate -> UpdateUiState.UpToDate
                    is AppUpdater.CheckResult.Available -> UpdateUiState.Available(result.apkUrl)
                    is AppUpdater.CheckResult.Failed -> UpdateUiState.Error(result.message)
                }
        }
    }

    SettingsGroup(
        title = R.string.app_update_group,
        modifier = modifier,
    ) {
        ActionSetting(
            title =
                when (val current = state) {
                    is UpdateUiState.Idle -> stringResource(R.string.app_update_check)
                    is UpdateUiState.Checking -> stringResource(R.string.app_update_checking)
                    is UpdateUiState.UpToDate -> stringResource(R.string.app_update_up_to_date)
                    is UpdateUiState.NotConfigured -> stringResource(R.string.app_update_not_configured)
                    is UpdateUiState.Available -> stringResource(R.string.app_update_available)
                    is UpdateUiState.NeedsPermission -> stringResource(R.string.app_update_needs_permission)
                    is UpdateUiState.Downloading -> stringResource(R.string.app_update_downloading, current.percent)
                    is UpdateUiState.Error -> stringResource(R.string.app_update_error, current.message)
                },
            onClick = {
                when (val current = state) {
                    is UpdateUiState.Checking, is UpdateUiState.Downloading -> Unit
                    is UpdateUiState.Available -> startDownload(current.apkUrl)
                    is UpdateUiState.NeedsPermission -> startDownload(current.apkUrl)
                    else -> startCheck()
                }
            },
            icon = {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                )
            },
        )
    }
}
