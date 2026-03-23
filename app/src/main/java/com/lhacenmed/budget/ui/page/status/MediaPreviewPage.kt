package com.lhacenmed.budget.ui.page.status

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.lhacenmed.budget.data.model.StatusItem

@Composable
fun MediaPreviewScreen(
    item:     StatusItem,
    isSaving: Boolean,
    onBack:   () -> Unit,
    onSave:   () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var showInfo     by remember { mutableStateOf(false) }
    val context      = LocalContext.current

    // No BackHandler needed — the NavHost handles the back gesture automatically
    // and fires the shared axis exit animation via popExitTransition in animatedComposable.

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { showControls = !showControls }
    ) {

        // ── Media ─────────────────────────────────────────────────────────────
        if (item.isVideo) {
            VideoPlayer(uri = item.uri, modifier = Modifier.fillMaxSize())
        } else {
            ZoomableImage(
                uri          = item.uri,
                name         = item.name,
                onTap        = { showControls = !showControls }
            )
        }

        // ── Top controls (tap anywhere to toggle) ─────────────────────────────
        AnimatedVisibility(
            visible  = showControls,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
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
                    text     = item.name,
                    color    = Color.White,
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 1
                )
            }
        }

        // ── Bottom controls ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = showControls,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
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
                    loading = isSaving,
                    onClick = onSave
                )
                ActionButton(
                    icon    = Icons.Default.Share,
                    label   = "Share",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = if (item.isVideo) "video/*" else "image/*"
                            putExtra(Intent.EXTRA_STREAM, item.uri)
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
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title            = { Text("File Info") },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Name", item.name)
                    InfoRow("Type", if (item.isVideo) "Video" else "Image")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("OK") }
            }
        )
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
    uri:   android.net.Uri,
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
                    scaleX        = scale,
                    scaleY        = scale,
                    translationX  = offset.x,
                    translationY  = offset.y
                )
        )
    }
}

// ── Video player ──────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(uri: android.net.Uri, modifier: Modifier) {
    val context = LocalContext.current
    val player  = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    AndroidView(
        modifier = modifier,
        factory  = {
            PlayerView(it).apply {
                this.player   = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                layoutParams  = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    )
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
                Icon(icon, contentDescription = label, tint = Color.White,
                    modifier = Modifier.size(24.dp))
            }
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
