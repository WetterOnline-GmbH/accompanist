/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.accompanist.imageloading

import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A generic image loading painter, which provides hooks for image loading libraries to use.
 * Apps shouldn't generally use this function, instead preferring one of the extension libraries
 * which build upon this, such as the Coil and Glide libraries.
 *
 * @param state The request to execute.
 * @param fadeIn Whether to run a fade-in animation when images are successfully loaded.
 * Default: `false`.
 * @param fadeInDurationMs Duration for the fade animation in milliseconds when [fadeIn] is enabled.
 * @param previewPlaceholder Drawable resource ID which will be displayed when this function is
 * ran in preview mode.
 */
@Composable
fun <R> rememberLoadPainter(
    request: R?,
    loader: suspend (R, size: IntSize) -> ImageLoadState,
    shouldRefetchOnSizeChange: (currentState: ImageLoadState, size: IntSize) -> Boolean,
    fadeIn: Boolean = false,
    fadeInDurationMs: Int = DefaultTransitionDuration,
    @DrawableRes previewPlaceholder: Int = 0,
): LoadPainter<R> {
    val coroutineScope = rememberCoroutineScope()

    // Our painter. We use a DelegatingPainter which delegates the actual drawn/laid-out painter
    // to our result painter state. That block will be called during layout and drawing,
    // which means that any changes to `state.loadState` will automatically re-trigger layout/draw.
    return remember(coroutineScope) {
        LoadPainter(
            loader = loader,
            coroutineScope = coroutineScope,
            shouldRefetchOnSizeChange = shouldRefetchOnSizeChange
        )
    }.apply {
        this.shouldRefetchOnSizeChange = shouldRefetchOnSizeChange
        this.request = request
    }.also { loadPainter ->
        // This runs our fade in animation
        loadPainter.fadeInAsState(
            enabled = { result ->
                // We run the fade in animation if the result is loaded from disk/network. This allows
                // us to approximate only running the animation on 'first load'
                fadeIn && result is ImageLoadState.Success && result.source != DataSource.MEMORY
            },
            durationMs = fadeInDurationMs,
        )

        // Our result painter, created from the ImageState with some composition lifecycle
        // callbacks
        updatePainter(loadPainter, previewPlaceholder)
    }
}

/**
 * TODO
 *
 * @param R
 * @property loader
 * @property coroutineScope
 * @constructor
 * TODO
 *
 * @param shouldRefetchOnSizeChange
 */
