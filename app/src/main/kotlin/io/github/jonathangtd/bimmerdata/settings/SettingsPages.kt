/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntime
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.export.ExportPhase
import io.github.jonathangtd.bimmerdata.export.TelegrafRuntime
import io.github.jonathangtd.bimmerdata.gps.GpsRuntime
import io.github.jonathangtd.bimmerdata.gps.GpsState
import io.github.jonathangtd.bimmerdata.i18n.TranslationSettings
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.RecordingStorage
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/** The categories of the Settings screen, each with its own page. */
enum class SettingsPage(@param:DrawableRes val icon: Int, @param:StringRes val title: Int) {
    Connection(R.drawable.ic_plug, R.string.s3_cat_connection),
    Recording(R.drawable.ic_record, R.string.s3_cat_recording),
    Gps(R.drawable.ic_location, R.string.s3_cat_gps),
    Telegraf(R.drawable.ic_upload, R.string.s3_cat_telegraf),
    Api(R.drawable.ic_api, R.string.s3_cat_api),
    Translations(R.drawable.ic_translate, R.string.s3_cat_translations),
    Backup(R.drawable.ic_download, R.string.backup_title),
    Tools(R.drawable.ic_tool32, R.string.s3_cat_tools),
    About(R.drawable.ic_info, R.string.s3_cat_about),
}

/** Categories in groups: the car, data leaving the phone, the app. */
private val Groups: List<Pair<Int, List<SettingsPage>>> = listOf(
    R.string.s3_group_car to listOf(SettingsPage.Connection, SettingsPage.Recording, SettingsPage.Gps),
    R.string.s3_group_data to listOf(SettingsPage.Telegraf, SettingsPage.Api),
    R.string.s3_group_app to listOf(SettingsPage.Translations, SettingsPage.Backup, SettingsPage.Tools, SettingsPage.About),
)

/** Home of the Settings tab: one row per category with a one-line summary of its state. */
@Composable
internal fun SettingsHome(onOpen: (SettingsPage) -> Unit) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val summaries = rememberSummaries()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Cockpit.Background).padding(top = insets.calculateTopPadding()),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_title), color = Cockpit.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = BarlowCondensed, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
        for ((label, pages) in Groups) {
            item(key = label) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(label))
                    SettingsCard {
                        pages.forEachIndexed { i, p ->
                            if (i > 0) Divider()
                            val s = summaries[p]
                            CategoryRow(p, s?.text.orEmpty(), s?.color ?: Cockpit.Muted) { onOpen(p) }
                        }
                    }
                }
            }
        }
    }
}

/** One-line state of a category. */
internal class Summary(val text: String, val color: Color = Cockpit.Muted)

