/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.faults

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.SectionTitle
import io.github.jonathangtd.bimmerdata.dashboard.ui.fieldColors
import io.github.jonathangtd.bimmerdata.diag.DiagSession
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.i18n.TranslationRepository
import io.github.jonathangtd.bimmerdata.i18n.rememberTranslator
import io.github.jonathangtd.bimmerdata.i18n.translationVersion
import io.github.jonathangtd.bimmerdata.live.CableUnavailableException
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.PhoneSensors
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.live.SgbdDescription
import io.github.jonathangtd.bimmerdata.live.i18n.Translator
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFunction
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFunctionKind
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFunctions
import io.github.jonathangtd.bimmerdata.live.vehicle.Standstill
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.Tool32ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** What one run of a function returned, kept until the next run of the same function. */
internal class FunctionRun(val sets: List<Map<String, ResultData>>, val error: String?)

/**
 * The functions of one ECU ([EcuFunctions]): its jobs read from the user's ECU file, run on the
 * app's single connection between two read cycles ([DiagSession]); tests after a confirmation and
 * only at a standstill ([Standstill]).
 */
internal class EcuFunctionsModel(private val app: Context, private val sgbd: String, private val scope: CoroutineScope) {
    var functions by mutableStateOf<List<EcuFunction>?>(null)
        private set
    var running by mutableStateOf<String?>(null)
        private set
    val results = mutableStateMapOf<String, FunctionRun>()
    private val session = DiagSession(LiveRepository.get(app), "Functions")

    fun load() {
        if (functions != null) return
        scope.launch {
            functions = withContext(Dispatchers.IO) {
                runCatching {
                    TranslationRepository.get(app).sgbdFile(sgbd)?.let { SgbdDescription.read(it) }?.let { EcuFunctions.of(it) }
                }.onFailure { android.util.Log.w("BimmerData.Functions", "cannot read the functions of $sgbd", it) }.getOrNull().orEmpty()
            }
        }
    }

    /** Speed that forbids a test now (DME / GPS readings), null when the car may be tested. */
    fun blockingSpeed(): Double? {
        val live = LiveRepository.get(app)
        val samples = live.liveDataSource.samples.value
        val ids = live.requests.merged().filter { it.unit.trim().equals("km/h", ignoreCase = true) }.map { it.id } + "${PhoneSensors.GPS}/speed"
        return Standstill.blockingSpeed(ids.mapNotNull { samples[it] }, System.currentTimeMillis())
    }

    fun run(f: EcuFunction, args: String) {
        if (running != null) return
        running = f.job
        scope.launch {
            try {
                val r = session.runJob(sgbd, f.job, args)
                results[f.job] = FunctionRun(r.sets, r.error)
            } catch (e: CableUnavailableException) {
                results[f.job] = FunctionRun(emptyList(), e.message ?: "not connected")
            } catch (e: Exception) {
                results[f.job] = FunctionRun(emptyList(), DiagSession.describe(e))
            } finally {
                running = null
            }
        }
    }

    fun cancel() = session.cancel()
}

