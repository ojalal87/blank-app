package com.bright.onepluscare.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.ui.screens.AdvancedScreen
import com.bright.onepluscare.ui.screens.AppDoctorScreen
import com.bright.onepluscare.ui.screens.BatteryScreen
import com.bright.onepluscare.ui.screens.CleanerScreen
import com.bright.onepluscare.ui.screens.DuplicatesScreen
import com.bright.onepluscare.ui.screens.HistoryScreen
import com.bright.onepluscare.ui.screens.HomeScreen
import com.bright.onepluscare.ui.screens.NetworkScreen
import com.bright.onepluscare.ui.screens.OnboardingScreen
import com.bright.onepluscare.ui.screens.PermissionCenterScreen
import com.bright.onepluscare.ui.screens.PrivacyScreen
import com.bright.onepluscare.ui.screens.RepairScreen
import com.bright.onepluscare.ui.screens.SettingsScreen
import com.bright.onepluscare.ui.screens.SmartScanScreen
import com.bright.onepluscare.ui.screens.ToolsScreen
import com.bright.onepluscare.ui.screens.TrashScreen
import com.bright.onepluscare.ui.vm.SettingsViewModel
import com.bright.onepluscare.ui.vm.careViewModel

enum class Dest {
    HOME, SCAN, APPS, TOOLS, CLEANER, DUPLICATES, BATTERY, NETWORK, PRIVACY, REPAIR, TRASH, HISTORY, ADVANCED, SETTINGS, PERMISSIONS,
    TUNER, TUNER_FIXES, TUNER_DEBLOAT, TUNER_DISPLAY, TUNER_CHANGES, SHIZUKU_SETUP,
    SECURITY, SECURITY_APP, FIREWALL, DATA_USAGE, ADBLOCK, HARDENING, PRIVACY_ACCESS, MALWARE_SCAN,
}

private enum class Tab(val dest: Dest, val label: Int, val icon: ImageVector) {
    HOME(Dest.HOME, R.string.tab_home, Icons.Outlined.Home),
    SCAN(Dest.SCAN, R.string.tab_clean, Icons.Outlined.CleaningServices),
    APPS(Dest.APPS, R.string.tab_apps, Icons.Outlined.Apps),
    TOOLS(Dest.TOOLS, R.string.tab_tools, Icons.Outlined.Handyman),
}

/** Navigation callbacks handed to screens. */
class Navigator(val go: (Dest) -> Unit, val back: () -> Unit)

/**
 * Minimal saveable back stack: each tab is a root; detail screens push on top. Kept dependency-free on
 * purpose (no reflection-based routing), and survives rotation and process death via rememberSaveable.
 */
@Composable
fun CareApp() {
    val settingsVm: SettingsViewModel = careViewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    if (!settings.onboardingDone) {
        OnboardingScreen(onDone = settingsVm::completeOnboarding)
        return
    }

    var stack by rememberSaveable { mutableStateOf(listOf(Dest.HOME.name)) }
    val current = Dest.valueOf(stack.last())
    val nav = Navigator(
        go = { d ->
            stack = if (Tab.entries.any { it.dest == d }) listOf(d.name) else stack + d.name
        },
        back = { if (stack.size > 1) stack = stack.dropLast(1) },
    )
    BackHandler(enabled = stack.size > 1) { nav.back() }

    ActivityResultHost()

    val rootTab = Tab.entries.firstOrNull { it.dest.name == stack.first() } ?: Tab.HOME
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!wide) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = rootTab == tab,
                            onClick = { nav.go(tab.dest) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = rootTab == tab,
                            onClick = { nav.go(tab.dest) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                    modifier = Modifier.widthIn(max = 840.dp),
                ) { dest ->
                    Screen(dest, nav, padding)
                }
            }
        }
    }
}

@Composable
private fun Screen(dest: Dest, nav: Navigator, padding: androidx.compose.foundation.layout.PaddingValues) {
    when (dest) {
        Dest.HOME -> HomeScreen(nav, padding)
        Dest.SCAN -> SmartScanScreen(nav, padding)
        Dest.APPS -> AppDoctorScreen(nav, padding)
        Dest.TOOLS -> ToolsScreen(nav, padding)
        Dest.CLEANER -> CleanerScreen(nav, padding)
        Dest.DUPLICATES -> DuplicatesScreen(nav, padding)
        Dest.BATTERY -> BatteryScreen(nav, padding)
        Dest.NETWORK -> NetworkScreen(nav, padding)
        Dest.PRIVACY -> PrivacyScreen(nav, padding)
        Dest.REPAIR -> RepairScreen(nav, padding)
        Dest.TRASH -> TrashScreen(nav, padding)
        Dest.HISTORY -> HistoryScreen(nav, padding)
        Dest.ADVANCED -> AdvancedScreen(nav, padding)
        Dest.SETTINGS -> SettingsScreen(nav, padding)
        Dest.PERMISSIONS -> PermissionCenterScreen(nav, padding)
        Dest.TUNER -> com.bright.onepluscare.ui.screens.TunerScreen(nav, padding)
        Dest.TUNER_FIXES -> com.bright.onepluscare.ui.screens.TunerFixesScreen(nav, padding)
        Dest.TUNER_DEBLOAT -> com.bright.onepluscare.ui.screens.DebloatScreen(nav, padding)
        Dest.TUNER_DISPLAY -> com.bright.onepluscare.ui.screens.TunerDisplayScreen(nav, padding)
        Dest.TUNER_CHANGES -> com.bright.onepluscare.ui.screens.TunerChangesScreen(nav, padding)
        Dest.SHIZUKU_SETUP -> com.bright.onepluscare.ui.screens.ShizukuSetupScreen(nav, padding)
        Dest.SECURITY -> com.bright.onepluscare.ui.screens.SecurityScreen(nav, padding)
        Dest.SECURITY_APP -> com.bright.onepluscare.ui.screens.SecurityAppScreen(nav, padding)
        Dest.FIREWALL -> com.bright.onepluscare.ui.screens.FirewallScreen(nav, padding)
        Dest.DATA_USAGE -> com.bright.onepluscare.ui.screens.DataUsageScreen(nav, padding)
        Dest.ADBLOCK -> com.bright.onepluscare.ui.screens.AdBlockScreen(nav, padding)
        Dest.HARDENING -> com.bright.onepluscare.ui.screens.HardeningScreen(nav, padding)
        Dest.MALWARE_SCAN -> com.bright.onepluscare.ui.screens.MalwareScanScreen(nav, padding)
        Dest.PRIVACY_ACCESS -> com.bright.onepluscare.ui.screens.PrivacyAccessScreen(nav, padding)
    }
}
