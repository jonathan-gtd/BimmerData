/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

/**
 * Swagger UI page for the API documentation. Swagger UI (swagger-ui-dist, Apache-2.0, version in
 * `assets/swagger-ui/VERSION`) is embedded in the APK: nothing is loaded from the internet.
 * Served by the API at `/api/v1/docs` (with "Try it out") and shown read-only in the app.
 */
object ApiDocs {
    /** Files of `assets/swagger-ui/` the server may serve under `/api/v1/docs/`. */
    val ASSETS = mapOf(
        "swagger-ui-bundle.js" to "application/javascript; charset=utf-8",
        "swagger-ui.css" to "text/css; charset=utf-8",
        "LICENSE" to "text/plain; charset=utf-8",
        "NOTICE" to "text/plain; charset=utf-8",
    )

    /**
     * The HTML page. Give either [specUrl] (fetched by the browser) or [specJson] (inlined).
     * [assetBase] ends with `/`. Without [tryItOut] the requests cannot be sent from the page.
     */
    fun html(assetBase: String, specUrl: String? = null, specJson: String? = null, tryItOut: Boolean = true, dark: Boolean = false): String {
        require((specUrl == null) != (specJson == null)) { "give specUrl or specJson" }
        val source = if (specJson != null) "spec: ${scriptSafe(specJson)}" else "url: ${jsString(specUrl!!)}"
        val methods = if (tryItOut) "['get', 'put', 'post', 'delete', 'patch']" else "[]"
        val darkCss = if (dark) {
            "html { background: #fff; filter: invert(0.92) hue-rotate(180deg); } img, .microlight, .highlight-code { filter: invert(1) hue-rotate(180deg); }"
        } else {
            ""
        }
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BimmerData API</title>
<link rel="stylesheet" href="${assetBase}swagger-ui.css">
<style>
body { margin: 0; }
.swagger-ui .topbar { display: none; }
$darkCss
</style>
</head>
<body>
<div id="swagger-ui"></div>
<script src="${assetBase}swagger-ui-bundle.js"></script>
<script>
window.ui = SwaggerUIBundle({
  $source,
  dom_id: '#swagger-ui',
  deepLinking: false,
  docExpansion: 'none',
  defaultModelsExpandDepth: 0,
  displayRequestDuration: true,
  persistAuthorization: false,
  supportedSubmitMethods: $methods,
  presets: [SwaggerUIBundle.presets.apis],
  layout: 'BaseLayout'
});
</script>
</body>
</html>
"""
    }

    /** JSON inside a script element: no `</script>` and no JS line separators. */
    private fun scriptSafe(json: String) = json.replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
