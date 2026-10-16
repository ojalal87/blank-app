package com.bright.onepluscare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.bright.onepluscare.ui.nav.CareApp
import com.bright.onepluscare.ui.theme.CareTheme
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.LocalViewModelFactory
import com.bright.onepluscare.ui.vm.careViewModelFactory

/**
 * Single exported entry point. It reads no Intent extras or data, so there is no intent-spoofing or
 * deep-link surface; everything starts from the in-app navigation state.
 */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as CareApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val factory = careViewModelFactory(graph)
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph, LocalViewModelFactory provides factory) {
                CareTheme { CareApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions granted in system Settings (All files, Usage access, notifications) take effect here.
        graph.refreshCapabilities()
    }
}