@Composable
private fun rememberSummaries(): Map<SettingsPage, Summary> {
    val context = LocalContext.current
    val settings by remember { AppSettings.get(context) }.state.collectAsStateWithLifecycle()
    val connection by remember { LiveRepository.get(context) }.connection.collectAsStateWithLifecycle()
    val gps by remember { GpsRuntime.get(context) }.state.collectAsStateWithLifecycle()
    val telegraf by remember { TelegrafRuntime.get(context) }.state.collectAsStateWithLifecycle()
    val api by remember { ApiRuntime.get(context) }.state.collectAsStateWithLifecycle()
    val translations by remember { TranslationSettings.get(context) }.state.collectAsStateWithLifecycle()
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val folder = remember(settings.recording) { RecordingStorage.describe(context) }

    val connectionText = when (val c = connection) {
        is LiveConnection.Connected -> Summary(stringResource(R.string.s3_sum_connected, c.label), Cockpit.Accent)
        is LiveConnection.Connecting -> Summary(stringResource(R.string.s3_sum_connecting), Cockpit.Warning)
        is LiveConnection.Failed -> Summary(stringResource(R.string.s3_sum_failed, c.message), Cockpit.Danger)
        LiveConnection.Disconnected -> Summary(
            stringResource(if (settings.autoConnectOnCable) R.string.s3_sum_disconnected_auto else R.string.s3_sum_disconnected),
        )
    }
    val rows = settings.recording.rowIntervalMs
    val recordingText = Summary(
        folder + " · " + if (rows == 0L) stringResource(R.string.s3_sum_rows_all) else stringResource(R.string.settings2_rec_rows_hz, (1000 / rows).toInt()),
    )
    val gpsText = when (val g = gps) {
        GpsState.Off -> Summary(stringResource(R.string.s3_sum_off))
        GpsState.Idle -> Summary(stringResource(R.string.s3_sum_gps_idle, seconds(settings.gps.intervalMs)))
        GpsState.NoPermission -> Summary(stringResource(R.string.s3_gps_state_no_permission), Cockpit.Warning)
        GpsState.LocationOff -> Summary(stringResource(R.string.s3_gps_state_location_off), Cockpit.Warning)
        GpsState.Searching -> Summary(stringResource(R.string.s3_gps_state_searching), Cockpit.Warning)
        is GpsState.Fix -> Summary(
            g.accuracyM?.let { stringResource(R.string.s3_sum_gps_fix, it.toInt()) } ?: stringResource(R.string.s3_sum_gps_fix_noacc),
            Cockpit.Accent,
        )
    }
    val t = settings.telegraf
    val telegrafText = when {
        !t.enabled -> Summary(stringResource(R.string.s3_sum_off))
        telegraf.blocked -> Summary(stringResource(R.string.s3_sum_tg_blocked), Cockpit.Danger)
        telegraf.phase == ExportPhase.AuthFailed -> Summary(stringResource(R.string.settings2_tg_phase_auth), Cockpit.Danger)
        else -> Summary(
            stringResource(
                R.string.s3_sum_tg,
                stringResource(if (t.mode == ExportMode.AfterTrip) R.string.settings2_tg_mode_after else R.string.settings2_tg_mode_live),
                numbers.format(telegraf.queuedLines),
            ) + if (telegraf.rejectedBatches > 0) " · " + pluralStringResource(R.plurals.s3_sum_tg_rejected, telegraf.rejectedBatches, telegraf.rejectedBatches) else "",
            if (telegraf.rejectedBatches > 0) Cockpit.Warning else Cockpit.Muted,
        )
    }
    val apiText = when {
        !settings.api.enabled -> Summary(stringResource(R.string.s3_sum_off))
        api.running -> Summary(stringResource(R.string.s3_sum_api_on, api.port), Cockpit.Accent)
        api.error != null -> Summary(stringResource(R.string.settings_api_status_error, api.error.orEmpty()), Cockpit.Danger)
        else -> Summary(stringResource(R.string.settings_api_status_starting), Cockpit.Warning)
    }
    val lang = translations.language
    val ui = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    fun languageName(l: Locale) = l.getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }
    val translationsText = Summary(
        when (lang) {
            "" -> stringResource(R.string.s3_sum_tr_phone, languageName(ui))
            "de" -> stringResource(R.string.s3_sum_tr_original)
            else -> languageName(Locale.forLanguageTag(lang))
        },
    )
    return mapOf(
        SettingsPage.Connection to connectionText,
        SettingsPage.Recording to recordingText,
        SettingsPage.Gps to gpsText,
        SettingsPage.Telegraf to telegrafText,
        SettingsPage.Api to apiText,
        SettingsPage.Translations to translationsText,
        SettingsPage.Backup to Summary(stringResource(R.string.backup_summary)),
        SettingsPage.Tools to Summary(stringResource(R.string.s3_sum_tools)),
        SettingsPage.About to Summary(stringResource(R.string.s3_sum_about, version)),
    )
}

@Composable
private fun seconds(ms: Long): String = stringResource(R.string.s3_seconds, (ms / 1000).toInt())

@Composable
private fun CategoryRow(page: SettingsPage, summary: String, summaryColor: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(Cockpit.Accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(page.icon), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(page.title), color = Cockpit.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (summary.isNotEmpty()) {
                Text(summary, color = summaryColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp))
    }
}

