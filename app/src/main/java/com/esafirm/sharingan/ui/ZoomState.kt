package com.esafirm.sharingan.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun Float.isOk() = !isNaN() && !isInfinite()
private fun Offset.isOk() = x.isOk() && y.isOk()

/**
 * Pinch / double-tap zoom. The layer scales around its centre, so every change is expressed as a
 * translation that keeps the touched point under the finger.
 *
 * Invariant: [scale] and [offset] are always finite. A NaN translation makes the layer draw nothing
 * (a black page) and every later calculation inherits the NaN, so each entry point below refuses
 * bad input and heals bad state rather than letting it stick.
 */
@Stable
internal class ZoomState(
    private val maxScale: Float = 4f,
    private val doubleTapScale: Float = 2.5f,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var job: Job? = null

    val isZoomed: Boolean get() = scale > 1.001f

    fun cancelAnimation() {
        job?.cancel()
    }

    fun reset() {
        job?.cancel()
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(t: Offset, s: Float, size: Size): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
    }

    /** Direct (finger-driven) change: pinch around [centroid] plus [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoomChange: Float, size: Size, lockY: Boolean) {
        // A lifted finger has no centroid (Compose reports Offset.Unspecified, i.e. NaN). Using it
        // would poison the state, so such events are simply ignored.
        if (!centroid.isOk() || !pan.isOk() || !zoomChange.isOk() || size.width <= 0f || size.height <= 0f) return
        if (!scale.isOk() || !offset.isOk()) reset()

        val s0 = scale
        val s1 = (s0 * zoomChange).coerceIn(1f, maxScale)
        val center = Offset(size.width / 2f, size.height / 2f)
        val focus = centroid - center
        val movement = if (lockY) Offset(pan.x, 0f) else pan
        val t = focus - (focus - offset) * (s1 / s0) + movement
        if (!t.isOk()) {
            reset()
            return
        }
        scale = s1
        offset = if (s1 <= 1f) Offset.Zero else clamp(t, s1, size)
    }

    /** Double-tap: animate to [doubleTapScale] centred on [tap], or back out if already zoomed. */
    fun toggle(tap: Offset, size: Size, scope: CoroutineScope) {
        if (!tap.isOk() || size.width <= 0f || size.height <= 0f) return
        if (!scale.isOk() || !offset.isOk()) reset()
        job?.cancel()

        val s0 = scale
        val t0 = offset
        val s1: Float
        val t1: Offset
        if (s0 > 1.05f) {
            s1 = 1f
            t1 = Offset.Zero
        } else {
            s1 = doubleTapScale
            val focus = tap - Offset(size.width / 2f, size.height / 2f)
            t1 = clamp(focus - (focus - t0) * (s1 / s0), s1, size)
        }
        job = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)) { f, _ ->
                scale = s0 + (s1 - s0) * f
                offset = Offset(t0.x + (t1.x - t0.x) * f, t0.y + (t1.y - t0.y) * f)
            }
        }
    }
}
