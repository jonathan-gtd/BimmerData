/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.ApiDocs
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntime
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The REST API documentation in the app: the embedded Swagger UI (assets/swagger-ui/) with the
 * OpenAPI document inlined, read-only ("Try it out" is off; use `/api/v1/docs` from a browser
 * for that). Works offline and with the API switched off.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ApiDocsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var html by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        html = withContext(Dispatchers.Default) {
            ApiDocs.html("file:///android_asset/swagger-ui/", specJson = ApiRuntime.get(context).openApiJson(), tryItOut = false, dark = true)
        }
    }
    Column(Modifier.fillMaxSize().background(Cockpit.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.settings2_back), onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.settings2_api_docs_title), color = Cockpit.Text, fontSize = 24.sp,
                fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
            )
        }
        val page = html
        if (page == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cockpit.Accent) }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false // file:///android_asset stays readable
                        settings.allowContentAccess = false
                        setBackgroundColor(android.graphics.Color.WHITE)
                        webViewClient = object : WebViewClient() {
                            // links (licenses, external docs) open in the browser, never inside the app
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (url.scheme == "http" || url.scheme == "https") {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                                return true
                            }
                        }
                        loadDataWithBaseURL("file:///android_asset/swagger-ui/", page, "text/html", "utf-8", null)
                    }
                },
            )
        }
    }
}
