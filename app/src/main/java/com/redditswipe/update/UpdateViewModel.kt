package com.redditswipe.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val checker = UpdateChecker(app)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // Downloaded APK waiting for the user to grant install permission.
    private var pendingApk: File? = null

    init {
        viewModelScope.launch {
            runCatching { checker.findNewerRelease() }.getOrNull()?.let {
                _state.value = UpdateState.Available(it)
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.None
    }

    fun update(release: AppRelease) {
        _state.value = UpdateState.Downloading(release)
        viewModelScope.launch {
            runCatching { checker.download(release) }
                .onSuccess { apk ->
                    _state.value = UpdateState.None
                    installOrAskPermission(apk)
                }
                .onFailure { _state.value = UpdateState.Failed(it.message ?: "Download failed") }
        }
    }

    /** Call when the app returns to the foreground, e.g. after the permission screen. */
    fun onResume() {
        val apk = pendingApk ?: return
        if (checker.canInstall()) {
            pendingApk = null
            checker.install(apk)
        }
    }

    private fun installOrAskPermission(apk: File) {
        if (checker.canInstall()) {
            checker.install(apk)
        } else {
            pendingApk = apk
            checker.openInstallPermissionSettings()
        }
    }
}
