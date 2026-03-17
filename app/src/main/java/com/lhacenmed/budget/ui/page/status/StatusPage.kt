package com.lhacenmed.budget.ui.page.status

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.repository.StatusSaverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Content ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun StatusContent(
    state: StatusUiState,
    padding: PaddingValues,
    onPermissionGranted: (Uri?) -> Unit,
    onSave: (StatusItem) -> Unit,
    onItemClick: (StatusItem) -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    LaunchedEffect(state.message) {
        state.message?.let { onShowSnackbar(it) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onPermissionGranted(uri) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            !state.hasPermission -> PermissionScreen {
                launcher.launch(StatusSaverRepository.WHATSAPP_ROOT_URI)
            }
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            else -> StatusPager(state = state, onSave = onSave, onItemClick = onItemClick)
        }
    }
}

// ── Permission screen ─────────────────────────────────────────────────────────

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null,
            modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("WhatsApp Status Saver",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Grant access to your WhatsApp folder to view and save statuses you've already watched.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select WhatsApp Folder")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Grant access to the WhatsApp folder (or any parent). " +
                    "The app will automatically find your statuses for single or multiple accounts.",
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ── Swipeable pager with synced TabRow ────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusPager(
    state: StatusUiState,
    onSave: (StatusItem) -> Unit,
    onItemClick: (StatusItem) -> Unit
) {
    val pages      = listOf(state.images, state.videos)
    val labels     = listOf("Images (${state.images.size})", "Videos (${state.videos.size})")
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope      = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            pages.indices.forEach { index ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick  = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text     = { Text(labels[index]) }
                )
            }
        }

        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val items = pages[page]
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (page == 0) "No images found.\nView some statuses in WhatsApp first."
                        else           "No videos found.\nView some statuses in WhatsApp first.",
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns               = GridCells.Fixed(3),
                    modifier              = Modifier.fillMaxSize(),
                    contentPadding        = PaddingValues(4.dp),
                    verticalArrangement   = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items, key = { it.uri }) { item ->
                        StatusItemCell(
                            item     = item,
                            isSaving = state.savingUri == item.uri,
                            onSave   = { onSave(item) },
                            onClick  = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

// ── Grid cell ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun StatusItemCell(
    item: StatusItem, isSaving: Boolean,
    onSave: () -> Unit, onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        if (item.isVideo) {
            VideoThumbnail(uri = item.uri, modifier = Modifier.fillMaxSize())
            Icon(
                imageVector = Icons.Default.PlayCircle, contentDescription = null,
                modifier    = Modifier.align(Alignment.Center).size(36.dp),
                tint        = Color.White.copy(alpha = 0.9f)
            )
        } else {
            AsyncImage(
                model        = item.uri, contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd).padding(4.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    MaterialTheme.shapes.small
                )
        ) {
            if (isSaving) {
                LoadingIndicator(modifier = Modifier.size(28.dp).padding(4.dp))
            } else {
                IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Download, contentDescription = "Save",
                        modifier = Modifier.size(18.dp),
                        tint     = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ── Video thumbnail ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun VideoThumbnail(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(0)
                }
            } catch (_: Exception) { null }
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
        }
    }
}
