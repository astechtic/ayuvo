package com.ayuvo.health.ui.workouts

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.imageLoader
import coil.request.ImageRequest
import com.ayuvo.health.data.ExerciseVisual
import com.ayuvo.health.services.FoodImageStore
import kotlinx.coroutines.delay

/**
 * Exercise media — Android analog of iOS `AnimatedExerciseVisual`.
 *
 * Catalogue exercises stream the dataset's 180×180 JPEG thumbnail (list rows) or animated
 * GIF (detail hero) through Coil's shared memory/disk cache. When the system "remove
 * animations" setting is on, or [animatesFrames] is false, the static thumbnail is shown.
 * User-created exercises show their on-device photo. Until media loads, or when it fails,
 * the card shows the placeholder and retries with backoff.
 */
@Composable
fun AnimatedExerciseImage(
    visual: ExerciseVisual,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackLabel: String? = null,
    animatesFrames: Boolean = true
) {
    val colors = workoutsColors()
    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val imageStore = remember(context) { FoodImageStore(context) }
    val animate = animatesFrames && animationsEnabled

    val localPhoto = remember(visual.photoFilename) {
        visual.photoFilename?.let { imageStore.file(it).takeIf { file -> file.isFile } }
    }
    val remoteUrl = if (animate) {
        visual.animationUrl ?: visual.thumbnailUrl
    } else {
        visual.thumbnailUrl ?: visual.animationUrl
    }
    val model: Any? = localPhoto ?: remoteUrl
    if (model == null) {
        ExerciseImagePlaceholder(modifier, colors, fallbackLabel)
        return
    }
    val isRemote = localPhoto == null

    var unavailable by remember(model) { mutableStateOf(false) }
    var loaded by remember(model) { mutableStateOf(false) }
    // Coil never retries a failed request by itself, so a transient outage would leave the
    // card blank until the composable is recreated. Bump the attempt with backoff while
    // unavailable; the request carries it as a parameter so AsyncImage sees a new model
    // without changing the cache keys.
    var retryAttempt by remember(model) { mutableIntStateOf(0) }
    LaunchedEffect(unavailable, retryAttempt, model) {
        if (!unavailable || !isRemote) return@LaunchedEffect
        delay(mediaRetryDelayMillis(retryAttempt))
        retryAttempt++
    }

    Box(modifier.background(if (isRemote && loaded) Color.White else colors.background)) {
        if (!loaded) {
            ExerciseImagePlaceholder(Modifier.fillMaxSize(), colors, fallbackLabel.takeIf { unavailable })
        }
        AsyncImage(
            model = remember(model, retryAttempt, animate) {
                ImageRequest.Builder(context)
                    .data(model)
                    .setParameter(MEDIA_RETRY_ATTEMPT_PARAMETER, retryAttempt, memoryCacheKey = null)
                    .build()
            },
            imageLoader = if (isRemote && animate) exerciseGifImageLoader(context) else context.imageLoader,
            contentDescription = null,
            onSuccess = {
                unavailable = false
                loaded = true
            },
            onError = {
                unavailable = true
                loaded = false
            },
            // Dataset media is square with a white background; never crop the movement.
            contentScale = if (isRemote) ContentScale.Fit else contentScale,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ExerciseImagePlaceholder(
    modifier: Modifier,
    colors: WorkoutsColors,
    fallbackLabel: String?
) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(colors.panel, colors.card, colors.accent.copy(alpha = 0.12f)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.FitnessCenter, null, tint = colors.charcoal, modifier = Modifier.size(36.dp))
            if (!fallbackLabel.isNullOrBlank()) {
                Text(
                    fallbackLabel.uppercase(),
                    color = colors.charcoal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

@Volatile
private var gifImageLoader: ImageLoader? = null

/** The app's shared Coil loader (same memory and disk caches) plus animated GIF decoding. */
private fun exerciseGifImageLoader(context: Context): ImageLoader =
    gifImageLoader ?: synchronized(ExerciseMediaLock) {
        gifImageLoader ?: context.applicationContext.imageLoader.newBuilder()
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
            .also { gifImageLoader = it }
    }

private object ExerciseMediaLock

/** Backoff between UI-driven retries of unavailable media: 15s, 30s, then every 60s. */
internal fun mediaRetryDelayMillis(attempt: Int): Long =
    (MEDIA_RETRY_BASE_MS shl attempt.coerceIn(0, 2)).coerceAtMost(MEDIA_RETRY_MAX_MS)

private const val MEDIA_RETRY_BASE_MS = 15_000L
private const val MEDIA_RETRY_MAX_MS = 60_000L
private const val MEDIA_RETRY_ATTEMPT_PARAMETER = "exerciseMediaRetryAttempt"