/** Section "Functions" of an ECU detail: search, then reads and tests, folded until opened. */
@Composable
internal fun EcuFunctionsSection(sgbd: String, ecuName: String, initiallyOpen: EcuFunctionKind? = null, initialJob: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(sgbd) { EcuFunctionsModel(context.applicationContext, sgbd, scope) }
    LaunchedEffect(sgbd) { model.load() }
    val translator = rememberTranslator()
    val tv = translationVersion()
    var query by rememberSaveable(sgbd) { mutableStateOf("") }
    var openGroup by rememberSaveable(sgbd) { mutableStateOf(initiallyOpen?.name) }
    var openJob by rememberSaveable(sgbd) { mutableStateOf(initialJob) }
    var confirm by remember { mutableStateOf<Pair<EcuFunction, String>?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.f2_functions))
        val all = model.functions
        if (all == null) {
            Text(stringResource(R.string.f2_functions_loading), color = Cockpit.Muted, fontSize = 13.sp)
            return@Column
        }
        if (all.isEmpty()) {
            Text(stringResource(R.string.f2_functions_none, sgbd.uppercase(Locale.ROOT)), color = Cockpit.Muted, fontSize = 13.sp)
            return@Column
        }
        val titles = remember(all, tv) { all.associate { it.job to title(translator, sgbd, it) } }
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(40) }, singleLine = true, colors = fieldColors(),
            placeholder = { Text(stringResource(R.string.f2_search), color = Cockpit.Muted) },
            modifier = Modifier.fillMaxWidth(),
        )
        val q = query.trim()
        val shown = if (q.isEmpty()) all else all.filter { f ->
            f.job.contains(q, ignoreCase = true) || titles[f.job].orEmpty().contains(q, ignoreCase = true)
        }
        for (kind in EcuFunctionKind.entries) {
            val list = shown.filter { it.kind == kind }
            if (list.isEmpty()) continue
            val open = q.isNotEmpty() || openGroup == kind.name
            GroupHeader(
                stringResource(if (kind == EcuFunctionKind.Read) R.string.f2_reads else R.string.f2_tests) + " (${list.size})", open,
            ) { openGroup = if (openGroup == kind.name) null else kind.name }
            if (!open) continue
            if (kind == EcuFunctionKind.Test) Text(stringResource(R.string.f2_tests_hint), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
                Column {
                    list.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = Cockpit.Border)
                        FunctionRow(
                            f = f, sgbd = sgbd, title = titles[f.job] ?: f.job, translator = translator,
                            open = openJob == f.job, running = model.running == f.job, busy = model.running != null,
                            result = model.results[f.job],
                            onToggle = { openJob = if (openJob == f.job) null else f.job },
                            onRun = { args ->
                                if (f.kind == EcuFunctionKind.Read) {
                                    model.run(f, args)
                                } else {
                                    val speed = model.blockingSpeed()
                                    if (speed != null) refused = String.format(Locale.ROOT, "%.0f", speed) else confirm = f to args
                                }
                            },
                            onCancel = model::cancel,
                        )
                    }
                }
            }
        }
    }

    confirm?.let { (f, args) ->
        ConfirmDialog(
            title = stringResource(R.string.f2_test_title),
            text = stringResource(R.string.f2_test_text, ecuName, title(translator, sgbd, f)),
            confirm = stringResource(R.string.f2_test_confirm),
            onConfirm = { confirm = null; model.run(f, args) },
            onDismiss = { confirm = null },
        )
    }
    refused?.let { kmh ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { refused = null },
            containerColor = Cockpit.Card,
            text = { Text(stringResource(R.string.f2_test_moving, kmh), color = Cockpit.Text, fontSize = 15.sp) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { refused = null }) { Text("OK", color = Cockpit.Accent) } },
        )
    }
}

/** Translated job title, else the job comment of the file, else the job name. */
private fun title(t: Translator, sgbd: String, f: EcuFunction): String =
    t.jobTitle(sgbd, f.job, f.description.ifEmpty { null }) ?: f.description.ifEmpty { f.job }

