/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.jonathangtd.bimmerdata.cars.CarBook
import io.github.jonathangtd.bimmerdata.cars.CarsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.jonathangtd.bimmerdata.dashboard.ui.DashboardScreen
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfilesScreen
import io.github.jonathangtd.bimmerdata.faults.FaultsScreen
import io.github.jonathangtd.bimmerdata.graph.ui.GraphScreen
import io.github.jonathangtd.bimmerdata.settings.ApiDocsScreen
import io.github.jonathangtd.bimmerdata.settings.AppServices
import io.github.jonathangtd.bimmerdata.settings.SettingsScreen

enum class AppTab(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    Dashboard(R.drawable.ic_dashboard, R.string.nav_dashboard),
    Graph(R.drawable.ic_graph, R.string.nav_graph),
    Profiles(R.drawable.ic_profile, R.string.nav_profiles),
    Faults(R.drawable.ic_faults, R.string.nav_faults),
    Settings(R.drawable.ic_settings, R.string.nav_settings),
}

/**
 * Top-level screens with a bottom navigation bar (hidden while editing the dashboard layout).
 * Tool32, the API documentation and My cars are secondary screens ([AppOverlay]) opened through
 * [LocalAppNavigator]: from Settings › Tools, from an ECU's detail with its SGBD selected, from the
 * profile screens ("Change car"). My cars also opens at start when there is no car or several.
 */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(AppTab.Dashboard) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf<AppOverlay?>(null) }
    var tool32Sgbd by rememberSaveable { mutableStateOf<String?>(null) }
    val holder = rememberSaveableStateHolder()
    val navigator = remember {
        AppNavigator(
            openTool32 = { sgbd -> tool32Sgbd = sgbd; overlay = AppOverlay.Tool32 },
            openApiDocs = { overlay = AppOverlay.ApiDocs },
            openProfiles = { overlay = null; tab = AppTab.Profiles },
            openDashboard = { overlay = null; tab = AppTab.Dashboard },
            openCars = { overlay = AppOverlay.Cars },
        )
    }
    BackHandler(enabled = overlay != null) { overlay = null }
    // Portrait everywhere, except the Graph tab which follows the phone (a wider time axis);
    // FULL_USER respects the system auto-rotate setting. In landscape the graph takes the whole screen.
    val activity = LocalActivity.current
    val graphShown = overlay == null && tab == AppTab.Graph
    SideEffect {
        val wanted = if (graphShown) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (activity != null && activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val barVisible = overlay == null && !(editing && tab == AppTab.Dashboard) && !(graphShown && landscape)
    // REST API server and USB auto-connect follow the settings for the whole process life.
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppServices.start(context) }
    // My cars first when there is no car (how to get one) or several (which one); one car: the dashboard.
    var startChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startChecked) return@LaunchedEffect
        startChecked = true
        val cars = withContext(Dispatchers.IO) { CarBook.get(context).snapshot().cars.size }
        if (cars != 1 && overlay == null) overlay = AppOverlay.Cars
    }
    Column(Modifier.fillMaxSize().background(Cockpit.Background)) {
        // While the bottom bar is shown it owns the navigation bar inset.
        Box(Modifier.fillMaxWidth().weight(1f).then(if (barVisible) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
            CompositionLocalProvider(LocalAppNavigator provides navigator) {
                when (overlay) {
                    AppOverlay.Tool32 -> Tool32Screen(initialSgbd = tool32Sgbd, onBack = { overlay = null })
                    AppOverlay.ApiDocs -> ApiDocsScreen(onBack = { overlay = null })
                    AppOverlay.Cars -> CarsScreen(onBack = { overlay = null }, onShown = { overlay = null; tab = AppTab.Dashboard })
                    null -> holder.SaveableStateProvider(tab.name) {
                        when (tab) {
                            AppTab.Dashboard -> DashboardScreen(onEditingChange = { editing = it })
                            AppTab.Graph -> GraphScreen()
                            AppTab.Profiles -> ProfilesScreen()
                            AppTab.Faults -> FaultsScreen()
                            AppTab.Settings -> SettingsScreen()
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = barVisible, enter = expandVertically(), exit = shrinkVertically()) {
            BottomBar(tab) { tab = it }
        }
    }
}

@Composable
internal fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
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
                        stringResource(t.label), color = if (isSel) Cockpit.Text else Cockpit.Muted,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                        // five tabs: long labels ("Tableau de bord") shrink a little instead of being cut
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
