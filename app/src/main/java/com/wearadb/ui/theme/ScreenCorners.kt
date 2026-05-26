package com.wearadb.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reads system screen corner radius.
 * - Xiaomi/MIUI: reads rounded_corner_radius_top / rounded_corner_radius_bottom from system resources
 * - Others: defaults to 28dp
 */
object ScreenCorners {

    private var cachedTopPx: Int? = null
    private var cachedBottomPx: Int? = null
    private var cachedIsXiaomi: Boolean? = null

    val isXiaomi: Boolean
        get() {
            if (cachedIsXiaomi == null) {
                cachedIsXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                        Build.MANUFACTURER.equals("Redmi", ignoreCase = true)
            }
            return cachedIsXiaomi!!
        }

    fun getTopRadiusPx(context: Context): Int {
        cachedTopPx?.let { return it }
        val radius = if (isXiaomi) {
            val id = context.resources.getIdentifier(
                "rounded_corner_radius_top", "dimen", "android"
            )
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        } else 0
        cachedTopPx = radius
        return radius
    }

    fun getBottomRadiusPx(context: Context): Int {
        cachedBottomPx?.let { return it }
        val radius = if (isXiaomi) {
            val id = context.resources.getIdentifier(
                "rounded_corner_radius_bottom", "dimen", "android"
            )
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        } else 0
        cachedBottomPx = radius
        return radius
    }

    /**
     * Returns the corner radius to use for UI cards/buttons.
     * Xiaomi: uses top radius (more visible, usually matches bottom)
     * Others: defaults to 28dp
     */
    fun getCornerRadiusDp(context: Context): Dp {
        val px = getTopRadiusPx(context)
        return if (px > 0) {
            with(context.resources.displayMetrics) { (px / density).dp }
        } else {
            28.dp
        }
    }
}

/**
 * Composable accessor for the screen corner radius.
 */
@Composable
@ReadOnlyComposable
fun screenCornerRadius(): Dp {
    val context = LocalContext.current
    return ScreenCorners.getCornerRadiusDp(context)
}