@Composable
private fun GroupHeader(text: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Cockpit.Text, fontSize = 16.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(
            painterResource(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right), contentDescription = null,
            tint = Cockpit.Muted, modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FunctionRow(
    f: EcuFunction,
    sgbd: String,
    title: String,
    translator: Translator,
    open: Boolean,
    running: Boolean,
    busy: Boolean,
    result: FunctionRun?,
    onToggle: () -> Unit,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val args = remember(f.job) { mutableStateMapOf<Int, String>() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Cockpit.Text, fontSize = 15.sp, lineHeight = 19.sp, maxLines = if (open) 4 else 2, overflow = TextOverflow.Ellipsis)
                Text(f.job, color = Cockpit.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                painterResource(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right), contentDescription = null,
                tint = Cockpit.Muted, modifier = Modifier.padding(start = 6.dp).size(16.dp),
            )
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                f.args.forEachIndexed { i, a ->
                    val help = a.comments.firstOrNull()?.let { translator.byOriginal(sgbd, it) }
                    OutlinedTextField(
                        value = args[i].orEmpty(), onValueChange = { args[i] = it.take(80) }, singleLine = true, colors = fieldColors(),
                        label = { Text(a.name) },
                        supportingText = help?.let { { Text(it, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        PillButton(stringResource(R.string.veh_cancel), onCancel)
                        Text(stringResource(R.string.f2_running), color = Cockpit.Muted, fontSize = 13.sp)
                    } else {
                        val values = f.args.indices.map { args[it].orEmpty().trim() }.dropLastWhile { it.isEmpty() }
                        PillButton(
                            stringResource(R.string.f2_run), { onRun(values.joinToString(";")) },
                            icon = if (f.kind == EcuFunctionKind.Read) R.drawable.ic_reset else R.drawable.ic_tool32,
                            color = if (f.kind == EcuFunctionKind.Read) Cockpit.OnAccent else Cockpit.Warning,
                            background = if (f.kind == EcuFunctionKind.Read) Cockpit.Accent else Cockpit.Warning.copy(alpha = 0.14f),
                            enabled = !busy,
                        )
                    }
                }
                result?.let { ResultView(sgbd, f, it, translator) }
            }
        }
    }
}

/** The values the ECU returned: labels translated when possible, units next to values. */
@Composable
private fun ResultView(sgbd: String, f: EcuFunction, run: FunctionRun, translator: Translator) {
    val tv = translationVersion()
    val lines = remember(run, tv) { resultLines(sgbd, f, run, translator) }
    val status = run.sets.lastOrNull()?.get("JOB_STATUS")?.opData as? String
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        run.error?.let { Text(stringResource(R.string.f2_result_error, it), color = Cockpit.Warning, fontSize = 13.sp) }
        status?.let {
            Text(it, color = if (it.equals("OKAY", ignoreCase = true)) FaultColors.Ok else Cockpit.Warning, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (run.error == null && lines.isEmpty()) Text(stringResource(R.string.f2_result_empty), color = Cockpit.Muted, fontSize = 13.sp)
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(k, color = Cockpit.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Spacer(Modifier.width(4.dp))
                Text(v, color = Cockpit.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * label -> value of the result sets after the system one: `X_WERT` gets its `X_EINH` unit and,
 * without a translation, its `X_INFO` text as label; JOB_STATUS, units, info texts and raw
 * telegrams are left out.
 */
private fun resultLines(sgbd: String, f: EcuFunction, run: FunctionRun, t: Translator): List<Pair<String, String>> {
    val docs = f.results.associateBy { it.name.uppercase(Locale.ROOT) }
    val out = ArrayList<Pair<String, String>>()
    for (set in run.sets.drop(1)) {
        for ((name, r) in set) {
            val n = name.uppercase(Locale.ROOT)
            if (n == "JOB_STATUS" || n.startsWith("_") || r.opData is ByteArray) continue
            if ((n.endsWith("_EINH") || n.endsWith("_INFO")) && set.containsKey(n.substringBeforeLast('_') + "_WERT")) continue
            val base = n.removeSuffix("_WERT")
            val unit = (set[base + "_EINH"]?.opData as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
            val label = t.resultLabel(sgbd, f.job, name)
                ?: (set[base + "_INFO"]?.opData as? String)?.takeIf { it.isNotBlank() }?.let { t.byOriginal(sgbd, it) }
                ?: docs[n]?.comments?.firstOrNull()?.let { t.byOriginal(sgbd, it) }
                ?: name
            val value = (r.opData as? String)?.let { t.byOriginal(sgbd, it) } ?: Tool32ViewModel.formatValue(r)
            out += label to listOfNotNull(value, unit).joinToString(" ")
        }
    }
    return out
}
