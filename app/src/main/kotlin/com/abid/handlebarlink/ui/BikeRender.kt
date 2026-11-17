package com.abid.handlebarlink.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.R
import com.abid.handlebarlink.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bike render (1200 × 864 PNG, about 4 MB decoded), decoded once off the main thread and shared by every
 * screen. `painterResource` decoded it again on the main thread each time Home or setup was opened, a stall
 * at every page switch (Compose performance skill: keep heavy work off the main thread).
 */
@Singleton
class BikeRender
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val state = MutableStateFlow<ImageBitmap?>(null)

        /** The decoded render; null until [load] finishes. */
        val image: StateFlow<ImageBitmap?> = state.asStateFlow()

        /** Decodes the render and uploads it to the GPU ahead of its first draw; once per process. */
        suspend fun load() {
            if (state.value != null) return
            state.value =
                withContext(io) {
                    ImageBitmap.imageResource(context.resources, R.drawable.bike_cb350rs).also { it.prepareToDraw() }
                }
        }
    }

/** The app's [BikeRender]; null in previews and screenshot tests, which load the image directly. */
val LocalBikeRender = staticCompositionLocalOf<BikeRender?> { null }

/** The bike render as a painter: the shared decoded image in the app (null until decoded). */
@Composable
private fun bikePainter(): Painter? {
    val render = LocalBikeRender.current ?: return painterResource(R.drawable.bike_cb350rs)
    val image by render.image.collectAsStateWithLifecycle()
    return image?.let { remember(it) { BitmapPainter(it) } }
}

/** The bike render, fitted; an empty space of the same size until it is decoded. */
@Composable
fun BikeImage(modifier: Modifier = Modifier) {
    val painter = bikePainter()
    if (painter == null) {
        Spacer(modifier)
    } else {
        Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    }
}
