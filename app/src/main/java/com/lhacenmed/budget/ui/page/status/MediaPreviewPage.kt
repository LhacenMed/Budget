package com.lhacenmed.budget.ui.page.status

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.lhacenmed.budget.data.model.StatusItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MediaPreviewScreen(
    items:        List<StatusItem>,
    initialIndex: Int,
    savingUri:    Uri?,
    onBack:       () -> Unit,
    onSave:       (StatusItem) -> Unit
) {
    val pagerState   = rememberPagerState(initialPage = initialIndex) { items.size }
    val currentItem  = items[pagerState.currentPage]
    var showControls by remember { mutableStateOf(true) }
    var showInfo     by remember { mutableStateOf(false) }
    val context      = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Media pager ───────────────────────────────────────────────────────
        // beyondViewportPageCount = 1 keeps adjacent pages in composition for
        // smooth swipes. Videos on non-settled pages are paused via [isActive].
        HorizontalPager(
            state                   = pagerState,
            modifier                = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            val item     = items[page]
            // Use settledPage so videos pause during the swipe animation
            // and resume only once the page is fully in view.
            val isActive = pagerState.settledPage == page

            if (item.isVideo) {
                VideoPlayerPage(
                    uri              = item.uri,
                    isActive         = isActive,
                    showControls     = showControls,
                    onToggleControls = { showControls = !showControls }
                )
            } else {
                ZoomableImage(
                    uri   = item.uri,
                    name  = item.name,
                    onTap = { showControls = !showControls }
                )
            }
        }

        // ── Top bar ───────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = showControls,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color.White
                    )
                }
                Text(
                    text     = currentItem.name,
                    color    = Color.White,
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    maxLines = 1
                )
            }
        }

        // ── Bottom action bar ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = showControls,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    icon    = Icons.Default.Download,
                    label   = "Save",
                    loading = savingUri == currentItem.uri,
                    onClick = { onSave(currentItem) }
                )
                ActionButton(
                    icon    = Icons.Default.Share,
                    label   = "Share",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = if (currentItem.isVideo) "video/*" else "image/*"
                            putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share via"))
                    }
                )
                ActionButton(
                    icon    = Icons.Default.Info,
                    label   = "Info",
                    onClick = { showInfo = true }
                )
            }
        }
    }

    if (showInfo) {
        val dateStr = if (currentItem.lastModified > 0L)
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                .format(Date(currentItem.lastModified))
        else "Unknown"
        val sourceLabel = currentItem.source.name
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }

        AlertDialog(
            onDismissRequest = { showInfo = false },
            title            = { Text("File Info") },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Name",   currentItem.name)
                    InfoRow("Type",   if (currentItem.isVideo) "Video" else "Image")
                    InfoRow("Date",   dateStr)
                    InfoRow("Source", sourceLabel)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("OK") }
            }
        )
    }
}

// ── Video player page ─────────────────────────────────────────────────────────

/**
 * Full-screen video player for a single pager page.
 *
 * [useController] is intentionally disabled on [PlayerView] for two reasons:
 *   1. The native controller bar would overlap our custom bottom action bar (Save/Share/Info).
 *   2. With the native controller active, [PlayerView] consumes all touch events,
 *      preventing our tap-to-toggle-controls gesture from reaching the overlay.
 *
 * Instead we expose a centered play/pause button that appears with [showControls],
 * and a transparent full-area tap overlay that fires [onToggleControls].
 *
 * [isActive] is driven by [PagerState.settledPage] so the video pauses during
 * swipe animations and resumes only once its page is fully settled.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerPage(
    uri:              Uri,
    isActive:         Boolean,
    showControls:     Boolean,
    onToggleControls: () -> Unit
) {
    val context = LocalContext.current
    val player  = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode    = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    // Pause when swiped off-screen; resume when this page settles back into view
    LaunchedEffect(isActive) { player.playWhenReady = isActive }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Track play/pause state for the overlay button icon
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Render the video surface — controller disabled (see KDoc above)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    this.player   = player
                    useController = false
                    layoutParams  = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        )

        // Full-area tap overlay — toggles controls visibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onToggleControls() }
        )

        // Centered play/pause button — shown alongside the top/bottom bars
        AnimatedVisibility(
            visible  = showControls,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            IconButton(
                onClick  = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint               = Color.White,
                    modifier           = Modifier.size(38.dp)
                )
            }
        }
    }
}

// ── Zoomable image ────────────────────────────────────────────────────────────

/**
 * An image that supports pinch-to-zoom (1x – 5x) and panning when zoomed in.
 * Tapping fires [onTap] only when at 1x scale (avoids fighting with pan gestures).
 * Double-tap is not implemented — kept intentionally simple.
 *
 * Uses Compose's built-in [rememberTransformableState] + [transformable] modifier.
 * No third-party library required.
 */
@Composable
private fun ZoomableImage(
    uri:   Uri,
    name:  String,
    onTap: () -> Unit
) {
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        // Clamp scale between 1x and 5x
        scale = (scale * zoomChange).coerceIn(1f, 5f)

        // When zoomed back to 1x, reset pan so the image re-centers cleanly
        if (scale == 1f) {
            offset = Offset.Zero
        } else {
            offset += panChange
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // transformable intercepts pinch and pan gestures
            .transformable(state = transformState)
            // Tap to toggle controls — only at 1x to avoid conflicting with panning
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() },
                enabled           = scale == 1f
            ) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model              = uri,
            contentDescription = name,
            contentScale       = ContentScale.Fit,
            modifier           = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX       = scale,
                    scaleY       = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionButton(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    label:   String,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) {
            LoadingIndicator(
                modifier = Modifier.size(40.dp).padding(bottom = 4.dp),
                color    = Color.White
            )
        } else {
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint               = Color.White,
                    modifier           = Modifier.size(24.dp)
                )
            }
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