class LoadPainter<R>(
    private val loader: suspend (request: R, size: IntSize) -> ImageLoadState,
    private val coroutineScope: CoroutineScope,
    shouldRefetchOnSizeChange: (currentState: ImageLoadState, size: IntSize) -> Boolean,
) : Painter(), RememberObserver {
    private val paint by lazy(LazyThreadSafetyMode.NONE) { Paint() }

    internal var painter by mutableStateOf<Painter>(EmptyPainter)
    internal var transitionColorFilter by mutableStateOf<ColorFilter?>(null)

    /**
     * TODO
     */
    var shouldRefetchOnSizeChange by mutableStateOf(shouldRefetchOnSizeChange)

    /**
     * TODO
     */
    var loadState: ImageLoadState by mutableStateOf(ImageLoadState.Empty)
        private set

    /**
     * TODO
     */
    var request by mutableStateOf<R?>(null)

    private var alpha: Float by mutableStateOf(1f)
    private var colorFilter: ColorFilter? by mutableStateOf(null)

    // Our size to use when performing the image load request
    private var requestSize by mutableStateOf<IntSize?>(null)

    // Current request job
    private var job: Job? = null

    override val intrinsicSize: Size
        get() = painter.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        // Update the request size, based on the provided canvas size
        requestSize = IntSize(
            width = if (size.width >= 0.5f) size.width.roundToInt() else -1,
            height = if (size.height >= 0.5f) size.width.roundToInt() else -1,
        )

        val transitionColorFilter = transitionColorFilter
        if (colorFilter != null && transitionColorFilter != null) {
            // If we have a transition color filter, and a specified color filter we need to
            // draw the content in a layer for both to apply.
            // See https://github.com/google/accompanist/issues/262
            drawIntoCanvas { canvas ->
                paint.colorFilter = transitionColorFilter
                canvas.saveLayer(bounds = size.toRect(), paint = paint)
                with(painter) {
                    draw(size, alpha, colorFilter)
                }
                canvas.restore()
            }
        } else {
            // Otherwise we just draw the content directly, using the filter
            with(painter) {
                draw(size, alpha, colorFilter ?: transitionColorFilter)
            }
        }
    }

    override fun onAbandoned() {
        // We've been abandoned from composition, so cancel our request handling coroutine
        job?.cancel()
        job = null
    }

    override fun onForgotten() {
        // We've been forgotten from composition, so cancel our request handling coroutine
        job?.cancel()
        job = null
    }

    override fun onRemembered() {
        // Cancel any on-going job (this shouldn't really happen anyway)
        job?.cancel()

        // We've been remembered, so launch a coroutine to observe the current request object,
        // and the request size. Whenever either of these values change, the collectLatest block
        // will run and execute the image load (with any on-going request cancelled).
        job = coroutineScope.launch {
            combine(
                snapshotFlow { request },
                snapshotFlow { requestSize }.filterNotNull(),
                transform = { request, size -> request to size }
            ).collectLatest { (request, size) ->
                execute(request, size)
            }
        }
    }

    /**
     * The function which executes the requests, and update [loadState] as appropriate with the
     * result.
     */
    private suspend fun execute(request: R?, size: IntSize) {
        if (request == null) {
            // If we don't have a request, set our state to Empty and return
            loadState = ImageLoadState.Empty
            return
        }

        if (loadState != ImageLoadState.Empty &&
            request == loadState.request &&
            !shouldRefetchOnSizeChange(loadState, size)
        ) {
            // If we're not empty, the request is the same and shouldRefetchOnSizeChange()
            // returns false, return now to skip this request
            return
        }

        // Otherwise we're about to start a request, so set us to 'Loading'
        loadState = ImageLoadState.Loading

        loadState = try {
            loader(request, size)
        } catch (ce: CancellationException) {
            // We specifically don't do anything for the request coroutine being
            // cancelled: https://github.com/google/accompanist/issues/217
            throw ce
        } catch (e: Error) {
            // Re-throw all Errors
            throw e
        } catch (e: IllegalStateException) {
            // Re-throw all IllegalStateExceptions
            throw e
        } catch (e: IllegalArgumentException) {
            // Re-throw all IllegalArgumentExceptions
            throw e
        } catch (t: Throwable) {
            // Anything else, we wrap in a Error state instance
            ImageLoadState.Error(result = null, throwable = t, request = request)
        }
    }
}

/**
 * Allows us observe the current result [Painter] as state. This function allows us to
 * minimize the amount of composition needed, such that only this function needs to be restarted
 * when the `loadState` changes.
 */
@SuppressLint("ComposableNaming")
@Composable
private fun <R> updatePainter(
    loadPainter: LoadPainter<R>,
    @DrawableRes previewPlaceholder: Int = 0,
) {
    loadPainter.painter = if (LocalInspectionMode.current && previewPlaceholder != 0) {
        // If we're in inspection mode (preview) and we have a preview placeholder, just draw
        // that using an Image and return
        painterResource(previewPlaceholder)
    } else {
        loadPainter.loadState.drawable?.let { rememberDrawablePainter(it) } ?: EmptyPainter
    }
}

@Composable
private fun <R> LoadPainter<R>.fadeInAsState(
    enabled: (ImageLoadState) -> Boolean,
    durationMs: Int,
) {
    val state = loadState
    transitionColorFilter = if (enabled(state)) {
        val colorMatrix = remember { ColorMatrix() }
        val fadeInTransition = updateFadeInTransition(state, durationMs)

        if (!fadeInTransition.isFinished) {
            colorMatrix.apply {
                updateAlpha(fadeInTransition.alpha)
                updateBrightness(fadeInTransition.brightness)
                updateSaturation(fadeInTransition.saturation)
            }.let { ColorFilter.colorMatrix(it) }
        } else {
            // If the fade-in isn't running, reset the color matrix
            null
        }
    } else null // If the fade in is not enabled, we don't use a fade in transition
}

private object EmptyPainter : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified
    override fun DrawScope.onDraw() {}
}
