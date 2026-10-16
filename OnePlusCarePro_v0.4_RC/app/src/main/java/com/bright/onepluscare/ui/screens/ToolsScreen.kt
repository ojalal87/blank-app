package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bright.onepluscare.R
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator

/** Discoverable but separated: diagnostics and repair first, power-user tools last. */
@Composable
fun ToolsScreen(nav: Navigator, padding: PaddingValues) {
    ScreenScaffold(title = stringResource(R.string.tab_tools), outerPadding = padding) {
        item { SectionHeader(stringResource(R.string.tools_diagnostics)) }
        item { NavRow(Icons.Outlined.BatteryChargingFull, stringResource(R.string.screen_battery), stringResource(R.string.battery_subtitle), { nav.go(Dest.BATTERY) }) }
        item { NavRow(Icons.Outlined.NetworkCheck, stringResource(R.string.screen_network), stringResource(R.string.network_subtitle), { nav.go(Dest.NETWORK) }) }
        item { NavRow(Icons.Outlined.PrivacyTip, stringResource(R.string.screen_privacy), stringResource(R.string.privacy_subtitle), { nav.go(Dest.PRIVACY) }) }
        item { SectionHeader(stringResource(R.string.tools_maintenance)) }
        item { NavRow(Icons.Outlined.Build, stringResource(R.string.screen_repair), stringResource(R.string.repair_subtitle), { nav.go(Dest.REPAIR) }) }
        item { NavRow(Icons.Outlined.DeleteOutline, stringResource(R.string.screen_trash), stringResource(R.string.trash_subtitle), { nav.go(Dest.TRASH) }) }
        item { NavRow(Icons.Outlined.History, stringResource(R.string.screen_history), stringResource(R.string.history_subtitle), { nav.go(Dest.HISTORY) }) }
        item { SectionHeader(stringResource(R.string.tools_app)) }
        item { NavRow(Icons.Outlined.VerifiedUser, stringResource(R.string.screen_permissions), stringResource(R.string.permissions_subtitle), { nav.go(Dest.PERMISSIONS) }) }
        item { NavRow(Icons.Outlined.Settings, stringResource(R.string.screen_settings), stringResource(R.string.settings_subtitle), { nav.go(Dest.SETTINGS) }) }
        item { NavRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.screen_advanced), stringResource(R.string.advanced_subtitle), { nav.go(Dest.ADVANCED) }) }
    }
}