/** A settings page: back button, title, then [content] (cards) in a scrolling column. */
@Composable
internal fun SettingsPageScaffold(page: SettingsPage, onBack: () -> Unit, content: @Composable () -> Unit) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Cockpit.Background).padding(top = insets.calculateTopPadding()),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.settings2_back), onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(page.title), color = Cockpit.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = BarlowCondensed, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

// ---------------------------------------------------------------- GPS position

@Composable
internal fun GpsSection(gps: GpsSettings, store: AppSettings) {
    val context = LocalContext.current
    val runtime = remember { GpsRuntime.get(context) }
    val state by runtime.state.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        denied = !fine
        if (fine) store.updateGps { it.copy(enabled = true) }
        runtime.recheck()
    }
    // the permission may change in the system settings while the page is open
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            runtime.recheck()
        }
    }

    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.s3_gps_enable),
            hint = stringResource(R.string.s3_gps_enable_hint),
            checked = gps.enabled,
            onChange = { on ->
                if (!on) {
                    store.updateGps { it.copy(enabled = false) }
                } else if (runtime.hasPermission()) {
                    store.updateGps { it.copy(enabled = true) }
                } else {
                    ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
        )
        if (denied && !runtime.hasPermission()) {
            Banner(stringResource(R.string.s3_gps_denied), Cockpit.Warning)
            SmallButton(stringResource(R.string.s3_open_app_settings)) { openAppSettings(context) }
        }
        if (gps.enabled) {
            Divider()
            val (color, text) = when (val s = state) {
                GpsState.Off -> Cockpit.Muted to stringResource(R.string.s3_sum_off)
                GpsState.Idle -> Cockpit.Muted to stringResource(R.string.s3_gps_state_idle)
                GpsState.NoPermission -> Cockpit.Warning to stringResource(R.string.s3_gps_state_no_permission)
                GpsState.LocationOff -> Cockpit.Warning to stringResource(R.string.s3_gps_state_location_off)
                GpsState.Searching -> Cockpit.Warning to stringResource(R.string.s3_gps_state_searching)
                is GpsState.Fix -> Cockpit.Accent to (
                    (s.accuracyM?.let { stringResource(R.string.s3_sum_gps_fix, it.toInt()) } ?: stringResource(R.string.s3_sum_gps_fix_noacc)) +
                        " · " + DateUtils.getRelativeTimeSpanString(s.timeMs, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                    )
            }
            StatusLine(color, text)
            when (state) {
                GpsState.NoPermission -> SmallButton(stringResource(R.string.s3_open_app_settings)) { openAppSettings(context) }
                GpsState.LocationOff -> SmallButton(stringResource(R.string.s3_gps_open_location)) {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                else -> Unit
            }
        }
    }

    SettingsCard {
        SectionLabel(stringResource(R.string.s3_gps_rate))
        Segmented(
            options = GpsSettings.INTERVALS.map { it to seconds(it) },
            selected = gps.intervalMs,
            onSelect = { ms -> store.updateGps { it.copy(intervalMs = ms) } },
        )
        Hint(stringResource(R.string.s3_gps_rate_hint))
    }

    SettingsCard {
        SectionLabel(stringResource(R.string.s3_gps_where))
        Hint(stringResource(R.string.s3_gps_values))
        Hint(stringResource(R.string.s3_gps_cards))
        Hint(stringResource(R.string.s3_gps_background))
        Hint(stringResource(R.string.s3_gps_privacy))
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// ---------------------------------------------------------------- Telegraf queue figures

/** "3 h 20 min", "45 min", "2 days 4 h". */
@Composable
internal fun durationText(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> stringResource(R.string.s3_duration_days, days.toInt(), hours.toInt())
        hours > 0 -> stringResource(R.string.s3_duration_hours, hours.toInt(), minutes.toInt())
        else -> stringResource(R.string.s3_duration_minutes, minutes.coerceAtLeast(1).toInt())
    }
}

internal fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
