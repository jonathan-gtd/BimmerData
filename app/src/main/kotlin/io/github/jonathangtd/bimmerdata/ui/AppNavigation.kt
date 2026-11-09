/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Secondary screens shown over the tabs (the bottom bar is hidden; system back closes them). */
enum class AppOverlay { Tool32, ApiDocs, Cars }

/** Opens secondary screens, or switches tab, from anywhere in the tab screens. */
class AppNavigator(
    /** Tool32 (EDIABAS jobs), with [sgbd] (an SGBD name) already selected when given. */
    val openTool32: (sgbd: String?) -> Unit = {},
    /** Swagger UI of the local REST API. */
    val openApiDocs: () -> Unit = {},
    /** The Profiles tab (manage profiles). */
    val openProfiles: () -> Unit = {},
    /** The Dashboard tab. */
    val openDashboard: () -> Unit = {},
    /** My cars (choose, add, detect the ECUs of a car). */
    val openCars: () -> Unit = {},
)

val LocalAppNavigator = staticCompositionLocalOf { AppNavigator() }
