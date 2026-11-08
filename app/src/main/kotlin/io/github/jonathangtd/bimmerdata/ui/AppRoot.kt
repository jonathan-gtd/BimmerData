/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.DashboardScreen
import io.github.jonathangtd.bimmerdata.faults.FaultsScreen
import io.github.jonathangtd.bimmerdata.settings.AppServices
import io.github.jonathangtd.bimmerdata.settings.SettingsScreen

enum class AppTab(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    Dashboard(R.drawable.ic_dashboard, R.string.nav_dashboard),
    Tool32(R.drawable.ic_tool32, R.string.nav_tool32),
    Faults(R.drawable.ic_faults, R.string.nav_faults),
    Settings(R.drawable.ic_settings, R.string.nav_settings),
}

/** Top-level screens with a bottom navigation bar (hidden while editing the dashboard layout). */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(AppTab.Dashboard) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val barVisible = !(editing && tab == AppTab.Dashboard)
    // REST API server and USB auto-connect follow the settings for the whole process life.
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppServices.start(context) }
    Column(Modifier.fillMaxSize().background(Cockpit.Background)) {
        // While the bottom bar is shown it owns the navigation bar inset.
        Box(Modifier.fillMaxWidth().weight(1f).then(if (barVisible) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    AppTab.Dashboard -> DashboardScreen(onEditingChange = { editing = it })
                    AppTab.Tool32 -> Tool32Screen()
                    AppTab.Faults -> FaultsScreen()
                    AppTab.Settings -> SettingsScreen()
                }
            }
        }
        AnimatedVisibility(visible = barVisible, enter = expandVertically(), exit = shrinkVertically()) {
            BottomBar(tab) { tab = it }
        }
    }
}

@Composable
private fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Cockpit.Background)) {
        HorizontalDivider(color = Cockpit.Card)
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(64.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { t ->
                val isSel = t == selected
                Column(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Tab) { onSelect(t) }
                        .semantics { this.selected = isSel },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSel) Cockpit.Accent.copy(alpha = 0.16f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(t.icon), contentDescription = null, tint = if (isSel) Cockpit.Accent else Cockpit.Muted, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(t.label), fontSize = 12.sp, color = if (isSel) Cockpit.Text else Cockpit.Muted,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    )
                }
            }
        }
    }
}
