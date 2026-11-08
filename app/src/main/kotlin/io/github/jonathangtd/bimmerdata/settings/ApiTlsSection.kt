/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntime
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntimeState
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File name proposed when exporting the certificate clients trust. */
const val API_CA_FILE_NAME = "bimmerdata-api-ca.pem"

/**
 * HTTPS block of the local API settings: the switch, the fingerprints to trust or pin, export /
 * share / regenerate of the certificate, and ready-to-copy client examples.
 */
@Composable
fun ApiTlsSection(config: ApiConfig, state: ApiRuntimeState, store: AppSettings, runtime: ApiRuntime) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val pem = runtime.caPem() ?: error("no certificate")
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(pem.toByteArray(Charsets.US_ASCII)) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) R.string.https_exported else R.string.https_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    ApiTlsContent(
        config = config,
        state = state,
        onHttpsChange = { on -> store.updateApi { it.copy(https = on) } },
        onExport = { exporter.launch(API_CA_FILE_NAME) },
        onShare = {
            scope.launch {
                val pem = withContext(Dispatchers.IO) { runtime.caPem() } ?: return@launch
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pem)
                    .putExtra(Intent.EXTRA_SUBJECT, API_CA_FILE_NAME)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }
        },
        onRegenerate = runtime::regenerateCertificate,
    )
}

/** Stateless content of [ApiTlsSection] (also used by the screenshot test). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApiTlsContent(
    config: ApiConfig,
    state: ApiRuntimeState,
    onHttpsChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val context = LocalContext.current
    var confirmRegenerate by rememberSaveable { mutableStateOf(false) }

    Divider()
    SwitchRow(stringResource(R.string.https_title), stringResource(R.string.https_hint), config.https, onChange = onHttpsChange)
    if (!config.https) return

    val tls = state.tls
    if (tls == null) {
        if (state.tlsError != null) Banner(stringResource(R.string.https_error, state.tlsError), Cockpit.Danger)
        else Hint(stringResource(R.string.https_preparing))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.https_fingerprint), color = Cockpit.Muted, fontSize = 13.sp)
        Mono(tls.certificateSha256)
        TextButton(onClick = { copyPlain(context, tls.certificateSha256) }) { Text(stringResource(R.string.https_copy), color = Cockpit.Accent) }
        Text(stringResource(R.string.https_pin), color = Cockpit.Muted, fontSize = 13.sp)
        Mono(tls.publicKeyPin)
        TextButton(onClick = { copyPlain(context, tls.publicKeyPin) }) { Text(stringResource(R.string.https_copy), color = Cockpit.Accent) }
    }
    Hint(stringResource(R.string.https_trust_hint))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(stringResource(R.string.https_export), onClick = onExport)
        SmallButton(stringResource(R.string.https_share), onClick = onShare)
        SmallButton(stringResource(R.string.https_regenerate), color = Cockpit.Warning) { confirmRegenerate = true }
    }

    val host = state.addresses.firstOrNull()?.host ?: "127.0.0.1"
    val port = if (state.running) state.port else config.port
    Text(stringResource(R.string.https_examples), color = Cockpit.Text, fontSize = 16.sp)
    Hint(stringResource(R.string.https_examples_hint))
    for (example in httpsExamples("https://$host:$port/api/v1", tls.publicKeyPin)) {
        Surface(color = Cockpit.Background, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Cockpit.Border), modifier = Modifier.fillMaxWidth()) {
            Text(
                example, color = Cockpit.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
        TextButton(onClick = { copyPlain(context, example) }) { Text(stringResource(R.string.https_copy), color = Cockpit.Accent) }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.https_regenerate_title)) },
            text = { Text(stringResource(R.string.https_regenerate_text)) },
            confirmButton = {
                TextButton(onClick = { onRegenerate(); confirmRegenerate = false }) {
                    Text(stringResource(R.string.https_regenerate), color = Cockpit.Warning)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(R.string.settings_cancel), color = Cockpit.Text) } },
            containerColor = Cockpit.Card,
        )
    }
}

/**
 * Client examples for [base] (`https://host:port/api/v1`): curl with the public-key pin (nothing to
 * install), curl with the exported certificate, Python `requests` with the exported certificate.
 * The token is read from `$BIMMERDATA_TOKEN`, never pasted into the example.
 */
internal fun httpsExamples(base: String, pin: String): List<String> = listOf(
    "curl --pinnedpubkey '$pin' -k \\\n  -H \"Authorization: Bearer \$BIMMERDATA_TOKEN\" \\\n  $base/status",
    "curl --cacert $API_CA_FILE_NAME \\\n  -H \"Authorization: Bearer \$BIMMERDATA_TOKEN\" \\\n  $base/status",
    "import os, requests\n" +
        "r = requests.get(\"$base/status\",\n" +
        "    headers={\"Authorization\": \"Bearer \" + os.environ[\"BIMMERDATA_TOKEN\"]},\n" +
        "    verify=\"$API_CA_FILE_NAME\")\n" +
        "print(r.json())",
)

private fun copyPlain(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("BimmerData", text))
}
