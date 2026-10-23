package com.bright.onepluscare.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bright.onepluscare.AppGraph

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

fun careViewModelFactory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(graph) }
    initializer { ScanViewModel(graph) }
    initializer { AppsViewModel(graph) }
    initializer { NetworkViewModel(graph) }
    initializer { TrashViewModel(graph) }
    initializer { HistoryViewModel(graph) }
    initializer { SettingsViewModel(graph) }
    initializer { AdvancedViewModel(graph) }
    initializer { RepairViewModel(graph) }
    initializer { PrivacyViewModel(graph) }
    initializer { TunerViewModel(graph) }
    initializer { SecurityViewModel(graph) }
    initializer { DataUsageViewModel(graph) }
    initializer { PrivacyAccessViewModel(graph) }
    initializer { HardeningViewModel(graph) }
    initializer { MalwareScanViewModel(graph) }
}

val LocalViewModelFactory = staticCompositionLocalOf<ViewModelProvider.Factory> { error("factory not provided") }

/** Activity-scoped ViewModels, so state survives tab switches and configuration changes. */
@Composable
inline fun <reified VM : ViewModel> careViewModel(): VM = viewModel(factory = LocalViewModelFactory.current)
