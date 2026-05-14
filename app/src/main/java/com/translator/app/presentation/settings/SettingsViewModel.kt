package com.translator.app.presentation.settings

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translator.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings = _settings.asStateFlow()

    init {
        // Единственный источник правды — поток DataStore. Первое значение придёт сразу.
        settingsStore.data
            .distinctUntilChanged()
            .onEach { fromDisk ->
                _settings.update { current ->
                    if (current == fromDisk) current else fromDisk
                }
            }
            .launchIn(viewModelScope)
    }

    fun update(transform: AppSettings.() -> AppSettings) {
        // Оптимистичное обновление UI.
        _settings.update(transform)
        viewModelScope.launch {
            settingsStore.updateData { it.transform() }
        }
    }
}